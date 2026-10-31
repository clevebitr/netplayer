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
2. Put `netplayer-0.2.0.jar` into `mods/`.
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
  "iceDebug": false,
  "verifyPremium": true
}
```

`signalingUrl` takes precedence when it is set, e.g.
`"signalingUrl": "wss://netplayer-signal.example.workers.dev/"`. Leave it empty to use
`signalingHost` / `signalingPort` (the self hosted TCP server) instead.

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

## Limitations

- Premium check only gates the tunnel; the host world still runs in offline mode, so in-game
  identities stay name based (a real online-mode integrated server would break LAN play). The check
  binds the Mojang account to the NetPlayer identity that owns the tunnel (proof is signed by that
  identity and the first account seen behind a friend id is pinned).
- Client and signaling server must be the same generation: the room id is no longer published in
  presence and invitation codes carry a signed `v: 2` payload. An 0.1.x client cannot talk to
  an 0.2.x server.
- No TURN relay in 0.1: symmetric NAT or carrier grade NAT pairs will fail, the mod tells you
  instead of silently hanging. IPv6 (both peers with a global address) or an open UDP port works.
- One Minecraft connection per tunnel; the guest side uses a random loopback port.
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
