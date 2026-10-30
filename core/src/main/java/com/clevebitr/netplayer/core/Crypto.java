package com.clevebitr.netplayer.core;

import com.google.gson.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

public final class Crypto {
    public static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private Crypto() {
    }

    public static KeyPair xKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("x25519 unavailable", e);
        }
    }

    public static PublicKey xPublic(String encoded) {
        try {
            return KeyFactory.getInstance("X25519")
                    .generatePublic(new X509EncodedKeySpec(Bytes.unb64(encoded)));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad x25519 key", e);
        }
    }

    public static SecretKeySpec agree(PrivateKey own, PublicKey peer) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(own);
            ka.doPhase(peer, true);
            return new SecretKeySpec(Bytes.sha256(ka.generateSecret()), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("x25519 failed", e);
        }
    }

    /** Encrypts with a random nonce and prefixes it, so every datagram is self contained. */
    public static byte[] seal(SecretKeySpec key, byte[] plain) {
        try {
            byte[] nonce = Bytes.random(NONCE);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] box = c.doFinal(plain);
            return Bytes.concat(nonce, box);
        } catch (Exception e) {
            throw new IllegalStateException("seal failed", e);
        }
    }

    /** Returns null when the packet is forged or corrupted. */
    public static byte[] open(SecretKeySpec key, byte[] packet, int offset, int length) {
        if (length <= NONCE) {
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packet, offset, NONCE));
            return c.doFinal(packet, offset + NONCE, length - NONCE);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] sign(Identity identity, byte[] data) {
        return identity.sign(data);
    }

    public static boolean verify(Profile profile, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(profile.edKey());
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verify(Profile profile, String data, String signatureB64) {
        try {
            return verify(profile, data.getBytes(java.nio.charset.StandardCharsets.UTF_8), Bytes.unb64(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }
}
