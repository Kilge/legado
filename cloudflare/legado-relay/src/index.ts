import { constantTimeEqual, isCredentialKey, publicRouteTag } from "./crypto";
import { DeviceObject } from "./device-object";
import { errorResponse, isValidDeviceId, parseDeviceHandle, parsePublicRoute } from "./http";
import type { Env } from "./types";

export { DeviceObject };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/health") {
        return new Response(JSON.stringify({ ok: true, protocolVersion: 1 }), {
          headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
        });
      }

      const adminMatch = /^\/v1\/admin\/devices\/([^/]+)$/u.exec(url.pathname);
      if (adminMatch) {
        if (!isValidDeviceId(adminMatch[1])) return errorResponse("INVALID_DEVICE_ID", "Invalid device ID.", 400);
        if (!env.RELAY_ADMIN_TOKEN || env.RELAY_ADMIN_TOKEN.length < 32) {
          return errorResponse("ADMIN_NOT_CONFIGURED", "Relay administration is not configured.", 503);
        }
        if (!isCredentialKey(env.RELAY_CREDENTIAL_KEY)) {
          return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
        }
        if (!isCredentialKey(env.RELAY_ROUTE_KEY)) {
          return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
        }
        const provided = bearerToken(request);
        if (!provided || !constantTimeEqual(provided, env.RELAY_ADMIN_TOKEN)) {
          return errorResponse("UNAUTHORIZED", "Administration authentication failed.", 401);
        }
        const forwarded = new Request(request);
        forwarded.headers.delete("authorization");
        forwarded.headers.set("x-relay-internal-admin", "1");
        return env.DEVICE_OBJECT.getByName(adminMatch[1]).fetch(forwarded);
      }

      if (url.pathname === "/v1/device/connect") {
        if (!isCredentialKey(env.RELAY_CREDENTIAL_KEY)) {
          return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
        }
        const handle = await authenticateDeviceHandle(url.searchParams.get("deviceHandle") || "", env);
        if (url.searchParams.size !== 1 || !handle) return errorResponse("NOT_FOUND", "Route not found.", 404);
        const forwarded = new Request(request);
        forwarded.headers.set("x-relay-device-id", handle.deviceId);
        forwarded.headers.set("x-legado-device-id", handle.deviceId);
        return env.DEVICE_OBJECT.getByName(handle.deviceId).fetch(forwarded);
      }

      if (url.pathname.startsWith("/v1/device/")) {
        if (!isCredentialKey(env.RELAY_CREDENTIAL_KEY)) {
          return errorResponse("CREDENTIAL_KEY_NOT_CONFIGURED", "Relay credential encryption is not configured.", 503);
        }
        const handle = await authenticateDeviceHandle(request.headers.get("x-legado-device-handle") || "", env);
        if (!handle) return errorResponse("NOT_FOUND", "Route not found.", 404);
        const forwarded = new Request(request);
        forwarded.headers.set("x-legado-device-id", handle.deviceId);
        forwarded.headers.set("x-relay-device-id", handle.deviceId);
        return env.DEVICE_OBJECT.getByName(handle.deviceId).fetch(forwarded);
      }

      const publicRoute = parsePublicRoute(url.pathname);
      if (publicRoute) {
        if (!isCredentialKey(env.RELAY_ROUTE_KEY)) {
          return errorResponse("ROUTE_KEY_NOT_CONFIGURED", "Public route signing is not configured.", 503);
        }
        const expectedTag = await publicRouteTag(env.RELAY_ROUTE_KEY, publicRoute.deviceId);
        if (!expectedTag || !constantTimeEqual(expectedTag, publicRoute.routeTag)) {
          return errorResponse("NOT_FOUND", "Route not found.", 404);
        }
        return env.DEVICE_OBJECT.getByName(publicRoute.deviceId).fetch(request);
      }
      if (url.pathname.startsWith("/d/")) return errorResponse("INVALID_DEVICE_ID", "Invalid public relay path.", 400);
      return errorResponse("NOT_FOUND", "Route not found.", 404);
    } catch (error) {
      void error;
      console.error("relay request failed");
      return errorResponse("INTERNAL_ERROR", "Internal relay error.", 500);
    }
  },
} satisfies ExportedHandler<Env>;

function bearerToken(request: Request): string | null {
  const authorization = request.headers.get("authorization");
  return authorization?.startsWith("Bearer ") ? authorization.slice(7).trim() : null;
}

async function authenticateDeviceHandle(handleValue: string, env: Env): Promise<ReturnType<typeof parseDeviceHandle>> {
  if (!isCredentialKey(env.RELAY_ROUTE_KEY)) return null;
  const handle = parseDeviceHandle(handleValue);
  if (!handle) return null;
  const expectedTag = await publicRouteTag(env.RELAY_ROUTE_KEY, handle.deviceId);
  return expectedTag && constantTimeEqual(expectedTag, handle.routeTag) ? handle : null;
}
