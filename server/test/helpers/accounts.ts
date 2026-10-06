import type { Component, AccountSession } from '../../src/services/AccountStore';
import crypto from 'node:crypto';

export function registration(seed: string = crypto.randomUUID()) {
  return { deviceIdentity: crypto.createHash('sha256').update(seed).digest('hex'), deviceModel: 'Test Model' };
}

/** Test names map to server-issued IDs; production peers always receive token-only auth. */
export class AccountFixture {
  private readonly sessions = new Map<string, AccountSession & { token: string; resident?: AccountSession & { token: string } }>();
  readonly ready: Promise<void>;
  readonly accountId: string;
  constructor(names: Array<{ name: string; component: Component }>, username = 'test-owner') {
    const { accountStore } = require('../../src/services/AccountStore') as typeof import('../../src/services/AccountStore');
    const password = 'private-fixture-password';
    this.accountId = accountStore.createAccount(username, password).accountId;
    this.ready = (async () => {
      for (const item of names) {
        if (this.sessions.has(item.name)) continue;
        const component = item.component === 'windows-resident' ? 'windows-inference' : item.component;
        const session = await accountStore.login({ username, password, component, ...registration(item.name) });
        accountStore.rename(session.account.accountId, session.device.deviceId, item.name);
        session.device.deviceName = item.name;
        if (session.resident) session.resident.device.deviceName = item.name;
        this.sessions.set(item.name, session);
      }
    })();
  }
  session(name: string, role?: string) { const session = this.sessions.get(name)!; return role === 'lifecycle' ? session.resident! : session; }
  id(name: string): string { return this.session(name).device.deviceId; }
  auth(name: string, role?: string, valid = true): object { return { type: 'auth', token: valid ? this.session(name, role).token : 'invalid-fixture-session-token-value' }; }
}
