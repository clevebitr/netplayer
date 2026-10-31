package com.clevebitr.netplayer.net;

/**
 * The datagram pipe a {@link Tunnel} runs on. Two implementations: a direct ICE/UDP path
 * ({@link IceLink}) and a TURN relay ({@link TurnRelay}). Both only carry sealed packets, so the
 * tunnel does not care which one is in use.
 */
public interface Link extends AutoCloseable {
    void send(byte[] data);

    /** Blocks until one datagram arrives; null once the link is closed. */
    byte[] receive();

    /** Human readable path kind: lan, ipv6, punch or relay. */
    String kind();

    @Override
    void close();
}
