package com.clevebitr.netplayer.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal signaling client with two transports:
 *
 * <ul>
 *   <li>{@link #connect(String, int, int)} dials the self hosted TCP server (line based JSON),</li>
 *   <li>{@link #connectUrl(String, int)} dials a WebSocket endpoint such as
 *       {@code wss://netplayer-signal.example.workers.dev/}, which is what the Cloudflare Workers
 *       deployment speaks.</li>
 * </ul>
 *
 * Either way one reader owns all incoming messages, so the call site never has to care about
 * callbacks or executors: messages arrive on {@link Listener}, sending is thread safe from any
 * game thread.
 */
public final class SignalClient implements AutoCloseable {
    public interface Listener {
        void onMessage(Msg msg);

        void onClosed(String reason);
    }

    /** How long a single WebSocket frame may take before the send is considered dead. */
    private static final long SEND_TIMEOUT_MS = 15_000L;

    private final Listener listener;

    /** TCP transport. */
    private Socket socket;
    private BufferedWriter out;
    private Thread reader;

    /** WebSocket transport. */
    private WebSocket webSocket;
    private BlockingQueue<String> outbox;
    private Thread sender;

    private volatile boolean closed;

    public SignalClient(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ transports

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

    /**
     * @param url {@code ws://host:port/} or {@code wss://host/}; the path is irrelevant to the server
     */
    public synchronized boolean connectUrl(String url, int timeoutMs) {
        closeQuietly();
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            WebSocket socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .buildAsync(URI.create(url), new FrameListener())
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            webSocket = socket;
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            outbox = queue;
            closed = false;
            sender = Thread.ofVirtual().name("netplayer-signal-send").start(() -> drain(socket, queue));
            return true;
        } catch (Exception e) {
            closeQuietly();
            return false;
        }
    }

    public boolean isConnected() {
        if (closed) {
            return false;
        }
        WebSocket ws = webSocket;
        if (ws != null) {
            return !ws.isInputClosed() && !ws.isOutputClosed();
        }
        Socket s = socket;
        return s != null && s.isConnected();
    }

    // ------------------------------------------------------------------ sending

    public synchronized void send(Msg msg) {
        WebSocket ws = webSocket;
        if (ws != null) {
            BlockingQueue<String> queue = outbox;
            if (queue != null) {
                queue.offer(msg.toLine());
            }
            return;
        }
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

    /** Serial sender: WebSocket does not allow two overlapping sendText calls. */
    private void drain(WebSocket ws, BlockingQueue<String> queue) {
        try {
            while (!closed) {
                String line = queue.take();
                if (line.isEmpty()) {
                    return;
                }
                ws.sendText(line, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
            // closing
        } catch (Exception e) {
            if (!closed) {
                listener.onClosed("send failed");
            }
            closeQuietly();
        }
    }

    private void dispatch(String payload) {
        for (String line : payload.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                listener.onMessage(Msg.parse(trimmed));
            } catch (Exception ignored) {
                // a broken frame must not kill the session
            }
        }
    }

    // ------------------------------------------------------------------ TCP reader

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

    // ------------------------------------------------------------------ WebSocket callbacks

    private final class FrameListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(Long.MAX_VALUE);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                dispatch(payload);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (!closed) {
                listener.onClosed(reason == null || reason.isBlank() ? "server closed the connection" : reason);
            }
            closeQuietly();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!closed) {
                listener.onClosed(error.getMessage() == null ? "connection lost" : error.getMessage());
            }
            closeQuietly();
        }
    }

    // ------------------------------------------------------------------ teardown

    private void closeQuietly() {
        closed = true;

        Socket s = socket;
        socket = null;
        out = null;

        WebSocket ws = webSocket;
        webSocket = null;
        BlockingQueue<String> queue = outbox;
        outbox = null;

        if (queue != null) {
            queue.offer("");
        }
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
        }

        Thread sending = sender;
        sender = null;
        if (sending != null && sending != Thread.currentThread()) {
            sending.interrupt();
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
