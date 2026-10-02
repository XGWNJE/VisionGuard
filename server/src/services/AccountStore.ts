import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export type Component = 'web-console' | 'android-console' | 'android-notifier' | 'android-camera' | 'windows-inference' | 'windows-resident';
export interface Account { accountId: string; username: string }
export interface AccountDevice {
  accountId: string; deviceId: string; deviceName: string; role: 'detector' | 'console' | 'notifier' | 'lifecycle';
  nodeType: 'visual' | 'sensor' | 'notification' | 'console' | 'resident'; platform: string; component: Component;
}
export interface AccountSession { sessionId: string; account: Account; device: AccountDevice; expiresAt: string; parentId?: string }
interface StoredAccount extends Account { salt: string; passwordHash: string }
interface StoredSession { sessionId: string; tokenHash: string; accountId: string; deviceId: string; component: Component; expiresAt: string; parentId?: string }
interface Data { accounts: StoredAccount[]; devices: AccountDevice[]; sessions: StoredSession[] }
const SESSION_MS = 30 * 24 * 3600 * 1000;
const COMPONENTS: Record<Exclude<Component, 'windows-resident'>, Pick<AccountDevice, 'role' | 'nodeType' | 'platform'>> = {
  'web-console': { role: 'console', nodeType: 'console', platform: 'web' },
  'android-console': { role: 'console', nodeType: 'console', platform: 'android' },
  'android-notifier': { role: 'notifier', nodeType: 'notification', platform: 'android' },
  'android-camera': { role: 'detector', nodeType: 'visual', platform: 'android' },
  'windows-inference': { role: 'detector', nodeType: 'visual', platform: 'windows' },
};
const tokenHash = (token: string) => crypto.createHash('sha256').update(token).digest('hex');
export class AccountError extends Error { constructor(readonly status: number, message: string) { super(message); } }
export function validUsername(value: unknown): value is string { return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test(value); }
export function validPassword(value: unknown): value is string { return typeof value === 'string' && value.length >= 8 && value.length <= 256; }
export function validDeviceName(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0 && value.trim().length <= 64 && !/[\x00-\x1f]/.test(value); }
function hashPassword(password: string, salt: string): string { return crypto.scryptSync(password, salt, 64).toString('hex'); }
function verifyPassword(password: string, account: StoredAccount): Promise<boolean> {
  return new Promise((resolve, reject) => crypto.scrypt(password, account.salt, 64, (error, derived) => {
    if (error) { reject(error); return; }
    const expected = Buffer.from(account.passwordHash, 'hex');
    resolve(expected.length === derived.length && crypto.timingSafeEqual(expected, derived));
  }));
}

