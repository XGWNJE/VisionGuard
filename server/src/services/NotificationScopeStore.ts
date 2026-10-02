import fs from 'node:fs';
import path from 'node:path';
import type { WsAlertPush } from '../models/types';

export interface NotificationTarget { deviceId: string; sourceId?: string }
export interface NotificationScope { mode: 'all' | 'selected'; targets: NotificationTarget[] }

export function parseNotificationScope(value: unknown): NotificationScope | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const scope = value as any;
  if (!['all', 'selected'].includes(scope.mode) || !Array.isArray(scope.targets) || scope.targets.length > 100) return undefined;
  if (scope.mode === 'all' && scope.targets.length) return undefined;
  const targets: NotificationTarget[] = [];
  const seen = new Set<string>();
  for (const target of scope.targets) {
    if (!target || typeof target !== 'object' || Array.isArray(target)
      || typeof target.deviceId !== 'string' || !/^[A-Za-z0-9._-]{1,128}$/.test(target.deviceId)
      || (target.sourceId !== undefined && (typeof target.sourceId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(target.sourceId)))) return undefined;
    const key = `${target.deviceId}:${target.sourceId ?? ''}`;
    if (seen.has(key)) continue;
    seen.add(key);
    targets.push({ deviceId: target.deviceId, ...(target.sourceId ? { sourceId: target.sourceId } : {}) });
  }
  return { mode: scope.mode, targets };
}

export function scopeAccepts(scope: NotificationScope, alert: Pick<WsAlertPush, 'deviceId' | 'sourceId' | 'eventKind'>): boolean {
  return scope.mode === 'all' || scope.targets.some(target => target.deviceId === alert.deviceId
    && (!target.sourceId || target.sourceId === alert.sourceId
      || (alert.eventKind === 'connection-lost' && !alert.sourceId)));
}

/** Save before updating memory: a failed write must leave the effective scope unchanged. */
export class NotificationScopeStore {
  private readonly scopes = new Map<string, NotificationScope>();
  constructor(private readonly file: string) {
    if (!fs.existsSync(file)) return;
    const values = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!values || typeof values !== 'object' || Array.isArray(values)) throw new Error('Invalid notification scopes');
    for (const [id, value] of Object.entries(values)) {
      const scope = parseNotificationScope(value);
      if (!/^[A-Za-z0-9._-]{1,128}$/.test(id) || !scope) throw new Error('Invalid notification scope');
      this.scopes.set(id, scope);
    }
  }
  get(id: string): NotificationScope {
    const scope = this.scopes.get(id) ?? { mode: 'all', targets: [] };
    return { mode: scope.mode, targets: scope.targets.map(target => ({ ...target })) };
  }
  set(id: string, scope: NotificationScope): void {
    if (!/^[A-Za-z0-9._-]{1,128}$/.test(id) || !parseNotificationScope(scope)) throw new Error('Invalid notification scope');
    const next = new Map(this.scopes);
    next.set(id, scope);
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp`;
    try {
      fs.writeFileSync(temporary, JSON.stringify(Object.fromEntries(next)), { mode: 0o600 });
      fs.renameSync(temporary, this.file);
    } finally {
      if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
    }
    this.scopes.set(id, { mode: scope.mode, targets: scope.targets.map(target => ({ ...target })) });
  }
}
