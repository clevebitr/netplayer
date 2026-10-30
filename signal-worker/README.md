# netplayer-signal (Cloudflare Workers + Durable Objects)

The same rendezvous server as `../server` (Java, plain TCP), re-implemented for the Cloudflare
free plan and speaking WebSocket instead of raw TCP.

Why the transport changed: Workers can create **outbound** TCP connections, but inbound public TCP
needs Spectrum (paid). Inbound WebSocket is free, so the signaling protocol moved to
`wss://…/` — same JSON envelope, same message types, only the framing changed.

Nothing about the tunnel changes: ICE candidates are relayed here, game traffic never touches it.

## Deploy (free plan, no card)

```bash
cd signal-worker
npm install
npx wrangler login          # browser, free account is enough
npx wrangler deploy
```

Wrangler prints the endpoint, e.g. `https://netplayer-signal.<you>.workers.dev`. Then in
`<game dir>/netplayer/config.json` on every client:

```json
{
  "signalingUrl": "wss://netplayer-signal.<you>.workers.dev/",
  "signalingHost": "127.0.0.1",
  "signalingPort": 25566
}
```

`signalingUrl` wins when it is non-empty; clear it to fall back to the self hosted TCP server.

Check the deployment from any browser or curl — it answers with a small status document:

```bash
curl https://netplayer-signal.<you>.workers.dev/
# {"ok":true,"service":"netplayer-signal","protocol":1,"transport":"websocket",...}
```

## Local development

```bash
npm install
npm run dev                 # http://127.0.0.1:8787, speaks ws://127.0.0.1:8787/
npm run smoke               # end to end protocol test against the dev server
npm run typecheck
```

`npm run smoke` walks the whole TESTING.md section 1 flow with two fake identities: signature
check (including a forged hello being rejected), presence fan out, friend request (online and
queued-while-offline), room open, join request/answer, ICE relay, whitelist and room token rules.

The Java side can be checked against the same dev server:

```bash
./gradlew :core:test -Pnetplayer.signal.url=ws://127.0.0.1:8787/
```

## How it maps to the Java server

| Java (`server/…/SignalServer.java`) | Here |
| --- | --- |
| one `ServerSocket`, `Session` per socket | one Durable Object, one accepted WebSocket per session |
| `synchronized` methods = one big lock | one object = one event loop, handlers are `async` and serialized |
| session fields (`id`, `state`, `detail`, `roomId`) | `ws.serializeAttachment()`, survives hibernation |
| `watchers`, `rooms`, `pendingAdds`, ticket counter | one `state` key in DO storage, write through on change |
| `Crypto.verify(profile, "hello:id:ts", sig)` | `crypto.subtle` Ed25519 over the SPKI key from the profile |

The state lives in memory-like storage rather than a database, exactly like the Java server: rooms
and presence are ephemeral by design, and friend relationships stay on the clients.

### Intentional differences

1. **Offline presence is actually broadcast.** In the Java version `disconnect()` removed
   `watchers[myId]` *before* calling `subscribersOf(myId)`, so nobody was ever told that a friend
   went offline. Both implementations now capture subscribers first and keep the subscription
   table, so `hello` can push the new presence and `disconnect` can push `offline`.
2. **`ping` → `pong`.** Extra message type, ignored by unaware clients; useful when checking a
   deployment by hand.
3. WebSocket frames are one message each. The reader still splits on `\n`, so batching keeps
   working in both directions.

## Free plan budget

Durable Objects on the Workers Free plan must use the SQLite backend (`new_sqlite_classes` in
`wrangler.jsonc`). Current free allowances are roughly:

| Resource | Free allowance | What this server does |
| --- | --- | --- |
| Worker requests | 100k / day | 1 request per connect, frames are not requests |
| DO requests | 100k / day | 1 per WebSocket frame |
| DO duration | 13k GB-s / day | hibernating while idle, so only active seconds count |
| SQL rows | generous | one `state` blob, a few writes per event |

A handful of friends online sends a few hundred frames a day; the limits are far away. Because
sockets are accepted with the Hibernation API, an idle server costs nothing and stays connected.

## Known limits

- One global shard (`idFromName("global")`). Thousands of concurrent players would need sharding by
  first byte of the id; a friend group never will.
- Pending friend requests expire after 24 h (same as Java) and are dropped if the DO storage is
  wiped. Adding real persistence is a `ctx.storage.put` away if it ever matters.
- No TURN. This only gets the two clients introduced; if both sides are behind symmetric NAT the
  tunnel still fails. TURN is the next milestone.
