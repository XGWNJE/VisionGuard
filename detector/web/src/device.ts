/** 浏览器身份与本地相机设置：身份按源隔离保存，不按型号或 UA 做指纹。 */

import type { CameraSettings, CameraResolution } from './protocol';
import { validateSetConfig } from './protocol';

export const IDENTITY_DATABASE = 'visionguard-camera-identity';
export const IDENTITY_STORE = 'identity';
export const IDENTITY_KEY = 'device';
export const SETTINGS_KEY = 'visionguard.camera.settings';
export const MAX_DISPLAY_NAME_LENGTH = 64;

export interface ValueStore {
  read(key: string): string | null;
  write(key: string, value: string): void;
}

/** 身份存入口：IndexedDB 实现交给调用方，便于在没有 DOM 的环境里测试逻辑。 */
export interface IdentityPort {
  read(): Promise<string | undefined>;
  write(identity: string): Promise<void>;
}

export function randomIdentity(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, '0')).join('');
}

export async function readOrCreateDeviceIdentity(port: IdentityPort, create: () => string = randomIdentity): Promise<string> {
  const existing = await port.read();
  if (typeof existing === 'string' && /^[a-f0-9]{64}$/.test(existing)) return existing;
  const identity = create();
  await port.write(identity);
  return identity;
}

export function browserDeviceModel(platform: string | undefined): string {
  const bounded = (platform || '浏览器').replace(/[\x00-\x1f\x7f]/g, ' ').trim().slice(0, 48);
  return bounded.replace(/[\ud800-\udbff]$/, '') || '浏览器';
}

export function normalizeDisplayName(value: unknown): string {
  if (typeof value !== 'string') throw new Error('请输入有效名称');
  const name = value.trim();
  if (!name || name.length > MAX_DISPLAY_NAME_LENGTH || /[\x00-\x1f]/.test(value)) throw new Error('名称须为 1–64 个字符，不能包含换行或控制字符');
  return name;
}

export function defaultSettings(): CameraSettings {
  return { cameraResolution: '480p', cameraDimScreen: false, cameraHidePreview: false };
}

/** 本地设置既是页面开关的来源，也是控制台远程配置的落地处。 */
export class CameraSettingsStore {
  constructor(private readonly store: ValueStore | undefined, private fallback: CameraSettings = defaultSettings()) {}
  get current(): CameraSettings { return { ...this.fallback }; }
  load(): CameraSettings {
    try {
      const raw = this.store?.read(SETTINGS_KEY);
      const parsed = raw ? JSON.parse(raw) as Partial<CameraSettings> : undefined;
      this.fallback = {
        cameraResolution: parsed?.cameraResolution === '720p' ? '720p' : '480p',
        cameraDimScreen: parsed?.cameraDimScreen === true,
        cameraHidePreview: parsed?.cameraHidePreview === true,
      };
    } catch { this.fallback = defaultSettings(); }
    return this.current;
  }
  /** 返回保存失败的说明；成功返回 undefined。 */
  save(key: keyof CameraSettings, raw: string): string | undefined {
    const verified = validateSetConfig(key, raw);
    if (!verified.ok) return verified.reason;
    const next: CameraSettings = { ...this.fallback };
    if (key === 'cameraResolution') next.cameraResolution = verified.value as CameraResolution;
    else if (key === 'cameraDimScreen') next.cameraDimScreen = verified.value === 'true';
    else next.cameraHidePreview = verified.value === 'true';
    try {
      this.store?.write(SETTINGS_KEY, JSON.stringify(next));
    } catch { return '本机存储不可用，配置未能保存'; }
    this.fallback = next;
    return undefined;
  }
}

/** 页面入口的 IndexedDB 实现，读写事务串行化同源多标签页的首次登录。 */
export function indexedDbIdentityPort(database = IDENTITY_DATABASE): IdentityPort {
  function open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(database, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(IDENTITY_STORE);
      request.onerror = () => reject(new Error('无法保存浏览器设备身份，请允许站点存储'));
      request.onsuccess = () => resolve(request.result);
    });
  }
  return {
    async read() {
      const db = await open();
      return new Promise<string | undefined>((resolve, reject) => {
        const transaction = db.transaction(IDENTITY_STORE, 'readonly');
        const request = transaction.objectStore(IDENTITY_STORE).get(IDENTITY_KEY);
        request.onsuccess = () => resolve(typeof request.result === 'string' ? request.result : undefined);
        request.onerror = () => reject(new Error('无法读取浏览器设备身份'));
        transaction.oncomplete = () => db.close();
      });
    },
    async write(identity: string) {
      const db = await open();
      return new Promise<void>((resolve, reject) => {
        const transaction = db.transaction(IDENTITY_STORE, 'readwrite');
        transaction.objectStore(IDENTITY_STORE).put(identity, IDENTITY_KEY);
        transaction.oncomplete = () => { db.close(); resolve(); };
        transaction.onerror = transaction.onabort = () => { db.close(); reject(new Error('无法保存浏览器设备身份，请允许站点存储')); };
      });
    },
  };
}
