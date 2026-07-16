import { env, runInDurableObject, SELF } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import {
  decryptVerifier,
  fromBase64Url,
  hmacSha256Base64Url,
  publicRouteTag,
  randomBase64Url,
  sha256Base64Url,
} from "../src/crypto";
import { decodeBinaryChunk } from "../src/protocol";
import { isReadOnlyRoute } from "../src/http";
import type { DeviceObject } from "../src/device-object";

const ADMIN_TOKEN = "test-admin-token-with-at-least-32-characters";
const CREDENTIAL_KEY = "ERERERERERERERERERERERERERERERERERERERERERE";
const ROUTE_KEY = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI";
const DEVICE_ID = "ABEiM0RVZneImaq7zN3u_w";
const DEVICE_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
const DEVICE_VERIFIER = "Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0";
const EMPTY_BODY_HASH = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU";
const openSockets: WebSocket[] = [];

afterEach(() => {
  for (const socket of openSockets.splice(0)) {
    try { socket.close(1000, "test complete"); } catch { /* already closed */ }
  }
});

describe("protocol contract", () => {
  it("matches the Android HMAC fixed vectors", async () => {
    const secret = fromBase64Url(DEVICE_SECRET);
    expect(secret).not.toBeNull();
    expect(await sha256Base64Url(secret!)).toBe(DEVICE_VERIFIER);
    const key = fromBase64Url(DEVICE_VERIFIER)!;
    const controlCanonical = [
      "LEGADO-RELAY-CONTROL-V1",
      "GET",
      "/v1/device/connect",
      "1784174400",
      "EjRWeJCrze8SNFZ4kKvN7w",
      EMPTY_BODY_HASH,
    ].join("\n");
    expect(await hmacSha256Base64Url(key, controlCanonical)).toBe("r0RmK6Pu5KhhQgey5wEavfDdz_uwBM2M3dmO3jTrBak");

    const challengeCanonical = [
      "v1",
      DEVICE_ID,
      "20015998343868",
      "1784174430000",
      "q83vEjRWeJCrze8SNFZ4kKvN7wEjRWeJCrze8SNFZ4",
    ].join("\n");
    expect(await hmacSha256Base64Url(key, challengeCanonical)).toBe("90OxZvsmTpOMUtqJWbgtewpwdtOmzgIQ1ipRiHnGvCA");
  });

  it("decodes the Android 24-byte binary frame", () => {
    const payload = new TextEncoder().encode("chunk");
    const frame = androidBinaryFrame(123456789, 7, payload);
    expect(decodeBinaryChunk(frame)).toEqual({
      header: { v: 1, type: "http_response_chunk", requestId: 123456789, seq: 7 },
      payload,
    });
    new DataView(frame).setInt32(20, payload.byteLength + 1, false);
    expect(decodeBinaryChunk(frame)).toBeNull();
  });

  it("keeps the public read allowlist narrow", () => {
    expect(isReadOnlyRoute("GET", "/getBookshelf")).toBe(true);
    expect(isReadOnlyRoute("POST", "/getBookshelf")).toBe(false);
    expect(isReadOnlyRoute("GET", "/cover")).toBe(false);
    expect(isReadOnlyRoute("GET", "/image")).toBe(false);
    expect(isReadOnlyRoute("GET", "/assets/app.js")).toBe(true);
    expect(isReadOnlyRoute("GET", "/assets/%2e%2e/secret")).toBe(false);
  });

  it("fails verifier decryption with the wrong master key", async () => {
    const { encryptVerifier } = await import("../src/crypto");
    const envelope = await encryptVerifier(DEVICE_VERIFIER, CREDENTIAL_KEY, DEVICE_ID);
    expect(envelope).not.toContain(DEVICE_VERIFIER);
    expect(await decryptVerifier(envelope, ROUTE_KEY, DEVICE_ID)).toBeNull();
    expect(await decryptVerifier(envelope, CREDENTIAL_KEY, DEVICE_ID)).toBe(DEVICE_VERIFIER);
  });
});

