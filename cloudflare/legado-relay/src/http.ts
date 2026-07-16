import {
  DEVICE_ID_PATTERN,
  MAX_CONTROL_BODY_BYTES,
  MAX_FORWARDED_HEADER_BYTES,
  MAX_REQUEST_TARGET_BYTES,
} from "./constants";
import type { ErrorBody } from "./types";

const API_READ_PATHS = new Set([
  "/getBookshelf",
  "/getChapterList",
  "/getBookContent",
  "/getReadConfig",
]);

const STATIC_EXACT_PATHS = new Set(["/", "/index.html", "/favicon.ico"]);
const STATIC_PREFIXES = ["/assets/"];
const DANGEROUS_ENCODED_PATH = /%(?:00|2e|2f|5c)/iu;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/u;

const REQUEST_HEADER_ALLOWLIST = new Set([
  "accept",
  "accept-language",
  "if-modified-since",
  "if-none-match",
  "range",
  "user-agent",
]);

const RESPONSE_HEADER_ALLOWLIST = new Set([
  "accept-ranges",
  "content-disposition",
  "content-range",
  "content-type",
  "etag",
  "last-modified",
]);

export function errorResponse(code: string, message: string, status: number): Response {
  const body: ErrorBody = { error: { code, message } };
  return new Response(JSON.stringify(body), {
    status,
    headers: securityHeaders({ "content-type": "application/json; charset=utf-8" }),
  });
}

export function securityHeaders(initial?: HeadersInit): Headers {
  const headers = new Headers(initial);
  headers.set("cache-control", "private, no-store");
  headers.set("x-content-type-options", "nosniff");
  headers.set("referrer-policy", "no-referrer");
  headers.set("permissions-policy", "camera=(), microphone=(), geolocation=()");
  return headers;
}

export function isValidDeviceId(deviceId: string): boolean {
  return DEVICE_ID_PATTERN.test(deviceId);
}

export interface PublicRoute {
  deviceId: string;
  publicHandle: string;
  routeTag: string;
  deviceBasePath: string;
  localPath: string;
}

export interface DeviceHandle {
  deviceId: string;
  publicHandle: string;
  routeTag: string;
}

export function parseDeviceHandle(publicHandle: string): DeviceHandle | null {
  const separator = publicHandle.indexOf(".");
  if (separator <= 0 || separator !== publicHandle.lastIndexOf(".")) return null;
  const deviceId = publicHandle.slice(0, separator);
  const routeTag = publicHandle.slice(separator + 1);
  if (!isValidDeviceId(deviceId) || !/^[A-Za-z0-9_-]{22}$/u.test(routeTag)) return null;
  return { deviceId, publicHandle, routeTag };
}

export function parsePublicRoute(pathname: string): PublicRoute | null {
  const match = /^\/d\/([^/]+)(\/.*)?$/u.exec(pathname);
  if (!match) return null;
  const handle = parseDeviceHandle(match[1]);
  if (!handle) return null;
  const localPath = match[2] || "";
  return { ...handle, deviceBasePath: `/d/${handle.publicHandle}`, localPath };
}

export function isSafeLocalPath(path: string): boolean {
  if (!path.startsWith("/") || path.includes("\\") || path.includes("//")) return false;
  if (CONTROL_CHARACTERS.test(path) || DANGEROUS_ENCODED_PATH.test(path)) return false;
  return new TextEncoder().encode(path).byteLength <= MAX_REQUEST_TARGET_BYTES;
}

export function isReadOnlyRoute(method: string, path: string): boolean {
  if (method !== "GET" || !isSafeLocalPath(path)) return false;
  return API_READ_PATHS.has(path) || STATIC_EXACT_PATHS.has(path) || STATIC_PREFIXES.some((prefix) => path.startsWith(prefix));
}

export function isStaticAssetRoute(path: string): boolean {
  return STATIC_EXACT_PATHS.has(path) || STATIC_PREFIXES.some((prefix) => path.startsWith(prefix));
}

export function isKnownReadPath(path: string): boolean {
  if (!isSafeLocalPath(path)) return false;
  return API_READ_PATHS.has(path) || STATIC_EXACT_PATHS.has(path) || STATIC_PREFIXES.some((prefix) => path.startsWith(prefix));
}

export function selectForwardRequestHeaders(headers: Headers): Array<[string, string]> {
  const selected: Array<[string, string]> = [];
  let totalBytes = 0;
  for (const [rawName, rawValue] of headers) {
    const name = rawName.toLowerCase();
    if (!REQUEST_HEADER_ALLOWLIST.has(name)) continue;
    const value = rawValue.trim();
    if (CONTROL_CHARACTERS.test(value)) continue;
    totalBytes += name.length + value.length + 4;
    if (totalBytes > MAX_FORWARDED_HEADER_BYTES) break;
    selected.push([name, value]);
  }
  return selected;
}

export function selectForwardResponseHeaders(entries: unknown): Headers {
  const headers = securityHeaders();
  const normalized: Array<[unknown, unknown]> = Array.isArray(entries)
    ? entries
    : typeof entries === "object" && entries !== null
      ? Object.entries(entries as Record<string, unknown>)
      : [];
  let totalBytes = 0;
  for (const entry of normalized) {
    if (!Array.isArray(entry) || entry.length !== 2) continue;
    const [rawName, rawValue] = entry;
    if (typeof rawName !== "string" || typeof rawValue !== "string") continue;
    const name = rawName.toLowerCase();
    if (!RESPONSE_HEADER_ALLOWLIST.has(name) || CONTROL_CHARACTERS.test(rawValue)) continue;
    totalBytes += name.length + rawValue.length + 4;
    if (totalBytes > MAX_FORWARDED_HEADER_BYTES) break;
    headers.set(name, rawValue);
  }
  return headers;
}

export async function readBoundedBody(request: Request, maxBytes = MAX_CONTROL_BODY_BYTES): Promise<Uint8Array> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    const length = Number.parseInt(declaredLength, 10);
    if (!Number.isFinite(length) || length < 0 || length > maxBytes) throw new RangeError("body too large");
  }
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel("body too large");
        throw new RangeError("body too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

export function parseCookie(headers: Headers, name: string): string | null {
  const cookie = headers.get("cookie");
  if (!cookie) return null;
  for (const part of cookie.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    if (part.slice(0, separator).trim() === name) return part.slice(separator + 1).trim();
  }
  return null;
}

export function requestBearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  if (authorization?.startsWith("Bearer ")) return authorization.slice(7).trim();
  return request.headers.get("x-legado-share-token")?.trim() || null;
}
