export interface Env {
  DEVICE_OBJECT: DurableObjectNamespace;
  ASSETS: Fetcher;
  RELAY_ADMIN_TOKEN?: string;
  RELAY_CREDENTIAL_KEY?: string;
  RELAY_ROUTE_KEY?: string;
}

export type ShareScope = "read";

export interface ErrorBody {
  error: {
    code: string;
    message: string;
  };
}

export interface DeviceAttachment {
  kind: "device";
  deviceId: string;
  nonce: string;
  challengeExpiresAt: number;
  authenticated: boolean;
  epoch: number;
}

export interface DeviceCredentialRow {
  verifier_envelope: string;
  disabled: number;
}

export interface ShareRow {
  id: string;
  salt: string;
  token_hash: string;
  scope: ShareScope;
  expires_at: number;
  created_at: number;
  revoked_at: number | null;
}

export interface SessionRow {
  id: string;
  share_id: string;
  salt: string;
  token_hash: string;
  expires_at: number;
}
