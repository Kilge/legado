# Legado Relay Protocol v1

This file is the executable interoperability contract between Android and the Cloudflare relay. Integers in JSON must remain within JavaScript's safe integer range. Base64url values are unpadded.

## Device identity and handles

- `deviceId`: 16 random bytes encoded as 22 base64url characters.
- `deviceSecret`: 32 random bytes, stored only by Android.
- `deviceVerifier`: `SHA-256(deviceSecret)`. Android uses these 32 derived bytes as the HMAC key. The Worker AES-GCM encrypts the verifier before SQLite storage.
- `routeTag`: first 16 bytes of `HMAC-SHA-256(RELAY_ROUTE_KEY, "LEGADO-RELAY-ROUTE-V1\n" + deviceId)`, base64url encoded.
- `deviceHandle`: `deviceId + "." + routeTag`.

The public path, device connect query and control headers use `deviceHandle`. The Worker verifies the tag in constant time before resolving a Durable Object name.

## Upgrade pre-authentication

Android opens:

```text
GET /v1/device/connect?deviceHandle={deviceHandle}
Upgrade: websocket
X-Legado-Device-Handle: {deviceHandle}
X-Legado-Timestamp: {UTC epoch seconds}
X-Legado-Nonce: {at least 16 random bytes, base64url}
X-Legado-Signature: {proof}
```

The proof key is the 32-byte `deviceVerifier`. The exact canonical UTF-8 text is:

```text
LEGADO-RELAY-CONTROL-V1\n
GET\n
/v1/device/connect\n
{timestamp}\n
{nonce}\n
{SHA-256(empty body), base64url}
```

The timestamp window is ±120 seconds. A valid nonce is inserted into a unique SQLite table and cannot be replayed. The Worker does not create or accept a WebSocket until this proof succeeds.

Other device control requests use the same format with the actual uppercase method, pathname without query parameters and SHA-256 of the exact bounded request body.

## WebSocket authentication

After pre-authentication, the Worker sends:

```json
{
  "v": 1,
  "type": "challenge",
  "protocolVersion": 1,
  "minimumProtocolVersion": 1,
  "nonce": "...",
  "expiresAt": 1784174430000,
  "epoch": 20015998343868
}
```

Android may send a `hello` control message for version negotiation, then responds:

```json
{
  "type": "authenticate",
  "deviceId": "...",
  "nonce": "...",
  "expiresAt": 1784174430000,
  "epoch": 20015998343868,
  "proof": "..."
}
```

The proof canonical text is:

```text
v1\n
{deviceId}\n
{epoch}\n
{expiresAt}\n
{nonce}
```

On success, the Worker closes the previous authenticated socket, fails every request attached to the old epoch and sends `ready` with the same epoch and protocol limits. WebSocket Hibernation attachments persist the authenticated epoch across Durable Object eviction.

## HTTP relay controls

Worker to Android:

```json
{
  "v": 1,
  "type": "http_request",
  "requestId": 123456789,
  "epoch": 20015998343868,
  "method": "GET",
  "path": "/getBookContent?...",
  "contentLength": 0,
  "headers": {"accept":"application/json"}
}
```

Headers are always a JSON object (`Record<string,string>` / `Map<String,String>`), never an array. Both peers apply hop-by-hop/header-size filtering. Read-only v1 requests have no request body and therefore need no `http_request_end` frame.

Android starts a response with:

```json
{
  "type": "http_response",
  "requestId": 123456789,
  "epoch": 20015998343868,
  "status": 200,
  "headers": {"content-type":"application/json; charset=utf-8"}
}
```

The Worker immediately sends an initial `credit` message after accepting response metadata. Android must not send more binary payload bytes than granted. Each browser pull replenishes credit only up to the 512 KiB high-water mark.

Android ends with `http_response_end`, or rejects with `http_error`. `cancel` releases work after a browser abort, timeout or protocol failure. `ping`/`pong` are control messages. Public `ws_open`, `ws_data` and `ws_close` remain reserved and are not implemented in v1.

## Binary response frame

All fields use network byte order. The fixed header is exactly 24 bytes:

| Offset | Size | Field |
| --- | ---: | --- |
| 0 | 4 | Magic `0x4c475231` (`LGR1`) |
| 4 | 1 | Protocol version `1` |
| 5 | 1 | Type `2` (`HttpResponseChunk`) |
| 6 | 2 | Flags, currently `0` |
| 8 | 8 | Positive request ID |
| 16 | 4 | Zero-based sequence |
| 20 | 4 | Payload length, maximum 32768 |
| 24 | N | Payload bytes |

The frame length must equal `24 + payloadLength`. Sequence, credit and total response size are checked before the payload enters the browser stream.

## Fixed interoperability vectors

These values are duplicated in Worker and Android tests and must change together:

```text
deviceSecret = AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8
deviceVerifier = Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0
deviceId = ABEiM0RVZneImaq7zN3u_w
emptyBodyHash = 47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU

timestamp = 1784174400
controlNonce = EjRWeJCrze8SNFZ4kKvN7w
connectSignature = r0RmK6Pu5KhhQgey5wEavfDdz_uwBM2M3dmO3jTrBak

epoch = 20015998343868
expiresAt = 1784174430000
challengeNonce = q83vEjRWeJCrze8SNFZ4kKvN7wEjRWeJCrze8SNFZ4
challengeProof = 90OxZvsmTpOMUtqJWbgtewpwdtOmzgIQ1ipRiHnGvCA
```

