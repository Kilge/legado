const encoder = new TextEncoder();

function ownedBuffer(bytes: Uint8Array): ArrayBuffer {
  return Uint8Array.from(bytes).buffer;
}

export function utf8(value: string): Uint8Array {
  return encoder.encode(value);
}

export function toBase64Url(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = "";
  for (const byte of view) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function fromBase64Url(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/u.test(value)) return null;
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  try {
    const binary = atob(value.replaceAll("-", "+").replaceAll("_", "/") + padding);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

export function randomBase64Url(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return toBase64Url(bytes);
}

export function randomId(): string {
  return randomBase64Url(18);
}

export async function sha256Base64Url(value: string | Uint8Array): Promise<string> {
  const data = typeof value === "string" ? utf8(value) : value;
  return toBase64Url(await crypto.subtle.digest("SHA-256", ownedBuffer(data)));
}

export async function saltedHash(salt: string, secret: string): Promise<string> {
  return sha256Base64Url(`${salt}.${secret}`);
}

export async function hmacSha256Base64Url(key: Uint8Array, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    ownedBuffer(key),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return toBase64Url(await crypto.subtle.sign("HMAC", cryptoKey, ownedBuffer(utf8(message))));
}

export async function publicRouteTag(routeKey: string, deviceId: string): Promise<string | null> {
  const keyBytes = fromBase64Url(routeKey);
  if (!keyBytes || keyBytes.byteLength !== 32) return null;
  const full = fromBase64Url(await hmacSha256Base64Url(keyBytes, `LEGADO-RELAY-ROUTE-V1\n${deviceId}`));
  return full ? toBase64Url(full.slice(0, 16)) : null;
}

export function constantTimeEqual(left: string, right: string): boolean {
  const leftBytes = utf8(left);
  const rightBytes = utf8(right);
  const maxLength = Math.max(leftBytes.length, rightBytes.length);
  let difference = leftBytes.length ^ rightBytes.length;
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}

export function parseCompoundToken(token: string): { id: string; secret: string } | null {
  const separator = token.indexOf(".");
  if (separator <= 0 || separator !== token.lastIndexOf(".")) return null;
  const id = token.slice(0, separator);
  const secret = token.slice(separator + 1);
  if (!/^[A-Za-z0-9_-]{16,64}$/u.test(id) || !/^[A-Za-z0-9_-]{32,64}$/u.test(secret)) {
    return null;
  }
  return { id, secret };
}

export function isSha256Verifier(value: string): boolean {
  return fromBase64Url(value)?.byteLength === 32;
}

export function randomSafeInteger(): number {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  let value = 0;
  for (const byte of bytes) value = value * 256 + byte;
  return value === 0 ? 1 : value;
}

export function isCredentialKey(value: string | undefined): value is string {
  return typeof value === "string" && fromBase64Url(value)?.byteLength === 32;
}

export async function encryptVerifier(verifier: string, credentialKey: string, deviceId: string): Promise<string> {
  const keyBytes = fromBase64Url(credentialKey);
  if (!keyBytes || keyBytes.byteLength !== 32) throw new Error("invalid credential key");
  const key = await crypto.subtle.importKey("raw", ownedBuffer(keyBytes), "AES-GCM", false, ["encrypt"]);
  const nonce = new Uint8Array(12);
  crypto.getRandomValues(nonce);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: ownedBuffer(nonce), additionalData: ownedBuffer(utf8(deviceId)), tagLength: 128 },
    key,
    ownedBuffer(utf8(verifier)),
  );
  return `v1.${toBase64Url(nonce)}.${toBase64Url(ciphertext)}`;
}

export async function decryptVerifier(envelope: string, credentialKey: string, deviceId: string): Promise<string | null> {
  const keyBytes = fromBase64Url(credentialKey);
  const parts = envelope.split(".");
  if (!keyBytes || keyBytes.byteLength !== 32 || parts.length !== 3 || parts[0] !== "v1") return null;
  const nonce = fromBase64Url(parts[1]);
  const ciphertext = fromBase64Url(parts[2]);
  if (!nonce || nonce.byteLength !== 12 || !ciphertext || ciphertext.byteLength < 17) return null;
  try {
    const key = await crypto.subtle.importKey("raw", ownedBuffer(keyBytes), "AES-GCM", false, ["decrypt"]);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: ownedBuffer(nonce), additionalData: ownedBuffer(utf8(deviceId)), tagLength: 128 },
      key,
      ownedBuffer(ciphertext),
    );
    const verifier = new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
    return isSha256Verifier(verifier) ? verifier : null;
  } catch {
    return null;
  }
}
