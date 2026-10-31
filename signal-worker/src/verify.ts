/**
 * Ed25519 verification with WebCrypto.
 *
 * The client writes its public key as X.509 SubjectPublicKeyInfo (what `KeyFactory("Ed25519")`
 * expects on the Java side), so `spki` is the natural import format. A raw 32 byte key is accepted
 * as a fallback in case a future client sends one.
 */

function b64ToBytes(text: string): Uint8Array | null {
  if (!text) {
    return null;
  }
  try {
    const bin = atob(text);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) {
      out[i] = bin.charCodeAt(i);
    }
    return out;
  } catch {
    return null;
  }
}

/**
 * A NetPlayer id is the first 16 hex chars of SHA-256 over the X.509 encoded signing key
 * (core/Profile.java). The server checks it on hello so that nobody can announce somebody
 * else's id with a key of their own, which is what makes the id usable as an identity at all.
 */
export async function profileIdMatches(keyB64: string, id: string): Promise<boolean> {
  const key = b64ToBytes(keyB64);
  if (!key || !id || id.length !== 16) {
    return false;
  }
  try {
    const digest = await crypto.subtle.digest("SHA-256", key as BufferSource);
    const hex = Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    return hex.slice(0, 16) === id;
  } catch {
    return false;
  }
}

export async function verifyEd25519(keyB64: string, data: string, signatureB64: string): Promise<boolean> {
  const key = b64ToBytes(keyB64);
  const sig = b64ToBytes(signatureB64);
  if (!key || !sig || key.length < 32 || sig.length !== 64) {
    return false;
  }

  const body = new TextEncoder().encode(data);
  const alg = { name: "Ed25519" } as unknown as { name: string };
  const candidates: Array<{ bytes: Uint8Array; format: "spki" | "raw" }> = [
    { bytes: key, format: key.length === 32 ? "raw" : "spki" },
  ];
  if (key.length !== 32) {
    candidates.push({ bytes: key.subarray(key.length - 32), format: "raw" });
  }

  for (const candidate of candidates) {
    try {
      const imported = await crypto.subtle.importKey(
        candidate.format,
        candidate.bytes as BufferSource,
        alg as never,
        false,
        ["verify"],
      );
      const ok = await crypto.subtle.verify(
        alg as never,
        imported,
        sig as BufferSource,
        body as BufferSource,
      );
      if (ok) {
        return true;
      }
    } catch {
      // Unsupported import format on this runtime, try the next candidate.
    }
  }
  return false;
}
