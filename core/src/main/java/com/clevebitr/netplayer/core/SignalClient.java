package com.clevebitr.netplayer.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal line based signaling client. One blocking reader lives on its own virtual thread, so the
 * call site never has to care about callbacks or executors.
 */
public final class SignalClient implements AutoCloseable {
    public interface Listener {
        void onMessage(Msg msg);

        void onClosed(String reason);
    }

    private final Listener listener;
    private Socket socket;
    private BufferedWriter out;
    private Thread reader;
    private volatile boolean closed;

    public SignalClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean connect(String host, int port, int timeoutMs) {
        closeQuietly();
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            socket = s;
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            closed = false;
            reader = Thread.ofVirtual().name("netplayer-signal").start(this::readLoop);
            return true;
        } catch (Exception e) {
            closeQuietly();
            return false;
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !closed;
    }

    public synchronized void send(Msg msg) {
        if (out == null) {
            return;
        }
        try {
            out.write(msg.toLine());
            out.write('\n');
            out.flush();
        } catch (Exception e) {
            closeQuietly();
            listener.onClosed("send failed");
        }
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                listener.onMessage(Msg.parse(line));
            }
            listener.onClosed("server closed the connection");
        } catch (Exception e) {
            if (!closed) {
                listener.onClosed(e.getMessage() == null ? "connection lost" : e.getMessage());
            }
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        closed = true;
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
            reader = null;
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
