package com.clevebitr.netplayer.net;

import com.clevebitr.netplayer.core.Crypto;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Carries exactly one Minecraft TCP connection over an encrypted ICE/UDP link. The guest side
 * listens on loopback so the vanilla client can connect to it like any LAN world.
 */
public final class Tunnel implements AutoCloseable {
    private final IceLink link;
    private final SecretKeySpec key;
    private final ReliableStream stream;
    private final boolean host;
    private final int lanPort;
    private final ServerSocket local;
    private volatile boolean closed;
    private volatile boolean synHandled;

    private Tunnel(IceLink link, SecretKeySpec key, boolean host, int lanPort, ServerSocket local) {
        this.link = link;
        this.key = key;
        this.host = host;
        this.lanPort = lanPort;
        this.local = local;
        this.stream = new ReliableStream(this::emit);
    }

    private static final java.net.InetAddress LOOPBACK = ipv4Loopback();

    private static java.net.InetAddress ipv4Loopback() {
        try {
            return java.net.InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            return java.net.InetAddress.getLoopbackAddress();
        }
    }

    public static Tunnel host(IceLink link, SecretKeySpec key, int lanPort) {
        return new Tunnel(link, key, true, lanPort, null);
    }

    public static Tunnel guest(IceLink link, SecretKeySpec key) throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(LOOPBACK, 0));
        return new Tunnel(link, key, false, 0, listener);
    }

    public int localPort() {
        return local == null ? 0 : local.getLocalPort();
    }

    public void start() {
        Thread.ofVirtual().name("netplayer-tunnel-rx").start(this::receiveLoop);
        Thread.ofVirtual().name("netplayer-tunnel-tick").start(this::tickLoop);
        if (local != null) {
            Thread.ofVirtual().name("netplayer-tunnel-accept").start(this::acceptLoop);
        }
    }

    private void acceptLoop() {
        try {
            Socket socket = local.accept();
            socket.setTcpNoDelay(true);
            stream.start();
            pump(socket);
        } catch (Exception ignored) {
            close();
        }
    }

    private void receiveLoop() {
        while (!closed) {
            byte[] packet = link.receive();
            if (packet == null) {
                break;
            }
            byte[] plain = Crypto.open(key, packet, 0, packet.length);
            if (plain == null || plain.length < 11) {
                continue;
            }
            if (!host && plain[0] == ReliableStream.SYN) {
                continue;
            }
            if (host && plain[0] == ReliableStream.SYN) {
                openHostSide();
            }
            stream.onPacket(plain);
        }
        close();
    }

    private synchronized void openHostSide() {
        if (synHandled || closed) {
            return;
        }
        synHandled = true;
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(LOOPBACK, lanPort), 5000);
            pump(socket);
        } catch (Exception e) {
            close();
        }
    }

    private void pump(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        Thread.ofVirtual().name("netplayer-tunnel-up").start(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while (!closed && (read = in.read(buffer)) > 0) {
                    stream.write(buffer, 0, read);
                }
            } catch (Exception ignored) {
            } finally {
                stream.finish();
            }
        });
        Thread.ofVirtual().name("netplayer-tunnel-down").start(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while (!closed && (read = stream.read(buffer, 0, buffer.length)) >= 0) {
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public int rttMillis() {
        return stream.rttMillis();
    }

    private void tickLoop() {
        long lastPing = 0L;
        while (!closed) {
            stream.tick();
            long now = System.currentTimeMillis();
            if (now - lastPing > 2000L) {
                lastPing = now;
                stream.ping();
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void emit(byte[] plain) {
        if (!closed) {
            link.send(Crypto.seal(key, plain));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stream.close();
        try {
            if (local != null) {
                local.close();
            }
        } catch (Exception ignored) {
        }
        link.close();
    }
}
