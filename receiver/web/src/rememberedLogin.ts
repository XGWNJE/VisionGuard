export type RememberedLogin = { username: string; password: string };
type EncryptedLogin = { key: CryptoKey; iv: Uint8Array<ArrayBuffer>; ciphertext: ArrayBuffer };
const databaseName = 'visionguard-remembered-login';
let operation: Promise<unknown> = Promise.resolve();
function serial<T>(run: () => Promise<T>): Promise<T> {
  const result = operation.then(run, run); operation = result.then(() => undefined, () => undefined); return result;
}
export function parseRememberedLogin(value: unknown): RememberedLogin {
  if (!value || typeof value !== 'object') throw new Error('记住的账号密码无效');
  const record = value as Partial<RememberedLogin>;
  if (typeof record.username !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test(record.username)
    || typeof record.password !== 'string' || !record.password.length || record.password.length > 256) throw new Error('记住的账号密码无效');
  return { username: record.username, password: record.password };
}
async function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1);
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore('credentials');
    request.onsuccess = () => { if (blocked) request.result.close(); else { request.result.onversionchange = () => request.result.close(); resolve(request.result); } };
    request.onerror = () => reject(new Error('无法访问账号密码存储，请检查浏览器站点存储权限'));
    request.onblocked = () => { blocked = true; reject(new Error('账号密码存储被占用，请关闭其他控制台页面后重试')); };
  });
}
async function readRecord(db: IDBDatabase): Promise<EncryptedLogin | undefined> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('credentials', 'readonly'), request = transaction.objectStore('credentials').get('login');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error('无法读取已记住的账号密码'));
  });
}
async function writeRecord(db: IDBDatabase, value: EncryptedLogin | null): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('credentials', 'readwrite'), store = transaction.objectStore('credentials');
    transaction.oncomplete = () => resolve();
    transaction.onerror = transaction.onabort = () => reject(new Error('无法保存或清除账号密码，请检查浏览器站点存储权限'));
    if (value) store.put(value, 'login'); else store.delete('login');
  });
}
export function readRememberedLogin(): Promise<RememberedLogin | null> {
  return serial(async () => {
    const db = await openDatabase();
    try {
      const saved = await readRecord(db); if (!saved) return null;
      try {
        if (!crypto.subtle || saved.key.extractable || saved.key.algorithm.name !== 'AES-GCM' || saved.iv.byteLength !== 12 || saved.ciphertext.byteLength > 8192) return null;
        const clear = await crypto.subtle.decrypt({ name:'AES-GCM', iv:saved.iv, additionalData:new TextEncoder().encode(location.origin) }, saved.key, saved.ciphertext);
        return parseRememberedLogin(JSON.parse(new TextDecoder().decode(clear)));
      } catch { return null; }
    } finally { db.close(); }
  });
}
export function saveRememberedLogin(value: RememberedLogin): Promise<void> {
  const safe = parseRememberedLogin(value);
  return serial(async () => {
    if (!crypto.subtle) throw new Error('记住账号密码需要 HTTPS 或本机安全页面');
    const key = await crypto.subtle.generateKey({ name:'AES-GCM', length:256 }, false, ['encrypt','decrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt({ name:'AES-GCM', iv, additionalData:new TextEncoder().encode(location.origin) }, key, new TextEncoder().encode(JSON.stringify(safe)));
    const db = await openDatabase();
    try { await writeRecord(db, { key, iv, ciphertext }); } finally { db.close(); }
  });
}
export function clearRememberedLogin(): Promise<void> {
  return serial(async () => { const db = await openDatabase(); try { await writeRecord(db, null); } finally { db.close(); } });
}
