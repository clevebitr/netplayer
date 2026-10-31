/**
 * End to end smoke test for the Worker signaling server.
 *
 *   npx wrangler dev --port 8787          # terminal 1
 *   node test/smoke.mjs                   # terminal 2
 *
 * Walks the same sequence TESTING.md section 1 walks in game: hello, watch, add friend, open a
 * room, request/accept a join, relay an ICE candidate, and see the host go offline.
 * No dependencies: uses Node's built in WebSocket and crypto (Node 22+).
 */
import crypto from "node:crypto";

const ENDPOINT = process.env.SIGNAL_URL ?? "ws://127.0.0.1:8787/";

function b64(buf) {
  return Buffer.from(buf).toString("base64");
}

function identity(name) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
  const ed = publicKey.export({ type: "spki", format: "der" });
  const x = crypto.generateKeyPairSync("x25519").publicKey.export({ type: "spki", format: "der" });
  const id = crypto.createHash("sha256").update(ed).digest("hex").slice(0, 16);
  return {
    profile: { id, name, ed: b64(ed), x: b64(x) },
    sign: (text) => b64(crypto.sign(null, Buffer.from(text, "utf8"), privateKey)),
  };
}

class Client {
  constructor(label) {
    this.label = label;
    this.queue = [];
    this.cursor = 0;
    this.waiters = [];
    this.closed = null;
  }

  async open() {
    this.ws = new WebSocket(ENDPOINT);
    this.ws.addEventListener("message", (event) => {
      const msg = JSON.parse(String(event.data));
      this.queue.push(msg);
      for (const waiter of this.waiters.splice(0)) {
        waiter();
      }
    });
    this.ws.addEventListener("close", (event) => {
      this.closed = { code: event.code, reason: event.reason };
      for (const waiter of this.waiters.splice(0)) {
        waiter();
      }
    });
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", () => reject(new Error(`${this.label}: connect failed`)), { once: true });
    });
    return this;
  }

  send(t, d = {}) {
    this.ws.send(JSON.stringify({ t, d }));
  }

  hello(identity, { skewMs = 0 } = {}) {
    const ts = Date.now() + skewMs;
    this.send("hello", {
      profile: identity.profile,
      ts,
      sig: identity.sign(`hello:${identity.profile.id}:${ts}`),
    });
  }

  /** Returns the next unconsumed message of a given type, waiting up to `ms`. */
  async next(type, ms = 4000) {
    const deadline = Date.now() + ms;
    for (;;) {
      while (this.cursor < this.queue.length) {
        const msg = this.queue[this.cursor++];
        if (msg.t === type) {
          return msg;
        }
        if (msg.t === "error") {
          throw new Error(`${this.label}: server error ${JSON.stringify(msg.d)}`);
        }
      }
      if (this.closed) {
        throw new Error(`${this.label}: closed ${JSON.stringify(this.closed)} while waiting for ${type}`);
      }
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        throw new Error(`${this.label}: timeout waiting for ${type}`);
      }
      await new Promise((resolve) => {
        const timer = setTimeout(resolve, remaining);
        this.waiters.push(() => {
          clearTimeout(timer);
          resolve();
        });
      });
    }
  }

  close() {
    try {
      this.ws.close(1000, "test done");
    } catch {
      // already gone
    }
  }
}

const failures = [];

/** Waits for the first presence message whose payload matches, skipping the ones that do not. */
async function presenceWith(client, predicate, ms = 4000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const remaining = Math.max(200, deadline - Date.now());
    const message = await client.next("presence", remaining);
    if (predicate(message.d)) {
      return message;
    }
  }
}

/** Asserts that {@code type} does not arrive within {@code ms}. */
async function expectSilence(client, type, ms = 700) {
  try {
    const msg = await client.next(type, ms);
    return { silent: false, msg };
  } catch (error) {
    if (String(error.message).includes("timeout")) {
      return { silent: true };
    }
    throw error;
  }
}

function check(label, condition) {
  if (condition) {
    console.log(`  ok   ${label}`);
  } else {
    failures.push(label);
    console.log(`  FAIL ${label}`);
  }
}

