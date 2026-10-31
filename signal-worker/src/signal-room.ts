/**
 * Durable Object port of server/src/main/java/com/clevebitr/netplayer/server/SignalServer.java.
 *
 * Same protocol, same semantics, but the transport is a WebSocket instead of a raw TCP socket, so
 * it runs on the Cloudflare Workers free plan (Workers cannot accept inbound TCP).
 *
 * Design notes
 * - One object (idFromName("global")) holds every session, exactly like the `synchronized` Java
 *   server holds every Session. Friend counts here are tiny; sharding would be premature.
 * - Sockets are accepted through the Hibernation API, so an idle object stops billing duration
 *   while the WebSocket stays connected. Per connection state lives in the WebSocket attachment,
 *   cross connection state (watchers / rooms / pending friend requests) lives in DO storage.
 */
import { Msg, JsonObject, bool, encode, msg, num, obj, parseLine, str, strings } from "./protocol";
import { profileIdMatches, verifyEd25519 } from "./verify";

const STATE_KEY = "state";
const PENDING_TTL_MS = 24 * 60 * 60 * 1000;
const HELLO_SKEW_MS = 120_000;
/** Guard rails against a single connection eating the object. */
const MAX_TEXT = 64 * 1024;
const MAX_PENDING_ADDS = 64;
const MAX_WATCH_IDS = 512;

export interface Env {
  SIGNAL: DurableObjectNamespace;
}

interface Room {
  id: string;
  host: string;
  world: string;
  token: string;
  allow: string[];
  premium: boolean;
  opened: number;
}

interface Pending {
  at: number;
  profile: JsonObject;
}

interface Persisted {
  /** targetId -> ids of the sessions that want that target's presence */
  watchers: Record<string, string[]>;
  rooms: Record<string, Room>;
  /** targetId -> friend requests queued while the target was offline */
  pending: Record<string, Pending[]>;
  /** roomId -> guests whose join_req was forwarded and is still unanswered */
  waiting: Record<string, string[]>;
  tickets: number;
}

interface Att {
  id: string | null;
  profile: JsonObject | null;
  state: string;
  detail: string;
  roomId: string | null;
}

function blank(): Att {
  return { id: null, profile: null, state: "menu", detail: "", roomId: null };
}

function emptyState(): Persisted {
  return { watchers: {}, rooms: {}, pending: {}, waiting: {}, tickets: 0 };
}

export class SignalRoom implements DurableObject {
  private data: Persisted | null = null;

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    if ((request.headers.get("Upgrade") ?? "").toLowerCase() !== "websocket") {
      return this.status();
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(blank());
    return new Response(null, { status: 101, webSocket: client });
  }

  private status(): Response {
    const sessions = this.sessions();
    const body = {
      ok: true,
      service: "netplayer-signal",
      protocol: 1,
      online: sessions.filter((s) => s.att.id).length,
      connections: sessions.length,
    };
    return new Response(JSON.stringify(body), {
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    });
  }

