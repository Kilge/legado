import { DurableObject } from "cloudflare:workers";
import {
  CHALLENGE_TTL_MS,
  CONTROL_CLOCK_SKEW_SECONDS,
  DEFAULT_SHARE_TTL_SECONDS,
  MAX_ACTIVE_SHARES,
  MAX_CHUNK_BYTES,
  MAX_CONCURRENT_REQUESTS,
  MAX_CONTROL_BODY_BYTES,
  MAX_DEVICE_SOCKETS,
  MAX_RESPONSE_BYTES,
  MAX_SESSIONS_PER_SHARE,
  MAX_SHARE_TTL_SECONDS,
  MAX_UNCONSUMED_BYTES,
  MIN_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
  RESPONSE_START_TIMEOUT_MS,
  SESSION_COOKIE_NAME,
  SESSION_TTL_SECONDS,
  TOTAL_REQUEST_TIMEOUT_MS,
} from "./constants";
import {
  constantTimeEqual,
  decryptVerifier,
  encryptVerifier,
  fromBase64Url,
  hmacSha256Base64Url,
  isCredentialKey,
  isSha256Verifier,
  parseCompoundToken,
  randomBase64Url,
  randomId,
  randomSafeInteger,
  publicRouteTag,
  saltedHash,
  sha256Base64Url,
} from "./crypto";
import {
  errorResponse,
  isKnownReadPath,
  isReadOnlyRoute,
  isStaticAssetRoute,
  parseCookie,
  parsePublicRoute,
  readBoundedBody,
  requestBearerToken,
  securityHeaders,
  selectForwardRequestHeaders,
  selectForwardResponseHeaders,
} from "./http";
import { controlMessage, decodeBinaryChunk, parseControlMessage } from "./protocol";
import type {
  DeviceAttachment,
  DeviceCredentialRow,
  Env,
  SessionRow,
  ShareRow,
} from "./types";

interface DeviceRow extends DeviceCredentialRow {
  device_id: string;
}

interface CountRow {
  count: number;
}

interface PendingRequest {
  requestId: number;
  epoch: number;
  socket: WebSocket;
  responseStarted: boolean;
  completed: boolean;
  nextSequence: number;
  receivedBytes: number;
  creditOutstanding: number;
  streamController?: ReadableStreamDefaultController<Uint8Array>;
  resolveResponse: (response: Response) => void;
  startTimer: ReturnType<typeof setTimeout>;
  totalTimer: ReturnType<typeof setTimeout>;
  abortHandler: () => void;
  abortSignal: AbortSignal;
}

interface AuthenticatedShare {
  id: string;
  scope: "read";
}

const CONTROL_SIGNATURE_PREFIX = "LEGADO-RELAY-CONTROL-V1";
const DUMMY_VERIFIER = new Uint8Array(32);

export class DeviceObject extends DurableObject<Env> {
  private readonly state: DurableObjectState;
  private readonly sql: SqlStorage;
  private readonly relayEnv: Env;
  private readonly pending = new Map<number, PendingRequest>();

  constructor(state: DurableObjectState, env: Env) {
    super(state, env);
    this.state = state;
    this.relayEnv = env;
    this.sql = state.storage.sql;
    state.blockConcurrencyWhile(async () => {
      this.initializeSchema();
    });
  }

