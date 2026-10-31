package com.clevebitr.netplayer.net;

import com.clevebitr.netplayer.core.TurnClient;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Tunnel transport through a TURN relay: the fallback when ICE cannot find a direct path
 * (symmetric NAT / carrier grade NAT on both sides).
 *
 * <p>Both peers allocate on the same TURN server and publish the relayed address they got through
 * the signed signaling message. Each side then opens a permission and binds a channel to the peer's
 * relayed address, and the sealed tunnel packets travel as ChannelData. The provider only ever sees
 * AES-GCM ciphertext.</p>
 */
public final class TurnRelay implements Link {
    private static final Logger LOG = LogUtils.getLogger();
    /** Any number in 0x4000-0x4FFF works; both sides may pick the same one, they are per allocation. */
    private static final int CHANNEL = 0x4001;

    private final TurnClient turn;
    private final InetSocketAddress peerRelay;
    private volatile boolean closed;

    private TurnRelay(TurnClient turn, InetSocketAddress peerRelay) {
        this.turn = turn;
        this.peerRelay = peerRelay;
    }

    /** Wires an existing allocation to {@code peerRelay}; the caller keeps ownership of the client. */
    public static TurnRelay start(TurnClient turn, InetSocketAddress peerRelay) throws IOException {
        turn.createPermission(peerRelay);
        turn.bindChannel(CHANNEL, peerRelay);
        LOG.info("TURN relay ready: local={} peer={}", turn.allocation().relayed(), peerRelay);
        return new TurnRelay(turn, peerRelay);
    }

    @Override
    public String kind() {
        return "relay";
    }

    @Override
    public void send(byte[] data) {
        if (!closed) {
            turn.send(CHANNEL, data);
        }
    }

    @Override
    public byte[] receive() {
        if (closed) {
            return null;
        }
        return turn.receive();
    }

    @Override
    public void close() {
        closed = true;
        turn.close();
    }
}
