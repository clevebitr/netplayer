package com.clevebitr.netplayer;

import java.util.HashSet;
import java.util.Set;

public final class Presence {
    public final String id;
    public String state;
    public String detail;
    public String roomId;
    public String world;
    public Set<String> allow = new HashSet<>();

    public Presence(String id, String state, String detail) {
        this.id = id;
        this.state = state;
        this.detail = detail;
    }

    public boolean online() {
        return !"offline".equals(state);
    }

    public boolean hosting() {
        return roomId != null && !roomId.isEmpty();
    }

    public boolean openTo(String playerId) {
        return hosting() && (allow.contains("*") || allow.contains(playerId));
    }

    public static Presence offline(String id) {
        return new Presence(id, "offline", "");
    }
}
