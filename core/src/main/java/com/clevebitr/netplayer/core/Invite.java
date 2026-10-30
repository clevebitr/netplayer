package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Shareable keys. A friend key carries the sender's public identity, a room key also points at a
 * world that is currently open. Both are plain text so they survive chat and clipboard.
 */
public final class Invite {
    private static final String FRIEND_PREFIX = "npfriend1.";
    private static final String ROOM_PREFIX = "npjoin1.";

    private Invite() {
    }

    public record Room(String roomId, Profile host, String world, String token, long expires) {
        public JsonObject toJson() {
            JsonObject o = Json.obj();
            o.addProperty("room", roomId);
            o.add("host", host.toJson());
            o.addProperty("world", world);
            o.addProperty("token", token);
            o.addProperty("expires", expires);
            return o;
        }

        public static Room fromJson(JsonObject o) {
            return new Room(Json.str(o, "room"), Profile.fromJson(Json.sub(o, "host")), Json.str(o, "world"),
                    Json.str(o, "token"), Json.num(o, "expires"));
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
        return p;
    }

    public static Room parseRoomKey(String text) {
        String body = body(text, ROOM_PREFIX);
        Room r = Room.fromJson(Json.parse(decode(body)));
        if (r.roomId().isEmpty() || r.host().id.isEmpty()) {
            throw new IllegalArgumentException("room key is incomplete");
        }
        return r;
    }

    public static boolean looksLikeKey(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.startsWith(FRIEND_PREFIX) || t.startsWith(ROOM_PREFIX);
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
