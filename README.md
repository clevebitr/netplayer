# NetPlayer

Peer to peer multiplayer for friends, without port forwarding.

NetPlayer is a **NeoForge** mod for Minecraft **1.21.11**. It adds a friend list to the game,
lets you open your single player world to selected friends, and connects the two clients through
ICE/UDP instead of asking anyone to configure a router.

## Features

- Friend list in the title screen, the pause menu and on a hotkey (`F9` by default)
- Friends are added with a self contained invite key, no account registration
- One click "Open to friends" for one friend, several friends or everyone
  (only offered while you are in your own world, never someone else's)
- Several friends can join the same world at once, each over its own tunnel; the host
  sees who is connected with the live link quality and can drop one without closing the world
- Right top toast notifications when a friend comes online or starts playing
- Join requests are always confirmed by the host, then the guest leaves its current world
  automatically and connects to the host world
- All game traffic is encrypted end to end (X25519 key agreement + AES-GCM) and never
  passes through the signaling server

## How it works

| Layer | Implementation |
| --- | --- |
| Signaling | A tiny JSON rendezvous server, only presence, friend requests and ICE candidates. Two interchangeable builds: `server/` (Java, TCP) and `signal-worker/` (Cloudflare Workers + Durable Objects, WebSocket, free) |
| NAT traversal | [ice4j](https://github.com/jitsi/ice4j): host candidates plus STUN discovered server reflexive candidates |
| Transport | Custom reliable ordered byte stream over UDP (`ReliableStream`), encrypted per datagram |
| Game | The host publishes its integrated server to LAN, the guest gets a loopback TCP port on its side |

```
guest  Minecraft --TCP--> 127.0.0.1:auto  ->  Tunnel (reliable UDP + AES-GCM)  ->  host 127.0.0.1:LAN port
```

The signaling server only sees friend lists metadata, presence and ICE candidates. It can be self
hosted, and it is not on the data path.

Invite keys are plain text tokens:

- `npfriend1....` long term friend key, contains the sender public identity, can be reused
- `npjoin1....` one time room key, points at a world that is currently open (single use by design)

## Security model

Who is allowed into a world is decided by **two independent checks**, both of them on the host:

1. **Identity.** An id is the first 16 hex chars of SHA-256 over the Ed25519 public key
   (`Profile.selfCertified()`). Both signaling servers verify `id == hash(ed)` on `hello`, so a
   client cannot announce someone else's id with a key it owns. The client additionally refuses any
   profile whose id does not hash from its key, so a compromised or impersonated relay still cannot
   pretend to be a friend.
2. **Authorization.** The guest is either on the host's allow list or presents the room token. The
   signaling server checks this, and the host checks it *again* against its own room state before
   accepting (`join_req` carries the token the guest used, so the host can re-derive the decision).

Signatures are always verified against the key that was stored for that peer when the handshake
started, never against the key the message itself carries:

| Message | Signed basis | Verified with |
| --- | --- | --- |
| `hello` | `hello:<id>:<ts>` | the profile being announced (must hash to `id`) |
| `ice` | `ice2:<room>:<from>:<to>:<ufrag>:<pwd>:<epk>` | the stored profile of `<from>`, which must be the peer we are connecting to |
| premium proof | `premium1:<name>:<uuid>:<serverId>` | the tunnel identity of the guest |
| `npair1` / `npans1` | canonical JSON of the whole payload minus `sig` | the host/guest profile inside the payload |

The room id, the room token and the allow list never leave the signaling server: presence only
answers "is it open for me". The host's room token, the guest's premium proof and the ICE
descriptions are the only secrets that cross the relay.

Things this model does **not** protect against: a malicious host showing you a fake world, and an
attacker who already owns your `identity.json` (the private keys are stored in
`<game dir>/netplayer/identity.json`, owner-only permissions where the file system supports it).

## Requirements

| Item | Version |
| --- | --- |
| Minecraft | 1.21.11 |
| NeoForge | 21.11.x (built against 21.11.48) |
| Java | 21+ |
| Modes | client side only, no server mod needed |

Both the host and the guest need the mod. A dedicated server does **not**.

## Installation

1. Install NeoForge 21.11.x for Minecraft 1.21.11.
2. Put `netplayer-1.0.2.jar` into `mods/`.
3. Launch the game.

## Usage

1. Open **Friends** from the title screen (or `F9` in game) and press **Add Friend**.
2. Send your own invite key to your friend (button **Copy My Key**), have them paste it.
3. Enter a single player world and press `Esc` -> **Open to Friends**.
4. Choose one friend, several friends or everyone, then **Open World**.
5. The friend sees the world in the list and presses the row; you get a toast and accept.
6. The guest is disconnected from its current world and dropped into yours.

## Configuration

`<game dir>/netplayer/config.json` is created on first launch:

```json
{
  "signalingUrl": "",
  "signalingHost": "127.0.0.1",
  "signalingPort": 25566,
  "stunServers": ["stun.miwifi.com:3478", "stun.chat.bilibili.com:3478"],
  "turnServers": [],
  "turnUser": "",
  "turnPass": "",
  "iceDebug": false,
  "verifyPremium": true
}
```

`signalingUrl` takes precedence when it is set, e.g.
`"signalingUrl": "wss://netplayer-signal.example.workers.dev/"`. Leave it empty to use
`signalingHost` / `signalingPort` (the self hosted TCP server) instead.

`turnServers` enables the relay fallback (see below); `turnUser` / `turnPass` are the long term
TURN credentials and can stay empty for an open relay. Everything except the proxy host and the TURN
credentials is editable in game under **Friends → Settings**.

`proxyHost` / `proxyPort` (default empty / 7890) route the WebSocket signaling and the Mojang
session calls through an HTTP proxy. `*.workers.dev` is not reachable from every network
(mainland China in particular), so either point the mod at a proxy or bind a custom domain to the
Worker. All of this is editable in game under **Friends → Settings** (with a *Test connection* button).

`verifyPremium` (default `true`) makes the host check the guest against Mojang's session server
(`session/minecraft/join` + `hasJoined`), the same two calls an online-mode server does, so only
genuine accounts can join. It can be toggled per world in **Open to Friends**, and it needs access
to `sessionserver.mojang.com` on both sides. Development clients use an offline token, so keep it
off while testing in the IDE.

## Running the signaling server

Two builds of the same protocol. Clients pick one with `signalingUrl` (WebSocket) or
`signalingHost` / `signalingPort` (TCP).

### Cloudflare Workers + Durable Objects (free, no card, no server to babysit)

```bash
cd signal-worker
npm install
npx wrangler login
npx wrangler deploy
# -> https://netplayer-signal.<you>.workers.dev  ("signalingUrl": "wss://.../")
```

Free plan friendly: WebSocket in, Durable Object state, hibernating while idle. Details, local dev
and the protocol test live in [signal-worker/README.md](signal-worker/README.md).

### Self hosted TCP server

```bash
./gradlew :server:build
java -jar server/build/libs/netplayer-server.jar --port 25566
```

or with Docker:

```bash
docker build -t netplayer-signal server
docker run -d -p 25566:25566 netplayer-signal
```

Any host that gives you an open TCP port works; the Cloudflare Worker above exists because
Cloudflare itself does not expose inbound raw TCP on the free plan.

## Building the mod

```bash
./gradlew build
# output: mod/build/libs/netplayer.jar
```

Development client run (first launch downloads Minecraft and assets):

```bash
./gradlew :mod:runClient
```

If your network blocks `maven.neoforged.net`, add proxy settings to `~/.gradle/gradle.properties`:

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

## Android and other non standard launchers

The mod runs on Android through launchers such as Zalith/Pojav (a patched JDK 21), and 1.0.2 fixes the
one thing that broke there: ice4j reads its own defaults (`reference.conf`) through the *thread
context class loader*. A launcher whose context loader cannot see the mod jar made `new Agent()`
throw `Key 'ice4j.consent-freshness.interval' not found in source 'typesafe config'`, which looked
like a dead network (`ICE failed before the agent started`). The constructor now pins the context
loader to its own class loader for the whole ICE setup. If you see that exception on 1.0.0/1.0.1,
update the jar; nothing else has to change.

## When it does not connect

**Friends → Settings → Network self test** (网络自检) harvests the addresses this machine would
advertise and runs one ICE handshake inside the process. The result line tells you which side of the
problem you are on:

| Self test | Meaning |
| --- | --- |
| `IPv6 2 / IPv4 1 (4 cands), ICE ipv6 110ms` | the mod and this machine are fine, the path to the peer is the problem |
| `candidates 0` | no usable interface (only link-local / Teredo / loopback), fix networking first |
| `local ICE failed` | a local firewall is blocking even loopback UDP, or the ice4j harvest is broken |

Both sides then check, in this order:

1. `logs/latest.log`: `ICE start` on both ends? If only one side logs it, the join itself never
   happened (signaling: wrong `signalingUrl`, an 0.1.x server, or a friend that is not on the allow
   list). `ICE failed: state=... local=[...] remote=[...]` prints both candidate lists; if `local`
   and `remote` do not contain each other's addresses, the signaling path mangled the exchange.
2. Windows firewall: inbound UDP for `javaw.exe` / `java.exe` must be allowed. Quick test:
   `netsh advfirewall set allprofiles state off`, retry, then `netsh advfirewall set allprofiles state on`.
3. `ping -6 <peer global address>` (ICMPv6 may be filtered even when UDP works, so a failure is a
   hint, not proof).
4. Router: many CPEs ship with an IPv6 firewall that drops unsolicited inbound. NAT traversal needs
   at least one side to accept an inbound check, so turn it off for a test or use TURN.
5. A TURN relay always works when both peers can reach it; if TURN connects and ICE does not, the
   path (not the mod) is the problem.

`"iceDebug": true` in `config.json` raises ice4j to INFO, which logs every connectivity check.

## TURN relay (symmetric NAT / CGNAT)

ICE only works when at least one side has a reachable UDP path. Two symmetric NATs (or two carrier
grade NATs, e.g. two phone hotspots) have none, and before 0.2 the mod just failed. Now it falls back
to a TURN relay:

1. Both sides allocate a relayed address on the configured TURN server and publish it over the
   signaling channel, signed with their identity (`relay1:<room>:<from>:<to>:<addr>`).
2. If ICE fails, each side opens a permission and binds a channel to the peer's relayed address and
   the tunnel continues over the relay. The relay only sees AES-GCM datagrams.
3. If no relay answers either, the old clear failure message is shown instead of hanging.

The allocation runs in parallel with ICE, so the direct path is still preferred and the relay only
costs quota when it is actually needed.

**Which server to use.** Public "free" relays exist but are shared, rate limited, and frequently
blocked (measured from a mainland China connection: `openrelay.metered.ca` did not resolve,
`a.relay.metered.ca` answered the TURN challenge but rejected the public `openrelayproject`
credentials, `eu-0.turn.peerjs.com` did not resolve, `free.expressturn.com` never answered). Use the
**Test relay** button in Settings to see what works from your network. Two options that actually
hold up:

- **Self hosted coturn** on any VPS with a UDP port:

  ```bash
  docker run -d --network host coturn/coturn \
    -n --lt-cred-mech --user=netplayer:secret --realm=netplayer
  # config.json: "turnServers": ["turn:your.vps:3478"], "turnUser": "netplayer", "turnPass": "secret"
  ```

- **Cloudflare Realtime TURN** (1 TB/month free, no server to babysit): create a TURN key in the
  dashboard, mint short lived credentials from its API, and put the resulting
  `turn:` / `turns:` URL plus username/credential into the config.

Share the credentials only with your friends: a TURN server that accepts anyone will relay anyone.

## Limitations

- Premium check only gates the tunnel; the host world still runs in offline mode, so in-game
  identities stay name based (a real online-mode integrated server would break LAN play). The check
  binds the Mojang account to the NetPlayer identity that owns the tunnel (proof is signed by that
  identity and the first account seen behind a friend id is pinned).
- Client and signaling server must be the same generation: the room id is no longer published in
  presence and invitation codes carry a signed `v: 2` payload. An 0.1.x client cannot talk to
  a 1.0 server.
- Symmetric NAT or CGNAT pairs need a TURN relay; without one the mod tells you instead of
  silently hanging. IPv6 (both peers with a global address) or an open UDP port works directly.
- The TURN client implements the UDP transport with long term credentials only: no `turns:` (TLS)
  and no TURN over TCP, so a network that blocks all UDP still cannot be helped.
- One Minecraft connection per tunnel; the guest side uses a random loopback port. A host
  accepts up to 8 guests at the same time.
- The transport has a fixed send window and no congestion control, which is fine for Minecraft
  traffic but not a general purpose file transfer.

## Layout

```
core/    protocol, identity, keys, signaling client          (pure JVM)
mod/     NeoForge mod: UI, hosting, joining, ICE + tunnel
server/  signaling server
```

## License

LGPL-3.0-only. Author: clevebitr.
