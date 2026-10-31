package com.clevebitr.netplayer.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Address helpers shared by the ICE and TURN paths. */
public final class Addresses {
    private Addresses() {
    }

    /**
     * Parses {@code ip:port} where {@code ip} must be a literal (IPv4, IPv6 or bracketed IPv6).
     * Relay addresses arrive over the signaling channel, so a host name here would mean the peer
     * could make us resolve names of its choosing.
     */
    public static InetSocketAddress parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        if (value.isEmpty()) {
            return null;
        }
        String host;
        String portText;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0 || close + 2 > value.length() || value.charAt(close + 1) != ':') {
                return null;
            }
            host = value.substring(1, close);
            portText = value.substring(close + 2);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon <= 0) {
                return null;
            }
            host = value.substring(0, colon);
            portText = value.substring(colon + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portText.strip());
        } catch (Exception e) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        InetAddress address = literal(host);
        return address == null ? null : new InetSocketAddress(address, port);
    }

    public static String format(InetSocketAddress address) {
        if (address == null) {
            return "";
        }
        String host = address.getAddress() == null
                ? address.getHostString() : address.getAddress().getHostAddress();
        boolean ipv6 = host.indexOf(':') >= 0;
        return (ipv6 ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    /** Only numeric literals; anything else (a name) is refused without touching the resolver. */
    private static InetAddress literal(String host) {
        try {
            String value = host.strip();
            if (value.indexOf(':') < 0) {
                String[] parts = value.split("[.]", -1);
                if (parts.length != 4) {
                    return null;
                }
                byte[] raw = new byte[4];
                for (int i = 0; i < 4; i++) {
                    if (parts[i].isEmpty() || parts[i].length() > 3) {
                        return null;
                    }
                    for (int j = 0; j < parts[i].length(); j++) {
                        if (!Character.isDigit(parts[i].charAt(j))) {
                            return null;
                        }
                    }
                    int octet = Integer.parseInt(parts[i]);
                    if (octet > 255) {
                        return null;
                    }
                    raw[i] = (byte) octet;
                }
                return InetAddress.getByAddress(raw);
            }
            if (value.indexOf(':') == value.lastIndexOf(':')) {
                return null;
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.digit(c, 16) < 0 && c != ':' && c != '.') {
                    return null;
                }
            }
            return InetAddress.getByName(value);
        } catch (Exception e) {
            return null;
        }
    }
}