  private initializeSchema(): void {
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS device_credentials (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        device_id TEXT NOT NULL,
        verifier_envelope TEXT NOT NULL,
        disabled INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS shares (
        id TEXT PRIMARY KEY,
        salt TEXT NOT NULL,
        token_hash TEXT NOT NULL,
        scope TEXT NOT NULL CHECK (scope = 'read'),
        expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        revoked_at INTEGER
      );
      CREATE INDEX IF NOT EXISTS shares_expiry ON shares(expires_at);
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        share_id TEXT NOT NULL,
        salt TEXT NOT NULL,
        token_hash TEXT NOT NULL,
        expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        FOREIGN KEY (share_id) REFERENCES shares(id) ON DELETE CASCADE
      );
      CREATE INDEX IF NOT EXISTS sessions_share ON sessions(share_id, created_at);
      CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);
      CREATE TABLE IF NOT EXISTS auth_nonces (
        nonce TEXT PRIMARY KEY,
        expires_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS auth_nonces_expiry ON auth_nonces(expires_at);
    `);
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.headers.get("x-relay-internal-admin") === "1") {
      return this.handleAdminRequest(request, url);
    }
    if (url.pathname === "/v1/device/connect") {
      return this.handleDeviceConnect(request);
    }
    if (url.pathname.startsWith("/v1/device/")) {
      return this.handleDeviceControl(request, url);
    }
    if (url.pathname.startsWith("/d/")) {
      return this.handlePublicRequest(request, url);
    }
    return errorResponse("NOT_FOUND", "Route not found.", 404);
  }

  async alarm(): Promise<void> {
    const now = Date.now();
    let nextExpiry: number | null = null;
    for (const socket of this.state.getWebSockets()) {
      const attachment = this.getAttachment(socket);
      if (!attachment || attachment.authenticated) continue;
      if (attachment.challengeExpiresAt <= now) {
        socket.close(4003, "Authentication timeout");
      } else {
        nextExpiry = nextExpiry === null ? attachment.challengeExpiresAt : Math.min(nextExpiry, attachment.challengeExpiresAt);
      }
    }
    if (nextExpiry !== null) await this.state.storage.setAlarm(nextExpiry);
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = this.getAttachment(socket);
    if (!attachment) {
      socket.close(4002, "Protocol error");
      return;
    }
    if (!attachment.authenticated) {
      if (typeof message !== "string") {
        socket.close(4002, "Protocol error");
        return;
      }
      await this.authenticateSocket(socket, attachment, message);
      return;
    }
    if (typeof message === "string") {
      this.handleDeviceControlMessage(socket, attachment, message);
    } else {
      this.handleDeviceBinaryMessage(socket, attachment, message);
    }
  }

  async webSocketClose(socket: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    void code;
    void reason;
    void wasClean;
    this.failRequestsForSocket(socket, "DEVICE_OFFLINE", "Device disconnected.", 503);
  }

  async webSocketError(socket: WebSocket, error: unknown): Promise<void> {
    void error;
    this.failRequestsForSocket(socket, "DEVICE_OFFLINE", "Device connection failed.", 503);
  }

  private async handleAdminRequest(request: Request, url: URL): Promise<Response> {
    const match = /^\/v1\/admin\/devices\/([^/]+)$/u.exec(url.pathname);
    if (!match) return errorResponse("NOT_FOUND", "Route not found.", 404);
    const deviceId = match[1];
    if (request.method === "PUT") {
      if (!isCredentialKey(this.relayEnv.RELAY_CREDENTIAL_KEY)) {
        return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
      }
      let body: Uint8Array;
      try {
        body = await readBoundedBody(request);
      } catch {
        return errorResponse("PAYLOAD_TOO_LARGE", "Control payload is too large.", 413);
      }
      const parsed = parseJsonObject(body);
      const verifier = parsed?.deviceVerifier;
      if (typeof verifier !== "string" || !isSha256Verifier(verifier)) {
        return errorResponse("BAD_REQUEST", "deviceVerifier must be a base64url SHA-256 digest.", 400);
      }
      const existing = this.deviceRow();
      if (existing && await decryptVerifier(existing.verifier_envelope, this.relayEnv.RELAY_CREDENTIAL_KEY, existing.device_id) === null) {
        return errorResponse("CREDENTIAL_DECRYPTION_FAILED", "Existing relay credentials cannot be decrypted.", 503);
      }
      const encryptedVerifier = await encryptVerifier(verifier, this.relayEnv.RELAY_CREDENTIAL_KEY, deviceId);
      const now = unixSeconds();
      this.sql.exec(
        `INSERT INTO device_credentials(singleton, device_id, verifier_envelope, disabled, created_at, updated_at)
         VALUES(1, ?, ?, 0, ?, ?)
         ON CONFLICT(singleton) DO UPDATE SET device_id = excluded.device_id, verifier_envelope = excluded.verifier_envelope,
           disabled = 0, updated_at = excluded.updated_at`,
        deviceId,
        encryptedVerifier,
        now,
        now,
      );
      this.sql.exec("DELETE FROM auth_nonces");
      for (const socket of this.state.getWebSockets()) socket.close(4001, "Device credential rotated");
      this.failAllPending("DEVICE_OFFLINE", "Device credential rotated.", 503);
      if (!isCredentialKey(this.relayEnv.RELAY_ROUTE_KEY)) {
        return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
      }
      const routeTag = await publicRouteTag(this.relayEnv.RELAY_ROUTE_KEY, deviceId);
      if (!routeTag) return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
      return jsonResponse({ deviceId, deviceHandle: `${deviceId}.${routeTag}`, provisioned: true });
    }
    if (request.method === "DELETE") {
      const now = unixSeconds();
      this.sql.exec("UPDATE device_credentials SET disabled = 1, updated_at = ? WHERE singleton = 1", now);
      this.sql.exec("UPDATE shares SET revoked_at = ? WHERE revoked_at IS NULL", now);
      this.sql.exec("DELETE FROM sessions");
      this.sql.exec("DELETE FROM auth_nonces");
      for (const socket of this.state.getWebSockets()) socket.close(4001, "Device disabled");
      this.failAllPending("DEVICE_OFFLINE", "Device disabled.", 503);
      return jsonResponse({ deviceId, disabled: true });
    }
    return errorResponse("METHOD_NOT_ALLOWED", "Only PUT and DELETE are supported.", 405);
  }

  private async handleDeviceConnect(request: Request): Promise<Response> {
    if (request.method !== "GET" || request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return errorResponse("UPGRADE_REQUIRED", "A WebSocket upgrade is required.", 426);
    }
    const deviceId = request.headers.get("x-relay-device-id") || "";
    if (!isCredentialKey(this.relayEnv.RELAY_CREDENTIAL_KEY)) {
      return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
    }
    const credentialState = await this.loadVerifier(deviceId);
    if (credentialState === "decrypt_error") {
      return errorResponse("CREDENTIAL_DECRYPTION_FAILED", "Relay credentials cannot be decrypted.", 503);
    }
    if (!(await this.verifyControlSignature(request, "/v1/device/connect", new Uint8Array()))) {
      return errorResponse("UNAUTHORIZED", "Device authentication failed.", 401);
    }
    const row = this.deviceRow();
    if (!row || row.disabled !== 0 || row.device_id !== deviceId) {
      return errorResponse("UNAUTHORIZED", "Device authentication failed.", 401);
    }
    if (this.state.getWebSockets().length >= MAX_DEVICE_SOCKETS) {
      return errorResponse("TOO_MANY_CONNECTIONS", "Too many pending device connections.", 429);
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const nonce = randomBase64Url(32);
    const challengeExpiresAt = Date.now() + CHALLENGE_TTL_MS;
    const epoch = randomSafeInteger();
    const attachment: DeviceAttachment = {
      kind: "device",
      deviceId,
      nonce,
      challengeExpiresAt,
      authenticated: false,
      epoch,
    };
    server.serializeAttachment(attachment);
    this.state.acceptWebSocket(server, ["device"]);
    await this.scheduleChallengeAlarm(challengeExpiresAt);
    server.send(controlMessage("challenge", {
      protocolVersion: PROTOCOL_VERSION,
      minimumProtocolVersion: MIN_PROTOCOL_VERSION,
      nonce,
      expiresAt: challengeExpiresAt,
      epoch,
    }));
    return new Response(null, { status: 101, webSocket: client });
  }

  private async authenticateSocket(socket: WebSocket, attachment: DeviceAttachment, message: string): Promise<void> {
    const control = parseControlMessage(message);
    if (control?.type === "hello") {
      if (control.protocolVersion !== PROTOCOL_VERSION || control.minimumProtocolVersion !== MIN_PROTOCOL_VERSION) {
        socket.close(4002, "Protocol mismatch");
      }
      return;
    }
    if (
      !control ||
      control.type !== "authenticate" ||
      typeof control.proof !== "string" ||
      control.deviceId !== attachment.deviceId ||
      control.nonce !== attachment.nonce ||
      control.expiresAt !== attachment.challengeExpiresAt ||
      control.epoch !== attachment.epoch ||
      Date.now() > attachment.challengeExpiresAt
    ) {
      socket.close(4003, "Authentication failed");
      return;
    }
    const row = this.deviceRow();
    const verifierText = row && row.disabled === 0 && row.device_id === attachment.deviceId
      ? await decryptVerifier(row.verifier_envelope, this.relayEnv.RELAY_CREDENTIAL_KEY || "", attachment.deviceId)
      : null;
    const currentRow = this.deviceRow();
    const verifier = verifierText && currentRow?.verifier_envelope === row?.verifier_envelope && currentRow?.disabled === 0
      ? fromBase64Url(verifierText)
      : null;
    const messageToSign = [
      "v1",
      attachment.deviceId,
      String(attachment.epoch),
      String(attachment.challengeExpiresAt),
      attachment.nonce,
    ].join("\n");
    const expected = await hmacSha256Base64Url(verifier ?? DUMMY_VERIFIER, messageToSign);
    if (!verifier || !constantTimeEqual(expected, control.proof)) {
      socket.close(4003, "Authentication failed");
      return;
    }

    for (const existing of this.state.getWebSockets()) {
      if (existing === socket) continue;
      const existingAttachment = this.getAttachment(existing);
      if (existingAttachment?.authenticated) {
        this.failRequestsForSocket(existing, "DEVICE_OFFLINE", "Device connection was replaced.", 503);
        existing.close(4001, "Replaced by a newer connection");
      }
    }
    const epoch = attachment.epoch;
    socket.serializeAttachment({ ...attachment, authenticated: true } satisfies DeviceAttachment);
    socket.send(controlMessage("ready", {
      protocolVersion: PROTOCOL_VERSION,
      minimumProtocolVersion: MIN_PROTOCOL_VERSION,
      epoch,
      maxConcurrentRequests: MAX_CONCURRENT_REQUESTS,
      maxChunkBytes: MAX_CHUNK_BYTES,
      maxResponseBytes: MAX_RESPONSE_BYTES,
      maxUnconsumedBytes: MAX_UNCONSUMED_BYTES,
    }));
  }

  private async handleDeviceControl(request: Request, url: URL): Promise<Response> {
    if (!isCredentialKey(this.relayEnv.RELAY_CREDENTIAL_KEY)) {
      return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
    }
    if (url.search) return errorResponse("BAD_REQUEST", "Control routes do not accept query parameters.", 400);
    let body: Uint8Array;
    try {
      body = await readBoundedBody(request, MAX_CONTROL_BODY_BYTES);
    } catch {
      return errorResponse("PAYLOAD_TOO_LARGE", "Control payload is too large.", 413);
    }
    if (!(await this.verifyControlSignature(request, url.pathname, body))) {
      return errorResponse("UNAUTHORIZED", "Device authentication failed.", 401);
    }

    if (request.method === "POST" && url.pathname === "/v1/device/share") {
      return this.createShare(request, body);
    }
    if (request.method === "GET" && url.pathname === "/v1/device/shares") {
      return this.listShares();
    }
    if (request.method === "GET" && url.pathname === "/v1/device/status") {
      return jsonResponse({
        connected: this.authenticatedSocket() !== null,
        protocolVersion: PROTOCOL_VERSION,
        inFlightRequests: this.pending.size,
      });
    }
    const revokeMatch = /^\/v1\/device\/share\/([A-Za-z0-9_-]{16,64})$/u.exec(url.pathname);
    if (request.method === "DELETE" && revokeMatch) return this.revokeShare(revokeMatch[1]);
    return errorResponse("NOT_FOUND", "Control route not found.", 404);
  }

  private async verifyControlSignature(request: Request, path: string, body: Uint8Array): Promise<boolean> {
    const deviceId = request.headers.get("x-legado-device-id") || "";
    const timestampRaw = request.headers.get("x-legado-timestamp") || "";
    const nonce = request.headers.get("x-legado-nonce") || "";
    const signature = request.headers.get("x-legado-signature") || "";
    if (!/^\d{10}$/u.test(timestampRaw) || !/^[A-Za-z0-9_-]{16,64}$/u.test(nonce) || signature.length > 128) {
      return false;
    }
    const timestamp = Number.parseInt(timestampRaw, 10);
    const now = unixSeconds();
    if (Math.abs(now - timestamp) > CONTROL_CLOCK_SKEW_SECONDS) return false;
    const row = this.deviceRow();
    const verifierText = row && row.disabled === 0 && row.device_id === deviceId
      ? await decryptVerifier(row.verifier_envelope, this.relayEnv.RELAY_CREDENTIAL_KEY || "", deviceId)
      : null;
    const currentRow = this.deviceRow();
    const verifier = verifierText && currentRow?.verifier_envelope === row?.verifier_envelope && currentRow?.disabled === 0
      ? fromBase64Url(verifierText)
      : null;
    const bodyHash = await sha256Base64Url(body);
    const canonical = [CONTROL_SIGNATURE_PREFIX, request.method, path, timestampRaw, nonce, bodyHash].join("\n");
    const expected = await hmacSha256Base64Url(verifier ?? DUMMY_VERIFIER, canonical);
    if (!verifier || !constantTimeEqual(expected, signature)) return false;

    this.sql.exec("DELETE FROM auth_nonces WHERE expires_at < ?", now);
    try {
      this.sql.exec("INSERT INTO auth_nonces(nonce, expires_at) VALUES(?, ?)", nonce, now + CONTROL_CLOCK_SKEW_SECONDS);
      return true;
    } catch {
      return false;
    }
  }

  private async createShare(request: Request, body: Uint8Array): Promise<Response> {
    const parsed = body.byteLength === 0 ? {} : parseJsonObject(body);
    if (!parsed) return errorResponse("BAD_REQUEST", "Request body must be a JSON object.", 400);
    if (parsed.scope !== undefined && parsed.scope !== "read") {
      return errorResponse("UNSUPPORTED_SCOPE", "Only the read scope is supported.", 400);
    }
    const requestedTtl = parsed.expiresInSeconds ?? DEFAULT_SHARE_TTL_SECONDS;
    if (!Number.isSafeInteger(requestedTtl) || Number(requestedTtl) < 60 || Number(requestedTtl) > MAX_SHARE_TTL_SECONDS) {
      return errorResponse("BAD_REQUEST", "expiresInSeconds must be between 60 and 2592000.", 400);
    }
    const deviceId = request.headers.get("x-legado-device-id") || "";
    if (!isCredentialKey(this.relayEnv.RELAY_ROUTE_KEY)) {
      return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
    }
    const routeTag = await publicRouteTag(this.relayEnv.RELAY_ROUTE_KEY, deviceId);
    if (!routeTag) return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
    const id = randomId();
    const secret = randomBase64Url(32);
    const salt = randomBase64Url(16);
    const tokenHash = await saltedHash(salt, secret);
    const now = unixSeconds();
    this.pruneExpired(now);
    const count = this.queryOne<CountRow>("SELECT COUNT(*) AS count FROM shares WHERE revoked_at IS NULL AND expires_at > ?", now)?.count ?? 0;
    if (count >= MAX_ACTIVE_SHARES) return errorResponse("SHARE_LIMIT_REACHED", "Too many active shares.", 409);
    const expiresAt = now + Number(requestedTtl);
    this.sql.exec(
      "INSERT INTO shares(id, salt, token_hash, scope, expires_at, created_at, revoked_at) VALUES(?, ?, ?, 'read', ?, ?, NULL)",
      id,
      salt,
      tokenHash,
      expiresAt,
      now,
    );
    const publicHandle = `${deviceId}.${routeTag}`;
    const token = `${id}.${secret}`;
    const shareUrl = `${new URL(request.url).origin}/d/${publicHandle}/#token=${encodeURIComponent(token)}`;
    return jsonResponse({ id, token, scope: "read", expiresAt, shareUrl }, 201);
  }

