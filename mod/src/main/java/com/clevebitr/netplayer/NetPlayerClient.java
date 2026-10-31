package com.clevebitr.netplayer;

import com.clevebitr.netplayer.core.Bytes;
import com.clevebitr.netplayer.core.Crypto;
import com.clevebitr.netplayer.core.Handshake;
import com.clevebitr.netplayer.core.Identity;
import com.clevebitr.netplayer.core.Invite;
import com.clevebitr.netplayer.core.Json;
import com.clevebitr.netplayer.core.Msg;
import com.clevebitr.netplayer.core.PremiumAuth;
import com.clevebitr.netplayer.core.Profile;
import com.clevebitr.netplayer.core.SignalClient;
import com.clevebitr.netplayer.net.IceLink;
import com.clevebitr.netplayer.net.Tunnel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
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
import org.slf4j.Logger;

/**
 * Client side brain: own identity, friend list, signaling connection, world hosting and the P2P
 * tunnels. All Minecraft touching work is bounced onto the client thread.
 */
public final class NetPlayerClient {
    private static final Logger LOG = LogUtils.getLogger();
    /** Bound the request queues: a flood must not be able to grow them without limit. */
    private static final int MAX_REQUESTS = 8;
    private static NetPlayerClient instance;

    public static synchronized NetPlayerClient get() {
        if (instance == null) {
            instance = new NetPlayerClient(Minecraft.getInstance());
        }
        return instance;
    }

    public record Room(String id, String world, String token, Set<String> allow, int port, boolean premium) {
    }

    public record Pending(Profile guest, String room, String world, long at, JsonObject proof, String token) {
    }

    private final Minecraft mc;
    private final Path dir;
    private final Path configFile;
    private final Path identityFile;
    private final Path friendsFile;

    private final Map<String, Friend> friends = new ConcurrentHashMap<>();
    private final Map<String, Presence> presences = new ConcurrentHashMap<>();
    private final List<Pending> pendingJoins = new CopyOnWriteArrayList<>();
    /** Friend requests that still need a human "accept" on this side. */
    private final List<Profile> addRequests = new CopyOnWriteArrayList<>();
    private final Map<String, Tunnel> tunnels = new ConcurrentHashMap<>();
    private final Map<String, IceState> icePeers = new ConcurrentHashMap<>();
    private final Map<String, Msg> earlyIce = new ConcurrentHashMap<>();

