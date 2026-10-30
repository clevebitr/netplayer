/**
 * Worker entry point.
 *
 * `wss://<worker>.workers.dev/` is the signaling endpoint; it is a drop-in replacement for the TCP
 * server on port 25566 once the client is configured with `signalingUrl` instead of
 * `signalingHost` / `signalingPort`.
 *
 * Plain HTTP requests are answered with a small status document so the deployment can be checked
 * from a browser or curl.
 */
import { SignalRoom, type Env } from "./signal-room";

export { SignalRoom };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const upgrade = (request.headers.get("Upgrade") ?? "").toLowerCase();

    if (upgrade !== "websocket") {
      if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/status")) {
        const body = {
          ok: true,
          service: "netplayer-signal",
          protocol: 1,
          transport: "websocket",
          endpoint: `${url.protocol === "http:" ? "ws" : "wss"}://${url.host}/`,
        };
        return new Response(JSON.stringify(body, null, 2), {
          headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
        });
      }
      return new Response("netplayer signaling: connect with a websocket\n", {
        status: 426,
        headers: { "content-type": "text/plain; charset=utf-8", Upgrade: "websocket" },
      });
    }

    // Single global shard: the friend graph of a hobby deployment is tiny and keeping every
    // session in one object keeps the port of SignalServer.java faithful (one big lock).
    const stub = env.SIGNAL.get(env.SIGNAL.idFromName("global"));
    return stub.fetch(request);
  },
};
