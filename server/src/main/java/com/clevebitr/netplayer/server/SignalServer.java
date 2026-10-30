package com.clevebitr.netplayer.server;

import com.clevebitr.netplayer.core.Crypto;
import com.clevebitr.netplayer.core.Json;
import com.clevebitr.netplayer.core.Msg;
import com.clevebitr.netplayer.core.Profile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rendezvous server: it only relays presence, friend requests and ICE candidates. Game traffic never
 * touches this process.
 */
public final class SignalServer implements AutoCloseable {
    private static final long PENDING_TTL_MS = 24L * 60L * 60L * 1000L;

    private final int port;
    private final Map<String, Session> online = new HashMap<>();
    private final Map<String, Set<String>> watchers = new HashMap<>();
    private final Map<String, Room> rooms = new HashMap<>();
    private final Map<String, List<Pending>> pendingAdds = new HashMap<>();
    private ServerSocket server;
    private final AtomicLong tickets = new AtomicLong();

    public SignalServer(int port) {
        this.port = port;
    }

    private record Room(String id, String host, String world, String token, Set<String> allow, boolean premium,
                        long opened) {
    }

    private record Pending(long at, JsonObject profile) {
    }

    public void start() throws Exception {
        server = new ServerSocket(port);
        log("listening on " + server.getLocalPort());
        Thread.ofVirtual().name("netplayer-accept").start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    Session session = new Session(s);
                    Thread.ofVirtual().name("netplayer-session").start(() -> serve(session));
                } catch (Exception e) {
                    if (!server.isClosed()) {
                        log("accept failed: " + e.getMessage());
                    }
                }
            }
        });
    }

    private void serve(Session session) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(session.socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(session.socket.getOutputStream(), StandardCharsets.UTF_8))) {
            session.out = out;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                handle(session, Msg.parse(line));
            }
        } catch (Exception ignored) {
        } finally {
            disconnect(session);
        }
    }

    private void handle(Session session, Msg msg) {
        switch (msg.type) {
            case "hello" -> hello(session, msg);
            case "watch" -> watch(session, msg);
            case "presence" -> presence(session, msg);
            case "add" -> addFriend(session, msg);
            case "room_open" -> roomOpen(session, msg);
            case "room_close" -> roomClose(session, msg);
            case "join_req" -> joinRequest(session, msg);
            case "join_ans" -> joinAnswer(session, msg);
            case "ice" -> relayIce(session, msg);
            default -> {
            }
        }
    }

    private synchronized void hello(Session session, Msg msg) {
        JsonObject profileJson = msg.obj("profile");
        Profile profile = Profile.fromJson(profileJson);
        long ts = msg.num("ts");
        if (profile.id.isEmpty() || Math.abs(System.currentTimeMillis() - ts) > 120_000L) {
            reject(session, "stale hello");
            return;
        }
        if (!Crypto.verify(profile, "hello:" + profile.id + ":" + ts, msg.str("sig"))) {
            reject(session, "bad signature");
            return;
        }
        Session previous = online.put(profile.id, session);
        if (previous != null && previous != session) {
            previous.close("replaced by a new session");
        }
        session.id = profile.id;
        session.profile = profileJson;
        log(profile.shortName() + " (" + profile.id + ") connected from " + session.socket.getRemoteSocketAddress());
        send(session, Msg.of("hello_ok").put("id", profile.id));
        for (Pending p : takePending(profile.id)) {
            send(session, Msg.of("add").put("from", p.profile()));
        }
        for (String watcher : subscribersOf(profile.id)) {
            Session w = online.get(watcher);
            if (w != null) {
                send(w, presenceOf(profile.id));
            }
        }
    }

    private synchronized void watch(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Set<String> ids = new HashSet<>(msg.strings("ids"));
        watchers.entrySet().removeIf(e -> {
            e.getValue().remove(session.id);
            return e.getValue().isEmpty();
        });
        for (String id : ids) {
            watchers.computeIfAbsent(id, k -> new HashSet<>()).add(session.id);
            if (online.containsKey(id)) {
                send(session, presenceOf(id));
            }
        }
    }

    private synchronized void presence(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        session.state = msg.str("state").isEmpty() ? "menu" : msg.str("state");
        session.detail = msg.str("detail");
        session.mcName = msg.str("name");
        session.mcUuid = msg.str("uuid");
        broadcastPresence(session.id);
    }

    private synchronized void addFriend(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        String to = msg.str("to");
        if (to.isEmpty() || to.equals(session.id)) {
            return;
        }
        JsonObject from = session.profile;
        Session target = online.get(to);
        if (target != null) {
            send(target, Msg.of("add").put("from", from));
            return;
        }
        pendingAdds.computeIfAbsent(to, k -> new ArrayList<>())
                .add(new Pending(System.currentTimeMillis(), from));
    }

    private synchronized void roomOpen(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        JsonObject room = msg.obj("room");
        String id = Json.str(room, "id");
        if (id.isEmpty()) {
            return;
        }
        Set<String> allow = new HashSet<>();
        if (room.has("allow") && room.get("allow").isJsonArray()) {
            room.getAsJsonArray("allow").forEach(e -> allow.add(e.getAsString()));
        } else {
            allow.add("*");
        }
        rooms.put(id, new Room(id, session.id, Json.str(room, "world"), Json.str(room, "token"), allow,
                Json.bool(room, "premium"), System.currentTimeMillis()));
        session.roomId = id;
        broadcastPresence(session.id);
    }

    private synchronized void roomClose(Session session, Msg msg) {
        Room room = rooms.remove(msg.str("room"));
        if (room == null) {
            return;
        }
        if (session.roomId != null && session.roomId.equals(room.id())) {
            session.roomId = null;
        }
        broadcastPresence(room.host());
        Session host = online.get(room.host());
        if (host != null) {
            send(host, Msg.of("room_closed").put("room", room.id()));
        }
    }

    private synchronized void joinRequest(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Room room = rooms.get(msg.str("room"));
        if (room == null) {
            send(session, Msg.of("join_ans").put("room", msg.str("room")).put("accept", false)
                    .put("reason", "room_closed"));
            return;
        }
        boolean allowed = room.allow().contains("*") || room.allow().contains(session.id);
        String token = msg.str("token");
        if (!allowed && token != null && !token.isEmpty() && token.equals(room.token())) {
            allowed = true;
        }
        if (!allowed) {
            send(session, Msg.of("join_ans").put("room", room.id()).put("accept", false)
                    .put("reason", "not_allowed"));
            return;
        }
        Session host = online.get(room.host());
        if (host == null) {
            send(session, Msg.of("join_ans").put("room", room.id()).put("accept", false)
                    .put("reason", "host_offline"));
            return;
        }
        send(host, Msg.of("join_req").put("room", room.id()).put("world", room.world())
                .put("ticket", tickets.incrementAndGet()).put("guest", session.profile)
                .put("mc", msg.obj("mc")));
    }

    private synchronized void joinAnswer(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Session guest = online.get(msg.str("to"));
        if (guest == null) {
            return;
        }
        Room room = rooms.get(msg.str("room"));
        Msg out = Msg.of("join_ans").put("room", msg.str("room")).put("accept", msg.bool("accept"));
        if (msg.bool("accept") && room != null) {
            out.put("host", session.profile).put("world", room.world()).put("token", room.token());
        }
        send(guest, out);
    }

    private synchronized void relayIce(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Session target = online.get(msg.str("to"));
        if (target == null) {
            return;
        }
        msg.data.add("from", session.profile);
        msg.data.remove("to");
        send(target, msg);
    }

    private synchronized void disconnect(Session session) {
        if (session.id == null) {
            try {
                session.socket.close();
            } catch (Exception ignored) {
            }
            return;
        }
        if (online.get(session.id) == session) {
            online.remove(session.id);
            // Note: watchers.get(session.id) (the sessions that follow me) is intentionally kept, so
            // the next hello() can push my fresh presence to them. Only my own subscriptions go away.
            watchers.entrySet().removeIf(e -> {
                e.getValue().remove(session.id);
                return e.getValue().isEmpty();
            });
            String roomId = session.roomId;
            if (roomId != null) {
                Room room = rooms.remove(roomId);
                if (room != null) {
                    log("room " + roomId + " closed with its host");
                }
            }
            log(session.profileName() + " (" + session.id + ") disconnected");
            for (String watcher : subscribersOf(session.id)) {
                Session w = online.get(watcher);
                if (w != null) {
                    send(w, Msg.of("presence").put("id", session.id).put("state", "offline").put("detail", ""));
                }
            }
        }
        session.close("bye");
    }

    private List<Pending> takePending(String id) {
        List<Pending> list = pendingAdds.remove(id);
        if (list == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return list.stream().filter(p -> now - p.at() < PENDING_TTL_MS).toList();
    }

    private Set<String> subscribersOf(String id) {
        Set<String> set = watchers.get(id);
        return set == null ? Set.of() : new HashSet<>(set);
    }

    private Msg presenceOf(String id) {
        Session s = online.get(id);
        Msg msg = Msg.of("presence").put("id", id);
        if (s == null) {
            return msg.put("state", "offline").put("detail", "");
        }
        msg.put("state", s.state).put("detail", s.detail).put("name", s.mcName).put("uuid", s.mcUuid);
        Room room = s.roomId == null ? null : rooms.get(s.roomId);
        if (room != null && room.host().equals(id)) {
            JsonArray allow = new JsonArray();
            room.allow().forEach(allow::add);
            JsonObject roomJson = Json.obj();
            roomJson.addProperty("id", room.id());
            roomJson.addProperty("world", room.world());
            roomJson.addProperty("host", room.host());
            roomJson.addProperty("premium", room.premium());
            roomJson.add("allow", allow);
            msg.put("room", roomJson);
        }
        return msg;
    }

    private void broadcastPresence(String id) {
        Msg presence = presenceOf(id);
        for (String watcher : subscribersOf(id)) {
            Session w = online.get(watcher);
            if (w != null) {
                send(w, presence);
            }
        }
    }

    private void reject(Session session, String reason) {
        send(session, Msg.of("error").put("reason", reason));
        session.close(reason);
    }

    private void send(Session session, Msg msg) {
        if (session.out == null) {
            return;
        }
        try {
            synchronized (session) {
                session.out.write(msg.toLine());
                session.out.write('\n');
                session.out.flush();
            }
        } catch (Exception e) {
            session.close("write failed");
        }
    }

    private void log(String text) {
        System.out.println("[" + java.time.LocalTime.now().withNano(0) + "] [netplayer] " + text);
    }

    @Override
    public void close() {
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
        synchronized (this) {
            for (Session s : online.values()) {
                s.close("server stopping");
            }
            online.clear();
            rooms.clear();
        }
    }

    private static final class Session {
        final Socket socket;
        BufferedWriter out;
        String id;
        JsonObject profile;
        String state = "menu";
        String detail = "";
        String mcName = "";
        String mcUuid = "";
        String roomId;

        Session(Socket socket) {
            this.socket = socket;
        }

        String profileName() {
            return profile == null ? "anonymous" : Json.str(profile, "name");
        }

        void close(String reason) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