describe("worker security boundary", () => {
  it("rejects administration without the operator secret", async () => {
    const response = await SELF.fetch(`https://relay.test/v1/admin/devices/${DEVICE_ID}`, {
      method: "PUT",
      body: JSON.stringify({ deviceVerifier: DEVICE_VERIFIER }),
    });
    expect(response.status).toBe(401);
  });

  it("stores only an encrypted device verifier", async () => {
    await provisionDevice();
    const stub = env.DEVICE_OBJECT.getByName(DEVICE_ID) as DurableObjectStub<DeviceObject>;
    const stored = await runInDurableObject(stub, (_instance, state) => {
      return state.storage.sql.exec("SELECT verifier_envelope FROM device_credentials WHERE singleton = 1").toArray()[0]?.verifier_envelope;
    });
    expect(typeof stored).toBe("string");
    expect(stored).not.toBe(DEVICE_VERIFIER);
    expect(String(stored).startsWith("v1.")).toBe(true);
  });

  it("rejects unsigned and replayed WebSocket upgrades before accepting a socket", async () => {
    await provisionDevice();
    const handle = await deviceHandle();
    const unsigned = await SELF.fetch(`https://relay.test/v1/device/connect?deviceHandle=${handle}`, {
      headers: { upgrade: "websocket" },
    });
    expect(unsigned.status).toBe(401);

    const signed = await signedRequest("GET", "/v1/device/connect", "", { upgrade: "websocket" });
    const accepted = await SELF.fetch(`https://relay.test/v1/device/connect?deviceHandle=${handle}`, signed.init);
    expect(accepted.status).toBe(101);
    accepted.webSocket?.accept();
    if (accepted.webSocket) openSockets.push(accepted.webSocket);

    const replayed = await SELF.fetch(`https://relay.test/v1/device/connect?deviceHandle=${handle}`, signed.init);
    expect(replayed.status).toBe(401);
  });

  it("rejects an invalid public handle without routing to device data", async () => {
    const response = await SELF.fetch(`https://relay.test/d/${DEVICE_ID}.AAAAAAAAAAAAAAAAAAAAAA/`);
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: { code: "NOT_FOUND", message: "Route not found." } });
  });

  it("rejects invalid device handles before WebSocket or control routing", async () => {
    const invalid = `${DEVICE_ID}.AAAAAAAAAAAAAAAAAAAAAA`;
    const connect = await SELF.fetch(`https://relay.test/v1/device/connect?deviceHandle=${invalid}`, {
      headers: { upgrade: "websocket" },
    });
    expect(connect.status).toBe(404);
    const control = await SELF.fetch("https://relay.test/v1/device/status", {
      headers: { "x-legado-device-handle": invalid },
    });
    expect(control.status).toBe(404);
  });

  it("exchanges a fragment token for a cookie and remains read-only while offline", async () => {
    await provisionDevice();
    const share = await createShare();
    const publicUrl = new URL(share.shareUrl);
    const basePath = publicUrl.pathname.replace(/\/$/u, "");

    const bootstrap = await SELF.fetch(`https://relay.test${basePath}/`);
    expect(bootstrap.status).toBe(200);
    expect(await bootstrap.text()).toContain("URLSearchParams(location.hash");

    const session = await SELF.fetch(`https://relay.test${basePath}/_session`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: share.token }),
    });
    expect(session.status).toBe(204);
    const cookie = session.headers.get("set-cookie");
    expect(cookie).toContain("HttpOnly");
    expect(cookie).toContain("SameSite=Strict");
    expect(cookie).not.toContain(share.token);

    const asset = await SELF.fetch(`https://relay.test${basePath}/`, { headers: { cookie: cookie! } });
    expect(asset.status).toBe(200);
    expect(asset.headers.get("content-security-policy")).toContain("script-src 'self'");

    const offline = await SELF.fetch(`https://relay.test${basePath}/getBookshelf`, { headers: { cookie: cookie! } });
    expect(offline.status).toBe(503);
    expect((await offline.json() as { error: { code: string } }).error.code).toBe("DEVICE_OFFLINE");

    const write = await SELF.fetch(`https://relay.test${basePath}/getBookshelf`, {
      method: "POST",
      headers: { authorization: `Bearer ${share.token}` },
    });
    expect(write.status).toBe(405);
    expect((await write.json() as { error: { code: string } }).error.code).toBe("METHOD_NOT_ALLOWED");

    for (const blockedPath of ["/cover", "/image"]) {
      const blocked = await SELF.fetch(`https://relay.test${basePath}${blockedPath}`, {
        headers: { authorization: `Bearer ${share.token}` },
      });
      expect(blocked.status).toBe(404);
    }
  });

  it("authenticates the Android socket and grants initial response credit", async () => {
    await provisionDevice();
    const socket = await openAuthenticatedDevice();
    const share = await createShare();
    const publicUrl = new URL(share.shareUrl);
    const requestMessage = nextTextMessage(socket);
    const responsePromise = SELF.fetch(`${publicUrl.origin}${publicUrl.pathname}getBookshelf`, {
      headers: { authorization: `Bearer ${share.token}` },
    });
    const request = JSON.parse(await requestMessage) as { type: string; requestId: number; epoch: number; headers: unknown; path: string };
    expect(request.type).toBe("http_request");
    expect(request.path).toBe("/getBookshelf");
    expect(Array.isArray(request.headers)).toBe(false);

    const creditMessage = nextTextMessage(socket);
    socket.send(JSON.stringify({
      type: "http_response",
      requestId: request.requestId,
      epoch: request.epoch,
      status: 200,
      headers: { "content-type": "text/plain; charset=utf-8" },
    }));
    const credit = JSON.parse(await creditMessage) as { type: string; bytes: number };
    expect(credit.type).toBe("credit");
    expect(credit.bytes).toBe(512 * 1024);

    socket.send(androidBinaryFrame(request.requestId, 0, new TextEncoder().encode("ok")));
    socket.send(JSON.stringify({ type: "http_response_end", requestId: request.requestId, epoch: request.epoch }));
    const response = await responsePromise;
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("ok");
  });
});

