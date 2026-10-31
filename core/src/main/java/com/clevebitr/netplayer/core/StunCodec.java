package com.clevebitr.netplayer.core;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal STUN/TURN wire format (RFC 5389 + RFC 5766): 20 byte header with a 96 bit transaction id
 * and type/length value attributes. Only what a TURN client needs is implemented, no extensions.
 *
 * <p>The message integrity helper is the classic long term credential form: HMAC-SHA1 over the
 * message so far, with the length field already counting the 24 bytes the attribute itself occupies.</p>
 */
final class StunCodec {
    static final int MAGIC_COOKIE = 0x2112A442;
    static final int HEADER = 20;
    static final int INTEGRITY_ATTR = 24;

    static final int ALLOCATE = 0x0003;
    static final int REFRESH = 0x0004;
    static final int CREATE_PERMISSION = 0x0008;
    static final int CHANNEL_BIND = 0x0009;
    static final int DATA_INDICATION = 0x0017;

    static final int ATTR_MAPPED_ADDRESS = 0x0001;
    static final int ATTR_USERNAME = 0x0006;
    static final int ATTR_MESSAGE_INTEGRITY = 0x0008;
    static final int ATTR_ERROR_CODE = 0x0009;
    static final int ATTR_CHANNEL_NUMBER = 0x000C;
    static final int ATTR_LIFETIME = 0x000D;
    static final int ATTR_XOR_PEER_ADDRESS = 0x0012;
    static final int ATTR_DATA = 0x0013;
    static final int ATTR_REALM = 0x0014;
    static final int ATTR_NONCE = 0x0015;
    static final int ATTR_XOR_RELAYED_ADDRESS = 0x0016;
    static final int ATTR_REQUESTED_TRANSPORT = 0x0019;
    static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    private StunCodec() {
    }

    record Attr(int type, byte[] value) {
    }

    static final class Message {
        final int type;
        final byte[] txid;
        final Map<Integer, byte[]> attrs;

        Message(int type, byte[] txid, Map<Integer, byte[]> attrs) {
            this.type = type;
            this.txid = txid;
            this.attrs = attrs;
        }

        boolean has(int attr) {
            return attrs.containsKey(attr);
        }

        byte[] raw(int attr) {
            return attrs.get(attr);
        }

        String text(int attr) {
            byte[] value = attrs.get(attr);
            return value == null ? "" : new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }

        int number(int attr) {
            byte[] value = attrs.get(attr);
            return value == null || value.length < 4
                    ? 0 : ((value[0] & 0xff) << 24) | ((value[1] & 0xff) << 16) | ((value[2] & 0xff) << 8) | (value[3] & 0xff);
        }

        /** The class of an error response, e.g. 401 or 438. */
        int errorCode() {
            byte[] value = attrs.get(ATTR_ERROR_CODE);
            if (value == null || value.length < 4) {
                return 0;
            }
            return (value[2] & 0x07) * 100 + (value[3] & 0xff);
        }

        /** The reason phrase that follows the numeric error code ("" for success). */
        String errorReason() {
            byte[] value = attrs.get(ATTR_ERROR_CODE);
            if (value == null || value.length <= 4) {
                return "";
            }
            return new String(value, 4, value.length - 4, java.nio.charset.StandardCharsets.UTF_8).strip();
        }

        boolean isSuccess() {
            return (type & 0x0110) == 0x0100;
        }

        boolean isError() {
            return (type & 0x0110) == 0x0110;
        }

        InetAddress xorAddress(int attr) {
            return decodeXor(attrs.get(attr), txid);
        }

        InetAddress unmappedAddress(int attr) {
            return decodePlain(attrs.get(attr));
        }
    }

    static byte[] randomTxid() {
        return Bytes.random(12);
    }

