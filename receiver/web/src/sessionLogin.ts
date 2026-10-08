import { accountRequest, AccountRequestError, parseLogin, type Login } from './account';
type EncryptedSession = { key: CryptoKey; iv: Uint8Array<ArrayBuffer>; ciphertext: ArrayBuffer };
const databaseName = 'visionguard-console-session';
let operation: Promise<unknown> = Promise.resolve();
function serial<T>(run: () => Promise<T>): Promise<T> {
  const result = operation.then(run, run); operation = result.then(() => undefined, () => undefined); return result;
}
async function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore('sessions');
    request.onsuccess = () => { if (blocked) request.result.close(); else { request.result.onversionchange = () => request.result.close(); resolve(request.result); } };
    request.onerror = () => reject(new Error('无法访问登录会话存储，请检查浏览器站点存储权限'));
    request.onblocked = () => { blocked = true; reject(new Error('登录会话存储被占用，请关闭其他控制台页面后重试')); };
  });
}
async function readRecord(db: IDBDatabase): Promise<EncryptedSession | undefined> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('sessions', 'readonly'), request = transaction.objectStore('sessions').get('session');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error('无法读取保存的登录会话'));
  });
}
async function writeRecord(db: IDBDatabase, value: EncryptedSession | null): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('sessions', 'readwrite'), store = transaction.objectStore('sessions');
    transaction.oncomplete = () => resolve();
    transaction.onerror = transaction.onabort = () => reject(new Error('无法保存或清除登录会话，请检查浏览器站点存储权限'));
    if (value) store.put(value, 'session'); else store.delete('session');
  });
}
export function readSessionLogin(): Promise<Login | null> {
  return serial(async () => {
    const db = await openDatabase();
    try {
      const saved = await readRecord(db); if (!saved) return null;
      try {
        if (!crypto.subtle || saved.key.extractable || saved.key.algorithm.name !== 'AES-GCM' || saved.iv.byteLength !== 12 || saved.ciphertext.byteLength > 8192) return null;
        const clear = await crypto.subtle.decrypt({ name:'AES-GCM', iv:saved.iv, additionalData:new TextEncoder().encode(location.origin) }, saved.key, saved.ciphertext);
        const login = parseLogin(JSON.parse(new TextDecoder().decode(clear)));
        if (Date.parse(login.expiresAt) <= Date.now()) { await writeRecord(db, null); return null; }
        return login;
      } catch { return null; }
    } finally { db.close(); }
  });
}
export function saveSessionLogin(value: Login): Promise<void> {
  const safe = parseLogin(value);
  return serial(async () => {
    if (!crypto.subtle) throw new Error('保持登录需要 HTTPS 或本机安全页面');
    const key = await crypto.subtle.generateKey({ name:'AES-GCM', length:256 }, false, ['encrypt','decrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt({ name:'AES-GCM', iv, additionalData:new TextEncoder().encode(location.origin) }, key, new TextEncoder().encode(JSON.stringify(safe)));
    const db = await openDatabase();
    try { await writeRecord(db, { key, iv, ciphertext }); } finally { db.close(); }
  });
}
export function clearSessionLogin(): Promise<void> {
  return serial(async () => { const db = await openDatabase(); try { await writeRecord(db, null); } finally { db.close(); } });
}

/** Restore the same credential; transport authentication remains required after a temporary outage. */
export async function restoreSessionLogin(signal?: AbortSignal): Promise<Login | null> {
  const saved = await readSessionLogin();
  if (!saved) return null;
  try {
    const current = parseLogin({ ...await accountRequest<Omit<Login, 'token'>>('/api/account/session', saved.token, undefined, undefined, signal), token: saved.token });
    if (Date.parse(current.expiresAt) <= Date.now()) { await clearSessionLogin(); return null; }
    return current;
  } catch (error) {
    if (error instanceof AccountRequestError && error.status === 401) { await clearSessionLogin(); return null; }
    return Date.parse(saved.expiresAt) > Date.now() ? saved : null;
  }
}
