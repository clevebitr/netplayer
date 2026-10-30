package com.clevebitr.netplayer;

import com.clevebitr.netplayer.core.Bytes;
import com.clevebitr.netplayer.core.Crypto;
import com.clevebitr.netplayer.core.Identity;
import com.clevebitr.netplayer.core.Invite;
import com.clevebitr.netplayer.core.Json;
import com.clevebitr.netplayer.core.Msg;
import com.clevebitr.netplayer.core.Profile;
import com.clevebitr.netplayer.core.SignalClient;
import com.clevebitr.netplayer.net.IceLink;
import com.clevebitr.netplayer.net.Tunnel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client side brain: own identity, friend list, signaling connection, world hosting and the P2P
 * tunnels. All Minecraft touching work is bounced onto the client thread.
 */
public final class NetPlayerClient {
    private static NetPlayerClient instance;

    public static synchronized NetPlayerClient get() {
        if (instance == null) {
            instance = new NetPlayerClient(Minecraft.getInstance());
        }
        return instance;
    }

    public record Room(String id, String world, String token, Set<String> allow, int port) {
    }

    public record Pending(Profile guest, String room, String world, long at) {
    }

    private final Minecraft mc;
    private final Path dir;
    private final Path configFile;
    private final Path identityFile;
    private final Path friendsFile;

    private final Map<String, Friend> friends = new ConcurrentHashMap<>();
    private final Map<String, Presence> presences = new ConcurrentHashMap<>();
    private final List<Pending> pendingJoins = new CopyOnWriteArrayList<>();
    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();
    private final Map<String, IceState> icePeers = new ConcurrentHashMap<>();
    private final Map<String, Msg> earlyIce = new ConcurrentHashMap<>();

    private com.clevebitr.netplayer.Config config;
    private Identity identity;
    private SignalClient signal;
    private volatile boolean helloDone;
    private volatile long lastConnectAttempt;
    private String sentPresence = "";
    private volatile boolean watchDirty = true;
    private volatile Room hosting;
    private volatile JoinFlow joinFlow;
    private boolean initialized;

    private NetPlayerClient(Minecraft mc) {
        this.mc = mc;
        this.dir = mc.gameDirectory.toPath().resolve("netplayer");
        this.configFile = dir.resolve("config.json");
        this.identityFile = dir.resolve("identity.json");
        this.friendsFile = dir.resolve("friends.json");
    }