    static byte[] encode(int type, byte[] txid, List<Attr> attrs) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(128);
        for (Attr attr : attrs) {
            writeShort(body, attr.type());
            writeShort(body, attr.value().length);
            body.writeBytes(attr.value());
            int pad = (4 - attr.value().length % 4) % 4;
            for (int i = 0; i < pad; i++) {
                body.write(0);
            }
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER + payload.length);
        writeShort(out, type);
        writeShort(out, payload.length);
        writeInt(out, MAGIC_COOKIE);
        out.writeBytes(txid);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * Appends MESSAGE-INTEGRITY. The header length must already include it, which is why the
     * attribute is written by hand instead of going through {@link #encode}.
     */
    static byte[] sign(byte[] message, byte[] key) {
        byte[] withLength = message.clone();
        int newLength = message.length - HEADER + INTEGRITY_ATTR;
        withLength[2] = (byte) (newLength >> 8);
        withLength[3] = (byte) newLength;
        byte[] digest = hmacSha1(key, withLength);
        byte[] out = new byte[withLength.length + INTEGRITY_ATTR];
        System.arraycopy(withLength, 0, out, 0, withLength.length);
        out[withLength.length] = (byte) (ATTR_MESSAGE_INTEGRITY >> 8);
        out[withLength.length + 1] = (byte) ATTR_MESSAGE_INTEGRITY;
        out[withLength.length + 2] = 0;
        out[withLength.length + 3] = 20;
        System.arraycopy(digest, 0, out, withLength.length + 4, digest.length);
        return out;
    }

    /** True when the message carries a valid MESSAGE-INTEGRITY for {@code key}. */
    static boolean check(byte[] message, byte[] key) {
        int at = indexOfAttr(message, ATTR_MESSAGE_INTEGRITY);
        if (at < 0 || at + 4 + 20 > message.length) {
            return false;
        }
        byte[] head = new byte[at];
        System.arraycopy(message, 0, head, 0, at);
        int newLength = at - HEADER + INTEGRITY_ATTR;
        head[2] = (byte) (newLength >> 8);
        head[3] = (byte) newLength;
        byte[] expected = hmacSha1(key, head);
        for (int i = 0; i < 20; i++) {
            if (expected[i] != message[at + 4 + i]) {
                return false;
            }
        }
        return true;
    }

    static Message parse(byte[] data) {
        if (data.length < HEADER) {
            return null;
        }
        int type = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
        int length = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        if (readInt(data, 4) != MAGIC_COOKIE || HEADER + length > data.length) {
            return null;
        }
        byte[] txid = new byte[12];
        System.arraycopy(data, 8, txid, 0, 12);
        Map<Integer, byte[]> attrs = new LinkedHashMap<>();
        int at = HEADER;
        while (at + 4 <= HEADER + length) {
            int attrType = ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
            int attrLength = ((data[at + 2] & 0xff) << 8) | (data[at + 3] & 0xff);
            if (at + 4 + attrLength > data.length) {
                return null;
            }
            byte[] value = new byte[attrLength];
            System.arraycopy(data, at + 4, value, 0, attrLength);
            attrs.putIfAbsent(attrType, value);
            at += 4 + attrLength;
            at += (4 - attrLength % 4) % 4;
        }
        return new Message(type, txid, attrs);
    }

    static byte[] xorPeerAddress(InetAddress address, int port, byte[] txid) {
        return encodeXor(address, port, txid);
    }

    static byte[] channelNumber(int channel) {
        return new byte[]{(byte) (channel >> 8), (byte) channel, 0, 0};
    }

    static byte[] lifetime(long seconds) {
        int value = (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    static byte[] requestedUdpTransport() {
        return new byte[]{17, 0, 0, 0};
    }

    static InetAddress decodeXor(byte[] value, byte[] txid) {
        if (value == null || value.length < 8 || (value[1] & 0xff) != 0x01) {
            return null;
        }
        byte[] raw = new byte[]{value[4], value[5], value[6], value[7]};
        byte[] mask = {(byte) (MAGIC_COOKIE >> 24), (byte) (MAGIC_COOKIE >> 16),
                (byte) (MAGIC_COOKIE >> 8), (byte) MAGIC_COOKIE};
        for (int i = 0; i < 4; i++) {
            raw[i] ^= mask[i];
        }
        try {
            return InetAddress.getByAddress(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static int decodeXorPort(byte[] value, byte[] txid) {
        if (value == null || value.length < 8) {
            return 0;
        }
        int port = ((value[2] & 0xff) << 8) | (value[3] & 0xff);
        return port ^ (MAGIC_COOKIE >> 16);
    }

    static InetAddress decodePlain(byte[] value) {
        if (value == null || value.length < 8 || (value[1] & 0xff) != 0x01) {
            return null;
        }
        try {
            return InetAddress.getByAddress(new byte[]{value[4], value[5], value[6], value[7]});
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] encodeXor(InetAddress address, int port, byte[] txid) {
        byte[] raw = address.getAddress();
        byte[] out = new byte[8];
        out[0] = 0;
        out[1] = 1;
        int xored = port ^ (MAGIC_COOKIE >> 16);
        out[2] = (byte) (xored >> 8);
        out[3] = (byte) xored;
        byte[] mask = {(byte) (MAGIC_COOKIE >> 24), (byte) (MAGIC_COOKIE >> 16),
                (byte) (MAGIC_COOKIE >> 8), (byte) MAGIC_COOKIE};
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) (raw[i] ^ mask[i]);
        }
        return out;
    }

    static List<Attr> attrs() {
        return new ArrayList<>();
    }

    /** Key for long term credentials: MD5(username:realm:password). */
    static byte[] longTermKey(String username, String realm, String password) {
        try {
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            md5.update((username + ":" + realm + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return md5.digest();
        } catch (Exception e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("hmac unavailable", e);
        }
    }

    private static int indexOfAttr(byte[] message, int want) {
        int length = ((message[2] & 0xff) << 8) | (message[3] & 0xff);
        int at = HEADER;
        while (at + 4 <= HEADER + length && at + 4 <= message.length) {
            int type = ((message[at] & 0xff) << 8) | (message[at + 1] & 0xff);
            int attrLength = ((message[at + 2] & 0xff) << 8) | (message[at + 3] & 0xff);
            if (type == want) {
                return at;
            }
            at += 4 + attrLength + (4 - attrLength % 4) % 4;
        }
        return -1;
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xff) << 24) | ((data[at + 1] & 0xff) << 16)
                | ((data[at + 2] & 0xff) << 8) | (data[at + 3] & 0xff);
    }
}