  // ------------------------------------------------------------------ websocket callbacks

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    if (text.length > MAX_TEXT) {
      return;
    }
    for (const line of text.split("\n")) {
      const trimmed = line.trim();
      if (!trimmed) {
        continue;
      }
      await this.handle(ws, parseLine(trimmed));
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    await this.disconnect(ws);
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, reason || "");
    } catch {
      // already gone
    }
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    await this.disconnect(ws);
  }

  // ------------------------------------------------------------------ dispatch

  private async handle(ws: WebSocket, m: Msg): Promise<void> {
    switch (m.t) {
      case "hello":
        await this.hello(ws, m);
        break;
      case "watch":
        await this.watch(ws, m);
        break;
      case "presence":
        await this.presence(ws, m);
        break;
      case "add":
        await this.addFriend(ws, m);
        break;
      case "room_open":
        await this.roomOpen(ws, m);
        break;
      case "room_close":
        await this.roomClose(ws, m);
        break;
      case "join_req":
        await this.joinRequest(ws, m);
        break;
      case "join_ans":
        await this.joinAnswer(ws, m);
        break;
      case "ice":
      case "relay":
      case "leave":
      case "kick":
        await this.relayIce(ws, m);
        break;
      case "ping":
        this.send(ws, msg("pong", { ts: Date.now() }));
        break;
      default:
        break;
    }
  }

  private async hello(ws: WebSocket, m: Msg): Promise<void> {
    const profile = obj(m.d, "profile");
    const id = str(profile, "id");
    const ts = num(m.d, "ts");
    if (!id || Math.abs(Date.now() - ts) > HELLO_SKEW_MS) {
      this.reject(ws, "stale hello");
      return;
    }
    // The id has to be the hash of the signing key, otherwise a client could announce any id it
    // likes (and kick that user off the server) with a key it owns.
    if (!(await profileIdMatches(str(profile, "ed"), id))) {
      this.reject(ws, "id does not match key");
      return;
    }
    if (!(await verifyEd25519(str(profile, "ed"), `hello:${id}:${ts}`, str(m.d, "sig")))) {
      this.reject(ws, "bad signature");
      return;
    }

    const previous = this.findSession(id);
    if (previous && previous.ws !== ws) {
      try {
        previous.ws.close(4000, "replaced by a new session");
      } catch {
        // already gone
      }
    }

    const att = this.att(ws);
    att.id = id;
    att.profile = profile;
    ws.serializeAttachment(att);

    this.send(ws, msg("hello_ok", { id }));

    const data = await this.state();
    const queued = data.pending[id] ?? [];
    if (queued.length) {
      delete data.pending[id];
      await this.save();
    }
    const now = Date.now();
    for (const p of queued) {
      if (now - p.at < PENDING_TTL_MS) {
        this.send(ws, msg("add", { from: p.profile }));
      }
    }
    for (const watcher of this.subscribersOf(id)) {
      this.sendToId(watcher, this.presenceOf(id, watcher));
    }
  }

  private async watch(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const data = await this.state();
    const ids = strings(m.d, "ids").slice(0, MAX_WATCH_IDS);
    for (const [target, list] of Object.entries(data.watchers)) {
      const next = list.filter((w) => w !== att.id);
      if (next.length) {
        data.watchers[target] = next;
      } else {
        delete data.watchers[target];
      }
    }
    for (const id of ids) {
      const list = data.watchers[id] ?? [];
      if (!list.includes(att.id)) {
        list.push(att.id);
      }
      data.watchers[id] = list;
      if (this.findSession(id)) {
        this.send(ws, this.presenceOf(id, att.id));
      }
    }
    await this.save();
  }

  private async presence(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    await this.state();
    att.state = str(m.d, "state") || "menu";
    att.detail = str(m.d, "detail");
    ws.serializeAttachment(att);
    this.broadcastPresence(att.id);
  }

  private async addFriend(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const to = str(m.d, "to");
    if (!to || to === att.id) {
      return;
    }
    const target = this.findWs(to);
    if (target) {
      this.send(target, msg("add", { from: att.profile }));
      return;
    }
    const data = await this.state();
    const list = data.pending[to] ?? [];
    if (list.length >= MAX_PENDING_ADDS) {
      return;
    }
    list.push({ at: Date.now(), profile: att.profile ?? {} });
    data.pending[to] = list;
    await this.save();
  }

  private async roomOpen(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const room = obj(m.d, "room");
    const id = str(room, "id");
    if (!id) {
      return;
    }
    const rawAllow = room["allow"];
    const allow = Array.isArray(rawAllow) ? rawAllow.map((e) => String(e)) : ["*"];

    const data = await this.state();
    // A host has exactly one world open: drop whatever it published before.
    for (const [previousId, previous] of Object.entries(data.rooms)) {
      if (previous.host === att.id && previousId !== id) {
        delete data.rooms[previousId];
        delete data.waiting[previousId];
      }
    }
    data.rooms[id] = {
      id,
      host: att.id,
      world: str(room, "world"),
      token: str(room, "token"),
      allow,
      premium: bool(room, "premium"),
      opened: Date.now(),
    };
    await this.save();

    att.roomId = id;
    ws.serializeAttachment(att);
    this.broadcastPresence(att.id);
  }

  private async roomClose(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    const data = await this.state();
    const room = data.rooms[str(m.d, "room")];
    // Only the host may close its own room; otherwise anyone could kick a stranger's guests.
    if (!room || !att.id || room.host !== att.id) {
      return;
    }
    delete data.rooms[room.id];
    delete data.waiting[room.id];
    await this.save();

    if (att.roomId === room.id) {
      att.roomId = null;
      ws.serializeAttachment(att);
    }
    this.broadcastPresence(room.host);
    this.sendToId(room.host, msg("room_closed", { room: room.id }));
  }

  private async joinRequest(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const data = await this.state();
    // The client no longer learns room ids from presence: it names the host and the server
    // resolves the currently open room. A room key (npjoin1) still arrives with an explicit id.
    const explicit = str(m.d, "room");
    const room = explicit
      ? data.rooms[explicit]
      : Object.values(data.rooms).find((r) => r.host === str(m.d, "host"));
    if (!room) {
      this.send(ws, msg("join_ans", { room: explicit, accept: false, reason: "room_closed" }));
      return;
    }
    const allowed = room.allow.includes("*") || room.allow.includes(att.id);
    const token = str(m.d, "token");
    const viaToken = !allowed && !!token && token === room.token;
    if (!allowed && !viaToken) {
      this.send(ws, msg("join_ans", { room: room.id, accept: false, reason: "not_allowed" }));
      return;
    }
    const host = this.findWs(room.host);
    if (!host) {
      this.send(ws, msg("join_ans", { room: room.id, accept: false, reason: "host_offline" }));
      return;
    }
    const waiting = data.waiting[room.id] ?? [];
    if (!waiting.includes(att.id)) {
      waiting.push(att.id);
    }
    data.waiting[room.id] = waiting;
    data.tickets += 1;
    await this.save();
    this.send(
      host,
      msg("join_req", {
        room: room.id,
        world: room.world,
        ticket: data.tickets,
        guest: att.profile,
        token: viaToken ? token : "",
        mc: obj(m.d, "mc"),
      }),
    );
  }

  private async joinAnswer(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const data = await this.state();
    const room = data.rooms[str(m.d, "room")];
    // Only the host of that room can answer for it, and only to a guest that actually asked.
    // Without this any session could accept on the host's behalf and receive the room token,
    // which in turn bypasses the allow list.
    if (!room || room.host !== att.id) {
      return;
    }
    const to = str(m.d, "to");
    const waiting = data.waiting[room.id] ?? [];
    if (!waiting.includes(to)) {
      return;
    }
    data.waiting[room.id] = waiting.filter((g) => g !== to);
    await this.save();

    const guest = this.findWs(to);
    if (!guest) {
      return;
    }
    const accept = bool(m.d, "accept");
    const d: JsonObject = { room: room.id, accept, reason: accept ? "" : str(m.d, "reason") };
    if (accept) {
      d.host = att.profile;
      d.world = room.world;
      d.token = room.token;
    }
    this.send(guest, msg("join_ans", d));
  }

  private async relayIce(ws: WebSocket, m: Msg): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const target = this.findWs(str(m.d, "to"));
    if (!target) {
      return;
    }
    const d: JsonObject = { ...m.d, from: att.profile };
    delete d["to"];
    this.send(target, { t: m.t, d });
  }

  private async disconnect(ws: WebSocket): Promise<void> {
    const att = this.att(ws);
    if (!att.id) {
      return;
    }
    const current = this.findSession(att.id);
    if (!current || current.ws !== ws) {
      // A newer session already replaced this one.
      return;
    }
    const id = att.id;
    // Watchers of this id are subscriptions to the friend, not to the connection: they are kept
    // so the next hello can push the new presence to them. Only our own subscriptions are dropped.
    const subscribers = this.subscribersOf(id);

    const data = await this.state();
    for (const [target, list] of Object.entries(data.watchers)) {
      const next = list.filter((w) => w !== id);
      if (next.length) {
        data.watchers[target] = next;
      } else {
        delete data.watchers[target];
      }
    }
    const roomId = att.roomId;
    if (roomId && data.rooms[roomId] && data.rooms[roomId].host === id) {
      delete data.rooms[roomId];
      delete data.waiting[roomId];
    }
    await this.save();

    att.roomId = null;
    for (const watcher of subscribers) {
      this.sendToId(watcher, msg("presence", { id, state: "offline", detail: "" }));
    }
  }

  // ------------------------------------------------------------------ state helpers

  private async state(): Promise<Persisted> {
    if (!this.data) {
      const stored = await this.ctx.storage.get<Persisted>(STATE_KEY);
      this.data = stored ?? emptyState();
      // State written by an older deployment has no `waiting` bucket.
      this.data.waiting ??= {};
    }
    return this.data;
  }

  private async save(): Promise<void> {
    if (this.data) {
      await this.ctx.storage.put(STATE_KEY, this.data);
    }
  }

  private sessions(): Array<{ ws: WebSocket; att: Att }> {
    return this.ctx.getWebSockets().map((ws) => ({ ws, att: this.att(ws) }));
  }

  private att(ws: WebSocket): Att {
    const stored = ws.deserializeAttachment() as Att | null;
    return stored ?? blank();
  }

  private findSession(id: string): { ws: WebSocket; att: Att } | undefined {
    return this.sessions().find((s) => s.att.id === id);
  }

  private findWs(id: string): WebSocket | null {
    return this.findSession(id)?.ws ?? null;
  }

  private subscribersOf(id: string): string[] {
    const data = this.data;
    if (!data) {
      // Only called synchronously after state() resolved at least once in this handler.
      return [];
    }
    const list = data.watchers[id];
    return list ? [...list] : [];
  }

  /**
   * Presence of `id` as seen by `viewer`. The room id, its token and the allow list stay on the
   * server: they used to be broadcast to every watcher, and anyone who can read the allow list
   * also learns the ids needed to impersonate one of the guests. The server answers the only
   * question a friend needs anyway, "is it open for me".
   */
  private presenceOf(id: string, viewer: string | null): Msg {
    const session = this.findSession(id);
    if (!session) {
      return msg("presence", { id, state: "offline", detail: "" });
    }
    const out = msg("presence", { id, state: session.att.state, detail: session.att.detail });
    const room = session.att.roomId ? this.data?.rooms[session.att.roomId] : undefined;
    if (room && room.host === id) {
      out.d.room = {
        world: room.world,
        premium: room.premium,
        open: room.allow.includes("*") || (!!viewer && room.allow.includes(viewer)),
      };
    }
    return out;
  }

  private broadcastPresence(id: string): void {
    for (const watcher of this.subscribersOf(id)) {
      this.sendToId(watcher, this.presenceOf(id, watcher));
    }
  }

  private reject(ws: WebSocket, reason: string): void {
    this.send(ws, msg("error", { reason }));
    try {
      ws.close(1008, reason);
    } catch {
      // already gone
    }
  }

  private send(ws: WebSocket | null, m: Msg): void {
    if (!ws) {
      return;
    }
    try {
      ws.send(encode(m));
    } catch {
      // socket is closing, the close handler will clean up
    }
  }

  private sendToId(id: string, m: Msg): void {
    this.send(this.findWs(id), m);
  }
}
