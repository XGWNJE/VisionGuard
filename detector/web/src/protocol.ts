/** 控制通道（/ws）与媒体通道（/media/ws）的消息契约。解析一律返回 undefined，不抛异常。 */

export type CameraResolution = '480p' | '720p';
export interface CameraSettings { cameraResolution: CameraResolution; cameraDimScreen: boolean; cameraHidePreview: boolean }
export interface StreamInfo { streamId: string; publisherDeviceId: string; targetDeviceId?: string; sourceId?: string; sourceName: string; isStreaming: boolean; stopReason?: string }
export interface TargetDevice { deviceId: string; deviceName: string; component: string; platform: string; capabilities?: string[]; online?: boolean }
export interface AccountIdentity { account: { accountId: string; username: string; isAdmin?: boolean }; device: { deviceId: string; deviceName: string; component: string; platform: string; role: string; nodeType: string } }
export interface LoginResult extends AccountIdentity { token: string; expiresAt: string; channel: string }

export interface ControlState {
  connected: boolean;
  status: string;
  stream?: StreamInfo;
  targets: TargetDevice[];
  targetsLoading: boolean;
  targetsLoadFailed: boolean;
  remoteSettings?: CameraSettings;
  session?: LoginResult;
}

const object = (value: unknown): value is Record<string, any> => !!value && typeof value === 'object' && !Array.isArray(value);

export function parseLogin(value: unknown): LoginResult | undefined {
  if (!object(value)) return undefined;
  const account = value.account, device = value.device;
  if (!object(account) || typeof account.accountId !== 'string' || typeof account.username !== 'string') return undefined;
  if (!object(device) || typeof device.deviceId !== 'string' || typeof device.deviceName !== 'string'
    || device.component !== 'web-camera' || device.platform !== 'web' || device.role !== 'detector' || device.nodeType !== 'visual') return undefined;
  if (typeof value.token !== 'string' || value.token.length < 32 || typeof value.expiresAt !== 'string'
    || typeof value.channel !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))) return undefined;
  return value as LoginResult;
}

export function parseStreams(value: unknown, publisherDeviceId: string): StreamInfo[] {
  if (!Array.isArray(value)) return [];
  const streams: StreamInfo[] = [];
  for (const item of value) {
    if (!object(item) || typeof item.streamId !== 'string' || item.publisherDeviceId !== publisherDeviceId) continue;
    streams.push({ streamId: item.streamId, publisherDeviceId: item.publisherDeviceId,
      targetDeviceId: typeof item.targetDeviceId === 'string' && item.targetDeviceId ? item.targetDeviceId : undefined,
      sourceId: typeof item.sourceId === 'string' && item.sourceId ? item.sourceId : undefined,
      sourceName: typeof item.sourceName === 'string' ? item.sourceName : '',
      isStreaming: item.isStreaming === true, stopReason: typeof item.stopReason === 'string' ? item.stopReason : undefined });
  }
  return streams;
}

/** Media-ready 是媒体通道开始发帧的唯一依据；字段不全时不认。 */
export function parseMediaReady(value: unknown): { sessionId: string; streamId: string; targetDeviceId?: string; sourceId?: string; targetSamplingRate: number } | undefined {
  if (!object(value)) return undefined;
  const { sessionId, streamId, targetSamplingRate } = value;
  if (typeof sessionId !== 'string' || !sessionId || typeof streamId !== 'string' || !streamId) return undefined;
  const rate = Number.isInteger(targetSamplingRate) ? Math.max(1, Math.min(5, targetSamplingRate)) : 5;
  return { sessionId, streamId, targetDeviceId: typeof value.targetDeviceId === 'string' ? value.targetDeviceId : undefined,
    sourceId: typeof value.sourceId === 'string' ? value.sourceId : undefined, targetSamplingRate: rate };
}

export function parseRate(value: unknown, sessionId: string, streamId: string): number | undefined {
  if (!object(value) || value.streamId !== streamId || value.sessionId !== sessionId || !Number.isInteger(value.targetSamplingRate)) return undefined;
  return Math.max(1, Math.min(5, value.targetSamplingRate));
}

export function parseFrameAck(value: unknown, sessionId: string, streamId: string): { sequence: number; accepted: boolean; dropReason?: string; relayDropped?: number } | undefined {
  if (!object(value) || value.streamId !== streamId || value.sessionId !== sessionId || !Number.isSafeInteger(value.sequence) || value.sequence < 0) return undefined;
  const stats = object(value.stats) ? value.stats : undefined;
  return { sequence: value.sequence, accepted: value.accepted === true,
    dropReason: typeof value.dropReason === 'string' ? value.dropReason : undefined,
    relayDropped: typeof stats?.dropped === 'number' ? stats.dropped : undefined };
}

