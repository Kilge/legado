import { describe, expect, it } from "vitest";
import {
  decryptVerifier,
  encryptVerifier,
  fromBase64Url,
  hmacSha256Base64Url,
  sha256Base64Url,
} from "../src/crypto";
import { isReadOnlyRoute, parseDeviceHandle } from "../src/http";
import { decodeBinaryChunk } from "../src/protocol";

const DEVICE_ID = "ABEiM0RVZneImaq7zN3u_w";
const SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
const VERIFIER = "Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0";
const CREDENTIAL_KEY = "ERERERERERERERERERERERERERERERERERERERERERE";
const WRONG_KEY = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI";

describe("relay protocol v1", () => {
  it("matches the shared Android HMAC vectors", async () => {
    const secret = fromBase64Url(SECRET)!;
    expect(await sha256Base64Url(secret)).toBe(VERIFIER);
    const key = fromBase64Url(VERIFIER)!;
    const emptyHash = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU";
    const connect = [
      "LEGADO-RELAY-CONTROL-V1", "GET", "/v1/device/connect", "1784174400",
      "EjRWeJCrze8SNFZ4kKvN7w", emptyHash,
    ].join("\n");
    expect(await hmacSha256Base64Url(key, connect)).toBe("r0RmK6Pu5KhhQgey5wEavfDdz_uwBM2M3dmO3jTrBak");
    const challenge = [
      "v1", DEVICE_ID, "20015998343868", "1784174430000",
      "q83vEjRWeJCrze8SNFZ4kKvN7wEjRWeJCrze8SNFZ4",
    ].join("\n");
    expect(await hmacSha256Base64Url(key, challenge)).toBe("90OxZvsmTpOMUtqJWbgtewpwdtOmzgIQ1ipRiHnGvCA");
  });

  it("decodes the fixed Android 24-byte binary header", () => {
    const payload = new TextEncoder().encode("chunk");
    const frame = new ArrayBuffer(24 + payload.byteLength);
    const view = new DataView(frame);
    view.setUint32(0, 0x4c475231, false);
    view.setUint8(4, 1);
    view.setUint8(5, 2);
    view.setUint16(6, 0, false);
    view.setBigInt64(8, 123456789n, false);
    view.setInt32(16, 7, false);
    view.setInt32(20, payload.byteLength, false);
    new Uint8Array(frame, 24).set(payload);
    expect(decodeBinaryChunk(frame)).toEqual({
      header: { v: 1, type: "http_response_chunk", requestId: 123456789, seq: 7 },
      payload,
    });
  });

  it("rejects write, traversal, cover and image routes", () => {
    expect(isReadOnlyRoute("GET", "/getBookContent")).toBe(true);
    expect(isReadOnlyRoute("POST", "/getBookContent")).toBe(false);
    expect(isReadOnlyRoute("GET", "/cover")).toBe(false);
    expect(isReadOnlyRoute("GET", "/image")).toBe(false);
    expect(isReadOnlyRoute("GET", "/assets/%2e%2e/secret")).toBe(false);
  });

  it("validates handles and encrypts the stored verifier", async () => {
    expect(parseDeviceHandle(`${DEVICE_ID}.AAAAAAAAAAAAAAAAAAAAAA`)?.deviceId).toBe(DEVICE_ID);
    expect(parseDeviceHandle(DEVICE_ID)).toBeNull();
    const envelope = await encryptVerifier(VERIFIER, CREDENTIAL_KEY, DEVICE_ID);
    expect(envelope.startsWith("v1.")).toBe(true);
    expect(envelope).not.toContain(VERIFIER);
    expect(await decryptVerifier(envelope, WRONG_KEY, DEVICE_ID)).toBeNull();
    expect(await decryptVerifier(envelope, CREDENTIAL_KEY, DEVICE_ID)).toBe(VERIFIER);
  });
});
