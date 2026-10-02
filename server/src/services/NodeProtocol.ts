import { accountStore, type AccountDevice } from './AccountStore';

export type NodeRole = 'detector' | 'console' | 'notifier' | 'lifecycle';
export type NodeType = 'visual' | 'sensor' | 'notification' | 'console' | 'resident';
export type NodeIdentity = AccountDevice;

export const REALTIME_TTL_MS = 30_000;
export const DETECTION_STALL_MS = 15_000;

export function validIdentity(value: any): boolean {
  const types: Record<string, readonly string[]> = { detector: ['visual', 'sensor'], console: ['console'], notifier: ['notification'], lifecycle: ['resident'] };
  return !!value && typeof value.deviceId === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(value.deviceId)
    && typeof value.platform === 'string' && /^[a-z][a-z0-9-]{0,31}$/.test(value.platform)
    && typeof value.role === 'string' && typeof value.nodeType === 'string'
    && Object.prototype.hasOwnProperty.call(types, value.role) && types[value.role].includes(value.nodeType);
}

export function registeredNodes(accountId: string, role: NodeRole): NodeIdentity[] {
  return accountStore.identities(accountId).filter(entry => entry.role === role);
}
export function authenticateNode(value: any): NodeIdentity | undefined {
  return value && typeof value === 'object' ? accountStore.authenticate(value.token)?.device : undefined;
}
export function authenticateToken(token: unknown): NodeIdentity | undefined { return accountStore.authenticate(token)?.device; }

export function clientType(identity: NodeIdentity): string {
  return identity.nodeType === 'visual' ? (identity.platform === 'windows' ? 'windows' : 'android-camera') : identity.nodeType;
}

export type EventKind = 'visual-detection' | 'sensor-detection' | 'connection-lost' | 'detection-interrupted';
export interface EventFields {
  eventKind: EventKind;
  expiresAt: string;
  summary: string;
}
export function validateEvent(value: any, identity: NodeIdentity, now = Date.now()): EventFields | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  if (identity.component === 'android-camera') return undefined;
  const kind = identity.nodeType === 'visual' ? 'visual-detection' : 'sensor-detection';
  const timestamp = Date.parse(value.timestamp);
  const expires = Date.parse(value.expiresAt);
  if (value.eventKind !== kind || !Number.isFinite(timestamp) || !Number.isFinite(expires)
    || timestamp > now + 5000 || expires <= now || expires <= timestamp || expires - timestamp > REALTIME_TTL_MS) return undefined;
  if (typeof value.summary !== 'string' || value.summary.length > 256) return undefined;
  if (kind === 'visual-detection' && (typeof value.sourceId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value.sourceId))) return undefined;
  if (kind === 'sensor-detection' && (value.sourceId !== undefined || value.sourceName !== undefined || value.imageBase64 !== undefined)) return undefined;
  if (kind === 'sensor-detection' && (!Array.isArray(value.detections) || value.detections.length !== 0)) return undefined;
  return { eventKind: kind, expiresAt: new Date(expires).toISOString(), summary: value.summary };
}

// Configuration remains the existing small set. New hardware-specific settings belong to its implementation stage.
export function allowedCapabilities(identity: NodeIdentity, values: unknown): string[] {
  const common = ['monitor-control', 'config-control', 'request-correlation'];
  const allowed = identity.component === 'android-camera' ? ['video-publish', 'request-correlation']
    : identity.nodeType === 'visual' ? [...common, 'screenshot-on-demand', 'source-control', 'directml', 'video-subscribe', 'visual-inference']
    : identity.nodeType === 'sensor' ? common : [];
  return Array.isArray(values) ? [...new Set(values.filter((v): v is string => typeof v === 'string' && allowed.includes(v)))] : [];
}
