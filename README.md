# NetPlayer

Peer to peer multiplayer for friends, without port forwarding.

NetPlayer is a **NeoForge** mod for Minecraft **1.21.11**. It adds a friend list to the game,
lets you open your single player world to selected friends, and connects the two clients through
ICE/UDP instead of asking anyone to configure a router.

## Features

- Friend list in the title screen, the pause menu and on a hotkey (`F9` by default)
- Friends are added with a self contained invite key, no account registration
- One click "Open to friends" for one friend, several friends or everyone
- Right top toast notifications when a friend comes online or starts playing
- Join requests are always confirmed by the host, then the guest leaves its current world
  automatically and connects to the host world
- All game traffic is encrypted end to end (X25519 key agreement + AES-GCM) and never
  passes through the signaling server

## How it works

| Layer | Implementation |
| --- | --- |
| Signaling | A tiny TCP/JSON rendezvous server (`server/`), only presence, friend requests and ICE candidates |
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
2. Put `netplayer-0.1.0.jar` into `mods/`.
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
  "signalingHost": "127.0.0.1",
  "signalingPort": 25566,
  "stunServers": ["stun.miwifi.com:3478", "stun.chat.bilibili.com:3478"],
  "iceDebug": false,
  "verifyPremium": true
}
```

Point `signalingHost` / `signalingPort` at your own signaling server if you run one.

`verifyPremium` (default `true`) makes the host check the guest against Mojang's session server
(`session/minecraft/join` + `hasJoined`), the same two calls an online-mode server does, so only
genuine accounts can join. It can be toggled per world in **Open to Friends**, and it needs access
to `sessionserver.mojang.com` on both sides. Development clients use an offline token, so keep it
off while testing in the IDE.

## Running the signaling server

```bash
./gradlew :server:build
java -jar server/build/libs/netplayer-server.jar --port 25566
```

or with Docker:

```bash
docker build -t netplayer-signal server
docker run -d -p 25566:25566 netplayer-signal
```

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
  identities stay name based (a real online-mode integrated server would break LAN play).
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
