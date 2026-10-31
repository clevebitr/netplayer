package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

/**
 * The exact byte strings that get signed during a handshake. Keeping them in one place is what
 * makes the signatures meaningful: the basis binds both identities, the room and the ephemeral key,
 * so a captured signature cannot be replayed to a different peer or re-used for another room.
 *
 * <p>Everything here is versioned with a trailing number. Bump it when the layout changes, old
 * signatures then simply fail to verify instead of silently verifying against the wrong fields.</p>
 */
public final class Handshake {
    private Handshake() {
    }

    /** ICE description, signed by the sender and verified against the peer we already know. */
    public static String ice(String roomId, String fromId, String toId, JsonObject description) {
        return "ice2:" + nz(roomId) + ":" + nz(fromId) + ":" + nz(toId) + ":"
                + Json.str(description, "ufrag") + ":" + Json.str(description, "pwd") + ":"
                + Json.str(description, "epk");
    }

    /** Mojang join proof, signed by the guest identity that owns the tunnel. */
    public static String premium(String name, String uuid, String serverId) {
        return "premium1:" + nz(name) + ":" + nz(uuid) + ":" + nz(serverId);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
