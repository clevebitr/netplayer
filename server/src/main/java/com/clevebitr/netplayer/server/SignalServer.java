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
    /** Guard rails against a single connection eating the process. */
    private static final int MAX_LINE = 64 * 1024;
    private static final int MAX_PENDING_ADDS = 64;
    private static final int MAX_WATCH_IDS = 512;
    private static final int MAX_SESSIONS = 4096;
    private static final int MAX_ICE_PER_SESSION = 256;

    private final int port;
    private final Map<String, Session> online = new HashMap<>();
    private final Map<String, Set<String>> watchers = new HashMap<>();
    private final Map<String, Room> rooms = new HashMap<>();
    /** roomId -> guests whose join_req this server forwarded and that are still unanswered. */
    private final Map<String, Set<String>> waiting = new HashMap<>();
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

    /** Port actually bound, useful when constructed with 0. */
    public int port() {
        ServerSocket socket = server;
        return socket == null ? port : socket.getLocalPort();
    }

    public void start() throws Exception {
        server = new ServerSocket(port);
        log("listening on " + server.getLocalPort());
        Thread.ofVirtual().name("netplayer-accept").start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    if (online.size() >= MAX_SESSIONS) {
                        s.close();
                        continue;
                    }
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
        try {
            java.io.InputStream in = new java.io.BufferedInputStream(session.socket.getInputStream());
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(session.socket.getOutputStream(), StandardCharsets.UTF_8));
            session.out = out;
            String line;
            while ((line = readLine(in)) != null) {
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

    /** One \n terminated line with a hard length cap, so one client cannot allocate unboundedly. */
    private static String readLine(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder(128);
        try {
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    return sb.toString();
                }
                if (sb.length() >= MAX_LINE) {
                    return null;
                }
                sb.append((char) c);
            }
        } catch (Exception e) {
            return null;
        }
        return sb.length() == 0 ? null : sb.toString();
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
            case "ice", "relay", "kick" -> relayIce(session, msg);
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
        // The id has to be the hash of the signing key, otherwise a client could announce any id
        // it likes (and kick that user off the server) with a key it owns.
        if (!profile.selfCertified()) {
            reject(session, "id does not match key");
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
                send(w, presenceOf(profile.id, watcher));
            }
        }
    }

    private synchronized void watch(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (String id : msg.strings("ids")) {
            if (ids.size() >= MAX_WATCH_IDS) {
                break;
            }
            ids.add(id);
        }
        watchers.entrySet().removeIf(e -> {
            e.getValue().remove(session.id);
            return e.getValue().isEmpty();
        });
        for (String id : ids) {
            watchers.computeIfAbsent(id, k -> new HashSet<>()).add(session.id);
            if (online.containsKey(id)) {
                send(session, presenceOf(id, session.id));
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
        List<Pending> list = pendingAdds.computeIfAbsent(to, k -> new ArrayList<>());
        if (list.size() < MAX_PENDING_ADDS) {
            list.add(new Pending(System.currentTimeMillis(), from));
        }
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
        // A host has exactly one world open: drop whatever it published before.
        for (Room previous : new ArrayList<>(rooms.values())) {
            if (previous.host().equals(session.id) && !previous.id().equals(id)) {
                rooms.remove(previous.id());
                waiting.remove(previous.id());
            }
        }
        rooms.put(id, new Room(id, session.id, Json.str(room, "world"), Json.str(room, "token"), allow,
                Json.bool(room, "premium"), System.currentTimeMillis()));
        session.roomId = id;
        broadcastPresence(session.id);
    }

    private synchronized void roomClose(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Room room = rooms.get(msg.str("room"));
        // Only the host may close its own room; otherwise anyone could kick a stranger's guests.
        if (room == null || !room.host().equals(session.id)) {
            return;
        }
        rooms.remove(room.id());
        waiting.remove(room.id());
        if (session.roomId != null && session.roomId.equals(room.id())) {
            session.roomId = null;
        }
        broadcastPresence(room.host());
        send(session, Msg.of("room_closed").put("room", room.id()));
    }

    private synchronized void joinRequest(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        // The client no longer learns room ids from presence: it names the host and the server
        // resolves the currently open room. A room key (npjoin1) still arrives with an explicit id.
        String roomId = msg.str("room");
        Room room = roomId.isEmpty() ? roomOfHost(msg.str("host")) : rooms.get(roomId);
        if (room == null) {
            send(session, Msg.of("join_ans").put("room", roomId).put("accept", false)
                    .put("reason", "room_closed"));
            return;
        }
        boolean allowed = room.allow().contains("*") || room.allow().contains(session.id);
        String token = msg.str("token");
        boolean viaToken = !allowed && !token.isEmpty() && token.equals(room.token());
        if (!allowed && !viaToken) {
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
        waiting.computeIfAbsent(room.id(), k -> new HashSet<>()).add(session.id);
        send(host, Msg.of("join_req").put("room", room.id()).put("world", room.world())
                .put("ticket", tickets.incrementAndGet()).put("guest", session.profile)
                .put("token", viaToken ? token : "")
                .put("mc", msg.obj("mc")));
    }

    private synchronized void joinAnswer(Session session, Msg msg) {
        if (session.id == null) {
            return;
        }
        Room room = rooms.get(msg.str("room"));
        // Only the host of that room can answer for it, and only to a guest that actually asked.
        // Without this any session could accept on the host's behalf and receive the room token,
        // which in turn bypasses the allow list.
        if (room == null || !room.host().equals(session.id)) {
            return;
        }
        Set<String> guests = waiting.get(room.id());
        String to = msg.str("to");
        if (guests == null || !guests.contains(to)) {
            return;
        }
        guests.remove(to);
        Session guest = online.get(to);
        if (guest == null) {
            return;
        }
        boolean accept = msg.bool("accept");
        Msg out = Msg.of("join_ans").put("room", room.id()).put("accept", accept)
                .put("reason", accept ? "" : msg.str("reason"));
        if (accept) {
            out.put("host", session.profile).put("world", room.world()).put("token", room.token());
        }
        send(guest, out);
    }

    private Room roomOfHost(String hostId) {
        for (Room room : rooms.values()) {
            if (room.host().equals(hostId)) {
                return room;
            }
        }
        return null;
    }

    private synchronized void relayIce(Session session, Msg msg) {
        if (session.id == null || session.iceSent >= MAX_ICE_PER_SESSION) {
            return;
        }
        Session target = online.get(msg.str("to"));
        if (target == null) {
            return;
        }
        session.iceSent++;
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
                    waiting.remove(room.id());
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

    /**
     * Presence of {@code id} as seen by {@code viewer}. The room id, its token and the allow list
     * stay on the server: they used to be broadcast to every watcher, and anyone who can read the
     * allow list also learns the ids needed to impersonate one of the guests. The server answers
     * the only question a friend needs anyway, "is it open for me".
     */
    private Msg presenceOf(String id, String viewer) {
        Session s = online.get(id);
        Msg msg = Msg.of("presence").put("id", id);
        if (s == null) {
            return msg.put("state", "offline").put("detail", "");
        }
        msg.put("state", s.state).put("detail", s.detail).put("name", s.mcName).put("uuid", s.mcUuid);
        Room room = s.roomId == null ? null : rooms.get(s.roomId);
        if (room != null && room.host().equals(id)) {
            JsonObject roomJson = Json.obj();
            roomJson.addProperty("world", room.world());
            roomJson.addProperty("premium", room.premium());
            roomJson.addProperty("open",
                    room.allow().contains("*") || (viewer != null && room.allow().contains(viewer)));
            msg.put("room", roomJson);
        }
        return msg;
    }

    private void broadcastPresence(String id) {
        for (String watcher : subscribersOf(id)) {
            Session w = online.get(watcher);
            if (w != null) {
                send(w, presenceOf(id, watcher));
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
        int iceSent;

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
