package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;

/** Public half of a NetPlayer identity: what friends store and what the invite key carries. */
public final class Profile {
    public final String id;
    public final String name;
    public final String ed;
    public final String x;

    public Profile(String id, String name, String ed, String x) {
        this.id = id;
        this.name = name;
        this.ed = ed;
        this.x = x;
    }

    public static Profile of(String name, byte[] edPub, byte[] xPub) {
        String id = Bytes.hex(Bytes.sha256(edPub)).substring(0, 16);
        return new Profile(id, name, Bytes.b64(edPub), Bytes.b64(xPub));
    }

    public JsonObject toJson() {
        JsonObject o = Json.obj();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("ed", ed);
        o.addProperty("x", x);
        return o;
    }

    public static Profile fromJson(JsonObject o) {
        return new Profile(Json.str(o, "id"), Json.str(o, "name"), Json.str(o, "ed"), Json.str(o, "x"));
    }

    public PublicKey edKey() {
        return parse("Ed25519", ed);
    }

    public PublicKey xKey() {
        return parse("X25519", x);
    }

    private static PublicKey parse(String algorithm, String encoded) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(Bytes.unb64(encoded)));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad key", e);
        }
    }

    public String shortName() {
        return name == null || name.isBlank() ? id : name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Profile p && id.equals(p.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return shortName() + "(" + id + ")";
    }
}
