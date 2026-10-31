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
import com.clevebitr.netplayer.core.TurnClient;
import com.clevebitr.netplayer.net.Addresses;
import com.clevebitr.netplayer.net.IceLink;
import com.clevebitr.netplayer.net.Link;
import com.clevebitr.netplayer.net.Tunnel;
import com.clevebitr.netplayer.net.TurnRelay;
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
import java.net.InetSocketAddress;
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
    /** Every guest costs one ICE agent and one tunnel; keep a LAN-sized ceiling. */
    private static final int MAX_GUESTS = 8;
    /** No datagram from the peer for this long means the tunnel is dead, not idle. */
    private static final long LINK_STALE_MS = 15_000L;
    /** ICE plus the relay fallback may take a while; this is the "nothing is happening" limit. */
    private static final long JOIN_TIMEOUT_MS = 60_000L;
    /** The Minecraft handshake runs over loopback, so it either works at once or not at all. */
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
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
    private final Map<String, Msg> earlyRelay = new ConcurrentHashMap<>();

    /** The single source of truth for "am I entering / inside somebody's world". */
    private volatile JoinSession session;
    private volatile long lastConfigCheck;
    private volatile long configStamp;
    private volatile String lastConnectError = "";
    private volatile String probeResult = "";
    private volatile String turnProbeText = "";
    private volatile boolean turnProbeOk;
    private volatile String iceProbeText = "";
    private volatile boolean iceProbeOk;
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
        refreshTunnels(now);
        trackSession(now);
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
            friend.note = cleanNote(note);
            saveFriends();
        }
    }

    /** Notes end up in buttons and titles: keep them one line, printable and short. */
    private static String cleanNote(String note) {
        if (note == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : note.strip().toCharArray()) {
            if (c >= 0x20 && c != 0x7f && sb.length() < 48) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
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
                Room openRoom = hosting;
                if (openRoom == null || !openRoom.id().equals(msg.str("room"))) {
                    // Nothing (or something else) is open here: answer instead of queueing.
                    send(Msg.of("join_ans").put("to", guest.id).put("room", msg.str("room")).put("accept", false)
                            .put("reason", "room_closed"));
                    return;
                }
                if (tunnels.containsKey(guest.id)) {
                    LOG.info("join request from {} ignored, already connected", guest.shortName());
                    return;
                }
                if (tunnels.size() >= MAX_GUESTS) {
                    LOG.warn("join request from {} refused, {} guests already connected", guest.shortName(),
                            tunnels.size());
                    send(Msg.of("join_ans").put("to", guest.id).put("room", msg.str("room")).put("accept", false)
                            .put("reason", "room_full"));
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
            case "relay" -> onRelay(msg);
            case "leave" -> onLeave(msg);
            case "kick" -> onKick(msg);
            case "room_closed" -> {
                JoinSession current = session;
                if (current != null && current.roomId.equals(msg.str("room"))) {
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
        return presence(friendId).openTo(me().id) && !insideWorldOf(friendId) && !joiningFriend(friendId);
    }

    /** True only once the guest is really playing inside that friend's world. */
    public boolean insideWorldOf(String friendId) {
        JoinSession current = session;
        return current != null && current.stage == JoinSession.Stage.INSIDE
                && friendId != null && current.host.id.equals(friendId);
    }

    /** True while a join to that friend is being negotiated (ICE, or the Minecraft connect). */
    public boolean joiningFriend(String friendId) {
        JoinSession current = session;
        return current != null && current.stage != JoinSession.Stage.INSIDE
                && friendId != null && current.host.id.equals(friendId);
    }

    /** Null when there is nothing to say, else a translation key describing the local join state. */
    public String joinStateKey(String friendId) {
        if (insideWorldOf(friendId)) {
            return "netplayer.detail.inside";
        }
        if (joiningFriend(friendId)) {
            return "netplayer.detail.joining";
        }
        return null;
    }

    public void requestJoin(String friendId) {
        if (insideWorldOf(friendId)) {
            notify("netplayer.error.already_inside");
            return;
        }
        if (joiningFriend(friendId)) {
            notify("netplayer.error.already_joining");
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
                case "room_full" -> "netplayer.error.room_full";
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
        JoinSession created = beginSession(host, msg.str("room"), msg.str("world"));
        Toasts.info(Component.literal(host.shortName()), Component.translatable("netplayer.toast.join_accepted"));
        startIce(host, created.roomId, true, 0);
    }

    // ------------------------------------------------------------------ join session

    /**
     * Starts (or replaces) the attempt to enter somebody's world. Every way out of a join - failure,
     * leave, kick, dead link - goes through {@link #endSession(String, boolean)} so no stale
     * "you are inside" or "guest is connected" state can survive.
     */
    private JoinSession beginSession(Profile host, String roomId, String world) {
        endSession(null, true);
        JoinSession created = new JoinSession(host, roomId, world);
        session = created;
        LOG.info("join session start with {} room {}", host.shortName(), roomId);
        return created;
    }

    private void endSession(String messageKey, boolean tellHost) {
        JoinSession current = session;
        session = null;
        if (current != null) {
            Tunnel tunnel = tunnels.remove(current.host.id);
            if (tunnel != null) {
                tunnel.close();
            }
            IceState state = icePeers.remove(current.host.id);
            if (state != null) {
                state.close();
            }
            if (tellHost && !current.roomId.isEmpty()) {
                // Fast path for the host: it drops the tunnel and the pending request right away
                // instead of waiting for the liveness watchdog.
                send(Msg.of("leave").put("to", current.host.id).put("room", current.roomId));
            }
            boolean wasInside = current.stage == JoinSession.Stage.INSIDE;
            LOG.info("join session with {} ended at stage {} ({})", current.host.shortName(),
                    current.stage, messageKey == null ? "left" : messageKey);
            if (wasInside) {
                Minecraft.getInstance().execute(() -> {
                    if (mc.level != null) {
                        mc.disconnect(new TitleScreen(), false);
                    }
                });
            }
        }
        if (messageKey != null) {
            Minecraft.getInstance().execute(() -> Toasts.info(Component.translatable("netplayer.title"),
                    Component.translatable(messageKey)));
        }
    }

    /** Kept for the pre-session failures: a declined request, a closed room, a bad answer. */
    private void failJoin(String messageKey) {
        endSession(messageKey, true);
    }

    /** Advances the session through ICE -> connecting -> inside, and kills it when it goes quiet. */
    private void trackSession(long now) {
        JoinSession current = session;
        if (current == null) {
            return;
        }
        Tunnel tunnel = tunnels.get(current.host.id);
        if (tunnel != null && tunnel.isStale(now, LINK_STALE_MS)) {
            LOG.warn("no packets from {} for {}ms", current.host.shortName(), LINK_STALE_MS);
            endSession("netplayer.error.link_lost", false);
            return;
        }
        switch (current.stage) {
            case ICE -> {
                if (now - current.startedAt > JOIN_TIMEOUT_MS) {
                    endSession("netplayer.error.connect_timeout", true);
                }
            }
            case CONNECTING -> {
                if (mc.level != null) {
                    // A slow world load keeps the level null for a while, so the transition to
                    // INSIDE is only trusted after we actually saw the level go away first.
                    if (current.sawEmptyLevel) {
                        current.stage(JoinSession.Stage.INSIDE);
                        LOG.info("inside {}'s world", current.host.shortName());
                    }
                } else {
                    current.sawEmptyLevel = true;
                    // Fast path for a refused/failed connection: the client had a connection and
                    // dropped it again without ever showing a world.
                    if (current.sawConnection && mc.getConnection() == null
                            && now - current.stageSince > 1000L) {
                        endSession("netplayer.error.connect_failed", true);
                    }
                }
                if (mc.getConnection() != null) {
                    current.sawConnection = true;
                }
                if (current.stage == JoinSession.Stage.CONNECTING
                        && now - current.stageSince > CONNECT_TIMEOUT_MS) {
                    endSession("netplayer.error.connect_timeout", true);
                }
            }
            case INSIDE -> {
                if (mc.level == null) {
                    LOG.info("left {}'s world", current.host.shortName());
                    endSession(null, true);
                } else if (tunnel == null) {
                    endSession("netplayer.error.link_lost", false);
                }
            }
        }
    }

    private void progressJoin() {
        JoinSession current = session;
        if (current == null || current.stage != JoinSession.Stage.ICE || current.port <= 0) {
            return;
        }
        if (mc.level != null && !current.disconnected) {
            current.disconnected = true;
            mc.disconnect(new TitleScreen(), false);
            return;
        }
        current.stage(JoinSession.Stage.CONNECTING);
        ServerAddress address = new ServerAddress("127.0.0.1", current.port);
        ServerData data = new ServerData("NetPlayer: " + current.host.shortName(), address.toString(),
                ServerData.Type.OTHER);
        LOG.info("connecting to {}'s world on 127.0.0.1:{}", current.host.shortName(), current.port);
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
                // The allocation runs next to ICE: when the direct path fails we already have a
                // relayed address to fall back on, and it is published as a signed "relay" message.
                if (relayConfigured()) {
                    allocateRelay(state);
                }
                Msg earlyRelayMessage = earlyRelay.remove(peer.id);
                if (earlyRelayMessage != null) {
                    processRelay(peer, earlyRelayMessage, state);
                }
            } catch (Exception e) {
                LOG.error("ICE could not start", e);
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
        state.peerEpk = Json.str(remote, "epk");
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
                    JoinSession current = session;
                    if (current != null && current.host.id.equals(from.id)) {
                        current.port = tunnel.localPort();
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
        if (state != null) {
            // Candidate lists on both sides are what makes this diagnosable from a user log.
            LOG.warn("ICE failed: state={} local={} remote={}", state.link.stateName(),
                    state.link.localDescription().get("cands"), state.link.describeRemote());
            LOG.warn("if the two peers share an IPv6 prefix but nothing connects, check the Windows");
            LOG.warn("firewall (inbound UDP for javaw.exe) or configure a TURN relay in settings");
        } else {
            LOG.warn("ICE failed before the agent started");
        }
        if (state == null) {
            notify("netplayer.error.ice_failed");
            return;
        }
        state.iceFailed = true;
        if (maybeStartRelay(state)) {
            // Either the relay is starting or its pieces are still on the way.
            return;
        }
        notify("netplayer.error.ice_failed");
        state.close();
        icePeers.values().remove(state);
        if (state.controlling) {
            endSession(null, true);
        }
    }

    // ------------------------------------------------------------------ TURN relay fallback

    private boolean relayConfigured() {
        return config.turnServers != null && !config.turnServers.isEmpty();
    }

    /** Allocates a relay on the first TURN server that answers, then publishes the address. */
    private void allocateRelay(IceState state) {
        Thread.ofVirtual().name("netplayer-turn").start(() -> {
            for (String spec : config.turnServers) {
                InetSocketAddress server = TurnClient.parseServer(spec);
                if (server == null) {
                    LOG.warn("ignoring bad turn server '{}'", spec);
                    continue;
                }
                TurnClient turn = null;
                try {
                    turn = new TurnClient(server, config.turnUser, config.turnPass);
                    TurnClient.Allocation allocation = turn.allocate(600L);
                    if (state.relayStarted) {
                        turn.close();
                        return;
                    }
                    state.turn = turn;
                    state.myRelay = allocation.relayed();
                    String relayed = Addresses.format(state.myRelay);
                    JsonObject payload = Json.obj();
                    payload.addProperty("room", state.roomId);
                    payload.addProperty("relay", relayed);
                    String basis = Handshake.relay(state.roomId, me().id, state.peer.id, relayed);
                    payload.addProperty("sig",
                            Bytes.b64(identity.sign(basis.getBytes(StandardCharsets.UTF_8))));
                    Msg msg = Msg.of("relay", payload);
                    msg.put("to", state.peer.id);
                    send(msg);
                    LOG.info("TURN relay allocated on {}: {}", server, relayed);
                    maybeStartRelay(state);
                    return;
                } catch (Exception e) {
                    LOG.warn("TURN allocation on {} failed: {}", server, e.getMessage());
                    if (turn != null) {
                        turn.close();
                    }
                }
            }
            // No relay available: if the direct path already failed, this is the end of the line.
            maybeStartRelay(state);
        });
    }

    private void onRelay(Msg msg) {
        Profile from = Profile.fromJson(msg.obj("from"));
        if (!from.selfCertified()) {
            LOG.warn("relay message from an identity that does not match its key, dropped");
            return;
        }
        IceState state = icePeers.get(from.id);
        if (state == null) {
            if (earlyRelay.size() < 8) {
                earlyRelay.put(from.id, msg);
            }
            return;
        }
        processRelay(from, msg, state);
    }

    private void processRelay(Profile from, Msg msg, IceState state) {
        Profile expected = state.peer;
        if (expected == null || !expected.id.equals(from.id)) {
            return;
        }
        if (!Json.str(msg.data, "room").equals(state.roomId)) {
            LOG.warn("relay address for another room ignored");
            return;
        }
        String relayed = Json.str(msg.data, "relay");
        String basis = Handshake.relay(state.roomId, expected.id, me().id, relayed);
        if (!Crypto.verify(expected, basis, msg.str("sig"))) {
            LOG.warn("relay address signature rejected for {}", expected.shortName());
            return;
        }
        InetSocketAddress peer = Addresses.parse(relayed);
        if (peer == null) {
            LOG.warn("relay address '{}' is not an IP literal, ignored", relayed);
            return;
        }
        state.peerRelay = peer;
        LOG.info("peer {} offers relay {}", expected.shortName(), relayed);
        maybeStartRelay(state);
    }

    /**
     * Brings up the relayed tunnel once both sides have an allocation and the direct path is known
     * to be dead. Returns true when the relay is running or still assembling.
     */
    private boolean maybeStartRelay(IceState state) {
        if (state.relayStarted) {
            return true;
        }
        if (!state.iceFailed || state.turn == null || state.peerRelay == null) {
            if (state.iceFailed && !relayConfigured()) {
                return false;
            }
            return relayConfigured();
        }
        state.relayStarted = true;
        Thread.ofVirtual().name("netplayer-relay").start(() -> startRelayTunnel(state));
        return true;
    }

    private void startRelayTunnel(IceState state) {
        try {
            if (state.peerEpk == null || state.peerEpk.isEmpty()) {
                LOG.warn("no peer key, cannot build the relay tunnel");
                notify("netplayer.error.ice_failed");
                return;
            }
            Link link = TurnRelay.start(state.turn, state.peerRelay);
            SecretKeySpec key = Crypto.agree(state.ephemeral.getPrivate(), Crypto.xPublic(state.peerEpk));
            Tunnel tunnel = state.controlling
                    ? Tunnel.guest(link, key)
                    : Tunnel.host(link, key, state.lanPort);
            tunnel.start();
            tunnels.put(state.peer.id, tunnel);
            if (state.controlling) {
                JoinSession current = session;
                if (current != null && current.host.id.equals(state.peer.id)) {
                    current.port = tunnel.localPort();
                }
            }
            LOG.info("relay tunnel up with {}", state.peer.shortName());
            Toasts.info(Component.translatable("netplayer.title"), Component.translatable("netplayer.toast.relay"));
        } catch (Exception e) {
            LOG.warn("relay tunnel failed: {}", e.getMessage());
            notify("netplayer.error.ice_failed");
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
        JoinSession flow = beginSession(host, roomId, Json.str(payload, "world"));
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
        return new LinkInfo(true, tunnel.kind(), tunnel.rttMillis(), pair.local(), pair.remote());
    }

    // ------------------------------------------------------------------ guests (host side)

    /** One tunnel that is currently up, seen from the host. */
    public record Guest(String id, String name, String kind, int rttMillis) {
    }

    /** Guests whose tunnel is still open, newest last. Drives the host's player panel. */
    public List<Guest> guests() {
        List<Guest> list = new ArrayList<>();
        for (Map.Entry<String, Tunnel> entry : tunnels.entrySet()) {
            if (!entry.getValue().isOpen()) {
                continue;
            }
            IceState state = icePeers.get(entry.getKey());
            String kind = state == null ? "" : state.link.pairInfo().kind();
            list.add(new Guest(entry.getKey(), friendName(entry.getKey()), kind, entry.getValue().rttMillis()));
        }
        return list;
    }

    /** Drops one guest: closes the tunnel and asks the guest's client to go back to the title screen. */
    public void kickGuest(String guestId) {
        Room room = hosting;
        if (room == null || guestId == null || guestId.isEmpty()) {
            return;
        }
        Tunnel tunnel = tunnels.remove(guestId);
        if (tunnel != null) {
            tunnel.close();
        }
        IceState state = icePeers.remove(guestId);
        if (state != null) {
            state.close();
        }
        send(Msg.of("kick").put("to", guestId).put("room", room.id()));
        LOG.info("kicked {} from {}", friendName(guestId), room.id());
    }

    /** The host closed our tunnel: leave the world instead of hanging on a dead connection. */
    /** The host closed our tunnel: end the session and let it disconnect us. */
    private void onKick(Msg msg) {
        Profile from = Profile.fromJson(msg.obj("from"));
        JoinSession current = session;
        if (current == null || !from.selfCertified() || !current.host.id.equals(from.id)) {
            return;
        }
        if (!current.roomId.isEmpty() && !current.roomId.equals(msg.str("room"))) {
            return;
        }
        LOG.info("host closed our tunnel");
        endSession("netplayer.error.kicked", false);
    }

    /** A guest says goodbye: drop it now instead of waiting for the liveness watchdog. */
    private void onLeave(Msg msg) {
        Profile from = Profile.fromJson(msg.obj("from"));
        if (!from.selfCertified()) {
            return;
        }
        Room room = hosting;
        if (room != null && !room.id().equals(msg.str("room"))) {
            return;
        }
        pendingJoins.removeIf(pending -> pending.guest().id.equals(from.id));
        IceState state = icePeers.remove(from.id);
        if (state != null) {
            state.close();
        }
        Tunnel tunnel = tunnels.remove(from.id);
        if (tunnel != null) {
            tunnel.close();
            LOG.info("{} left the world", from.shortName());
            if (hosting != null) {
                Toasts.info(Component.literal(from.shortName()),
                        Component.translatable("netplayer.toast.guest_left"));
            }
        }
    }

    /**
     * Keeps the tunnel map honest: a closed stream, or one that stopped answering pings, is removed
     * together with its ICE state, so the host panel and the friend list cannot show a ghost.
     */
    private void refreshTunnels(long now) {
        for (Map.Entry<String, Tunnel> entry : tunnels.entrySet()) {
            Tunnel tunnel = entry.getValue();
            boolean open = tunnel.isOpen();
            if (open && !tunnel.isStale(now, LINK_STALE_MS)) {
                continue;
            }
            if (!tunnels.remove(entry.getKey(), tunnel)) {
                continue;
            }
            tunnel.close();
            IceState state = icePeers.remove(entry.getKey());
            if (state != null) {
                state.close();
            }
            pendingJoins.removeIf(pending -> pending.guest().id.equals(entry.getKey()));
            LOG.info("tunnel with {} dropped ({})", friendName(entry.getKey()),
                    open ? "silent" : "closed");
            if (hosting != null) {
                Toasts.info(Component.literal(friendName(entry.getKey())),
                        Component.translatable("netplayer.toast.guest_left"));
            }
        }
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

    public String turnServersText() {
        init();
        return String.join(", ", config.turnServers);
    }

    /** Last result of the TURN probe, rendered next to the settings field. */
    public String turnProbeText() {
        return turnProbeText;
    }

    public boolean turnProbeOk() {
        return turnProbeOk;
    }

    public String iceProbeText() {
        return iceProbeText;
    }

    public boolean iceProbeOk() {
        return iceProbeOk;
    }

    /**
     * Local network self test: harvests the candidates this machine would advertise and runs a full
     * ICE handshake between two agents inside this process. When the loopback handshake works but a
     * real peer does not connect, the problem is on the path (firewall, CPE IPv6 filter, ISP), not
     * in the mod. Candidates and the selected pair go to the log.
     */
    public void probeIce() {
        init();
        iceProbeText = "netplayer.settings.ice_probing";
        iceProbeOk = false;
        Thread.ofVirtual().name("netplayer-ice-probe").start(() -> {
            IceLink a = null;
            IceLink b = null;
            try {
                a = new IceLink(true, config.stunServers);
                b = new IceLink(false, config.stunServers);
                JsonObject descA = a.localDescription();
                JsonObject descB = b.localDescription();
                int candidates = descA.getAsJsonArray("cands") == null ? 0
                        : descA.getAsJsonArray("cands").size();
                int v6 = 0;
                int v4 = 0;
                for (JsonElement element : descA.getAsJsonArray("cands")) {
                    String ip = Json.str(element.getAsJsonObject(), "ip");
                    if (ip.indexOf(':') >= 0) {
                        v6++;
                    } else {
                        v4++;
                    }
                }
                long started = System.currentTimeMillis();
                a.acceptRemote(descB);
                b.acceptRemote(descA);
                boolean ok = a.awaitConnected(15_000L);
                long elapsed = System.currentTimeMillis() - started;
                iceProbeOk = ok;
                if (ok) {
                    iceProbeText = "IPv6 " + v6 + " / IPv4 " + v4 + " (" + candidates + " cands), ICE "
                            + a.pairInfo().kind() + " " + elapsed + "ms";
                } else {
                    iceProbeText = "candidates " + candidates + ", local ICE failed (" + elapsed + "ms)";
                }
                LOG.info("ICE self test: candidates={} v6={} v4={} ok={} pair={}", candidates, v6, v4, ok,
                        a.pairInfo());
            } catch (Exception e) {
                iceProbeOk = false;
                iceProbeText = "ICE self test failed: " + e.getMessage();
                LOG.warn("ICE self test failed", e);
            } finally {
                if (a != null) {
                    a.close();
                }
                if (b != null) {
                    b.close();
                }
            }
        });
    }

    /** Tries every configured TURN server once, so a user can tell which one works from here. */
    public void probeTurn() {
        init();
        turnProbeText = "netplayer.settings.turn_probing";
        turnProbeOk = false;
        Thread.ofVirtual().name("netplayer-turn-probe").start(() -> {
            if (!relayConfigured()) {
                turnProbeText = "netplayer.settings.turn_none";
                return;
            }
            String lastError = "";
            for (String spec : config.turnServers) {
                InetSocketAddress server = TurnClient.parseServer(spec);
                if (server == null) {
                    lastError = "cannot parse " + spec;
                    continue;
                }
                long started = System.currentTimeMillis();
                try (TurnClient turn = new TurnClient(server, config.turnUser, config.turnPass)) {
                    TurnClient.Allocation allocation = turn.allocate(60L);
                    turnProbeOk = true;
                    turnProbeText = spec + " -> " + Addresses.format(allocation.relayed())
                            + " (" + (System.currentTimeMillis() - started) + "ms)";
                    return;
                } catch (Exception e) {
                    lastError = spec + ": " + e.getMessage();
                    LOG.warn("TURN probe failed: {}", lastError);
                }
            }
            turnProbeText = lastError.isEmpty() ? "netplayer.settings.turn_failed" : lastError;
        });
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
        applySettings(host, port, stunCsv, config.proxyHost, config.proxyPort, turnServersText());
    }

    public void applySettings(String host, int port, String stunCsv, String proxy, int proxyPort) {
        applySettings(host, port, stunCsv, proxy, proxyPort, turnServersText());
    }

    public void applySettings(String host, int port, String stunCsv, String proxy, int proxyPort, String turnCsv) {
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
        List<String> relays = new ArrayList<>();
        String turnText = turnCsv == null ? "" : turnCsv;
        for (String part : turnText.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                String spec = part.strip();
                if (TurnClient.parseServer(spec) == null) {
                    LOG.warn("ignoring bad turn server '{}'", spec);
                    continue;
                }
                relays.add(spec);
            }
        }
        config.turnServers = relays;
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
        } catch (Throwable e) {
            // Relevant on non standard JVMs (Android launchers have shipped without java.net.http):
            // a missing session client must degrade to "cannot prove", never crash the join.
            LOG.warn("premium proof unavailable: {}", e.toString());
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
        String mojangId = dashless(Json.str(profile, "id"));
        if (mojangId.isEmpty() || !mojangId.equalsIgnoreCase(dashless(uuid))) {
            LOG.warn("premium check rejected: proof uuid does not match the mojang profile");
            return false;
        }
        Friend known = friends.get(peer.id);
        if (known != null && known.mcUuid != null && !known.mcUuid.isEmpty()
                && !dashless(known.mcUuid).equalsIgnoreCase(mojangId)) {
            LOG.warn("premium check rejected: {} switched minecraft account (pinned {})", peer.id, known.mcUuid);
            return false;
        }
        LOG.info("premium check for {} ({}) -> ok", name, peer.id);
        // Keep the dashed form: the head renderer wants a real UUID, comparisons go dashless.
        pinMcAccount(peer.id, name, uuid.isEmpty() ? mojangId : uuid);
        return true;
    }

    private static String dashless(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "");
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
        /** Peer ephemeral public key from its (verified) ICE description; needed for the relay too. */
        volatile String peerEpk = "";
        /** Our own TURN allocation, null until it is ready (or when TURN is off). */
        volatile TurnClient turn;
        volatile InetSocketAddress myRelay;
        volatile InetSocketAddress peerRelay;
        volatile boolean iceFailed;
        volatile boolean relayStarted;

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
            TurnClient client = turn;
            if (client != null) {
                client.close();
            }
        }
    }

    /** One attempt to enter a friend's world: ICE, then the Minecraft connect, then playing inside. */
    private static final class JoinSession {
        enum Stage {
            /** Handshake in progress (ICE, and the relay fallback if the direct path fails). */
            ICE,
            /** The tunnel is up and the vanilla client is connecting to it. */
            CONNECTING,
            /** The guest is playing inside the host's world. */
            INSIDE
        }

        final Profile host;
        final String roomId;
        final String world;
        volatile Stage stage = Stage.ICE;
        volatile long startedAt = System.currentTimeMillis();
        volatile long stageSince = startedAt;
        volatile int port;
        volatile boolean disconnected;
        /** Set once the client actually left its own world, so a slow transition cannot look like success. */
        volatile boolean sawEmptyLevel;
        /** Set once the vanilla client had a connection in this attempt: its disappearance is a failure. */
        volatile boolean sawConnection;

        JoinSession(Profile host, String roomId, String world) {
            this.host = host;
            this.roomId = roomId;
            this.world = world;
        }

        void stage(Stage next) {
            if (stage != next) {
                stage = next;
                stageSince = System.currentTimeMillis();
            }
        }
    }

    public Map<String, Friend> friendMap() {
        return new LinkedHashMap<>(friends);
    }
}
