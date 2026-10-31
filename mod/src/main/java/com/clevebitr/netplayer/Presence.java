package com.clevebitr.netplayer;

/**
 * What a friend look like from here. The signaling server decides the {@code open} flag: room ids,
 * room tokens and allow lists never leave the server (a leaked allow list would hand out the friend
 * ids needed to impersonate guests).
 */
public final class Presence {
    public final String id;
    public String state;
    public String detail;
    /** True while the friend has a world published. */
    public boolean hosting;
    /** True when that world is open for this install. */
    public boolean open;
    public String world = "";
    public boolean premium;
    public String mcName = "";
    public String mcUuid = "";

    public Presence(String id, String state, String detail) {
        this.id = id;
        this.state = state;
        this.detail = detail;
    }

    public boolean online() {
        return !"offline".equals(state);
    }

    public boolean hosting() {
        return hosting;
    }

    /** Kept as a method so call sites do not have to care that the server answers this for us. */
    public boolean openTo(String playerId) {
        return hosting && open;
    }

    public static Presence offline(String id) {
        return new Presence(id, "offline", "");
    }
}
