package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/** Locally generated long term identity: Ed25519 for signing, X25519 for key agreement. */
public final class Identity {
    public final Profile profile;
    private final PrivateKey edPriv;
    private final PrivateKey xPriv;

    private Identity(Profile profile, PrivateKey edPriv, PrivateKey xPriv) {
        this.profile = profile;
        this.edPriv = edPriv;
        this.xPriv = xPriv;
    }

    public static Identity create(String name) {
        try {
            KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KeyPair x = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            return new Identity(Profile.of(name, ed.getPublic().getEncoded(), x.getPublic().getEncoded()),
                    ed.getPrivate(), x.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("crypto unavailable", e);
        }
    }

    public static Identity loadOrCreate(Path file, String name) {
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = Json.parse(Files.readString(file));
                Profile p = Profile.fromJson(Json.sub(o, "profile"));
                KeyFactory edf = KeyFactory.getInstance("Ed25519");
                KeyFactory xf = KeyFactory.getInstance("X25519");
                PrivateKey ed = edf.generatePrivate(new PKCS8EncodedKeySpec(Bytes.unb64(Json.str(o, "ed_priv"))));
                PrivateKey x = xf.generatePrivate(new PKCS8EncodedKeySpec(Bytes.unb64(Json.str(o, "x_priv"))));
                return new Identity(p, ed, x);
            } catch (Exception ignored) {
                // corrupted identity: fall through and generate a new one
            }
        }
        Identity identity = create(name);
        identity.save(file);
        return identity;
    }

    public void save(Path file) {
        JsonObject o = Json.obj();
        o.add("profile", profile.toJson());
        o.addProperty("ed_priv", Bytes.b64(edPriv.getEncoded()));
        o.addProperty("x_priv", Bytes.b64(xPriv.getEncoded()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.GSON.toJson(o));
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    public Identity renamed(String name, Path file) {
        Identity identity = new Identity(new Profile(profile.id, name, profile.ed, profile.x), edPriv, xPriv);
        identity.save(file);
        return identity;
    }

    public byte[] sign(byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(edPriv);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public PrivateKey xPrivate() {
        return xPriv;
    }

    public PublicKey xPublic() {
        return profile.xKey();
    }
}