async function provisionDevice(): Promise<void> {
  const response = await SELF.fetch(`https://relay.test/v1/admin/devices/${DEVICE_ID}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${ADMIN_TOKEN}`, "content-type": "application/json" },
    body: JSON.stringify({ deviceVerifier: DEVICE_VERIFIER }),
  });
  expect(response.status).toBe(200);
}

async function createShare(): Promise<{ token: string; shareUrl: string }> {
  const body = JSON.stringify({ scope: "read", expiresInSeconds: 3600 });
  const signed = await signedRequest("POST", "/v1/device/share", body, { "content-type": "application/json" });
  const response = await SELF.fetch("https://relay.test/v1/device/share", signed.init);
  expect(response.status).toBe(201);
  return response.json() as Promise<{ token: string; shareUrl: string }>;
}

async function openAuthenticatedDevice(): Promise<WebSocket> {
  const signed = await signedRequest("GET", "/v1/device/connect", "", { upgrade: "websocket" });
  const response = await SELF.fetch(`https://relay.test/v1/device/connect?deviceHandle=${await deviceHandle()}`, signed.init);
  expect(response.status).toBe(101);
  const socket = response.webSocket!;
  socket.accept();
  openSockets.push(socket);
  const challenge = JSON.parse(await nextTextMessage(socket)) as { nonce: string; expiresAt: number; epoch: number };
  socket.send(JSON.stringify({ type: "hello", protocolVersion: 1, minimumProtocolVersion: 1, deviceId: DEVICE_ID }));
  const key = fromBase64Url(DEVICE_VERIFIER)!;
  const canonical = ["v1", DEVICE_ID, String(challenge.epoch), String(challenge.expiresAt), challenge.nonce].join("\n");
  const readyMessage = nextTextMessage(socket);
  socket.send(JSON.stringify({
    type: "authenticate",
    deviceId: DEVICE_ID,
    nonce: challenge.nonce,
    expiresAt: challenge.expiresAt,
    epoch: challenge.epoch,
    proof: await hmacSha256Base64Url(key, canonical),
  }));
  const ready = JSON.parse(await readyMessage) as { type: string; epoch: number; protocolVersion: number };
  expect(ready).toMatchObject({ type: "ready", epoch: challenge.epoch, protocolVersion: 1 });
  return socket;
}

async function signedRequest(
  method: string,
  path: string,
  body: string,
  extraHeaders: Record<string, string> = {},
): Promise<{ init: RequestInit; nonce: string }> {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = randomBase64Url(18);
  const bodyHash = await sha256Base64Url(new TextEncoder().encode(body));
  const canonical = ["LEGADO-RELAY-CONTROL-V1", method, path, timestamp, nonce, bodyHash].join("\n");
  const signature = await hmacSha256Base64Url(fromBase64Url(DEVICE_VERIFIER)!, canonical);
  return {
    nonce,
    init: {
      method,
      headers: {
        ...extraHeaders,
        "x-legado-device-handle": await deviceHandle(),
        "x-legado-timestamp": timestamp,
        "x-legado-nonce": nonce,
        "x-legado-signature": signature,
      },
      body: body || undefined,
    },
  };
}

async function deviceHandle(): Promise<string> {
  return `${DEVICE_ID}.${await publicRouteTag(ROUTE_KEY, DEVICE_ID)}`;
}

function nextTextMessage(socket: WebSocket): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("WebSocket message timeout")), 2_000);
    socket.addEventListener("message", (event) => {
      clearTimeout(timer);
      if (typeof event.data === "string") resolve(event.data);
      else reject(new Error("Expected a text WebSocket message"));
    }, { once: true });
  });
}

function androidBinaryFrame(requestId: number, sequence: number, payload: Uint8Array): ArrayBuffer {
  const frame = new ArrayBuffer(24 + payload.byteLength);
  const view = new DataView(frame);
  view.setUint32(0, 0x4c475231, false);
  view.setUint8(4, 1);
  view.setUint8(5, 2);
  view.setUint16(6, 0, false);
  view.setBigInt64(8, BigInt(requestId), false);
  view.setInt32(16, sequence, false);
  view.setInt32(20, payload.byteLength, false);
  new Uint8Array(frame, 24).set(payload);
  return frame;
}
