package com.clevebitr.netplayer.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnClientTest {
    private static final String USER = "relay-user";
    private static final String PASS = "relay-pass";

    @Test
    void xorAddressesRoundTrip() throws Exception {
        byte[] txid = StunCodec.randomTxid();
        InetAddress address = InetAddress.getByName("203.0.113.7");
        StunCodec.Message message = new StunCodec.Message(0x0103, txid, java.util.Map.of(
                StunCodec.ATTR_XOR_RELAYED_ADDRESS, StunCodec.xorPeerAddress(address, 50000, txid)));
        assertEquals(address, message.xorAddress(StunCodec.ATTR_XOR_RELAYED_ADDRESS));
        assertEquals(50000,
                StunCodec.decodeXorPort(message.raw(StunCodec.ATTR_XOR_RELAYED_ADDRESS), txid));
    }

    @Test
    void messageIntegrityIsChecked() {
        byte[] key = StunCodec.longTermKey(USER, TurnServerStub.REALM, PASS);
        byte[] message = StunCodec.encode(StunCodec.ALLOCATE, StunCodec.randomTxid(),
                java.util.List.of(new StunCodec.Attr(StunCodec.ATTR_USERNAME,
                        USER.getBytes(StandardCharsets.UTF_8))));
        byte[] signed = StunCodec.sign(message, key);
        assertTrue(StunCodec.check(signed, key));
        byte wrong = signed[signed.length - 1];
        signed[signed.length - 1] = (byte) (wrong ^ 0x01);
        assertTrue(!StunCodec.check(signed, key));
        assertTrue(!StunCodec.check(signed, StunCodec.longTermKey(USER, TurnServerStub.REALM, "nope")));
    }

    @Test
    void serverTextIsParsed() {
        InetSocketAddress a = TurnClient.parseServer("turn:127.0.0.1:3478");
        assertNotNull(a);
        assertEquals(3478, a.getPort());
        assertEquals(3478, TurnClient.parseServer("127.0.0.1").getPort());
        assertEquals(80, TurnClient.parseServer("turn:127.0.0.1:80?transport=udp").getPort());
        assertNull(TurnClient.parseServer("turns:127.0.0.1:5349"));
        assertNull(TurnClient.parseServer(""));
    }

    @Test
    void allocationGoesThroughTheCredentialChallenge() throws Exception {
        try (TurnServerStub server = new TurnServerStub(USER, PASS);
             TurnClient client = new TurnClient(server.address(), USER, PASS)) {
            TurnClient.Allocation allocation = client.allocate(600);
            assertNotNull(allocation.relayed());
            assertTrue(allocation.relayed().getPort() > 0);
            assertEquals("127.0.0.1", allocation.relayed().getAddress().getHostAddress());
            assertTrue(allocation.lifetimeSeconds() >= 60);
        }
    }

    @Test
    void wrongCredentialsFail() throws Exception {
        try (TurnServerStub server = new TurnServerStub(USER, PASS);
             TurnClient client = new TurnClient(server.address(), USER, "wrong")) {
            IOException error = assertThrows(IOException.class, () -> client.allocate(600));
            assertTrue(error.getMessage().contains("401") || client.lastError().contains("integrity"),
                    "unexpected error: " + error.getMessage());
        }
    }

    @Test
    void dataTravelsThroughTheRelayInBothDirections() throws Exception {
        try (TurnServerStub server = new TurnServerStub(USER, PASS);
             TurnClient a = new TurnClient(server.address(), USER, PASS);
             TurnClient b = new TurnClient(server.address(), USER, PASS)) {
            InetSocketAddress aRelay = a.allocate(600).relayed();
            InetSocketAddress bRelay = b.allocate(600).relayed();

            int aChannel = 0x4001;
            int bChannel = 0x4002;
            a.createPermission(bRelay);
            b.createPermission(aRelay);
            a.bindChannel(aChannel, bRelay);
            b.bindChannel(bChannel, aRelay);

            byte[] hello = "hello through the relay".getBytes(StandardCharsets.UTF_8);
            a.send(aChannel, hello);
            assertArrayEquals(hello, await(b), "b should receive what a sent");

            // A full tunnel datagram is bigger than a few bytes; make sure lengths above 255 work.
            byte[] big = new byte[1200];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i * 31);
            }
            b.send(bChannel, big);
            assertArrayEquals(big, await(a), "a should receive what b sent");
        }
    }

    private static byte[] await(TurnClient client) {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            byte[] payload = client.receive();
            if (payload != null) {
                return payload;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                break;
            }
        }
        return null;
    }
}
