package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {
    @Test
    void friendKeyRoundTrip() {
        Identity identity = Identity.create("Alice");
        String key = Invite.friendKey(identity.profile);
        assertTrue(Invite.looksLikeKey(key));
        Profile parsed = Invite.parseFriendKey(key);
        assertEquals(identity.profile.id, parsed.id);
        assertEquals("Alice", parsed.name);
        assertEquals(identity.profile.ed, parsed.ed);
    }

    @Test
    void roomKeyRoundTrip() {
        Identity host = Identity.create("Host");
        Invite.Room room = new Invite.Room("room-1", host.profile, "My World", "token", 42L, true);
        Invite.Room parsed = Invite.parseRoomKey(Invite.roomKey(room));
        assertEquals("room-1", parsed.roomId());
        assertEquals("My World", parsed.world());
        assertEquals("token", parsed.token());
        assertEquals(host.profile.id, parsed.host().id);
        assertEquals(true, parsed.premium());
    }

    @Test
    void tamperedKeyIsRejected() {
        Identity identity = Identity.create("Alice");
        String key = Invite.friendKey(identity.profile);
        String tampered = key.substring(0, key.length() - 3) + "abc";
        assertThrows(IllegalArgumentException.class, () -> Invite.parseFriendKey(tampered));
    }

    @Test
    void signaturesVerifyAndReject() {
        Identity alice = Identity.create("Alice");
        byte[] data = "hello:123".getBytes(StandardCharsets.UTF_8);
        String signature = Bytes.b64(alice.sign(data));
        assertTrue(Crypto.verify(alice.profile, "hello:123", signature));
        assertTrue(!Crypto.verify(alice.profile, "hello:124", signature));
    }

    @Test
    void x25519AgreementMatches() {
        KeyPair a = Crypto.xKeyPair();
        KeyPair b = Crypto.xKeyPair();
        SecretKeySpec ka = Crypto.agree(a.getPrivate(), b.getPublic());
        SecretKeySpec kb = Crypto.agree(b.getPrivate(), a.getPublic());
        assertEquals(Bytes.b64(ka.getEncoded()), Bytes.b64(kb.getEncoded()));

        byte[] plain = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = Crypto.seal(ka, plain);
        assertArrayEquals(plain, Crypto.open(kb, sealed, 0, sealed.length));
        sealed[sealed.length - 1] ^= 0x01;
        assertNull(Crypto.open(kb, sealed, 0, sealed.length));
    }

    @Test
    void anIdMustBelongToTheKeyThatSignsForIt() {
        Identity alice = Identity.create("Alice");
        Identity mallory = Identity.create("Mallory");
        // Mallory claims Alice's id with her own key: this is the shape of every impersonation.
        Profile forged = new Profile(alice.profile.id, "Alice", mallory.profile.ed, mallory.profile.x);
        assertTrue(alice.profile.selfCertified());
        assertTrue(!forged.selfCertified());
        String sig = Bytes.b64(mallory.sign(("hello:" + alice.profile.id + ":1").getBytes(StandardCharsets.UTF_8)));
        assertTrue(!Crypto.verify(forged, "hello:" + alice.profile.id + ":1", sig));
        assertThrows(IllegalArgumentException.class, () -> Invite.parseFriendKey(Invite.friendKey(forged)));
    }

    @Test
    void canonicalJsonIgnoresFieldOrder() {
        JsonObject a = Json.obj();
        a.addProperty("room", "r1");
        a.addProperty("premium", true);
        JsonObject b = Json.obj();
        b.addProperty("premium", true);
        b.addProperty("room", "r1");
        assertEquals(Json.canonical(a), Json.canonical(b));
        b.addProperty("premium", false);
        assertNotEquals(Json.canonical(a), Json.canonical(b));
    }

    @Test
    void iceBasisBindsBothEnds() {
        JsonObject desc = Json.obj();
        desc.addProperty("ufrag", "u");
        desc.addProperty("pwd", "p");
        desc.addProperty("epk", "e");
        String toHost = Handshake.ice("room", "guest", "host", desc);
        JsonObject partial = Json.obj();
        partial.addProperty("ufrag", "u");
        assertNotEquals(toHost, Handshake.ice("room", "guest", "someone-else", desc));
        assertNotEquals(toHost, Handshake.ice("other-room", "guest", "host", desc));
        assertNotEquals(toHost, Handshake.ice("room", "guest", "host", partial));
    }

    @Test
    void messagesSurviveTheWire() {
        Msg msg = Msg.of("ice").put("room", "r1").put("accept", true).put("ids", java.util.List.of("a", "b"));
        Msg parsed = Msg.parse(msg.toLine());
        assertEquals("ice", parsed.type);
        assertEquals("r1", parsed.str("room"));
        assertTrue(parsed.bool("accept"));
        assertEquals(java.util.List.of("a", "b"), parsed.strings("ids"));
        assertNotEquals("", parsed.toLine());
    }
}
