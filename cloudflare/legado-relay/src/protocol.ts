import { MAX_CHUNK_BYTES, MAX_CONTROL_BYTES, PROTOCOL_VERSION } from "./constants";

export interface BinaryChunkHeader {
  v: 1;
  type: "http_response_chunk";
  requestId: number;
  seq: number;
}

export interface DecodedBinaryChunk {
  header: BinaryChunkHeader;
  payload: Uint8Array;
}

export function parseControlMessage(message: string): Record<string, unknown> | null {
  if (new TextEncoder().encode(message).byteLength > MAX_CONTROL_BYTES) return null;
  try {
    const parsed: unknown = JSON.parse(message);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function decodeBinaryChunk(frame: ArrayBuffer): DecodedBinaryChunk | null {
  const headerBytes = 24;
  if (frame.byteLength < headerBytes || frame.byteLength > headerBytes + MAX_CHUNK_BYTES) return null;
  const view = new DataView(frame);
  if (view.getUint32(0, false) !== 0x4c475231 || view.getUint8(4) !== PROTOCOL_VERSION || view.getUint8(5) !== 2) return null;
  const flags = view.getUint16(6, false);
  if (flags !== 0) return null;
  const requestIdBig = view.getBigInt64(8, false);
  if (requestIdBig <= 0n || requestIdBig > BigInt(Number.MAX_SAFE_INTEGER)) return null;
  const seq = view.getInt32(16, false);
  const payloadLength = view.getInt32(20, false);
  if (seq < 0 || payloadLength < 0 || payloadLength > MAX_CHUNK_BYTES || frame.byteLength !== headerBytes + payloadLength) return null;
  return {
    header: { v: 1, type: "http_response_chunk", requestId: Number(requestIdBig), seq },
    payload: new Uint8Array(frame, headerBytes),
  };
}

export function controlMessage(type: string, fields: Record<string, unknown> = {}): string {
  return JSON.stringify({ v: PROTOCOL_VERSION, type, ...fields });
}
