package com.clevebitr.netplayer.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-process TURN server used by the tests. It implements the parts a client actually exercises:
 * the long term credential challenge, allocate, create permission, channel bind, refresh, and
 * relaying between two allocations. Everything is on loopback and single threaded, which is enough
 * to test the client's framing and authentication without a coturn deployment.
 */
final class TurnServerStub implements AutoCloseable {
    static final String REALM = "netplayer.test";

    private static final class Allocation {
        final InetSocketAddress client;
        final DatagramSocket relay;
        final Set<InetAddress> permissions = new HashSet<>();
        final Map<Integer, InetSocketAddress> channels = new HashMap<>();

        Allocation(InetSocketAddress client, DatagramSocket relay) {
            this.client = client;
            this.relay = relay;
        }
    }

    private final DatagramSocket socket;
    private final String username;
    private final String password;
    private final byte[] nonce = Bytes.random(16);
    private final Map<String, Allocation> byClient = new HashMap<>();
    private final Map<String, Allocation> byPort = new HashMap<>();
    private volatile boolean running = true;

    TurnServerStub(String username, String password) throws Exception {
        this.username = username;
        this.password = password;
        this.socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread.ofVirtual().name("turn-stub").start(this::loop);
    }

    InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort());
    }

    private void loop() {
        byte[] buffer = new byte[4096];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (Exception e) {
                return;
            }
            InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
            byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
            try {
                if (data.length >= 4 && (data[0] & 0xff) >= 0x40 && (data[0] & 0xff) <= 0x7f) {
                    onChannelData(from, data);
                } else {
                    onStun(from, data);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void onStun(InetSocketAddress from, byte[] data) throws Exception {
        StunCodec.Message request = StunCodec.parse(data);
        if (request == null) {
            return;
        }
        byte[] key = StunCodec.longTermKey(username, REALM, password);
        if (!request.has(StunCodec.ATTR_MESSAGE_INTEGRITY) || !StunCodec.check(data, key)) {
            List<StunCodec.Attr> attrs = new ArrayList<>();
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_ERROR_CODE,
                    new byte[]{0, 0, 4, 1, 'U', 'n', 'a', 'u', 't', 'h'}));
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_REALM, REALM.getBytes()));
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_NONCE, nonce));
            reply(from, request.txid, errorType(request.type), attrs, null);
            return;
        }
        List<StunCodec.Attr> attrs = new ArrayList<>();
        switch (request.type) {
            case StunCodec.ALLOCATE -> {
                Allocation allocation = byClient.get(key(from));
                if (allocation == null) {
                    DatagramSocket relay = new DatagramSocket(new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), 0));
                    allocation = new Allocation(from, relay);
                    byClient.put(key(from), allocation);
                    byPort.put("127.0.0.1:" + relay.getLocalPort(), allocation);
                }
                attrs.add(new StunCodec.Attr(StunCodec.ATTR_XOR_RELAYED_ADDRESS, StunCodec.xorPeerAddress(
                        InetAddress.getByName("127.0.0.1"), allocation.relay.getLocalPort(), request.txid)));
                attrs.add(new StunCodec.Attr(StunCodec.ATTR_XOR_MAPPED_ADDRESS, StunCodec.xorPeerAddress(
                        from.getAddress(), from.getPort(), request.txid)));
                attrs.add(new StunCodec.Attr(StunCodec.ATTR_LIFETIME, StunCodec.lifetime(600)));
                reply(from, request.txid, successType(request.type), attrs, key);
            }
            case StunCodec.REFRESH -> {
                attrs.add(new StunCodec.Attr(StunCodec.ATTR_LIFETIME, StunCodec.lifetime(600)));
                reply(from, request.txid, successType(request.type), attrs, key);
            }
            case StunCodec.CREATE_PERMISSION -> {
                Allocation allocation = byClient.get(key(from));
                InetAddress peer = request.xorAddress(StunCodec.ATTR_XOR_PEER_ADDRESS);
                if (allocation != null && peer != null) {
                    allocation.permissions.add(peer);
                }
                reply(from, request.txid, successType(request.type), null, key);
            }
            case StunCodec.CHANNEL_BIND -> {
                Allocation allocation = byClient.get(key(from));
                byte[] number = request.raw(StunCodec.ATTR_CHANNEL_NUMBER);
                InetAddress peer = request.xorAddress(StunCodec.ATTR_XOR_PEER_ADDRESS);
                int port = StunCodec.decodeXorPort(request.raw(StunCodec.ATTR_XOR_PEER_ADDRESS), request.txid);
                if (allocation != null && number != null && peer != null) {
                    int channel = ((number[0] & 0xff) << 8) | (number[1] & 0xff);
                    allocation.channels.put(channel, new InetSocketAddress(peer, port));
                    allocation.permissions.add(peer);
                }
                reply(from, request.txid, successType(request.type), null, key);
            }
            default -> {
            }
        }
    }

    private void onChannelData(InetSocketAddress from, byte[] data) throws Exception {
        Allocation sender = byClient.get(key(from));
        if (sender == null) {
            return;
        }
        int channel = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
        int length = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        InetSocketAddress peer = sender.channels.get(channel);
        if (peer == null || data.length < 4 + length) {
            return;
        }
        byte[] payload = java.util.Arrays.copyOfRange(data, 4, 4 + length);
        deliver(sender, peer, payload);
    }

    /** Hands {@code payload} to whatever client owns {@code peerAddress}. */
    private void deliver(Allocation sender, InetSocketAddress peerAddress, byte[] payload) throws Exception {
        Allocation target = byPort.get(peerAddress.getAddress().getHostAddress() + ":" + peerAddress.getPort());
        if (target == null) {
            return;
        }
        InetSocketAddress senderRelayed = new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                sender.relay.getLocalPort());
        for (Map.Entry<Integer, InetSocketAddress> entry : target.channels.entrySet()) {
            if (entry.getValue().getPort() == senderRelayed.getPort()) {
                byte[] packet = new byte[4 + payload.length];
                packet[0] = (byte) (entry.getKey() >> 8);
                packet[1] = (byte) entry.getKey().intValue();
                packet[2] = (byte) (payload.length >> 8);
                packet[3] = (byte) payload.length;
                System.arraycopy(payload, 0, packet, 4, payload.length);
                socket.send(new DatagramPacket(packet, packet.length, target.client));
                return;
            }
        }
        // No channel binding on the receiving side: fall back to a Data indication.
        List<StunCodec.Attr> attrs = new ArrayList<>();
        attrs.add(new StunCodec.Attr(StunCodec.ATTR_XOR_PEER_ADDRESS,
                StunCodec.xorPeerAddress(senderRelayed.getAddress(), senderRelayed.getPort(), new byte[12])));
        attrs.add(new StunCodec.Attr(StunCodec.ATTR_DATA, payload));
        reply(target.client, StunCodec.randomTxid(), StunCodec.DATA_INDICATION, attrs, null);
    }

    private void reply(InetSocketAddress to, byte[] txid, int type, List<StunCodec.Attr> attrs, byte[] key)
            throws Exception {
        byte[] message = StunCodec.encode(type, txid, attrs == null ? List.of() : attrs);
        if (key != null) {
            message = StunCodec.sign(message, key);
        }
        socket.send(new DatagramPacket(message, message.length, to));
    }

    private static int successType(int requestType) {
        return requestType | 0x0100;
    }

    private static int errorType(int requestType) {
        return requestType | 0x0110;
    }

    private static String key(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        for (Allocation allocation : byClient.values()) {
            allocation.relay.close();
        }
    }
}