  private listShares(): Response {
    const now = unixSeconds();
    this.pruneExpired(now);
    const shares = this.sql.exec<Pick<ShareRow, "id" | "scope" | "expires_at" | "created_at">>(
      "SELECT id, scope, expires_at, created_at FROM shares WHERE revoked_at IS NULL AND expires_at > ? ORDER BY created_at DESC",
      now,
    ).toArray();
    return jsonResponse({ shares: shares.map((share) => ({
      id: share.id,
      scope: share.scope,
      expiresAt: share.expires_at,
      createdAt: share.created_at,
    })) });
  }

  private revokeShare(shareId: string): Response {
    const now = unixSeconds();
    this.sql.exec("UPDATE shares SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, shareId);
    this.sql.exec("DELETE FROM sessions WHERE share_id = ?", shareId);
    return new Response(null, { status: 204, headers: securityHeaders() });
  }

  private async handlePublicRequest(request: Request, url: URL): Promise<Response> {
    const route = parsePublicRoute(url.pathname);
    if (!route) return errorResponse("INVALID_DEVICE_ID", "Invalid public relay path.", 400);
    if (route.localPath === "") {
      return new Response(null, {
        status: 308,
        headers: securityHeaders({ location: `${route.deviceBasePath}/${url.search}` }),
      });
    }
    if (route.localPath === "/_session") return this.exchangeSession(request, route.deviceBasePath);
    if (request.headers.get("upgrade")?.toLowerCase() === "websocket") {
      return errorResponse("PUBLIC_WEBSOCKET_NOT_SUPPORTED", "Public WebSocket relay is not supported in protocol v1.", 501);
    }
    if (!isKnownReadPath(route.localPath)) return errorResponse("ROUTE_NOT_ALLOWED", "This route is not available to public shares.", 404);
    if (!isReadOnlyRoute(request.method, route.localPath)) {
      return errorResponse("METHOD_NOT_ALLOWED", "Public shares are read-only.", 405);
    }
    const share = await this.authenticatePublicRequest(request);
    if (!share) {
      if (request.method === "GET" && route.localPath === "/") return bootstrapResponse(route.deviceBasePath);
      return errorResponse("UNAUTHORIZED", "Share authentication failed.", 401);
    }
    if (share.scope !== "read") return errorResponse("FORBIDDEN", "Share scope does not permit this request.", 403);
    if (isStaticAssetRoute(route.localPath)) return this.serveStaticAsset(request, route.localPath);
    return this.forwardReadRequest(request, url, route.localPath);
  }

  private async serveStaticAsset(request: Request, localPath: string): Promise<Response> {
    const assetUrl = new URL(request.url);
    assetUrl.pathname = localPath === "/" ? "/index.html" : localPath;
    assetUrl.search = "";
    const asset = await this.relayEnv.ASSETS.fetch(new Request(assetUrl.toString(), {
      method: "GET",
      headers: { accept: request.headers.get("accept") || "*/*" },
    }));
    if (!asset.ok) return errorResponse("ASSET_NOT_FOUND", "Web asset not found.", 404);
    const headers = securityHeaders(asset.headers);
    headers.delete("set-cookie");
    if (localPath === "/" || localPath === "/index.html") {
      headers.set("cache-control", "private, no-store");
      headers.set("content-security-policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    } else if (localPath.startsWith("/assets/")) {
      headers.set("cache-control", "private, max-age=31536000, immutable");
    }
    return new Response(asset.body, { status: asset.status, statusText: asset.statusText, headers });
  }

  private async exchangeSession(request: Request, basePath: string): Promise<Response> {
    if (request.method !== "POST") return errorResponse("METHOD_NOT_ALLOWED", "Session exchange requires POST.", 405);
    let body: Uint8Array;
    try {
      body = await readBoundedBody(request, 4 * 1024);
    } catch {
      return errorResponse("PAYLOAD_TOO_LARGE", "Session payload is too large.", 413);
    }
    const parsed = parseJsonObject(body);
    const token = parsed?.token;
    const share = typeof token === "string" ? await this.authenticateShareToken(token) : null;
    if (!share) return errorResponse("UNAUTHORIZED", "Share authentication failed.", 401);

    const sessionId = randomId();
    const secret = randomBase64Url(32);
    const salt = randomBase64Url(16);
    const tokenHash = await saltedHash(salt, secret);
    const now = unixSeconds();
    this.pruneExpired(now);
    const currentShare = this.queryOne<ShareRow>("SELECT * FROM shares WHERE id = ?", share.id);
    if (!currentShare || currentShare.revoked_at !== null || currentShare.expires_at <= now) {
      return errorResponse("UNAUTHORIZED", "Share authentication failed.", 401);
    }
    const sessions = this.sql.exec(
      "SELECT id FROM sessions WHERE share_id = ? ORDER BY created_at DESC LIMIT ?",
      share.id,
      MAX_SESSIONS_PER_SHARE,
    ).toArray() as unknown as Array<{ id: string }>;
    if (sessions.length >= MAX_SESSIONS_PER_SHARE) {
      this.sql.exec("DELETE FROM sessions WHERE id = ?", sessions[sessions.length - 1].id);
    }
    const expiresAt = Math.min(now + SESSION_TTL_SECONDS, currentShare.expires_at);
    this.sql.exec(
      "INSERT INTO sessions(id, share_id, salt, token_hash, expires_at, created_at) VALUES(?, ?, ?, ?, ?, ?)",
      sessionId,
      share.id,
      salt,
      tokenHash,
      expiresAt,
      now,
    );
    const cookie = [
      `${SESSION_COOKIE_NAME}=${sessionId}.${secret}`,
      `Path=${basePath}/`,
      `Max-Age=${Math.max(1, expiresAt - now)}`,
      "Secure",
      "HttpOnly",
      "SameSite=Strict",
    ].join("; ");
    return new Response(null, {
      status: 204,
      headers: securityHeaders({ "set-cookie": cookie }),
    });
  }

  private async authenticatePublicRequest(request: Request): Promise<AuthenticatedShare | null> {
    const directToken = requestBearerToken(request);
    if (directToken) return this.authenticateShareToken(directToken);
    const sessionToken = parseCookie(request.headers, SESSION_COOKIE_NAME);
    if (!sessionToken) return null;
    const parsed = parseCompoundToken(sessionToken);
    if (!parsed) return null;
    const now = unixSeconds();
    const row = this.queryOne<SessionRow & { scope: "read"; share_expires_at: number; revoked_at: number | null }>(
      `SELECT sessions.id, sessions.share_id, sessions.salt, sessions.token_hash, sessions.expires_at,
         shares.scope, shares.expires_at AS share_expires_at, shares.revoked_at
       FROM sessions JOIN shares ON shares.id = sessions.share_id WHERE sessions.id = ?`,
      parsed.id,
    );
    const expected = await saltedHash(row?.salt ?? randomBase64Url(16), parsed.secret);
    const current = row ? this.queryOne<typeof row>(
      `SELECT sessions.id, sessions.share_id, sessions.salt, sessions.token_hash, sessions.expires_at,
         shares.scope, shares.expires_at AS share_expires_at, shares.revoked_at
       FROM sessions JOIN shares ON shares.id = sessions.share_id WHERE sessions.id = ?`,
      parsed.id,
    ) : null;
    if (
      !row || !current || current.token_hash !== row.token_hash ||
      !constantTimeEqual(expected, current.token_hash) ||
      current.expires_at <= now ||
      current.share_expires_at <= now ||
      current.revoked_at !== null
    ) return null;
    return { id: current.share_id, scope: current.scope };
  }

  private async authenticateShareToken(token: string): Promise<ShareRow | null> {
    if (token.length > 160) return null;
    const parsed = parseCompoundToken(token);
    if (!parsed) return null;
    const row = this.queryOne<ShareRow>("SELECT * FROM shares WHERE id = ?", parsed.id);
    const expected = await saltedHash(row?.salt ?? randomBase64Url(16), parsed.secret);
    const current = row ? this.queryOne<ShareRow>("SELECT * FROM shares WHERE id = ?", parsed.id) : null;
    const now = unixSeconds();
    if (
      !row || !current || current.token_hash !== row.token_hash ||
      !constantTimeEqual(expected, current.token_hash) ||
      current.revoked_at !== null ||
      current.expires_at <= now
    ) return null;
    return current;
  }

  private async forwardReadRequest(request: Request, url: URL, localPath: string): Promise<Response> {
    if (this.pending.size >= MAX_CONCURRENT_REQUESTS) {
      return errorResponse("DEVICE_BUSY", "The device is handling too many requests.", 429);
    }
    const connected = this.authenticatedSocket();
    if (!connected?.attachment.epoch) return errorResponse("DEVICE_OFFLINE", "The device is offline.", 503);
    const target = `${localPath}${url.search}`;
    if (new TextEncoder().encode(target).byteLength > 8 * 1024) {
      return errorResponse("REQUEST_TARGET_TOO_LARGE", "Request target is too large.", 414);
    }
    const requestId = randomSafeInteger();
    let resolveResponse!: (response: Response) => void;
    const responsePromise = new Promise<Response>((resolve) => { resolveResponse = resolve; });
    const abortHandler = (): void => {
      const pending = this.pending.get(requestId);
      if (pending) this.failPending(pending, "CLIENT_CANCELLED", "Client cancelled the request.", 499, true);
    };
    const pending: PendingRequest = {
      requestId,
      epoch: connected.attachment.epoch,
      socket: connected.socket,
      responseStarted: false,
      completed: false,
      nextSequence: 0,
      receivedBytes: 0,
      creditOutstanding: 0,
      resolveResponse,
      startTimer: setTimeout(() => {
        const current = this.pending.get(requestId);
        if (current) this.failPending(current, "RESPONSE_START_TIMEOUT", "Device did not start the response in time.", 504, true);
      }, RESPONSE_START_TIMEOUT_MS),
      totalTimer: setTimeout(() => {
        const current = this.pending.get(requestId);
        if (current) this.failPending(current, "REQUEST_TIMEOUT", "Device did not finish the response in time.", 504, true);
      }, TOTAL_REQUEST_TIMEOUT_MS),
      abortHandler,
      abortSignal: request.signal,
    };

    const stream = new ReadableStream<Uint8Array>({
      start: (controller) => { pending.streamController = controller; },
      pull: () => { this.grantCredit(pending); },
      cancel: () => { this.failPending(pending, "CLIENT_CANCELLED", "Client cancelled the response.", 499, true); },
    }, {
      highWaterMark: MAX_UNCONSUMED_BYTES,
      size: (chunk) => chunk.byteLength,
    });
    this.pending.set(requestId, pending);
    request.signal.addEventListener("abort", abortHandler, { once: true });
    try {
      connected.socket.send(controlMessage("http_request", {
        requestId,
        epoch: pending.epoch,
        method: "GET",
        path: target,
        contentLength: 0,
        headers: Object.fromEntries(selectForwardRequestHeaders(request.headers)),
      }));
    } catch {
      this.failPending(pending, "DEVICE_OFFLINE", "The device is offline.", 503, false);
    }

    const response = await responsePromise;
    if (response.headers.get("x-legado-relay-stream") === "1") {
      const headers = new Headers(response.headers);
      headers.delete("x-legado-relay-stream");
      return new Response(stream, { status: response.status, statusText: response.statusText, headers });
    }
    return response;
  }

  private handleDeviceControlMessage(socket: WebSocket, attachment: DeviceAttachment, message: string): void {
    const control = parseControlMessage(message);
    if (!control || typeof control.type !== "string") {
      this.protocolFailure(socket, "Invalid control frame.");
      return;
    }
    if (control.type === "ping") {
      socket.send(controlMessage("pong", { now: Date.now() }));
      return;
    }
    if (control.type === "pong") return;
    const requestId = control.requestId;
    const epoch = control.epoch === undefined ? attachment.epoch : control.epoch;
    if (typeof requestId !== "number" || !Number.isSafeInteger(requestId) || typeof epoch !== "number" || !Number.isSafeInteger(epoch)) {
      this.protocolFailure(socket, "Missing request identity.");
      return;
    }
    const pending = this.pending.get(requestId);
    if (!pending || pending.socket !== socket || pending.epoch !== epoch || attachment.epoch !== epoch) return;

    if (control.type === "http_response") {
      if (pending.responseStarted || !Number.isSafeInteger(control.status)) {
        this.failPending(pending, "PROTOCOL_ERROR", "Invalid response metadata.", 502, true);
        return;
      }
      const status = Number(control.status);
      if (status < 200 || status > 599 || status === 101) {
        this.failPending(pending, "PROTOCOL_ERROR", "Invalid response status.", 502, true);
        return;
      }
      pending.responseStarted = true;
      clearTimeout(pending.startTimer);
      const headers = selectForwardResponseHeaders(control.headers);
      headers.set("x-legado-relay-stream", "1");
      pending.resolveResponse(new Response(null, { status, headers }));
      this.grantCredit(pending);
      return;
    }
    if (control.type === "http_response_end") {
      if (!pending.responseStarted) {
        this.failPending(pending, "PROTOCOL_ERROR", "Response ended before metadata.", 502, true);
        return;
      }
      this.completePending(pending);
      return;
    }
    if (control.type === "http_error") {
      this.failPending(pending, "DEVICE_ERROR", "The device failed to serve the request.", 502, false);
      return;
    }
    this.protocolFailure(socket, "Unsupported control frame.");
  }

  private handleDeviceBinaryMessage(socket: WebSocket, attachment: DeviceAttachment, frame: ArrayBuffer): void {
    const decoded = decodeBinaryChunk(frame);
    if (!decoded) {
      this.protocolFailure(socket, "Invalid binary frame.");
      return;
    }
    const { header, payload } = decoded;
    const pending = this.pending.get(header.requestId);
    if (!pending || pending.socket !== socket || pending.epoch !== attachment.epoch) return;
    if (!pending.responseStarted || header.seq !== pending.nextSequence || payload.byteLength > pending.creditOutstanding) {
      this.failPending(pending, "PROTOCOL_ERROR", "Invalid response chunk sequence or credit.", 502, true);
      return;
    }
    pending.nextSequence += 1;
    pending.creditOutstanding -= payload.byteLength;
    pending.receivedBytes += payload.byteLength;
    if (pending.receivedBytes > MAX_RESPONSE_BYTES) {
      this.failPending(pending, "RESPONSE_TOO_LARGE", "Device response exceeded the size limit.", 502, true);
      return;
    }
    pending.streamController?.enqueue(payload.slice());
  }

  private grantCredit(pending: PendingRequest): void {
    if (pending.completed || !pending.responseStarted || !pending.streamController) return;
    const desired = pending.streamController.desiredSize ?? 0;
    const available = Math.floor(Math.max(0, desired - pending.creditOutstanding));
    if (available <= 0) return;
    const bytes = Math.min(available, MAX_UNCONSUMED_BYTES);
    pending.creditOutstanding += bytes;
    try {
      pending.socket.send(controlMessage("credit", {
        requestId: pending.requestId,
        epoch: pending.epoch,
        bytes,
      }));
    } catch {
      this.failPending(pending, "DEVICE_OFFLINE", "The device is offline.", 503, false);
    }
  }

  private completePending(pending: PendingRequest): void {
    if (pending.completed) return;
    pending.completed = true;
    pending.streamController?.close();
    this.cleanupPending(pending);
  }

  private failPending(
    pending: PendingRequest,
    code: string,
    message: string,
    status: number,
    notifyDevice: boolean,
  ): void {
    if (pending.completed) return;
    pending.completed = true;
    if (notifyDevice && pending.socket.readyState === WebSocket.OPEN) {
      try {
        pending.socket.send(controlMessage("cancel", { requestId: pending.requestId, epoch: pending.epoch, code }));
      } catch {
        // The socket is already unusable; local cleanup still proceeds.
      }
    }
    if (pending.responseStarted) pending.streamController?.error(new Error(code));
    else pending.resolveResponse(errorResponse(code, message, status));
    this.cleanupPending(pending);
  }

  private cleanupPending(pending: PendingRequest): void {
    clearTimeout(pending.startTimer);
    clearTimeout(pending.totalTimer);
    pending.abortSignal.removeEventListener("abort", pending.abortHandler);
    this.pending.delete(pending.requestId);
  }

  private failRequestsForSocket(socket: WebSocket, code: string, message: string, status: number): void {
    for (const pending of [...this.pending.values()]) {
      if (pending.socket === socket) this.failPending(pending, code, message, status, false);
    }
  }

  private failAllPending(code: string, message: string, status: number): void {
    for (const pending of [...this.pending.values()]) this.failPending(pending, code, message, status, false);
  }

  private protocolFailure(socket: WebSocket, reason: string): void {
    this.failRequestsForSocket(socket, "PROTOCOL_ERROR", "The device sent an invalid response.", 502);
    socket.close(4002, reason.slice(0, 120));
  }

  private authenticatedSocket(): { socket: WebSocket; attachment: DeviceAttachment } | null {
    for (const socket of this.state.getWebSockets("device")) {
      const attachment = this.getAttachment(socket);
      if (attachment?.authenticated && attachment.epoch && socket.readyState === WebSocket.OPEN) {
        return { socket, attachment };
      }
    }
    return null;
  }

  private getAttachment(socket: WebSocket): DeviceAttachment | null {
    const value: unknown = socket.deserializeAttachment();
    if (typeof value !== "object" || value === null) return null;
    const candidate = value as Partial<DeviceAttachment>;
    if (
      candidate.kind !== "device" ||
      typeof candidate.deviceId !== "string" ||
      typeof candidate.nonce !== "string" ||
      typeof candidate.challengeExpiresAt !== "number" ||
      typeof candidate.authenticated !== "boolean" ||
      typeof candidate.epoch !== "number"
    ) return null;
    return candidate as DeviceAttachment;
  }

  private async scheduleChallengeAlarm(expiresAt: number): Promise<void> {
    const existing = await this.state.storage.getAlarm();
    if (existing === null || expiresAt < existing) await this.state.storage.setAlarm(expiresAt);
  }

  private deviceRow(): DeviceRow | null {
    return this.queryOne<DeviceRow>("SELECT device_id, verifier_envelope, disabled FROM device_credentials WHERE singleton = 1") ?? null;
  }

  private async loadVerifier(deviceId: string): Promise<Uint8Array | "missing" | "decrypt_error"> {
    const row = this.deviceRow();
    if (!row || row.disabled !== 0 || row.device_id !== deviceId) return "missing";
    const plaintext = await decryptVerifier(row.verifier_envelope, this.relayEnv.RELAY_CREDENTIAL_KEY || "", deviceId);
    if (!plaintext) return "decrypt_error";
    return fromBase64Url(plaintext) ?? "decrypt_error";
  }

  private queryOne<T>(query: string, ...bindings: SqlStorageValue[]): T | undefined {
    return this.sql.exec(query, ...bindings).toArray()[0] as unknown as T | undefined;
  }

  private pruneExpired(now: number): void {
    this.sql.exec("DELETE FROM sessions WHERE expires_at <= ?", now);
    this.sql.exec("DELETE FROM sessions WHERE share_id IN (SELECT id FROM shares WHERE revoked_at IS NOT NULL OR expires_at <= ?)", now);
    this.sql.exec("DELETE FROM shares WHERE revoked_at IS NOT NULL OR expires_at <= ?", now);
    this.sql.exec("DELETE FROM auth_nonces WHERE expires_at <= ?", now);
  }
}

function parseJsonObject(bytes: Uint8Array): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
    return value as Record<string, unknown>;
  } catch {
    return null;
  }
}

function unixSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: securityHeaders({ "content-type": "application/json; charset=utf-8" }),
  });
}

function bootstrapResponse(basePath: string): Response {
  const nonce = randomBase64Url(18);
  const escapedBasePath = JSON.stringify(basePath);
  const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Legado 公网访问</title>
  <style>body{font-family:system-ui,sans-serif;max-width:34rem;margin:12vh auto;padding:0 1.25rem;line-height:1.6;color:#202124}main{padding:1.5rem;border:1px solid #dadce0;border-radius:12px}p{overflow-wrap:anywhere}@media(prefers-color-scheme:dark){body{background:#111;color:#eee}main{border-color:#444}}</style>
</head>
<body><main><h1>Legado 公网访问</h1><p id="status">正在验证访问链接…</p></main>
<script nonce="${nonce}">
(() => {
  const basePath = ${escapedBasePath};
  const status = document.getElementById("status");
  const params = new URLSearchParams(location.hash.slice(1));
  const token = params.get("token");
  history.replaceState(null, "", location.pathname + location.search);
  if (!token) { status.textContent = "访问链接缺少凭据，或会话已经过期。请从 App 重新复制链接。"; return; }
  fetch(basePath + "/_session", {
    method: "POST",
    credentials: "same-origin",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token })
  }).then((response) => {
    if (!response.ok) throw new Error("unauthorized");
    location.reload();
  }).catch(() => { status.textContent = "访问凭据无效或已经过期。请从 App 重新创建链接。"; });
})();
</script></body></html>`;
  const headers = securityHeaders({ "content-type": "text/html; charset=utf-8" });
  headers.set("content-security-policy", `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`);
  return new Response(html, { headers });
}
