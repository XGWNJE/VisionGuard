import type { Identity } from './protocol';

export type Login = {
  token: string;
  expiresAt: string;
  channel: string;
  account: { accountId: string; username: string; isAdmin?: boolean };
  device: Identity & { deviceName: string; component: string };
};

export const MAX_DISPLAY_NAME_LENGTH = 64;
/** Browsers expose a profile/origin identity, not a physical hardware identity. Never fingerprint by model or user agent. */
export async function browserDeviceIdentity(): Promise<string> {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open('visionguard-device-identity', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('identity');
    open.onerror = () => reject(new Error('无法保存浏览器设备身份，请允许站点存储'));
    open.onsuccess = () => {
      const db = open.result;
      // A read/write transaction serializes first login across tabs, including private-LAN HTTP contexts.
      const transaction = db.transaction('identity', 'readwrite');
      const store = transaction.objectStore('identity'), request = store.get('device');
      let identity = '';
      request.onsuccess = () => {
        identity = typeof request.result === 'string' && /^[a-f0-9]{64}$/.test(request.result) ? request.result
          : Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2,'0')).join('');
        store.put(identity, 'device');
      };
      transaction.oncomplete = () => { db.close(); resolve(identity); };
      transaction.onerror = transaction.onabort = () => { db.close(); reject(new Error('无法保存浏览器设备身份，请允许站点存储')); };
    };
  });
}
export function browserDeviceModel(): string {
  const model = navigator.platform || '浏览器';
  const bounded = model.replace(/[\x00-\x1f\x7f]/g, ' ').trim().slice(0,48);
  return bounded.replace(/[\ud800-\udbff]$/, '') || '浏览器';
}
export function normalizeDisplayName(value: unknown): string {
  if (typeof value !== 'string') throw new Error('请输入有效名称');
  const name = value.trim();
  if (!name || name.length > MAX_DISPLAY_NAME_LENGTH || /[\x00-\x1f]/.test(value)) throw new Error('名称须为 1–64 个字符，不能包含换行或控制字符');
  return name;
}

export class AccountRequestError extends Error {
  readonly status: number;
  constructor(status: number, message: string) { super(message); this.status = status; }
}

export function parseLogin(value: unknown): Login {
  if (!value || typeof value !== 'object') throw new Error('登录响应无效');
  const item = value as Partial<Login>;
  if (typeof item.token !== 'string' || item.token.length < 16 || typeof item.channel !== 'string'
    || typeof item.expiresAt !== 'string' || !Number.isFinite(Date.parse(item.expiresAt))
    || !item.account || typeof item.account.accountId !== 'string' || typeof item.account.username !== 'string'
    || !item.device || typeof item.device.deviceId !== 'string' || typeof item.device.deviceName !== 'string'
    || item.device.role !== 'console' || item.device.nodeType !== 'console' || item.device.platform !== 'web'
    || item.device.component !== 'web-console') throw new Error('登录响应无效');
  return item as Login;
}

export async function accountRequest<T>(path: string, token?: string, body?: unknown, method?: string): Promise<T> {
  if (method === 'PATCH' && path.startsWith('/api/devices/') && body && typeof body === 'object' && 'deviceName' in body) {
    body = { ...body, deviceName: normalizeDisplayName((body as {deviceName: unknown}).deviceName) };
  }
  const response = await fetch(path, {
    method: method ?? (body === undefined ? 'GET' : 'POST'),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    cache: 'no-store', credentials: 'omit',
  }).catch((error: unknown) => {
    if (error instanceof TypeError) throw new Error('连接失败，请检查网络后重试');
    throw error;
  });
  let data: { ok?: boolean; error?: string };
  try { data = await response.json(); } catch { throw new Error('服务暂不可用'); }
  if (!response.ok || data.ok === false) {
    const unauthorizedMessage = path === '/api/account/login' ? '账号或密码不正确'
      : path === '/api/account/password' && data.error === 'Current password is incorrect' ? '当前密码不正确'
      : '登录已失效，请重新登录';
    const messages: Record<number,string> = { 401:unauthorizedMessage, 403:'没有权限执行此操作', 404:'账号、设备或关联目标不存在', 409:data.error === 'The last active administrator must be retained' ? '必须保留至少一个启用的管理员' : '账号已存在', 429:'尝试过于频繁，请稍后重试' };
    throw new AccountRequestError(response.status, messages[response.status] || (response.status >= 500 ? '服务暂不可用' : '请检查填写内容后重试'));
  }
  return data as T;
}

/** Detach the old socket before rotation; revoke a replacement that arrived after local logout. */
export async function rotateLogin(login: Login, adopt: (replacement: Login) => boolean, suspend: () => void): Promise<Login | null> {
  suspend();
  const replacement = parseLogin(await accountRequest('/api/account/refresh', login.token, {}));
  if (adopt(replacement)) return replacement;
  await accountRequest('/api/account/logout', replacement.token, {});
  return null;
}
