package com.clevebitr.netplayer.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tiny TURN client (RFC 5766, UDP transport, long term credentials), just enough to relay one
 * byte stream:
 *
 * <pre>
 * allocate()  -> relayed address, handed to the peer over the signaling channel
 * createPermission(peer relayed address)
 * bindChannel(channel, peer relayed address)
 * send(channel, payload)  /  receive()        <-> ChannelData framing
 * </pre>
 *
 * <p>Why hand rolled: ice4j, which does the direct NAT traversal, has no TURN support, and a relay
 * is the only way out of symmetric NAT / carrier grade NAT pairs. The relay only ever sees the
 * AES-GCM datagrams of the tunnel, so the provider cannot read game traffic.</p>
 *
 * <p>One reader thread owns the socket: it matches responses to the request waiting for that
 * transaction id and queues ChannelData / Data indications for {@link #receive()}.</p>
 */
public final class TurnClient implements AutoCloseable {
    private static final int MAX_DATAGRAM = 2048;
    private static final long REQUEST_TIMEOUT_MS = 5000L;

    public record Allocation(InetSocketAddress relayed, InetSocketAddress mapped, long lifetimeSeconds) {
    }

    private record Reply(StunCodec.Message message, byte[] raw) {
    }

    private final InetSocketAddress server;
    private final String username;
    private final String password;
    private final DatagramSocket socket;
    private final Map<String, CompletableFuture<Reply>> pending = new ConcurrentHashMap<>();
    private final ArrayDeque<byte[]> inbox = new ArrayDeque<>();
    private final Object inboxLock = new Object();

    private volatile String realm = "";
    private volatile byte[] nonce = new byte[0];
    private volatile boolean closed;
    private volatile Allocation allocation;
    private volatile long refreshAt;
    private volatile String lastError = "";

    public TurnClient(InetSocketAddress server, String username, String password) throws IOException {
        this.server = server;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.socket = new DatagramSocket();
        Thread.ofVirtual().name("netplayer-turn-rx").start(this::readLoop);
    }

    /** Parses {@code turn:host:port}, {@code host:port} or plain {@code host} (port 3478). */
    public static InetSocketAddress parseServer(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        if (value.contains("://")) {
            if (!value.startsWith("turn://")) {
                return null;
            }
            value = value.substring("turn://".length());
        } else if (value.startsWith("turn:")) {
            value = value.substring("turn:".length());
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int colon = value.lastIndexOf(':');
        String host = colon > 0 ? value.substring(0, colon) : value;
        int port = colon > 0 ? parseInt(value.substring(colon + 1)) : 3478;
        if (host.isBlank() || port <= 0 || port > 65535) {
            return null;
        }
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (Exception e) {
            return 0;
        }
    }

    public String lastError() {
        return lastError;
    }

    public Allocation allocation() {
        return allocation;
    }

    /** Allocates a relayed transport address, handling the 401/438 long term credential dance. */
    public Allocation allocate(long lifetimeSeconds) throws IOException {
        StunCodec.Message response = request(StunCodec.ALLOCATE, lifetimeSeconds, true);
        InetAddress relayed = response.xorAddress(StunCodec.ATTR_XOR_RELAYED_ADDRESS);
        int relayedPort = StunCodec.decodeXorPort(response.raw(StunCodec.ATTR_XOR_RELAYED_ADDRESS), response.txid);
        if (relayed == null || relayedPort <= 0) {
            lastError = "no relayed address in the allocate response";
            throw new IOException(lastError);
        }
        InetAddress mapped = response.xorAddress(StunCodec.ATTR_XOR_MAPPED_ADDRESS);
        int mappedPort = StunCodec.decodeXorPort(response.raw(StunCodec.ATTR_XOR_MAPPED_ADDRESS), response.txid);
        long lifetime = response.number(StunCodec.ATTR_LIFETIME);
        allocation = new Allocation(new InetSocketAddress(relayed, relayedPort),
                mapped == null ? null : new InetSocketAddress(mapped, mappedPort),
                lifetime <= 0 ? lifetimeSeconds : lifetime);
        // Refresh at half of the lifetime the server actually granted.
        refreshAt = System.currentTimeMillis() + Math.max(30_000L, lifetimeSeconds * 500L);
        return allocation;
    }

    /** A permission is per peer IP; port 0 means "any port", which is what a relayed peer needs. */
    public void createPermission(InetSocketAddress peer) throws IOException {
        byte[] peerAddress = StunCodec.xorPeerAddress(peer.getAddress(), 0, StunCodec.randomTxid());
        request(StunCodec.CREATE_PERMISSION, 0, false,
                new StunCodec.Attr(StunCodec.ATTR_XOR_PEER_ADDRESS, peerAddress));
    }

    /** Binds a channel number (0x4000-0x4FFF) to a peer so each payload costs 4 bytes, not 36. */
    public void bindChannel(int channel, InetSocketAddress peer) throws IOException {
        byte[] peerAddress = StunCodec.xorPeerAddress(peer.getAddress(), peer.getPort(), StunCodec.randomTxid());
        request(StunCodec.CHANNEL_BIND, 0, false,
                new StunCodec.Attr(StunCodec.ATTR_CHANNEL_NUMBER, StunCodec.channelNumber(channel)),
                new StunCodec.Attr(StunCodec.ATTR_XOR_PEER_ADDRESS, peerAddress));
    }

    public void refresh(long lifetimeSeconds) throws IOException {
        request(StunCodec.REFRESH, lifetimeSeconds, false);
        refreshAt = System.currentTimeMillis() + Math.max(30_000L, lifetimeSeconds * 500L);
    }

    /** Wraps {@code payload} in ChannelData; the channel has to be bound first. */
    public void send(int channel, byte[] payload) {
        if (closed) {
            return;
        }
        byte[] packet = new byte[4 + payload.length];
        packet[0] = (byte) (channel >> 8);
        packet[1] = (byte) channel;
        packet[2] = (byte) (payload.length >> 8);
        packet[3] = (byte) payload.length;
        System.arraycopy(payload, 0, packet, 4, payload.length);
        try {
            socket.send(new DatagramPacket(packet, packet.length, server));
        } catch (Exception ignored) {
        }
    }

    /** Blocks until one relayed payload arrives; returns null once the client is closed. */
    public byte[] receive() {
        synchronized (inboxLock) {
            while (inbox.isEmpty() && !closed) {
                try {
                    inboxLock.wait(200);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return inbox.poll();
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                return;
            }
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            try {
                dispatch(data);
            } catch (Exception ignored) {
            }
            maybeRefresh();
        }
    }

    private void dispatch(byte[] data) {
        if (data.length >= 4 && (data[0] & 0xff) >= 0x40 && (data[0] & 0xff) <= 0x7f) {
            int length = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
            if (data.length >= 4 + length) {
                enqueue(Arrays.copyOfRange(data, 4, 4 + length));
            }
            return;
        }
        StunCodec.Message message = StunCodec.parse(data);
        if (message == null) {
            return;
        }
        if (message.type == StunCodec.DATA_INDICATION) {
            byte[] payload = message.raw(StunCodec.ATTR_DATA);
            if (payload != null) {
                enqueue(payload);
            }
            return;
        }
        CompletableFuture<Reply> waiter = pending.remove(Bytes.hex(message.txid));
        if (waiter != null) {
            waiter.complete(new Reply(message, data));
        }
    }

    private void enqueue(byte[] payload) {
        synchronized (inboxLock) {
            if (inbox.size() > 1024) {
                return;
            }
            inbox.add(payload);
            inboxLock.notifyAll();
        }
    }

    private void maybeRefresh() {
        if (closed || allocation == null || System.currentTimeMillis() < refreshAt) {
            return;
        }
        refreshAt = System.currentTimeMillis() + 60_000L;
        try {
            refresh(Math.max(60L, allocation.lifetimeSeconds()));
        } catch (Exception e) {
            lastError = "refresh failed: " + e.getMessage();
        }
    }

    /** One request, retried once with credentials after a 401/438 challenge. */
    private StunCodec.Message request(int type, long lifetime, boolean needTransport, StunCodec.Attr... extra)
            throws IOException {
        StunCodec.Message response = attempt(type, lifetime, needTransport, extra);
        if (response.isError() && (response.errorCode() == 401 || response.errorCode() == 438)) {
            realm = response.text(StunCodec.ATTR_REALM);
            byte[] fresh = response.raw(StunCodec.ATTR_NONCE);
            nonce = fresh == null ? new byte[0] : fresh;
            if (username.isEmpty() || password.isEmpty()) {
                lastError = "turn server requires credentials";
                throw new IOException(lastError);
            }
            response = attempt(type, lifetime, needTransport, extra);
        }
        if (response.isError()) {
            int code = response.errorCode();
            String reason = response.errorReason();
            lastError = code == 401 || code == 438
                    ? "turn server rejected the credentials (" + code + ")"
                    : "turn error " + code + (reason.isEmpty() ? "" : " " + reason);
            throw new IOException(lastError);
        }
        return response;
    }

    private StunCodec.Message attempt(int type, long lifetime, boolean needTransport, StunCodec.Attr... extra)
            throws IOException {
        byte[] txid = StunCodec.randomTxid();
        List<StunCodec.Attr> attrs = new ArrayList<>();
        if (needTransport) {
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_REQUESTED_TRANSPORT, StunCodec.requestedUdpTransport()));
        }
        if (lifetime > 0) {
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_LIFETIME, StunCodec.lifetime(lifetime)));
        }
        attrs.addAll(Arrays.asList(extra));
        boolean authenticated = nonce.length > 0;
        byte[] key = authenticated ? StunCodec.longTermKey(username, realm, password) : new byte[0];
        if (authenticated) {
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_USERNAME, utf8(username)));
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_REALM, utf8(realm)));
            attrs.add(new StunCodec.Attr(StunCodec.ATTR_NONCE, nonce));
        }
        byte[] message = StunCodec.encode(type, txid, attrs);
        if (authenticated) {
            message = StunCodec.sign(message, key);
        }
        CompletableFuture<Reply> waiter = new CompletableFuture<>();
        pending.put(Bytes.hex(txid), waiter);
        try {
            socket.send(new DatagramPacket(message, message.length, server));
            Reply reply = waiter.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (authenticated && reply.message().isSuccess() && !StunCodec.check(reply.raw(), key)) {
                // Success responses are checked: an off-path spoofer must not be able to hand us a
                // bogus relayed address. Error responses are not, because a server rejecting our
                // credentials signs them with the credential it has on file, not with ours.
                lastError = "turn response failed its integrity check";
                throw new IOException(lastError);
            }
            return reply.message();
        } catch (TimeoutException e) {
            lastError = "turn server did not answer in time";
            throw new IOException(lastError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause() == null ? "turn request failed" : e.getCause().getMessage());
        } finally {
            pending.remove(Bytes.hex(txid));
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        synchronized (inboxLock) {
            inbox.clear();
            inboxLock.notifyAll();
        }
        for (CompletableFuture<Reply> waiter : pending.values()) {
            waiter.completeExceptionally(new IOException("closed"));
        }
        pending.clear();
    }
}
