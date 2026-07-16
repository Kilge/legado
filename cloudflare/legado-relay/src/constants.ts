export const PROTOCOL_VERSION = 1;
export const MIN_PROTOCOL_VERSION = 1;

export const MAX_CONTROL_BYTES = 32 * 1024;
export const MAX_CHUNK_BYTES = 32 * 1024;
export const MAX_BINARY_FRAME_BYTES = MAX_CONTROL_BYTES + MAX_CHUNK_BYTES + 4;
export const MAX_REQUEST_TARGET_BYTES = 8 * 1024;
export const MAX_FORWARDED_HEADER_BYTES = 16 * 1024;
export const MAX_CONTROL_BODY_BYTES = 16 * 1024;
export const MAX_SESSION_BODY_BYTES = 4 * 1024;
export const MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
export const MAX_UNCONSUMED_BYTES = 512 * 1024;
export const MAX_CONCURRENT_REQUESTS = 4;
export const RESPONSE_START_TIMEOUT_MS = 15_000;
export const TOTAL_REQUEST_TIMEOUT_MS = 60_000;
export const CHALLENGE_TTL_MS = 30_000;
export const CONTROL_CLOCK_SKEW_SECONDS = 120;
export const SESSION_TTL_SECONDS = 15 * 60;
export const MAX_SHARE_TTL_SECONDS = 30 * 24 * 60 * 60;
export const DEFAULT_SHARE_TTL_SECONDS = 24 * 60 * 60;
export const MAX_ACTIVE_SHARES = 64;
export const MAX_SESSIONS_PER_SHARE = 16;
export const MAX_DEVICE_SOCKETS = 4;

export const DEVICE_ID_PATTERN = /^[A-Za-z0-9_-]{22,64}$/;
export const TOKEN_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/;

export const SESSION_COOKIE_NAME = "__Secure-legado_relay_session";