async function main() {
  const alice = identity("Alice");
  const bob = identity("Bob");

  console.log("hello handshake");
  const a = await new Client("alice").open();
  a.hello(alice);
  const aOk = await a.next("hello_ok");
  check("alice hello_ok echoes her id", aOk.d.id === alice.profile.id);

  const b = await new Client("bob").open();
  b.hello(bob);
  const bOk = await b.next("hello_ok");
  check("bob hello_ok echoes his id", bOk.d.id === bob.profile.id);

  const forged = await new Client("mallory").open();
  const hijack = {
    profile: alice.profile,
    sign: () => b64(crypto.randomBytes(64)),
  };
  forged.hello(hijack);
  const rejected = await forged.next("error");
  check("bad signature is rejected", rejected.d.reason === "bad signature");
  forged.close();

  // Mallory signs a valid hello, but claims Alice's id with her own key. The id has to be the
  // hash of the signing key, otherwise this would kick Alice off and receive her messages.
  const imposter = await new Client("imposter").open();
  const mallory = identity("Mallory");
  const stolen = {
    profile: { ...mallory.profile, id: alice.profile.id },
    sign: (text) => mallory.sign(text),
  };
  imposter.hello(stolen);
  const refused = await imposter.next("error");
  check("an id that does not hash from the key is rejected", refused.d.reason === "id does not match key");
  imposter.close();

  console.log("presence");
  b.send("watch", { ids: [alice.profile.id] });
  const aPresence = await b.next("presence");
  check("watcher gets the target presence", aPresence.d.id === alice.profile.id && aPresence.d.state === "menu");

  a.send("presence", { state: "playing", detail: "World" });
  const aPlaying = await b.next("presence");
  check("presence change is pushed to watchers", aPlaying.d.state === "playing" && aPlaying.d.detail === "World");

  console.log("friend request");
  b.send("add", { to: alice.profile.id });
  const aAdd = await a.next("add");
  check("online add is delivered immediately", aAdd.d.from.id === bob.profile.id);

  console.log("offline queue");
  a.close();
  const offline = await b.next("presence");
  check("watchers see the host go offline", offline.d.id === alice.profile.id && offline.d.state === "offline");

  b.send("add", { to: alice.profile.id });
  await new Promise((resolve) => setTimeout(resolve, 150));

  const a2 = await new Client("alice2").open();
  a2.hello(alice);
  await a2.next("hello_ok");
  const queued = await a2.next("add");
  check("a pending friend request is delivered on reconnect", queued.d.from.id === bob.profile.id);

  console.log("room + join");
  a2.send("room_open", { room: { id: "room-1", world: "My World", token: "tok-1", allow: ["*"] } });
  const roomPresence = await presenceWith(b, (d) => d.room);
  check(
    "open room shows up in presence",
    roomPresence.d.room.world === "My World" && roomPresence.d.room.open === true,
  );
  check(
    "presence no longer leaks the room id, token or allow list",
    !("id" in roomPresence.d.room) && !("allow" in roomPresence.d.room) && !("token" in roomPresence.d.room),
  );

  // The guest names the host, the server resolves the currently open room, so no room id has to
  // be published for a join to work.
  b.send("join_req", { host: alice.profile.id, mc: { name: "Bob" } });
  const joinReq = await a2.next("join_req");
  check("host receives join_req", joinReq.d.room === "room-1" && joinReq.d.guest.id === bob.profile.id);
  check("join_req tells the host how the guest was authorized", joinReq.d.token === "");

  a2.send("join_ans", { to: bob.profile.id, room: "room-1", accept: true });
  const joinAns = await b.next("join_ans");
  check(
    "guest gets host profile and room token",
    joinAns.d.accept === true && joinAns.d.host.id === alice.profile.id && joinAns.d.token === "tok-1",
  );

  console.log("ice relay");
  b.send("ice", { to: alice.profile.id, cand: [{ type: "host", ip: "10.0.0.2" }], ufrag: "uf", pwd: "pw" });
  const ice = await a2.next("ice");
  check("ice is relayed with the sender profile", ice.d.from.id === bob.profile.id && ice.d.ufrag === "uf");
  check("ice no longer carries the 'to' field", !("to" in ice.d));

  console.log("room rejection");
  const carol = identity("Carol");
  const c = await new Client("carol").open();
  c.hello(carol);
  await c.next("hello_ok");
  a2.send("room_open", { room: { id: "room-2", world: "Private", token: "tok-2", allow: [bob.profile.id] } });
  c.send("join_req", { room: "room-2", token: "wrong" });
  const denied = await c.next("join_ans");
  check("whitelist blocks an outsider", denied.d.accept === false && denied.d.reason === "not_allowed");
  c.send("join_req", { room: "room-2", token: "tok-2" });
  const viaToken = await a2.next("join_req");
  check("a valid room token bypasses the whitelist", viaToken.d.room === "room-2");
  check("the host is told the guest came through the token", viaToken.d.token === "tok-2");

  // Carol must not be able to answer a join request on Alice's behalf: that answer carries the
  // room token, which would hand out a whitelist bypass to anyone who knows a room id.
  c.send("join_ans", { to: bob.profile.id, room: "room-2", accept: true });
  const forgedAnswer = await expectSilence(b, "join_ans");
  check("a non-host cannot accept on the host's behalf", forgedAnswer.silent);

  // Alice closing a room Carol never owned must be ignored.
  c.send("room_close", { room: "room-2" });
  const notClosed = await expectSilence(b, "join_ans");
  check("a non-host cannot close a room", notClosed.silent);
  b.send("join_req", { host: alice.profile.id });
  const stillOpen = await a2.next("join_req");
  check("the room is still open", stillOpen.d.room === "room-2");

  b.close();
  c.close();
  a2.close();
  await new Promise((resolve) => setTimeout(resolve, 300));

  console.log("");
  if (failures.length) {
    console.log(`${failures.length} check(s) failed`);
    process.exit(1);
  }
  console.log("all checks passed");
  process.exit(0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