    private volatile String joinedHostId = "";
    private volatile long connectingSince;
    private volatile long lastConfigCheck;
    private volatile long configStamp;
    private volatile String lastConnectError = "";
    private volatile String probeResult = "";
    private volatile boolean restoreAuth = true;
    private volatile IceState directPending;
    private volatile String lastDirectOffer = "";
    private volatile String lastDirectAnswer = "";

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
        if (config.iceDebug) {
            java.util.logging.Logger.getLogger("org.ice4j").setLevel(java.util.logging.Level.INFO);
            java.util.logging.Logger.getLogger("org.jitsi").setLevel(java.util.logging.Level.INFO);
        }
        String name = mc.getUser() == null ? "player" : mc.getUser().getName();
        identity = Identity.loadOrCreate(identityFile, name);
        if (!name.equals(identity.profile.name) && !name.isBlank()) {
            identity = identity.renamed(name, identityFile);
        }
        loadFriends();
        configStamp = stamp(configFile);
        PremiumAuth.setProxy(config.proxyHost, config.proxyPort);
        LOG.info("config: signaling={} stun=[{}] premiumCheck={} proxy={}", config.signalingTarget(),
                String.join(", ", config.stunServers), config.verifyPremium,
                config.proxyHost.isBlank() ? "-" : config.proxyHost + ":" + config.proxyPort);
    }

    private static long stamp(java.nio.file.Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Reloads config.json when it was edited outside the game. */
    private void watchConfig(long now) {
        if (now - lastConfigCheck < 5000L) {
            return;
        }
        lastConfigCheck = now;
        long current = stamp(configFile);
        if (current == 0L || current == configStamp) {
            return;
        }
        configStamp = current;
        config = com.clevebitr.netplayer.Config.load(configFile);
        PremiumAuth.setProxy(config.proxyHost, config.proxyPort);
        LOG.info("config reloaded: signaling={} stun=[{}] premiumCheck={} proxy={}", config.signalingTarget(),
                String.join(", ", config.stunServers), config.verifyPremium,
                config.proxyHost.isBlank() ? "-" : config.proxyHost + ":" + config.proxyPort);
        SignalClient old = signal;
        signal = null;
        helloDone = false;
        sentPresence = "";
        lastConnectAttempt = 0L;
        if (old != null) {
            old.close();
        }
    }

    public void tick() {
        init();
        long now = System.currentTimeMillis();
        watchConfig(now);
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
            LOG.info("world closed by the host, telling friends");
            closeWorld(true);
        }
        if (mc.level != null) {
            connectingSince = 0L;
        } else if (connectingSince != 0L && now - connectingSince > 60_000L) {
            LOG.info("join attempt timed out");
            connectingSince = 0L;
            joinedHostId = "";
        }
        boolean connecting = connectingSince != 0L;
        if (mc.level == null && joinFlow == null && !connecting && (!tunnels.isEmpty() || !icePeers.isEmpty())) {
            LOG.info("left the world, closing {} tunnel(s)", tunnels.size());
            for (Tunnel tunnel : tunnels.values()) {
                tunnel.close();
            }
            tunnels.clear();
            for (IceState state : icePeers.values()) {
                state.close();
            }
            icePeers.clear();
            joinedHostId = "";
        }
        progressJoin();
    }

    private void connect() {
        final SignalClient[] self = new SignalClient[1];
        SignalClient client = new SignalClient(new SignalClient.Listener() {
            @Override
            public void onMessage(Msg msg) {
                handle(msg);
            }

            @Override
            public void onClosed(String reason) {
                // A stale client (replaced socket, previous attempt) must not clear the live session.
                if (self[0] != null && self[0] != signal) {
                    return;
                }
                helloDone = false;
                sentPresence = "";
                LOG.info("signaling link lost: {}", reason);
            }
        });
        self[0] = client;
        String target = config.signalingTarget();
        if (!connect(client)) {
            if (!client.lastError().equals(lastConnectError)) {
                lastConnectError = client.lastError();
                String hint = target.startsWith("ws") && config.proxyHost.isBlank()
                        ? " (if the endpoint needs a proxy, set one in NetPlayer settings)" : "";
                LOG.warn("signaling connect to {} failed: {}{}", target, lastConnectError, hint);
            }
            client.close();
            return;
        }
        lastConnectError = "";
        SignalClient previous = signal;
        signal = client;
        if (previous != null) {
            previous.close();
        }
        long ts = System.currentTimeMillis();
        String payload = "hello:" + identity.profile.id + ":" + ts;
        signal.send(Msg.of("hello")
                .put("profile", identity.profile.toJson())
                .put("ts", ts)
                .put("sig", Bytes.b64(identity.sign(payload.getBytes(StandardCharsets.UTF_8)))));
        LOG.info("connected to signaling server {}", target);
    }

    private boolean connect(SignalClient client) {
        String url = config.signalingUrl;
        if ((url == null || url.isBlank()) && config.signalingHost != null
                && config.signalingHost.contains("://")) {
            // tolerate a url dropped into signalingHost by hand
            url = config.signalingHost;
        }
        if (url != null && !url.isBlank()) {
            return client.connectUrl(url.trim(), 12000, config.proxyHost, config.proxyPort);
        }
        return client.connect(config.signalingHost, config.signalingPort, 5000);
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
                System.currentTimeMillis() + 6L * 60L * 60L * 1000L, room.premium()));
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
        String result = doAddFriend(text);
        LOG.info("add friend request ({} chars) -> {}", text == null ? 0 : text.strip().length(),
                result == null ? "ok" : result);
        return result;
    }

    private String doAddFriend(String text) {
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
            requestJoin(room.host().id, room.roomId(), room.token(), room.premium());
            return null;
        } catch (Exception e) {
            return "netplayer.error.bad_key";
        }
    }

    public void setNote(String id, String note) {
        Friend friend = friends.get(id);
        if (friend != null) {
            friend.note = note == null ? "" : note;
            saveFriends();
        }
    }

    public void removeFriend(String id) {
        friends.remove(id);
        presences.remove(id);
        addRequests.removeIf(p -> p.id.equals(id));
        saveFriends();
        watchDirty = true;
    }

    // ------------------------------------------------------------------ friend requests (opt in)

    /** Friend requests waiting for this user to accept or decline. */
    public List<Profile> addRequests() {
        return new ArrayList<>(addRequests);
    }

    public void answerAddRequest(Profile profile, boolean accept) {
        if (profile == null) {
            return;
        }
        addRequests.removeIf(p -> p.id.equals(profile.id));
        if (!accept) {
            return;
        }
        storeFriend(profile);
        Toasts.info(Component.literal(profile.shortName()),
                Component.translatable("netplayer.toast.friend_added"));
    }

    private void storeFriend(Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty() || !profile.selfCertified()) {
            return;
        }
        friends.computeIfAbsent(profile.id, k -> new Friend(profile));
        LOG.info("friend stored {} ({})", profile.shortName(), profile.id);
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
                    JsonObject entry = element.getAsJsonObject();
                    Profile profile = Profile.fromJson(entry);
                    if (!profile.selfCertified()) {
                        LOG.warn("dropping stored friend {}: id does not match its key", profile.id);
                        continue;
                    }
                    Friend friend = new Friend(profile);
                    friend.note = Json.str(entry, "note");
                    friend.mcName = Json.str(entry, "mc_name");
                    friend.mcUuid = Json.str(entry, "mc_uuid");
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
                JsonObject entry = friend.profile.toJson();
                entry.addProperty("note", friend.note == null ? "" : friend.note);
                entry.addProperty("mc_name", friend.mcName == null ? "" : friend.mcName);
                entry.addProperty("mc_uuid", friend.mcUuid == null ? "" : friend.mcUuid);
                array.add(entry);
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
            Msg msg = Msg.of("presence").put("state", state).put("detail", detail);
            User user = mc.getUser();
            if (user != null) {
                msg.put("name", user.getName());
                msg.put("uuid", user.getProfileId() == null ? "" : user.getProfileId().toString());
            }
            send(msg);
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
        // The server only tells us whether the world is open for us; the room id, its token and the
        // allow list stay server side on purpose.
        presence.hosting = room.size() > 0;
        presence.open = Json.bool(room, "open");
        presence.world = Json.str(room, "world");
        presence.premium = Json.bool(room, "premium");
        presence.mcName = msg.str("name");
        presence.mcUuid = msg.str("uuid");
        Friend known = friends.get(id);
        if (known != null) {
            // Only the display name comes from presence: the uuid we pin is the one Mojang
            // confirmed through the premium proof, never one the relay claims.
            if (!presence.mcName.isEmpty() && !presence.mcName.equals(known.mcName)) {
                known.mcName = presence.mcName;
                saveFriends();
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
                LOG.info("signaling handshake done as {} ({})", me().shortName(), me().id);
            }
            case "presence" -> onPresence(msg);
            case "add" -> {
                Profile profile = Profile.fromJson(msg.obj("from"));
                // Never write a friend into the list on somebody else's word: the user decides.
                if (profile.selfCertified() && !friends.containsKey(profile.id) && addRequests.size() < MAX_REQUESTS
                        && addRequests.stream().noneMatch(p -> p.id.equals(profile.id))) {
                    addRequests.add(profile);
                    Toasts.info(Component.literal(profile.shortName()),
                            Component.translatable("netplayer.toast.friend_request"));
                }
            }
            case "join_req" -> {
                Profile guest = Profile.fromJson(msg.obj("guest"));
                if (!guest.selfCertified()) {
                    LOG.warn("join request with a forged identity, ignored");
                    return;
                }
                if (pendingJoins.size() >= MAX_REQUESTS) {
                    LOG.warn("join request from {} dropped, queue is full", guest.shortName());
                    return;
                }
                pendingJoins.add(new Pending(guest, msg.str("room"), msg.str("world"), System.currentTimeMillis(),
                        msg.obj("mc"), msg.str("token")));
                LOG.info("join request from {} ({})", guest.shortName(), guest.id);
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
        restoreAuth = server.usesAuthentication();
        server.setUsesAuthentication(config.verifyPremium);
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
        hosting = new Room(Bytes.hex(Bytes.random(8)), worldName(server), Bytes.hex(Bytes.random(8)), allow, port,
                config.verifyPremium);
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
        payload.addProperty("premium", room.premium());
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
        IceState pending = directPending;
        directPending = null;
        if (pending != null) {
            pending.close();
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && server.isPublished()) {
            server.setUsesAuthentication(restoreAuth);
        }
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
        return presence(friendId).openTo(me().id) && !insideWorldOf(friendId);
    }

    /** True while we already play inside that friend's world. */
    public boolean insideWorldOf(String friendId) {
        return friendId != null && !friendId.isEmpty() && friendId.equals(joinedHostId);
    }

    public void requestJoin(String friendId) {
        if (insideWorldOf(friendId)) {
            notify("netplayer.error.already_inside");
            return;
        }
        Presence presence = presence(friendId);
        if (!presence.openTo(me().id)) {
            notify("netplayer.error.not_open");
            return;
        }
        // The room id is not public any more: the server resolves the host's open room.
        requestJoin(friendId, "", "", presence.premium);
    }

    private void requestJoin(String hostId, String roomId, String token, boolean needPremium) {
        if (!signalReachable()) {
            notify("netplayer.error.offline");
            return;
        }
        if (needPremium && !localPremiumCapable()) {
            notify("netplayer.error.not_premium_local");
            return;
        }
        Thread.ofVirtual().name("netplayer-join").start(() -> {
            JsonObject proof = Json.obj();
            if (needPremium) {
                JsonObject made = premiumProof();
                if (made == null) {
                    notify("netplayer.error.not_premium");
                    return;
                }
                proof = made;
            }
            Msg msg = Msg.of("join_req").put("room", roomId).put("host", hostId);
            if (token != null && !token.isEmpty()) {
                msg.put("token", token);
            }
            if (proof.size() > 0) {
                msg.put("mc", proof);
            }
            send(msg);
            notify("netplayer.toast.request_sent");
        });
    }

    public void answerJoin(Pending pending, boolean accept) {
        pendingJoins.remove(pending);
        Room room = hosting;
        if (room == null) {
            return;
        }
        if (!room.id().equals(pending.room())) {
            return;
        }
        if (!accept) {
            send(Msg.of("join_ans").put("to", pending.guest().id).put("room", room.id()).put("accept", false)
                    .put("reason", "declined"));
            return;
        }
        // Re-check authorization locally instead of trusting the relay: the guest either is on the
        // allow list or presented this room's token. Otherwise a compromised or buggy signaling
        // server could talk anybody into our world.
        boolean allowed = room.allow().contains("*") || room.allow().contains(pending.guest().id)
                || (!pending.token().isEmpty() && pending.token().equals(room.token()));
        if (!allowed) {
            send(Msg.of("join_ans").put("to", pending.guest().id).put("room", room.id()).put("accept", false)
                    .put("reason", "not_allowed"));
            return;
        }
        if (!config.verifyPremium) {
            acceptGuest(pending, room);
            return;
        }
        Thread.ofVirtual().name("netplayer-premium-check").start(() -> {
            if (!verifyProof(pending.proof(), pending.guest())) {
                send(Msg.of("join_ans").put("to", pending.guest().id).put("room", room.id()).put("accept", false)
                        .put("reason", "not_premium"));
                Toasts.info(Component.literal(pending.guest().shortName()),
                        Component.translatable("netplayer.error.guest_not_premium"));
                return;
            }
            acceptGuest(pending, room);
        });
    }

    private void acceptGuest(Pending pending, Room room) {
        send(Msg.of("join_ans").put("to", pending.guest().id).put("room", room.id()).put("accept", true));
        startIce(pending.guest(), room.id(), false, room.port());
    }

    private void onJoinAnswer(Msg msg) {
        if (!msg.bool("accept")) {
            String reason = msg.str("reason");
            failJoin(switch (reason) {
                case "not_allowed" -> "netplayer.error.not_allowed";
                case "host_offline" -> "netplayer.error.host_offline";
                case "not_premium" -> "netplayer.error.not_premium";
                default -> "netplayer.error.room_closed";
            });
            return;
        }
        Profile host = Profile.fromJson(msg.obj("host"));
        if (host.id == null || host.id.isEmpty()) {
            return;
        }
        if (!host.selfCertified()) {
            LOG.warn("join answer carrying an identity that does not match its key, ignored");
            failJoin("netplayer.error.bad_signature");
            return;
        }
        // Trust on first use: once we know a friend we never let a later message swap his keys.
        Friend known = friends.get(host.id);
        if (known != null) {
            host = known.profile;
        }
        storeFriend(host);
        JoinFlow flow = new JoinFlow(host, msg.str("room"), msg.str("world"));
        joinFlow = flow;
        joinedHostId = host.id;
        connectingSince = System.currentTimeMillis();
        Toasts.info(Component.literal(host.shortName()), Component.translatable("netplayer.toast.join_accepted"));
        startIce(host, flow.roomId, true, 0);
    }

    private void failJoin(String messageKey) {
        joinFlow = null;
        joinedHostId = "";
        connectingSince = 0L;
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
                state = new IceState(link, ephemeral, controlling, lanPort, peer, roomId);
                icePeers.put(peer.id, state);
                LOG.info("ICE start as {} with {} ({})", controlling ? "guest" : "host", peer.shortName(), peer.id);
                JsonObject description = link.localDescription();
                String epk = Bytes.b64(ephemeral.getPublic().getEncoded());
                description.addProperty("room", roomId);
                description.addProperty("epk", epk);
                // Bind both identities, the room and the ephemeral key into the signature: the
                // peer verifies it against the profile it already stored for us, so a relay
                // cannot swap in its own key.
                String signed = Handshake.ice(roomId, me().id, peer.id, description);
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
        // An identity only counts when its id is derived from its own signing key.
        if (!from.selfCertified()) {
            LOG.warn("ice from an identity that does not match its key, dropped");
            return;
        }
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
        // Verify against the key we already trust for this peer, never against the key the message
        // itself carries; otherwise anybody could sign a message "as" a friend with a fresh key.
        Profile expected = state.peer;
        if (expected == null || !expected.id.equals(from.id)) {
            LOG.warn("ice from {} ignored: not the peer we are connecting to", from.id);
            return;
        }
        JsonObject remote = msg.data.deepCopy();
        if (!Json.str(remote, "room").equals(state.roomId)) {
            LOG.warn("ice for another room ignored");
            return;
        }
        String signed = Handshake.ice(state.roomId, expected.id, me().id, remote);
        if (!Crypto.verify(expected, signed, msg.str("sig"))) {
            LOG.warn("ice signature rejected for {}", expected.shortName());
            return;
        }
        Thread.ofVirtual().name("netplayer-ice-finish").start(() -> {
            try {
                state.link.acceptRemote(remote);
                if (!state.link.awaitConnected(30_000L)) {
                    iceFailed(state);
                    return;
                }
                LOG.info("ICE connected with {} (relayed={})", from.shortName(), state.link.isRelayed());
                SecretKeySpec key = Crypto.agree(state.ephemeral.getPrivate(), Crypto.xPublic(Json.str(remote, "epk")));
                if (state.controlling) {
                    Tunnel tunnel = Tunnel.guest(state.link, key);
                    tunnel.start();
                    tunnels.put(from.id, tunnel);
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
        LOG.warn("ICE failed, no direct path");
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.ice_failed"));
        if (state != null) {
            state.close();
            icePeers.values().remove(state);
        }
        if (joinFlow != null) {
            joinFlow = null;
        }
    }

    // ------------------------------------------------------------------ direct mode (no server)

    /**
     * Serverless signaling: the host produces a code containing its ICE description and hands it over
     * through any chat, the guest answers with another code. No rendezvous server involved.
     */
    public void generateDirectOffer() {
        init();
        if (hosting == null) {
            openWorld(GameType.SURVIVAL, false, true, Set.of());
        }
        Room room = hosting;
        if (room == null) {
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.error.no_world"));
            return;
        }
        IceState previous = directPending;
        if (previous != null) {
            previous.close();
        }
        Thread.ofVirtual().name("netplayer-direct-offer").start(() -> {
            IceState state = null;
            try {
                IceLink link = new IceLink(false, config.stunServers);
                KeyPair ephemeral = Crypto.xKeyPair();
                state = new IceState(link, ephemeral, false, room.port(), null, room.id());
                directPending = state;
                JsonObject ice = link.localDescription();
                String epk = Bytes.b64(ephemeral.getPublic().getEncoded());
                JsonObject payload = Json.obj();
                payload.addProperty("v", Invite.CODE_VERSION);
                payload.addProperty("room", room.id());
                payload.addProperty("world", room.world());
                payload.addProperty("token", room.token());
                payload.addProperty("premium", room.premium());
                payload.addProperty("port", room.port());
                payload.add("host", me().toJson());
                JsonArray allow = new JsonArray();
                room.allow().forEach(allow::add);
                payload.add("allow", allow);
                payload.add("ice", ice);
                payload.addProperty("epk", epk);
                // Everything above is covered, so premium/allow/world cannot be edited downstream.
                payload.addProperty("sig", Bytes.b64(identity.sign(
                        Json.canonical(payload).getBytes(StandardCharsets.UTF_8))));
                String code = Invite.directOffer(payload);
                lastDirectOffer = code;
                String name = room.world();
                Minecraft.getInstance().execute(() -> {
                    mc.keyboardHandler.setClipboard(code);
                    Toasts.info(Component.literal(name), Component.translatable("netplayer.toast.direct_offer"));
                });
            } catch (Exception e) {
                iceFailed(state);
            }
        });
    }

    public boolean verifyPremium() {
        init();
        return config.verifyPremium;
    }

    public void setVerifyPremium(boolean value) {
        init();
        config.verifyPremium = value;
        config.save(configFile);
        Room room = hosting;
        IntegratedServer server = mc.getSingleplayerServer();
        if (room != null && server != null) {
            server.setUsesAuthentication(value);
            hosting = new Room(room.id(), room.world(), room.token(), room.allow(), room.port(), value);
            sendRoomOpen();
            updatePresence();
        }
    }

    public String lastDirectOffer() {
        return lastDirectOffer;
    }

    public String lastDirectAnswer() {
        return lastDirectAnswer;
    }

    /** Accepts a friend key, a room key or a direct offer/answer code. Returns an error key or null. */
    public String applyCode(String text) {
        init();
        if (text == null || text.isBlank()) {
            return "netplayer.error.empty";
        }
        try {
            if (Invite.isDirectOffer(text)) {
                return applyDirectOffer(Invite.parseDirectOffer(text));
            }
            lastDirectAnswer = "";
            if (Invite.isDirectAnswer(text)) {
                return applyDirectAnswer(Invite.parseDirectAnswer(text));
            }
        } catch (Exception e) {
            return "netplayer.error.bad_key";
        }
        return addFriend(text);
    }

    private String applyDirectOffer(JsonObject payload) {
        Profile host = Profile.fromJson(Json.sub(payload, "host"));
        JsonObject ice = Json.sub(payload, "ice");
        String epk = Json.str(payload, "epk");
        String roomId = Json.str(payload, "room");
        if ((long) Json.num(payload, "v") != Invite.CODE_VERSION) {
            return "netplayer.error.key_too_old";
        }
        // The signature covers every field of the payload except itself, so allow/premium/world
        // cannot be edited by whoever forwards the code.
        if (!Crypto.verify(host, Json.canonical(Json.without(payload, "sig")), Json.str(payload, "sig"))) {
            return "netplayer.error.bad_signature";
        }
        boolean allowed = false;
        JsonElement allow = payload.get("allow");
        if (allow != null && allow.isJsonArray()) {
            for (JsonElement element : allow.getAsJsonArray()) {
                if ("*".equals(element.getAsString()) || me().id.equals(element.getAsString())) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            return "netplayer.error.not_allowed";
        }
        boolean needPremium = Json.bool(payload, "premium");
        if (needPremium && !localPremiumCapable()) {
            return "netplayer.error.not_premium_local";
        }
        storeFriend(host);
        JoinFlow flow = new JoinFlow(host, roomId, Json.str(payload, "world"));
        joinFlow = flow;
        joinedHostId = host.id;
        connectingSince = System.currentTimeMillis();
        IceState previous = icePeers.remove(host.id);
        if (previous != null) {
            previous.close();
        }
        Thread.ofVirtual().name("netplayer-direct-guest").start(() -> {
            IceState state = null;
            try {
                IceLink link = new IceLink(true, config.stunServers);
                KeyPair ephemeral = Crypto.xKeyPair();
                state = new IceState(link, ephemeral, true, 0, host, roomId);
                icePeers.put(host.id, state);
                JsonObject mine = link.localDescription();
                String myEpk = Bytes.b64(ephemeral.getPublic().getEncoded());
                JsonObject proof = Json.obj();
                if (needPremium) {
                    JsonObject made = premiumProof();
                    if (made == null) {
                        notify("netplayer.error.not_premium");
                        iceFailed(state);
                        return;
                    }
                    proof = made;
                }
                JsonObject answer = Json.obj();
                answer.addProperty("v", Invite.CODE_VERSION);
                answer.addProperty("room", roomId);
                answer.addProperty("token", Json.str(payload, "token"));
                answer.add("guest", me().toJson());
                if (proof.size() > 0) {
                    answer.add("mc", proof);
                }
                answer.add("ice", mine);
                answer.addProperty("epk", myEpk);
                answer.addProperty("sig", Bytes.b64(identity.sign(
                        Json.canonical(answer).getBytes(StandardCharsets.UTF_8))));
                String code = Invite.directAnswer(answer);
                lastDirectAnswer = code;
                Minecraft.getInstance().execute(() -> {
                    mc.keyboardHandler.setClipboard(code);
                    Toasts.info(Component.literal(host.shortName()), Component.translatable("netplayer.toast.direct_answer"));
                });
                state.link.acceptRemote(ice);
                if (!state.link.awaitConnected(120_000L)) {
                    iceFailed(state);
                    return;
                }
                LOG.info("direct ICE connected with {} (relayed={})", host.shortName(), state.link.isRelayed());
                SecretKeySpec key = Crypto.agree(state.ephemeral.getPrivate(), Crypto.xPublic(epk));
                Tunnel tunnel = Tunnel.guest(state.link, key);
                tunnel.start();
                tunnels.put(host.id, tunnel);
                flow.port = tunnel.localPort();
            } catch (Exception e) {
                iceFailed(state);
            }
        });
        return null;
    }

    private String applyDirectAnswer(JsonObject payload) {
        Profile guest = Profile.fromJson(Json.sub(payload, "guest"));
        JsonObject ice = Json.sub(payload, "ice");
        String epk = Json.str(payload, "epk");
        String roomId = Json.str(payload, "room");
        if ((long) Json.num(payload, "v") != Invite.CODE_VERSION) {
            return "netplayer.error.key_too_old";
        }
        if (!Crypto.verify(guest, Json.canonical(Json.without(payload, "sig")), Json.str(payload, "sig"))) {
            return "netplayer.error.bad_signature";
        }
        Room room = hosting;
        IceState state = directPending;
        if (room == null || state == null || !room.id().equals(roomId)) {
            return "netplayer.error.direct_stale";
        }
        // Authorize against our own room state, not against the fields of the answer: the guest
        // has to be on the allow list or echo the token it got inside our signed offer.
        boolean allowed = room.allow().contains("*") || room.allow().contains(guest.id)
                || (!Json.str(payload, "token").isEmpty() && Json.str(payload, "token").equals(room.token()));
        if (!allowed) {
            return "netplayer.error.not_allowed";
        }
        if (config.verifyPremium && !verifyProof(Json.sub(payload, "mc"), guest)) {
            return "netplayer.error.guest_not_premium";
        }
        storeFriend(guest);
        directPending = null;
        Thread.ofVirtual().name("netplayer-direct-host").start(() -> {
            try {
                state.link.acceptRemote(ice);
                if (!state.link.awaitConnected(30_000L)) {
                    iceFailed(state);
                    return;
                }
                LOG.info("direct ICE connected with {} (relayed={})", guest.shortName(), state.link.isRelayed());
                SecretKeySpec key = Crypto.agree(state.ephemeral.getPrivate(), Crypto.xPublic(epk));
                Tunnel tunnel = Tunnel.host(state.link, key, room.port());
                tunnel.start();
                tunnels.put(guest.id, tunnel);
            } catch (Exception e) {
                iceFailed(state);
            }
        });
        return null;
    }

    // ------------------------------------------------------------------ settings and link info

    public record LinkInfo(boolean active, String kind, int rttMillis, String local, String remote) {
        public static final LinkInfo NONE = new LinkInfo(false, "", -1, "", "");
    }

    public LinkInfo linkInfo(String friendId) {
        Tunnel tunnel = tunnels.get(friendId);
        IceState state = icePeers.get(friendId);
        if (tunnel == null || state == null) {
            return LinkInfo.NONE;
        }
        IceLink.PairInfo pair = state.link.pairInfo();
        return new LinkInfo(true, pair.kind(), tunnel.rttMillis(), pair.local(), pair.remote());
    }

    public String signalingHost() {
        init();
        return config.signalingHost;
    }

    public int signalingPort() {
        init();
        return config.signalingPort;
    }

    public String probeResult() {
        return probeResult;
    }

    /** Connects (without announcing) to verify the endpoint, used by the settings screen. */
    public void probeSignaling() {
        init();
        probeResult = "netplayer.settings.probing";
        Thread.ofVirtual().name("netplayer-probe").start(() -> {
            SignalClient probe = new SignalClient(new SignalClient.Listener() {
                @Override
                public void onMessage(Msg msg) {
                }

                @Override
                public void onClosed(String reason) {
                }
            });
            if (connect(probe)) {
                probeResult = "netplayer.settings.probe_ok";
            } else {
                probeResult = probe.lastError().isEmpty() ? "netplayer.settings.probe_failed" : probe.lastError();
            }
            probe.close();
        });
    }

    public String stunServersText() {
        init();
        return String.join(", ", config.stunServers);
    }

    /** Human readable endpoint currently in use (url or host:port). */
    public String signalingAddress() {
        init();
        return config.signalingTarget();
    }

    /** Accepts either {@code host}, {@code host:port} or a {@code ws(s)://} / plain url. */
    public String proxyHost() {
        init();
        return config.proxyHost;
    }

    public int proxyPort() {
        init();
        return config.proxyPort;
    }

    public void applySettings(String host, int port, String stunCsv) {
        applySettings(host, port, stunCsv, config.proxyHost, config.proxyPort);
    }

    public void applySettings(String host, int port, String stunCsv, String proxy, int proxyPort) {
        init();
        String raw = host == null ? "" : host.strip();
        if (raw.contains("://")) {
            config.signalingUrl = raw;
        } else {
            config.signalingUrl = "";
            config.signalingHost = raw.isBlank() ? "127.0.0.1" : raw;
            config.signalingPort = port <= 0 || port > 65535 ? 25566 : port;
        }
        List<String> servers = new ArrayList<>();
        for (String part : stunCsv.split("[,;\s]+")) {
            if (!part.isBlank()) {
                servers.add(part.strip());
            }
        }
        if (!servers.isEmpty()) {
            config.stunServers = servers;
        }
        config.proxyHost = proxy == null ? "" : proxy.strip();
        config.proxyPort = proxyPort;
        PremiumAuth.setProxy(config.proxyHost, config.proxyPort);
        config.save(configFile);
        configStamp = stamp(configFile);
        LOG.info("settings applied: signaling={} stun=[{}]", config.signalingTarget(),
                String.join(", ", config.stunServers));
        SignalClient old = signal;
        signal = null;
        helloDone = false;
        sentPresence = "";
        lastConnectAttempt = 0L;
        if (old != null) {
            old.close();
        }
    }

    // ------------------------------------------------------------------ premium accounts

    /** True when this install has a real Mojang session (dev clients use the placeholder token). */
    public boolean localPremiumCapable() {
        User user = mc.getUser();
        if (user == null) {
            return false;
        }
        String token = user.getAccessToken();
        return token != null && !token.isBlank() && !"0".equals(token);
    }

    /** Registers the session with Mojang and returns the proof the host can verify. */
    private JsonObject premiumProof() {
        try {
            User user = mc.getUser();
            if (user == null) {
                return null;
            }
            String profileId = user.getProfileId() == null ? "" : user.getProfileId().toString();
            String name = user.getName() == null ? "" : user.getName();
            String serverId = PremiumAuth.newServerId();
            if (!PremiumAuth.join(user.getAccessToken(), profileId, serverId)) {
                return null;
            }
            JsonObject proof = Json.obj();
            proof.addProperty("name", name);
            proof.addProperty("uuid", profileId);
            proof.addProperty("serverId", serverId);
            // Signed with the tunnel identity: whoever relays the proof cannot reuse it, and the
            // host can tell that the account belongs to the peer it is talking to.
            String basis = Handshake.premium(name, profileId, serverId);
            proof.addProperty("sig", Bytes.b64(identity.sign(basis.getBytes(StandardCharsets.UTF_8))));
            return proof;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verifies that {@code peer} owns a genuine Mojang account: the proof must be signed by that
     * identity, must still be valid per Mojang, and the account has to stay the same one for the
     * same friend id (first use pins it).
     */
    private boolean verifyProof(JsonObject proof, Profile peer) {
        if (proof == null || proof.size() == 0) {
            LOG.warn("premium check rejected: guest sent no proof");
            return false;
        }
        if (peer == null || !peer.selfCertified()) {
            LOG.warn("premium check rejected: peer identity is not self certified");
            return false;
        }
        String name = Json.str(proof, "name");
        String uuid = Json.str(proof, "uuid");
        String serverId = Json.str(proof, "serverId");
        if (!Crypto.verify(peer, Handshake.premium(name, uuid, serverId), Json.str(proof, "sig"))) {
            LOG.warn("premium check rejected: proof was not signed by {}", peer.shortName());
            return false;
        }
        JsonObject profile = PremiumAuth.hasJoined(name, serverId);
        if (profile == null) {
            LOG.warn("premium check rejected: mojang does not know serverId {}", serverId);
            return false;
        }
        String mojangId = Json.str(profile, "id").replace("-", "");
        if (mojangId.isEmpty() || !mojangId.equalsIgnoreCase(uuid.replace("-", ""))) {
            LOG.warn("premium check rejected: proof uuid does not match the mojang profile");
            return false;
        }
        Friend known = friends.get(peer.id);
        if (known != null && known.mcUuid != null && !known.mcUuid.isEmpty()
                && !known.mcUuid.replace("-", "").equalsIgnoreCase(mojangId)) {
            LOG.warn("premium check rejected: {} switched minecraft account (pinned {})", peer.id, known.mcUuid);
            return false;
        }
        LOG.info("premium check for {} ({}) -> ok", name, peer.id);
        pinMcAccount(peer.id, name, mojangId);
        return true;
    }

    /** Remembers which Minecraft account sits behind a NetPlayer identity (trust on first use). */
    private void pinMcAccount(String friendId, String name, String uuid) {
        Friend friend = friends.get(friendId);
        if (friend == null || (friend.mcUuid != null && !friend.mcUuid.isEmpty())) {
            return;
        }
        friend.mcName = name;
        friend.mcUuid = uuid;
        saveFriends();
    }

    private void notify(String key) {
        Toasts.info(Component.translatable("netplayer.title"), Component.translatable(key));
    }

    // ------------------------------------------------------------------ helpers

    private static final class IceState {
        final IceLink link;
        final KeyPair ephemeral;
        final boolean controlling;
        final int lanPort;
        /** The peer's identity as we knew it when the handshake started: the only key we accept. */
        final Profile peer;
        final String roomId;

        IceState(IceLink link, KeyPair ephemeral, boolean controlling, int lanPort, Profile peer, String roomId) {
            this.link = link;
            this.ephemeral = ephemeral;
            this.controlling = controlling;
            this.lanPort = lanPort;
            this.peer = peer;
            this.roomId = roomId;
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
