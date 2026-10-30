/**
 * Envelope shared with the Java client (core/src/main/java/.../Msg.java).
 *
 * On the wire a message is still `{"t":"type","d":{...}}`, but over WebSocket it travels as one
 * text frame per message instead of one newline terminated line. The reader still splits on
 * newlines so a batched or chunked sender keeps working.
 */

export type JsonObject = Record<string, unknown>;

export interface Msg {
  t: string;
  d: JsonObject;
}

export function msg(t: string, d: JsonObject = {}): Msg {
  return { t, d };
}

export function encode(m: Msg): string {
  return JSON.stringify({ t: m.t, d: m.d });
}

export function parseLine(text: string): Msg {
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    return msg("");
  }
  if (!root || typeof root !== "object") {
    return msg("");
  }
  const t = typeof (root as JsonObject).t === "string" ? ((root as JsonObject).t as string) : "";
  const d = sub(root as JsonObject, "d");
  return { t, d };
}

export function str(o: JsonObject | null | undefined, key: string): string {
  const v = o?.[key];
  if (v === undefined || v === null) {
    return "";
  }
  return typeof v === "string" ? v : String(v);
}

export function num(o: JsonObject | null | undefined, key: string): number {
  const v = o?.[key];
  if (typeof v === "number") {
    return Number.isFinite(v) ? v : 0;
  }
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

export function bool(o: JsonObject | null | undefined, key: string): boolean {
  const v = o?.[key];
  if (typeof v === "boolean") {
    return v;
  }
  return typeof v === "string" && v.toLowerCase() === "true";
}

export function sub(o: JsonObject | null | undefined, key: string): JsonObject {
  const v = o?.[key];
  return v && typeof v === "object" && !Array.isArray(v) ? (v as JsonObject) : {};
}

/** Same as {@link sub}, kept under the name the ported server code uses. */
export const obj = sub;

export function strings(o: JsonObject | null | undefined, key: string): string[] {
  const v = o?.[key];
  if (!Array.isArray(v)) {
    return [];
  }
  return v.filter((e) => e !== null && e !== undefined).map((e) => (typeof e === "string" ? e : String(e)));
}
