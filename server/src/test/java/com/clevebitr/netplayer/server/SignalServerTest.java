package com.clevebitr.netplayer.server;

import com.clevebitr.netplayer.core.Bytes;
import com.clevebitr.netplayer.core.Identity;
import com.clevebitr.netplayer.core.Json;
import com.clevebitr.netplayer.core.Msg;
import com.clevebitr.netplayer.core.Profile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java server and the Cloudflare worker share one protocol, so they share one threat model:
 * an id has to be backed by the key that signs for it, room ids and allow lists stay server side,
 * and only the host may answer or close its own room.
 */
class SignalServerTest {
    private SignalServer server;

    @BeforeEach
    void start() throws Exception {
        server = new SignalServer(0);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void anIdMustHashFromItsOwnKey() throws Exception {
        Identity alice = Identity.create("Alice");
        Identity mallory = Identity.create("Mallory");
        // Mallory signs a perfectly valid hello, but claims Alice's id with her own key.
        Profile stolen = new Profile(alice.profile.id, "Alice", mallory.profile.ed, mallory.profile.x);
        try (Peer peer = new Peer(server.port())) {
            peer.hello(stolen, mallory);
            Msg error = peer.await("error");
            assertEquals("id does not match key", error.str("reason"));
        }
    }

    @Test
    void presenceDoesNotLeakTheRoomIdOrTheAllowList() throws Exception {
        Identity host = Identity.create("Host");
        Identity guest = Identity.create("Guest");
        try (Peer h = new Peer(server.port()); Peer g = new Peer(server.port())) {
            h.hello(host);
            h.await("hello_ok");
            g.hello(guest);
            g.await("hello_ok");

            h.send(Msg.of("room_open").put("room", room("room-1", "tok-1", List.of(guest.profile.id))));
            g.send(Msg.of("watch").put("ids", List.of(host.profile.id)));

            Msg presence = g.await("presence");
            JsonObject room = Json.sub(presence.data, "room");
            assertEquals("World", Json.str(room, "world"));
            assertTrue(Json.bool(room, "open"), "the friend on the allow list sees it as open");
            assertFalse(room.has("id"), "the room id must stay on the server");
            assertFalse(room.has("allow"), "the allow list must stay on the server");
            assertFalse(room.has("token"), "the token must stay on the server");
        }
    }

    @Test
    void onlyTheHostCanAnswerAndOnlyForAWaitingGuest() throws Exception {
        Identity host = Identity.create("Host");
        Identity guest = Identity.create("Guest");
        Identity mallory = Identity.create("Mallory");
        try (Peer h = new Peer(server.port());
             Peer g = new Peer(server.port());
             Peer m = new Peer(server.port())) {
            h.hello(host);
            h.await("hello_ok");
            g.hello(guest);
            g.await("hello_ok");
            m.hello(mallory);
            m.await("hello_ok");

            h.send(Msg.of("room_open").put("room", room("room-1", "tok-1", List.of("*"))));
            // The guest joins by naming the host: the room id never left the server.
            g.send(Msg.of("join_req").put("host", host.profile.id));
            Msg request = h.await("join_req");
            assertEquals(guest.profile.id, Json.str(Json.sub(request.data, "guest"), "id"));

            // A stranger answering "accept" must not be forwarded (and must not see the token).
            m.send(Msg.of("join_ans").put("to", guest.profile.id).put("room", "room-1").put("accept", true));
            assertNull(g.poll("join_ans", 400));

            // The host's answer is delivered and carries the host identity plus the room token.
            h.send(Msg.of("join_ans").put("to", guest.profile.id).put("room", "room-1").put("accept", true));
            Msg answer = g.await("join_ans");
            assertTrue(answer.bool("accept"));
            assertEquals("tok-1", answer.str("token"));
            assertEquals(host.profile.id, Json.str(Json.sub(answer.data, "host"), "id"));
        }
    }

    @Test
    void onlyTheHostCanCloseItsRoom() throws Exception {
        Identity host = Identity.create("Host");
        Identity mallory = Identity.create("Mallory");
        try (Peer h = new Peer(server.port()); Peer m = new Peer(server.port())) {
            h.hello(host);
            h.await("hello_ok");
            m.hello(mallory);
            m.await("hello_ok");

            h.send(Msg.of("room_open").put("room", room("room-1", "tok-1", List.of("*"))));
            m.send(Msg.of("room_close").put("room", "room-1"));

            m.send(Msg.of("join_req").put("host", host.profile.id));
            Msg request = h.await("join_req");
            assertEquals("room-1", request.str("room"));
        }
    }

    @Test
    void aRoomTokenStillBypassesTheAllowListAndTellsTheHost() throws Exception {
        Identity host = Identity.create("Host");
        Identity stranger = Identity.create("Stranger");
        try (Peer h = new Peer(server.port()); Peer s = new Peer(server.port())) {
            h.hello(host);
            h.await("hello_ok");
            s.hello(stranger);
            s.await("hello_ok");

            h.send(Msg.of("room_open").put("room", room("room-1", "tok-1", List.of())));
            s.send(Msg.of("join_req").put("room", "room-1").put("token", "wrong"));
            assertEquals("not_allowed", s.await("join_ans").str("reason"));

            s.send(Msg.of("join_req").put("room", "room-1").put("token", "tok-1"));
            Msg request = h.await("join_req");
            assertEquals("tok-1", request.str("token"));
        }
    }

    private static JsonObject room(String id, String token, List<String> allow) {
        JsonObject room = Json.obj();
        room.addProperty("id", id);
        room.addProperty("world", "World");
        room.addProperty("token", token);
        room.addProperty("premium", false);
        JsonArray list = new JsonArray();
        allow.forEach(list::add);
        room.add("allow", list);
        return room;
    }

    /** Minimal protocol client over a raw socket. */
    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final BufferedWriter out;

        Peer(int port) throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(4000);
            in = socket.getInputStream();
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void hello(Identity identity) throws Exception {
            hello(identity.profile, identity);
        }

        void hello(Profile profile, Identity signer) throws Exception {
            long ts = System.currentTimeMillis();
            send(Msg.of("hello").put("profile", profile.toJson()).put("ts", ts)
                    .put("sig", Bytes.b64(signer.sign(("hello:" + profile.id + ":" + ts)
                            .getBytes(StandardCharsets.UTF_8)))));
        }

        void send(Msg msg) throws Exception {
            out.write(msg.toLine());
            out.write('\n');
            out.flush();
        }

        /** Next message of {@code type}, skipping the others. */
        Msg await(String type) throws Exception {
            Msg msg = poll(type, 4000);
            assertNotNull(msg, "no " + type + " arrived");
            return msg;
        }

        Msg poll(String type, int timeoutMs) throws Exception {
            socket.setSoTimeout(timeoutMs);
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                while (System.currentTimeMillis() < deadline) {
                    String line = readLine();
                    if (line == null) {
                        return null;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    Msg msg = Msg.parse(line);
                    if (msg.type.equals(type)) {
                        return msg;
                    }
                }
            } catch (java.net.SocketTimeoutException timeout) {
                return null;
            } finally {
                socket.setSoTimeout(4000);
            }
            return null;
        }

        private String readLine() throws Exception {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    return sb.toString();
                }
                sb.append((char) c);
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

}