/** Single-process store. Every write is committed before the corresponding operation is acknowledged. */
export class AccountStore {
  private data: Data = { accounts: [], devices: [], sessions: [] };
  private readonly listeners = new Set<() => void>();
  constructor(readonly file: string) {
    if (fs.existsSync(file)) {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (!parsed || !Array.isArray(parsed.accounts) || !Array.isArray(parsed.devices) || !Array.isArray(parsed.sessions)) throw new Error('Invalid account store');
      this.data = parsed;
    }
  }
  onChange(listener: () => void): () => void { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  private commit(next: Data): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const temporary = `${this.file}.tmp`;
    try {
      const fd = fs.openSync(temporary, 'w', 0o600);
      try { fs.writeFileSync(fd, JSON.stringify(next), 'utf8'); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
      fs.renameSync(temporary, this.file);
    } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
    this.data = next;
    for (const listener of this.listeners) { try { listener(); } catch { console.error('[account] connection refresh failed after persistence'); } }
  }
  createAccount(username: string, password: string): Account {
    if (!validUsername(username) || !validPassword(password)) throw new AccountError(400, 'Invalid username or password');
    username = username.toLowerCase();
    if (this.data.accounts.some(account => account.username === username)) throw new AccountError(409, 'Account already exists');
    const salt = crypto.randomBytes(24).toString('hex');
    const account = { accountId: crypto.randomUUID(), username, salt, passwordHash: hashPassword(password, salt) };
    this.commit({ ...this.data, accounts: [...this.data.accounts, account] });
    return { accountId: account.accountId, username };
  }
  private issue(account: StoredAccount, device: AccountDevice, parentId?: string): { stored: StoredSession; result: AccountSession & { token: string } } {
    const token = crypto.randomBytes(32).toString('base64url');
    const stored = { sessionId: crypto.randomUUID(), tokenHash: tokenHash(token), accountId: account.accountId, deviceId: device.deviceId,
      component: device.component, expiresAt: new Date(Date.now() + SESSION_MS).toISOString(), ...(parentId ? { parentId } : {}) };
    return { stored, result: { sessionId: stored.sessionId, token, expiresAt: stored.expiresAt,
      account: { accountId: account.accountId, username: account.username }, device: { ...device }, ...(parentId ? { parentId } : {}) } };
  }
  async login(value: any): Promise<AccountSession & { token: string; resident?: AccountSession & { token: string } }> {
    if (!value || typeof value !== 'object' || !validUsername(value.username) || typeof value.password !== 'string' || value.password.length > 256
      || typeof value.component !== 'string' || !Object.prototype.hasOwnProperty.call(COMPONENTS, value.component)) throw new AccountError(400, 'Invalid login');
    const account = this.data.accounts.find(item => item.username === value.username.toLowerCase());
    // Use the same password work for unknown accounts so lookup is not a cheap username oracle.
    const verification = account ?? { accountId: '', username: '', salt: 'invalid-account', passwordHash: '00'.repeat(64) };
    if (!(await verifyPassword(value.password, verification)) || !account
      || this.data.accounts.find(item => item.accountId === account.accountId)?.passwordHash !== account.passwordHash) throw new AccountError(401, 'Invalid username or password');
    const component = value.component as Exclude<Component, 'windows-resident'>;
    let device = value.deviceId ? this.data.devices.find(item => item.deviceId === value.deviceId && item.component === component) : undefined;
    if (value.deviceId && (!device || device.accountId !== account.accountId)) throw new AccountError(403, 'Device is not owned by this account');
    if (value.deviceName !== undefined && !validDeviceName(value.deviceName)) throw new AccountError(400, 'Invalid device name');
    device = device ? { ...device, ...(value.deviceName ? { deviceName: value.deviceName.trim() } : {}) }
      : { accountId: account.accountId, deviceId: crypto.randomUUID(), deviceName: value.deviceName?.trim() || component, component, ...COMPONENTS[component] };
    const session = this.issue(account, device);
    const devices = this.data.devices.filter(item => !(item.deviceId === device!.deviceId && item.component === component));
    devices.push(device);
    let resident: ReturnType<AccountStore['issue']> | undefined;
    if (component === 'windows-inference') {
      const child: AccountDevice = { ...device, component: 'windows-resident', role: 'lifecycle', nodeType: 'resident' };
      devices.splice(0, devices.length, ...devices.filter(item => !(item.deviceId === child.deviceId && item.component === child.component)), child);
      resident = this.issue(account, child, session.stored.sessionId);
    }
    const replaced = new Set(this.data.sessions.filter(item => item.accountId === account.accountId && item.deviceId === device!.deviceId && item.component === component).map(item => item.sessionId));
    this.commit({ ...this.data, devices, sessions: [...this.data.sessions.filter(item => Date.parse(item.expiresAt) > Date.now() && !replaced.has(item.sessionId) && !replaced.has(item.parentId ?? '')), session.stored, ...(resident ? [resident.stored] : [])] });
    return { ...session.result, ...(resident ? { resident: resident.result } : {}) };
  }
  authenticate(token: unknown): AccountSession | undefined {
    if (typeof token !== 'string' || token.length < 32 || token.length > 256) return undefined;
    const session = this.data.sessions.find(item => item.tokenHash === tokenHash(token) && Date.parse(item.expiresAt) > Date.now());
    if (!session) return undefined;
    if (session.parentId && !this.data.sessions.some(item => item.sessionId === session.parentId && item.accountId === session.accountId && Date.parse(item.expiresAt) > Date.now())) return undefined;
    const account = this.data.accounts.find(item => item.accountId === session.accountId);
    const device = this.data.devices.find(item => item.accountId === session.accountId && item.deviceId === session.deviceId && item.component === session.component);
    if (!account || !device) return undefined;
    return { sessionId: session.sessionId, account: { accountId: account.accountId, username: account.username }, device: { ...device }, expiresAt: session.expiresAt, ...(session.parentId ? { parentId: session.parentId } : {}) };
  }
  refresh(session: AccountSession): AccountSession & { token: string; resident?: AccountSession & { token: string } } {
    const account = this.data.accounts.find(item => item.accountId === session.account.accountId)!;
    const issued = this.issue(account, session.device, session.parentId);
    const resident = session.device.component === 'windows-inference' ? this.issue(account, { ...session.device, component: 'windows-resident', role: 'lifecycle', nodeType: 'resident' }, issued.stored.sessionId) : undefined;
    this.commit({ ...this.data, sessions: [...this.data.sessions.filter(item => item.sessionId !== session.sessionId && item.parentId !== session.sessionId), issued.stored, ...(resident ? [resident.stored] : [])] });
    return { ...issued.result, ...(resident ? { resident: resident.result } : {}) };
  }
  logout(session: AccountSession): void {
    this.commit({ ...this.data, sessions: this.data.sessions.filter(item => item.sessionId !== session.sessionId && item.parentId !== session.sessionId) });
  }
  async changePassword(session: AccountSession, currentPassword: unknown, newPassword: unknown): Promise<void> {
    if (typeof currentPassword !== 'string' || currentPassword.length > 256 || !validPassword(newPassword)) throw new AccountError(400, 'Invalid password');
    const account = this.data.accounts.find(item => item.accountId === session.account.accountId)!;
    if (!(await verifyPassword(currentPassword, account)) || !this.data.sessions.some(item => item.sessionId === session.sessionId)
      || this.data.accounts.find(item => item.accountId === account.accountId)?.passwordHash !== account.passwordHash) throw new AccountError(401, 'Current password is incorrect');
    const salt = crypto.randomBytes(24).toString('hex');
    this.commit({ ...this.data, accounts: this.data.accounts.map(item => item.accountId === account.accountId ? { ...item, salt, passwordHash: hashPassword(newPassword, salt) } : item),
      sessions: this.data.sessions.filter(item => item.accountId !== account.accountId) });
  }
  devices(accountId: string): AccountDevice[] { return this.data.devices.filter(item => item.accountId === accountId && item.role !== 'lifecycle').map(item => ({ ...item })); }
  identities(accountId: string): AccountDevice[] { return this.data.devices.filter(item => item.accountId === accountId).map(item => ({ ...item })); }
  device(accountId: string, deviceId: string): AccountDevice | undefined { return this.devices(accountId).find(item => item.deviceId === deviceId); }
  rename(accountId: string, deviceId: string, deviceName: unknown): void {
    if (!this.device(accountId, deviceId)) throw new AccountError(404, 'Device not found');
    if (!validDeviceName(deviceName)) throw new AccountError(400, 'Invalid device name');
    this.commit({ ...this.data, devices: this.data.devices.map(item => item.accountId === accountId && item.deviceId === deviceId ? { ...item, deviceName: deviceName.trim() } : item) });
  }
  unbind(accountId: string, deviceId: string): void {
    if (!this.device(accountId, deviceId)) throw new AccountError(404, 'Device not found');
    this.commit({ ...this.data, devices: this.data.devices.filter(item => !(item.accountId === accountId && item.deviceId === deviceId)),
      sessions: this.data.sessions.filter(item => !(item.accountId === accountId && item.deviceId === deviceId)) });
  }
}

export const accountDataDir = path.resolve(process.env.VISIONGUARD_DATA_DIR || path.resolve(__dirname, '..', '..', 'data'));
export const accountStore = new AccountStore(path.join(accountDataDir, 'accounts.json'));
export function accountDirectory(accountId: string): string {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(accountId)) throw new Error('Invalid account identity');
  return path.join(accountDataDir, 'accounts', accountId);
}