/** 相机配置的本地状态必须完整，缺失字段按未上报处理。 */
export function parseRemoteSettings(value: unknown): CameraSettings | undefined {
  if (!object(value)) return undefined;
  if (value.cameraResolution !== '480p' && value.cameraResolution !== '720p') return undefined;
  if (typeof value.cameraDimScreen !== 'boolean' || typeof value.cameraHidePreview !== 'boolean') return undefined;
  return { cameraResolution: value.cameraResolution, cameraDimScreen: value.cameraDimScreen, cameraHidePreview: value.cameraHidePreview };
}

export type SetConfigResult = { ok: true; value: string } | { ok: false; reason: string };

/** 本机保存入口同时服务页面开关与控制台远程改配置，边界必须与 Android 相机一致。 */
export function validateSetConfig(key: string, raw: string): SetConfigResult {
  if (key === 'cameraResolution') return raw === '480p' || raw === '720p' ? { ok: true, value: raw } : { ok: false, reason: '规格只能选择 480p 或 720p' };
  if (key === 'cameraDimScreen' || key === 'cameraHidePreview') return raw === 'true' || raw === 'false' ? { ok: true, value: raw } : { ok: false, reason: '必须为布尔值' };
  return { ok: false, reason: '不支持的相机配置' };
}

export function boundedSize(width: number, height: number, longSide: number, shortSide: number): { width: number; height: number } {
  const ratio = Math.min(1, longSide / Math.max(width, height), shortSide / Math.min(width, height));
  return { width: Math.max(1, Math.round(width * ratio)), height: Math.max(1, Math.round(height * ratio)) };
}

/** 服务端只接受以 EOI 结尾的 JPEG；canvas 输出偶尔可能带尾随字节，发送前截断到 EOI。 */
export function trimJpeg(bytes: Uint8Array): Uint8Array | undefined {
  if (bytes.length < 12 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return undefined;
  for (let index = bytes.length - 2; index >= 2; index--) {
    if (bytes[index] === 0xff && bytes[index + 1] === 0xd9) return index === bytes.length - 2 ? bytes : bytes.subarray(0, index + 2);
  }
  return undefined;
}

export function jpegDimensions(bytes: Uint8Array): { width: number; height: number } | undefined {
  if (bytes.length < 12) return undefined;
  let position = 2;
  while (position + 3 < bytes.length) {
    if (bytes[position++] !== 0xff) return undefined;
    while (bytes[position] === 0xff) position++;
    const marker = bytes[position++];
    if (marker === 0xda || marker === 0xd9) return undefined;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (position + 2 > bytes.length) return undefined;
    const length = (bytes[position] << 8) | bytes[position + 1];
    if (length < 2 || position + length > bytes.length) return undefined;
    if (marker === 0xc0 || marker === 0xc1 || marker === 0xc2) {
      if (length < 8) return undefined;
      const height = (bytes[position + 3] << 8) | bytes[position + 4], width = (bytes[position + 5] << 8) | bytes[position + 6];
      return width > 0 && height > 0 ? { width, height } : undefined;
    }
    position += length;
  }
  return undefined;
}

export function websocketURL(origin: string, path: '/ws' | '/media/ws'): string {
  const url = new URL(path, origin);
  const parts = url.hostname.split('.').map(Number);
  const privateIPv4 = /^\d+\.\d+\.\d+\.\d+$/.test(url.hostname) && parts.every(n => n >= 0 && n <= 255)
    && (parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168));
  if (url.protocol === 'https:') url.protocol = 'wss:';
  else if (url.protocol === 'http:' && (['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || privateIPv4)) url.protocol = 'ws:';
  else throw new Error('请通过 HTTPS 打开相机页面');
  return url.href;
}

/** 浏览器只在安全上下文提供摄像头；明文私网地址会被浏览器直接拒绝且不给授权弹窗。 */
export function cameraContextIssue(location: { protocol: string; hostname: string }): string | undefined {
  if (location.protocol === 'https:') return undefined;
  if (['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname)) return undefined;
  return '浏览器只在 HTTPS（或本机 localhost）下提供摄像头，请改用正式服务的 HTTPS 地址打开本页面';
}

export function createRequestId(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
}

export const MEDIA_PATHS = { control: '/ws', media: '/media/ws' } as const;
