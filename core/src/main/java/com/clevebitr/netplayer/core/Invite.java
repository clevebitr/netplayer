package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Shareable keys. A friend key carries the sender's public identity, a room key also points at a
 * world that is currently open. Both are plain text so they survive chat and clipboard.
 */
public final class Invite {
    /** Bump when the signed payload layout changes: older codes then fail closed. */
    public static final int CODE_VERSION = 2;

    private static final String FRIEND_PREFIX = "npfriend1.";
    private static final String ROOM_PREFIX = "npjoin1.";
    private static final String OFFER_PREFIX = "npair1.";
    private static final String ANSWER_PREFIX = "npans1.";

    private Invite() {
    }

    public record Room(String roomId, Profile host, String world, String token, long expires, boolean premium) {
        public JsonObject toJson() {
            JsonObject o = Json.obj();
            o.addProperty("room", roomId);
            o.add("host", host.toJson());
            o.addProperty("world", world);
            o.addProperty("token", token);
            o.addProperty("expires", expires);
            o.addProperty("premium", premium);
            return o;
        }

        public static Room fromJson(JsonObject o) {
            return new Room(Json.str(o, "room"), Profile.fromJson(Json.sub(o, "host")), Json.str(o, "world"),
                    Json.str(o, "token"), Json.num(o, "expires"), Json.bool(o, "premium"));
        }
    }

    public static String friendKey(Profile profile) {
        return FRIEND_PREFIX + encode(Json.GSON.toJson(profile.toJson()));
    }

    public static String roomKey(Room room) {
        return ROOM_PREFIX + encode(Json.GSON.toJson(room.toJson()));
    }

    public static Profile parseFriendKey(String text) {
        String body = body(text, FRIEND_PREFIX);
        Profile p = Profile.fromJson(Json.parse(decode(body)));
        if (p.id.isEmpty() || p.ed.isEmpty() || p.x.isEmpty()) {
            throw new IllegalArgumentException("friend key is incomplete");
        }
        if (!p.selfCertified()) {
            throw new IllegalArgumentException("friend key id does not match its key");
        }
        return p;
    }

    public static Room parseRoomKey(String text) {
        String body = body(text, ROOM_PREFIX);
        Room r = Room.fromJson(Json.parse(decode(body)));
        if (r.roomId().isEmpty() || r.host().id.isEmpty()) {
            throw new IllegalArgumentException("room key is incomplete");
        }
        if (!r.host().selfCertified()) {
            throw new IllegalArgumentException("room key host id does not match its key");
        }
        return r;
    }

    public static boolean looksLikeKey(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.startsWith(FRIEND_PREFIX) || t.startsWith(ROOM_PREFIX)
                || t.startsWith(OFFER_PREFIX) || t.startsWith(ANSWER_PREFIX);
    }

    /** Self contained direct connection offer, exchanged through any chat. */
    public static String directOffer(JsonObject payload) {
        return OFFER_PREFIX + encode(Json.GSON.toJson(payload));
    }

    public static JsonObject parseDirectOffer(String text) {
        return Json.parse(decode(body(text, OFFER_PREFIX)));
    }

    public static String directAnswer(JsonObject payload) {
        return ANSWER_PREFIX + encode(Json.GSON.toJson(payload));
    }

    public static JsonObject parseDirectAnswer(String text) {
        return Json.parse(decode(body(text, ANSWER_PREFIX)));
    }

    public static boolean isDirectOffer(String text) {
        return text != null && text.strip().startsWith(OFFER_PREFIX);
    }

    public static boolean isDirectAnswer(String text) {
        return text != null && text.strip().startsWith(ANSWER_PREFIX);
    }

    private static String encode(String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        String sum = Bytes.hex(Bytes.sha256(raw)).substring(0, 8);
        return Bytes.b64u(raw) + "." + sum;
    }

    private static String decode(String body) {
        int dot = body.lastIndexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("key is malformed");
        }
        byte[] raw = Bytes.unb64u(body.substring(0, dot));
        String sum = Bytes.hex(Bytes.sha256(raw)).substring(0, 8);
        if (!sum.equals(body.substring(dot + 1))) {
            throw new IllegalArgumentException("key is corrupted");
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String body(String text, String prefix) {
        if (text == null) {
            throw new IllegalArgumentException("key is empty");
        }
        String t = text.replaceAll("\\s+", "");
        if (!t.startsWith(prefix)) {
            throw new IllegalArgumentException("not a NetPlayer key");
        }
        return t.substring(prefix.length());
    }
}
