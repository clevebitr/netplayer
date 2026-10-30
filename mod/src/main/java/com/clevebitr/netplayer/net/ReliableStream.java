package com.clevebitr.netplayer.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.TreeMap;

/**
 * Ordered reliable byte stream on top of datagrams: cumulative acks, a fixed send window and
 * timeout based retransmission. Deliberately tiny, Minecraft traffic is small and bursty.
 */
public final class ReliableStream {
    public static final int SYN = 1;
    public static final int DATA = 2;
    public static final int ACK = 3;
    public static final int FIN = 4;
    public static final int PING = 5;
    public static final int PONG = 6;

    private static final int HEADER = 11;
    private static final int WINDOW = 32;
    private static final int PAYLOAD = 1024;
    private static final long RETRANSMIT_MS = 250L;

    public interface Sink {
        void send(byte[] packet);
    }

    private final Sink sink;
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final TreeMap<Integer, byte[]> inflight = new TreeMap<>();
    private final TreeMap<Integer, Boolean> inflightFin = new TreeMap<>();
    private final TreeMap<Integer, byte[]> reorder = new TreeMap<>();
    private final ByteArrayOutputStream inbox = new ByteArrayOutputStream();
    private int nextSeq;
    private int baseSeq;
    private int expect;
    private int finSeq = -1;
    private long lastRetransmit = System.currentTimeMillis();
    private boolean eof;
    private boolean closed;
    private boolean synSent;

    public ReliableStream(Sink sink) {
        this.sink = sink;
    }

    public void start() {
        synchronized (lock) {
            if (!synSent) {
                synSent = true;
                emit(SYN, nextSeq, expect, EMPTY);
            }
        }
    }

    public void write(byte[] data, int offset, int length) throws IOException {
        for (int pos = 0; pos < length; pos += PAYLOAD) {
            int size = Math.min(PAYLOAD, length - pos);
            byte[] chunk = Arrays.copyOfRange(data, offset + pos, offset + pos + size);
            synchronized (lock) {
                while (!closed && inflight.size() + pending.size() >= WINDOW) {
                    try {
                        lock.wait(200);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
                if (closed) {
                    throw new IOException("stream closed");
                }
                pending.add(chunk);
                flush();
            }
        }
    }

    /** Returns bytes read, -1 on end of stream, 0 when nothing is available yet. */
    public int read(byte[] buffer, int offset, int length) throws IOException {
        synchronized (lock) {
            while (inbox.size() == 0 && !eof && !closed) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }
            if (closed && inbox.size() == 0) {
                return -1;
            }
            if (inbox.size() == 0) {
                return eof ? -1 : 0;
            }
            byte[] all = inbox.toByteArray();
            int size = Math.min(length, all.length);
            System.arraycopy(all, 0, buffer, offset, size);
            inbox.reset();
            if (all.length > size) {
                inbox.write(all, size, all.length - size);
            }
            return size;
        }
    }

    public void finish() {
        synchronized (lock) {
            if (closed || finSeq >= 0) {
                return;
            }
            finSeq = nextSeq++;
            inflight.put(finSeq, EMPTY);
            inflightFin.put(finSeq, Boolean.TRUE);
            emit(FIN, finSeq, expect, EMPTY);
            lastRetransmit = System.currentTimeMillis();
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public void onPacket(byte[] packet) {
        if (packet.length < HEADER) {
            return;
        }
        int type = packet[0];
        int seq = readInt(packet, 1);
        int ack = readInt(packet, 5);
        int size = readShort(packet, 9);
        synchronized (lock) {
            if (ack > baseSeq) {
                inflight.headMap(ack, false).clear();
                inflightFin.headMap(ack, false).clear();
                baseSeq = ack;
                lock.notifyAll();
                flush();
            }
            if (type == DATA && size > 0 && packet.length >= HEADER + size) {
                deliver(seq, Arrays.copyOfRange(packet, HEADER, HEADER + size));
                emit(ACK, nextSeq, expect, EMPTY);
            } else if (type == PING) {
                if (size >= 8) {
                    emit(PONG, 0, expect, Arrays.copyOfRange(packet, HEADER, HEADER + size));
                }
            } else if (type == PONG) {
                if (size >= 8) {
                    long sent = readLong(packet, HEADER);
                    if (sent > 0) {
                        rttMillis = (int) Math.max(0, System.currentTimeMillis() - sent);
                    }
                }
            } else if (type == FIN) {
                if (finSeq < 0) {
                    finSeq = seq;
                }
                deliver(seq, EMPTY);
                emit(ACK, nextSeq, expect, EMPTY);
                lock.notifyAll();
            }
        }
    }

    private volatile int rttMillis = -1;

    public int rttMillis() {
        return rttMillis;
    }

    public void ping() {
        long now = System.currentTimeMillis();
        byte[] stamp = new byte[8];
        for (int i = 0; i < 8; i++) {
            stamp[i] = (byte) (now >>> (56 - i * 8));
        }
        synchronized (lock) {
            emit(PING, 0, expect, stamp);
        }
    }

    public void tick() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (inflight.isEmpty() || now - lastRetransmit < RETRANSMIT_MS) {
                return;
            }
            lastRetransmit = now;
            for (var entry : inflight.entrySet()) {
                if (inflightFin.containsKey(entry.getKey())) {
                    emit(FIN, entry.getKey(), expect, EMPTY);
                } else {
                    emit(DATA, entry.getKey(), expect, entry.getValue());
                }
            }
        }
    }

    private void deliver(int seq, byte[] data) {
        if (seq < expect) {
            return;
        }
        if (seq == expect) {
            inbox.write(data, 0, data.length);
            expect++;
            while (reorder.containsKey(expect)) {
                byte[] more = reorder.remove(expect);
                inbox.write(more, 0, more.length);
                expect++;
            }
            if (finSeq >= 0 && expect > finSeq) {
                eof = true;
            }
            lock.notifyAll();
        } else if (seq < expect + WINDOW * 4) {
            reorder.putIfAbsent(seq, data);
        }
    }

    private void flush() {
        while (!pending.isEmpty() && inflight.size() < WINDOW) {
            byte[] chunk = pending.poll();
            int seq = nextSeq++;
            inflight.put(seq, chunk);
            emit(DATA, seq, expect, chunk);
        }
        lastRetransmit = System.currentTimeMillis();
    }

    private void emit(int type, int seq, int ack, byte[] payload) {
        byte[] packet = new byte[HEADER + payload.length];
        packet[0] = (byte) type;
        writeInt(packet, 1, seq);
        writeInt(packet, 5, ack);
        packet[9] = (byte) ((payload.length >> 8) & 0xff);
        packet[10] = (byte) (payload.length & 0xff);
        System.arraycopy(payload, 0, packet, HEADER, payload.length);
        sink.send(packet);
    }

    private static final byte[] EMPTY = new byte[0];

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static void writeInt(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 24);
        b[at + 1] = (byte) (value >>> 16);
        b[at + 2] = (byte) (value >>> 8);
        b[at + 3] = (byte) value;
    }

    private static long readLong(byte[] b, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (b[at + i] & 0xffL);
        }
        return value;
    }

    private static int readShort(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }
}
