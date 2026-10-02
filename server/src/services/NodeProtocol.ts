import fs from 'node:fs';
import crypto from 'node:crypto';

export type NodeRole = 'detector' | 'console' | 'notifier' | 'lifecycle';
export type NodeType = 'visual' | 'sensor' | 'notification' | 'console' | 'resident';
export interface NodeIdentity {
  deviceId: string;
  role: NodeRole;
  nodeType: NodeType;
  platform: string;
}
interface Credential extends NodeIdentity { apiKey: string }

export const REALTIME_TTL_MS = 30_000;
export const DETECTION_STALL_MS = 15_000;

export function validIdentity(value: any): boolean {
  const types: Record<string, readonly string[]> = { detector: ['visual', 'sensor'], console: ['console'], notifier: ['notification'], lifecycle: ['resident'] };
  return !!value && typeof value.deviceId === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(value.deviceId)
    && typeof value.platform === 'string' && /^[a-z][a-z0-9-]{0,31}$/.test(value.platform)
    && typeof value.role === 'string' && typeof value.nodeType === 'string'
    && Object.prototype.hasOwnProperty.call(types, value.role) && types[value.role].includes(value.nodeType);
}

// Provisioned identities are authoritative; a node cannot grant itself another role or device ID.
export function loadCredentials(file: string | undefined): Credential[] {
  if (!file) return [];
  const values: unknown = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!Array.isArray(values) || values.length > 1000) throw new Error('Invalid node credentials file');
  const ids = new Set<string>();
  const tokens = new Set<string>();
  return values.map((value: any) => {
    if (!validIdentity(value) || typeof value.apiKey !== 'string' || value.apiKey.length < 16) throw new Error('Invalid node credential');
    const id = `${value.role}:${value.deviceId}`;
    if (ids.has(id) || tokens.has(value.apiKey)) throw new Error('Duplicate node credential');
    ids.add(id); tokens.add(value.apiKey);
    return value as Credential;
  });
}

const credentials = loadCredentials(process.env.VISIONGUARD_IDENTITIES_FILE);
// Test sessions are process-local and never change the provisioned identity file.
const testCredentials = new Map<string, { credential: Credential; expiresAt: number }>();
function currentCredentials(): Credential[] {
  const now = Date.now();
  for (const [id, entry] of testCredentials) if (entry.expiresAt <= now) testCredentials.delete(id);
  return [...credentials, ...Array.from(testCredentials.values(), entry => entry.credential)];
}
export function createTestConsoleCredential(): Credential | undefined {
  currentCredentials();
  if (testCredentials.size >= 512) return undefined;
  const credential: Credential = { deviceId: `test-web-${crypto.randomUUID()}`, role: 'console', nodeType: 'console', platform: 'web', apiKey: crypto.randomBytes(32).toString('hex') };
  testCredentials.set(credential.deviceId, { credential, expiresAt: Date.now() + 24 * 60 * 60 * 1000 });
  return credential;
}
export const hasNodeCredentials = credentials.length > 0;
export function registeredNodes(role: NodeRole): NodeIdentity[] {
  return credentials.filter(entry => entry.role === role).map(({ apiKey, ...identity }) => identity);
}
export function authenticateNode(value: any): NodeIdentity | undefined {
  if (!validIdentity(value) || typeof value.apiKey !== 'string') return undefined;
  const supplied = crypto.createHash('sha256').update(value.apiKey).digest();
  const entry = currentCredentials().find(item => crypto.timingSafeEqual(supplied, crypto.createHash('sha256').update(item.apiKey).digest()));
  if (!entry || entry.deviceId !== value.deviceId || entry.role !== value.role || entry.nodeType !== value.nodeType || entry.platform !== value.platform) return undefined;
  const { apiKey, ...identity } = entry;
  return identity;
}

export function authenticateToken(apiKey: unknown): NodeIdentity | undefined {
  if (typeof apiKey !== 'string') return undefined;
  const entry = currentCredentials().find(item => crypto.timingSafeEqual(crypto.createHash('sha256').update(apiKey).digest(), crypto.createHash('sha256').update(item.apiKey).digest()));
  if (!entry) return undefined;
  const { apiKey: ignored, ...identity } = entry;
  return identity;
}

export function clientType(identity: NodeIdentity): string {
  return identity.nodeType === 'visual' ? (identity.platform === 'windows' ? 'windows' : 'android-detector') : identity.nodeType;
}

export type EventKind = 'visual-detection' | 'sensor-detection' | 'connection-lost' | 'detection-interrupted';
export interface EventFields {
  eventKind: EventKind;
  expiresAt: string;
  summary: string;
}
export function validateEvent(value: any, identity: NodeIdentity, now = Date.now()): EventFields | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
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
  const allowed = identity.nodeType === 'visual' ? [...common, 'screenshot-on-demand', 'source-control', 'directml']
    : identity.nodeType === 'sensor' ? common : [];
  return Array.isArray(values) ? [...new Set(values.filter((v): v is string => typeof v === 'string' && allowed.includes(v)))] : [];
}
