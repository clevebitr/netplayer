package com.clevebitr.netplayer.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live check of the WebSocket transport against a running signaling server.
 *
 * <pre>
 *   npx wrangler dev --port 8787                       # in signal-worker/
 *   ./gradlew :core:test -Dnetplayer.signal.url=ws://127.0.0.1:8787/
 * </pre>
 *
 * Skipped when the system property is absent, so the normal test run stays offline.
 */
class SignalClientWsTest {
    @Test
    void helloHandshake() throws Exception {
        String url = System.getProperty("netplayer.signal.url", "");
        assumeTrue(url != null && !url.isBlank(), "netplayer.signal.url not set");

        BlockingQueue<Msg> inbox = new LinkedBlockingQueue<>();
        SignalClient client = new SignalClient(new SignalClient.Listener() {
            @Override
            public void onMessage(Msg msg) {
                inbox.add(msg);
            }

            @Override
            public void onClosed(String reason) {
            }
        });

        try {
            assertTrue(client.connectUrl(url, 5000), "connect to " + url);
            assertTrue(client.isConnected());

            Identity identity = Identity.create("WsTest");
            long ts = System.currentTimeMillis();
            String payload = "hello:" + identity.profile.id + ":" + ts;
            client.send(Msg.of("hello")
                    .put("profile", identity.profile.toJson())
                    .put("ts", ts)
                    .put("sig", Bytes.b64(identity.sign(payload.getBytes(StandardCharsets.UTF_8)))));

            Msg reply = inbox.poll(10, TimeUnit.SECONDS);
            assertNotNull(reply, "no reply from " + url);
            assertEquals("hello_ok", reply.type);
            assertEquals(identity.profile.id, reply.str("id"));

            // A signed hello must be enough to keep the session usable afterwards.
            client.send(Msg.of("presence").put("state", "playing").put("detail", "World"));
            Thread.sleep(300);
            assertTrue(client.isConnected(), "connection dropped after presence");
        } finally {
            client.close();
        }
    }
}