    // ------------------------------------------------------------------ lifecycle

    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        config = com.clevebitr.netplayer.Config.load(configFile);
        String name = mc.getUser() == null ? "player" : mc.getUser().getName();
        identity = Identity.loadOrCreate(identityFile, name);
        if (!name.equals(identity.profile.name) && !name.isBlank()) {
            identity = identity.renamed(name, identityFile);
        }
        loadFriends();
    }

    public void tick() {
        init();
        long now = System.currentTimeMillis();
        if (!connected() && now - lastConnectAttempt > 8000L) {
            lastConnectAttempt = now;
            Thread.ofVirtual().name("netplayer-signal-connect").start(this::connect);
        }
        if (connected() && helloDone && watchDirty) {
            watchDirty = false;
            sendWatch();
        }
        updatePresence();
        if (hosting != null && mc.getSingleplayerServer() == null) {
            closeWorld(false);
        }
        progressJoin();
    }

    private void connect() {
        SignalClient client = new SignalClient(new SignalClient.Listener() {
            @Override
            public void onMessage(Msg msg) {
                handle(msg);
            }

            @Override
            public void onClosed(String reason) {
                helloDone = false;
                sentPresence = "";
            }
        });
        if (!client.connect(config.signalingHost, config.signalingPort, 5000)) {
            return;
        }
        signal = client;
        long ts = System.currentTimeMillis();
        String payload = "hello:" + identity.profile.id + ":" + ts;
        signal.send(Msg.of("hello")
                .put("profile", identity.profile.toJson())
                .put("ts", ts)
                .put("sig", Bytes.b64(identity.sign(payload.getBytes(StandardCharsets.UTF_8)))));
    }

    public boolean connected() {
        return signal != null && signal.isConnected() && helloDone;
    }

    public boolean signalReachable() {
        return signal != null && signal.isConnected();
    }

    private void send(Msg msg) {
        SignalClient client = signal;
        if (client != null) {
            client.send(msg);
        }
    }

    private void sendWatch() {
        send(Msg.of("watch").put("ids", new ArrayList<>(friends.keySet())));
    }

    // ------------------------------------------------------------------ identity and friends

    public Profile me() {
        init();
        return identity.profile;
    }

    public String myFriendKey() {
        return Invite.friendKey(me());
    }

    public String myRoomKey() {
        Room room = hosting;
        if (room == null) {
            return "";
        }
        return Invite.roomKey(new Invite.Room(room.id(), me(), room.world(), room.token(),
                System.currentTimeMillis() + 6L * 60L * 60L * 1000L));
    }

    public List<Friend> friends() {
        init();
        List<Friend> list = new ArrayList<>(friends.values());
        list.sort(Comparator
                .comparing((Friend f) -> !presence(f.profile.id).openTo(me().id))
                .thenComparing(f -> !presence(f.profile.id).online())
                .thenComparing(Friend::label, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Presence presence(String id) {
        Presence p = presences.get(id);
        return p == null ? Presence.offline(id) : p;
    }

    public String addFriend(String text) {
        init();
        if (text == null || text.isBlank()) {
            return "netplayer.error.empty";
        }
        try {
            if (!Invite.looksLikeKey(text)) {
                return "netplayer.error.not_key";
            }
            if (text.strip().startsWith("npfriend1.")) {
                Profile profile = Invite.parseFriendKey(text);
                if (profile.id.equals(me().id)) {
                    return "netplayer.error.self";
                }
                storeFriend(profile);
                send(Msg.of("add").put("to", profile.id).put("profile", me().toJson()));
                return null;
            }
            Invite.Room room = Invite.parseRoomKey(text);
            storeFriend(room.host());
            requestJoin(room.host().id, room.roomId(), room.token());
            return null;
        } catch (Exception e) {
            return "netplayer.error.bad_key";
        }
    }

    public void removeFriend(String id) {
        friends.remove(id);
        presences.remove(id);
        saveFriends();
        watchDirty = true;
    }

    private void storeFriend(Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty()) {
            return;
        }
        friends.computeIfAbsent(profile.id, k -> new Friend(profile));
        saveFriends();
        watchDirty = true;
    }

    private void loadFriends() {
        friends.clear();
        if (!Files.isRegularFile(friendsFile)) {
            return;
        }
        try {
            JsonObject root = Json.parse(Files.readString(friendsFile));
            JsonElement list = root.get("friends");
            if (list != null && list.isJsonArray()) {
                for (JsonElement element : list.getAsJsonArray()) {
                    Friend friend = new Friend(Profile.fromJson(element.getAsJsonObject()));
                    friends.put(friend.profile.id, friend);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveFriends() {
        try {
            JsonArray array = new JsonArray();
            for (Friend friend : friends.values()) {
                array.add(friend.profile.toJson());
            }
            JsonObject root = Json.obj();
            root.add("friends", array);
            Files.createDirectories(dir);
            Files.writeString(friendsFile, Json.GSON.toJson(root));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ presence

    private void updatePresence() {
        if (!connected()) {
            return;
        }
        String state = "menu";
        String detail = "";
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level != null && server != null) {
            state = hosting != null ? "hosting" : "playing";
            detail = worldName(server);
        } else if (mc.level != null) {
            state = "server";
            ServerData current = mc.getCurrentServer();
            detail = current == null ? "" : current.ip;
        }
        String marker = state + "|" + detail;
        if (!marker.equals(sentPresence)) {
            sentPresence = marker;
            send(Msg.of("presence").put("state", state).put("detail", detail));
        }
    }

    private static String worldName(IntegratedServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private void onPresence(Msg msg) {
        String id = msg.str("id");
        String state = msg.str("state");
        Presence presence = new Presence(id, state.isEmpty() ? "offline" : state, msg.str("detail"));
        JsonObject room = msg.obj("room");
        presence.roomId = Json.str(room, "id");
        presence.world = Json.str(room, "world");
        JsonElement allow = room.get("allow");
        if (allow != null && allow.isJsonArray()) {
            for (JsonElement element : allow.getAsJsonArray()) {
                presence.allow.add(element.getAsString());
            }
        }
        Presence previous = presences.put(id, presence);
        if (!friends.containsKey(id)) {
            return;
        }
        boolean cameOnline = (previous == null || !previous.online()) && presence.online();
        boolean startedHosting = previous != null && !previous.hosting() && presence.hosting();
        if (cameOnline) {
            Component text = presence.detail.isEmpty()
                    ? Component.translatable("netplayer.toast.online")
                    : Component.translatable("netplayer.toast.playing", presence.detail);
            Toasts.info(Component.literal(friendName(id)), text);
        } else if (startedHosting) {
            Toasts.info(Component.literal(friendName(id)), Component.translatable("netplayer.toast.hosting"));
        }
    }

    private String friendName(String id) {
        Friend friend = friends.get(id);
        return friend == null ? id : friend.label();
    }

    // ------------------------------------------------------------------ signaling messages

    private void handle(Msg msg) {
        switch (msg.type) {
            case "hello_ok" -> {
                helloDone = true;
                watchDirty = true;
                sentPresence = "";
                if (hosting != null) {
                    sendRoomOpen();
                }
            }
            case "presence" -> onPresence(msg);
            case "add" -> {
                Profile profile = Profile.fromJson(msg.obj("from"));
                if (profile.id != null && !profile.id.isEmpty() && !friends.containsKey(profile.id)) {
                    storeFriend(profile);
                    Toasts.info(Component.literal(profile.shortName()),
                            Component.translatable("netplayer.toast.friend_added"));
                }
            }
            case "join_req" -> {
                Profile guest = Profile.fromJson(msg.obj("guest"));
                pendingJoins.add(new Pending(guest, msg.str("room"), msg.str("world"), System.currentTimeMillis()));
                Toasts.info(Component.literal(guest.shortName()),
                        Component.translatable("netplayer.toast.join_request"));
            }
            case "join_ans" -> onJoinAnswer(msg);
            case "ice" -> onIce(msg);
            case "room_closed" -> {
                JoinFlow flow = joinFlow;
                if (flow != null && flow.roomId.equals(msg.str("room"))) {
                    failJoin("netplayer.error.room_closed");
                }
            }
            case "error" -> Toasts.info(Component.translatable("netplayer.title"),
                    Component.literal(msg.str("reason")));
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ hosting

    public boolean hosting() {
        return hosting != null;
    }

    public Room room() {
        return hosting;
    }

    public List<Pending> pendingJoins() {
        return new ArrayList<>(pendingJoins);
    }

    public void openWorld(GameType gameType, boolean allowCheats, boolean everyone, Set<String> allowed) {
        init();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.no_world"));
            return;
        }
        int port = server.isPublished() ? server.getPort() : freePort();
        if (!server.isPublished() && !server.publishServer(gameType, allowCheats, port)) {
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.lan_failed"));
            return;
        }
        Set<String> allow = new HashSet<>();
        if (everyone) {
            allow.add("*");
        } else {
            allow.addAll(allowed);
        }
        hosting = new Room(Bytes.hex(Bytes.random(8)), worldName(server), Bytes.hex(Bytes.random(8)), allow, port);
        sendRoomOpen();
        updatePresence();
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.toast.world_open"));
    }

    private void sendRoomOpen() {
        Room room = hosting;
        if (room == null) {
            return;
        }
        JsonObject payload = Json.obj();
        payload.addProperty("id", room.id());
        payload.addProperty("world", room.world());
        payload.addProperty("token", room.token());
        JsonArray allow = new JsonArray();
        room.allow().forEach(allow::add);
        payload.add("allow", allow);
        send(Msg.of("room_open").put("room", payload));
    }

    public void closeWorld() {
        closeWorld(true);
    }

    private void closeWorld(boolean announce) {
        Room room = hosting;
        hosting = null;
        pendingJoins.clear();
        for (Tunnel tunnel : tunnels.values()) {
            tunnel.close();
        }
        tunnels.clear();
        for (IceState state : icePeers.values()) {
            state.close();
        }
        icePeers.clear();
        if (announce && room != null) {
            send(Msg.of("room_close").put("room", room.id()));
        }
        sentPresence = "";
        updatePresence();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 25565;
        }
    }

    // ------------------------------------------------------------------ joining

    public boolean canJoin(String friendId) {
        return presence(friendId).openTo(me().id);
    }

    public void requestJoin(String friendId) {
        Presence presence = presence(friendId);
        if (!presence.openTo(me().id)) {
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.not_open"));
            return;
        }
        requestJoin(friendId, presence.roomId, "");
    }

    private void requestJoin(String hostId, String roomId, String token) {
        if (!signalReachable()) {
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.offline"));
            return;
        }
        Msg msg = Msg.of("join_req").put("room", roomId).put("host", hostId);
        if (token != null && !token.isEmpty()) {
            msg.put("token", token);
        }
        send(msg);
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.toast.request_sent"));
    }

    public void answerJoin(Pending pending, boolean accept) {
        pendingJoins.remove(pending);
        Room room = hosting;
        if (room == null) {
            return;
        }
        send(Msg.of("join_ans").put("to", pending.guest().id).put("room", room.id()).put("accept", accept));
        if (accept) {
            startIce(pending.guest(), room.id(), false, room.port());
        }
    }

    private void onJoinAnswer(Msg msg) {
        if (!msg.bool("accept")) {
            String reason = msg.str("reason");
            failJoin(switch (reason) {
                case "not_allowed" -> "netplayer.error.not_allowed";
                case "host_offline" -> "netplayer.error.host_offline";
                default -> "netplayer.error.room_closed";
            });
            return;
        }
        Profile host = Profile.fromJson(msg.obj("host"));
        if (host.id == null || host.id.isEmpty()) {
            return;
        }
        storeFriend(host);
        JoinFlow flow = new JoinFlow(host, msg.str("room"), msg.str("world"));
        joinFlow = flow;
        Toasts.info(Component.literal(host.shortName()), Component.translatable("netplayer.toast.join_accepted"));
        startIce(host, flow.roomId, true, 0);
    }

    private void failJoin(String messageKey) {
        joinFlow = null;
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable(messageKey));
    }

    private void progressJoin() {
        JoinFlow flow = joinFlow;
        if (flow == null || flow.port <= 0) {
            return;
        }
        if (mc.level != null && flow.disconnectTicks == 0) {
            mc.disconnect(new TitleScreen(), false);
            flow.disconnectTicks = 20;
            return;
        }
        if (flow.disconnectTicks > 0) {
            flow.disconnectTicks--;
            return;
        }
        joinFlow = null;
        ServerAddress address = new ServerAddress("127.0.0.1", flow.port);
        ServerData data = new ServerData("NetPlayer: " + flow.host.shortName(), address.toString(),
                ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, data, false, null);
    }

    // ------------------------------------------------------------------ ICE

    private void startIce(Profile peer, String roomId, boolean controlling, int lanPort) {
        Thread.ofVirtual().name("netplayer-ice").start(() -> {
            IceState state = null;
            try {
                IceLink link = new IceLink(controlling, config.stunServers);
                KeyPair ephemeral = Crypto.xKeyPair();
                state = new IceState(link, ephemeral, controlling, lanPort);
                icePeers.put(peer.id, state);
                JsonObject description = link.localDescription();
                String epk = Bytes.b64(ephemeral.getPublic().getEncoded());
                String signed = "ice:" + roomId + ":" + Json.str(description, "ufrag") + ":"
                        + Json.str(description, "pwd") + ":" + epk;
                description.addProperty("room", roomId);
                description.addProperty("epk", epk);
                description.addProperty("sig", Bytes.b64(identity.sign(signed.getBytes(StandardCharsets.UTF_8))));
                Msg msg = Msg.of("ice", description);
                msg.put("to", peer.id);
                send(msg);
                Msg early = earlyIce.remove(peer.id);
                if (early != null) {
                    processIce(peer, early, state);
                }
            } catch (Exception e) {
                iceFailed(state);
            }
        });
    }

    private void onIce(Msg msg) {
        Profile from = Profile.fromJson(msg.obj("from"));
        IceState state = icePeers.get(from.id);
        if (state == null) {
            if (earlyIce.size() < 8) {
                earlyIce.put(from.id, msg);
            }
            return;
        }
        processIce(from, msg, state);
    }

    private void processIce(Profile from, Msg msg, IceState state) {
        String signed = "ice:" + Json.str(msg.data, "room") + ":" + msg.str("ufrag") + ":" + msg.str("pwd")
                + ":" + msg.str("epk");
        if (!Crypto.verify(from, signed, msg.str("sig"))) {
            return;
        }
        JsonObject remote = msg.data.deepCopy();
        Thread.ofVirtual().name("netplayer-ice-finish").start(() -> {
            try {
                state.link.acceptRemote(remote);
                if (!state.link.awaitConnected(30_000L)) {
                    iceFailed(state);
                    return;
                }
                SecretKeySpec key = Crypto.agree(state.ephemeral.getPrivate(), Crypto.xPublic(Json.str(remote, "epk")));
                if (state.controlling) {
                    Tunnel tunnel = Tunnel.guest(state.link, key);
                    tunnel.start();
                    tunnels.put("guest-" + from.id, tunnel);
                    JoinFlow flow = joinFlow;
                    if (flow != null) {
                        flow.port = tunnel.localPort();
                    }
                } else {
                    Tunnel tunnel = Tunnel.host(state.link, key, state.lanPort);
                    tunnel.start();
                    tunnels.put(from.id, tunnel);
                }
            } catch (Exception e) {
                iceFailed(state);
            }
        });
    }

    private void iceFailed(IceState state) {
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.ice_failed"));
        if (state != null) {
            state.close();
            icePeers.values().remove(state);
        }
        if (joinFlow != null) {
            joinFlow = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class IceState {
        final IceLink link;
        final KeyPair ephemeral;
        final boolean controlling;
        final int lanPort;

        IceState(IceLink link, KeyPair ephemeral, boolean controlling, int lanPort) {
            this.link = link;
            this.ephemeral = ephemeral;
            this.controlling = controlling;
            this.lanPort = lanPort;
        }

        void close() {
            link.close();
        }
    }

    private static final class JoinFlow {
        final Profile host;
        final String roomId;
        final String world;
        volatile int port;
        volatile int disconnectTicks;

        JoinFlow(Profile host, String roomId, String world) {
            this.host = host;
            this.roomId = roomId;
            this.world = world;
        }
    }

    public Map<String, Friend> friendMap() {
        return new LinkedHashMap<>(friends);
    }
}
