# Legado Cloudflare Relay

This is the first, deliberately read-only implementation of the Legado public Web relay. It is independent from `cloudflare/download-gate` and is not deployed by the Android release workflow.

The Android app opens an authenticated outbound WebSocket to one SQLite-backed Durable Object per device. Public browser requests use a separately revocable share secret, and the Durable Object forwards only the permitted API request to the connected device. Book content, images, source data and request bodies are never persisted in Cloudflare storage.

The repository-wide trust boundary and limits are documented in [`../../docs/public-web-relay.md`](../../docs/public-web-relay.md). Exact protocol fields and interoperability vectors are in [`PROTOCOL.md`](PROTOCOL.md).

## Security boundary

- Production `workers.dev` and preview URLs are disabled. Bind a custom domain before deployment.
- A self-authenticating `deviceHandle` is required before the Worker calls `getByName()`. Random IDs cannot create arbitrary Durable Objects.
- WebSocket upgrades require a timestamped HMAC and single-use nonce before a socket is accepted. A second challenge authenticates the live socket.
- The device HMAC verifier is encrypted at rest with AES-256-GCM using `RELAY_CREDENTIAL_KEY`. The raw device secret is never sent to or stored by the Worker.
- Share and browser-session secrets are stored only as independently salted SHA-256 hashes.
- Browser links place the share token in the URL fragment. `POST /d/{deviceHandle}/_session` exchanges it for a 15-minute `Secure; HttpOnly; SameSite=Strict` cookie.
- The first release allows only `GET` for `/getBookshelf`, `/getChapterList`, `/getBookContent` and `/getReadConfig`, plus authenticated Web assets.
- `/cover` and `/image` are intentionally blocked: their current path parameters could otherwise become a local-file disclosure primitive. They require opaque, device-authorized resource handles before being enabled.
- Public WebSockets, writes, uploads, progress sync, source/login APIs and debug/search functions are not implemented.
- Four forwarded requests are allowed per device. Responses are limited to 32 MiB, 32 KiB chunks and 512 KiB of unconsumed data per request with credit-based flow control.
- Control/request bodies are read as bounded streams. Missing `Content-Length` cannot force an unbounded `arrayBuffer()` allocation.

## Required secrets

Generate three independent random values. Do not reuse a value between purposes:

```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

Store them as Wrangler secrets:

```powershell
cd cloudflare/legado-relay
npx wrangler secret put RELAY_ADMIN_TOKEN
npx wrangler secret put RELAY_CREDENTIAL_KEY
npx wrangler secret put RELAY_ROUTE_KEY
```

- `RELAY_ADMIN_TOKEN`: operator-only device provisioning/revocation credential, at least 32 characters.
- `RELAY_CREDENTIAL_KEY`: exactly 32 random bytes encoded as unpadded base64url. Rotating it requires re-provisioning every device.
- `RELAY_ROUTE_KEY`: a different 32-byte base64url key used to authenticate public/device handles. Rotating it invalidates every existing handle and share URL.

The Worker fails closed when a required key is absent or malformed. Never put these values in `wrangler.toml`, `.dev.vars` committed to Git, Android resources, logs or backup files.

## Device provisioning

The App generates a 32-byte device secret and a 16-byte device ID. It derives:

```text
deviceVerifier = SHA-256(deviceSecret)
```

An operator provisions only the verifier. The raw secret stays in Android Keystore-encrypted storage:

```http
PUT /v1/admin/devices/{deviceId}
Authorization: Bearer {RELAY_ADMIN_TOKEN}
Content-Type: application/json

{"deviceVerifier":"{base64url SHA-256 digest}"}
```

The response returns the non-secret self-authenticating handle the App must use afterward:

```json
{
  "deviceId": "...",
  "deviceHandle": "{deviceId}.{routeTag}",
  "provisioned": true
}
```

`DELETE /v1/admin/devices/{deviceId}` disables the device, closes its socket and revokes its shares and sessions.

## Device routes

- `GET /v1/device/connect?deviceHandle={deviceHandle}` — authenticated WebSocket upgrade.
- `POST /v1/device/share` — creates a read share and returns its raw token exactly once.
- `GET /v1/device/shares` — lists metadata only; tokens and hashes are never returned.
- `DELETE /v1/device/share/{shareId}` — revokes a share and all of its sessions.
- `GET /v1/device/status` — connection and in-flight request state.

Control requests use `X-Legado-Device-Handle`, `X-Legado-Timestamp`, `X-Legado-Nonce` and `X-Legado-Signature`. The canonical signing format is specified in `PROTOCOL.md`. A share may live from 60 seconds to 30 days; the current default is one day.

## Browser flow

The share-creation response contains a URL like:

```text
https://read.example.com/d/{deviceHandle}/#token={shareId}.{secret}
```

An unauthenticated request to the route root receives only a small Worker-owned bootstrap page. It removes the fragment from browser history, posts the token to:

```http
POST /d/{deviceHandle}/_session
Content-Type: application/json

{"token":"{shareId}.{secret}"}
```

After the cookie is issued, the Worker serves the committed Web assets from `app/src/main/assets/web/vue`. API requests are authenticated by the same path-scoped cookie. Bearer share tokens remain supported for non-browser API clients; query-string tokens are never accepted.

## Web assets

`wrangler.toml` binds the committed Android Web artifact rather than ignored local build output. When the Web frontend changes, build it in `modules/web`, synchronize the resulting files into `app/src/main/assets/web/vue`, and review/commit that artifact before a Worker deployment.

## Development and verification

```powershell
cd cloudflare/legado-relay
npm ci
npm run typecheck
npm run test:unit
npm test
node node_modules/wrangler/bin/wrangler.js deploy --dry-run --outdir .wrangler/dry-run
```

- `test:unit` runs portable protocol, crypto and allowlist vectors in Node.
- `test` runs the Worker/Durable Object integration suite through Cloudflare's Vitest pool and `workerd`.
- The dry run bundles the Worker, validates the Durable Object/Assets bindings and does not upload anything.

On Windows, Cloudflare's current `workerd` requires a current Microsoft Visual C++ Redistributable. A native runtime startup failure is an environment failure, not a passing integration suite.

## Deploy

Deployment is intentionally manual:

```powershell
npm run deploy
```

Before deploying, bind the intended custom domain and optionally Cloudflare Access. Do not enable the `workers.dev` subdomain, which would bypass custom-domain policy. The initial Durable Object migration is `v1` with `new_sqlite_classes = ["DeviceObject"]`; future schema/class changes must add a new Wrangler migration tag.

## Error codes

All API errors are JSON in the form `{"error":{"code":"...","message":"..."}}` with `Cache-Control: private, no-store`. Important codes include:

- `UNAUTHORIZED` — invalid/replayed device proof or invalid/expired share.
- `NOT_FOUND` / `ROUTE_NOT_ALLOWED` — invalid handle or excluded public route.
- `DEVICE_OFFLINE` — authenticated share, but no device socket (`503`).
- `DEVICE_BUSY` — four requests are already in flight (`429`).
- `RESPONSE_START_TIMEOUT` / `REQUEST_TIMEOUT` — 15-second start or 60-second total deadline (`504`).
- `PROTOCOL_ERROR` / `RESPONSE_TOO_LARGE` — invalid device frame, flow-control violation or response limit.
- `CREDENTIAL_KEY_NOT_CONFIGURED`, `CREDENTIAL_DECRYPTION_FAILED`, `ROUTE_KEY_NOT_CONFIGURED` — server configuration failure (`503`).

