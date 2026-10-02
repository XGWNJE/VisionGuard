export type Identity = { deviceId: string; nodeType: string; platform: string; role: string; component?: string; deviceName?: string };
export type Stream = { streamId: string; publisherDeviceId: string; publisherName: string; targetDeviceId?: string; sourceId?: string; sourceName: string; isStreaming: boolean; lastFrameAt?: string; stopReason?: string };
export type Source = { sourceId: string; sourceName: string; isMonitoring: boolean; isReady: boolean; cooldown: number; confidence: number; targetSamplingRate: number; targets: string; modelKey: string; actualFps?: number; error?: string };
export type Device = Identity & { deviceName: string; online: boolean; isMonitoring: boolean; isReady: boolean; lastSeen?: string; capabilities: string[]; cooldown?: number; confidence?: number; targetSamplingRate?: number; targets?: string; modelKey?: string; modelOptions?: string[]; sources: Source[]; components?: Record<string,string>; canSwitchModelWhileMonitoring?: boolean; sourceLimitExceeded?: boolean; maxSources?: number };
export type Alert = { alertId: string; deviceId: string; deviceName: string; sourceId?: string; sourceName?: string; timestamp: string; eventKind: string; summary: string; hasScreenshot?: boolean; screenshotUrl?: string; detections?: { label: string; confidence: number }[] };
export type Target = { deviceId: string; sourceId?: string };
export type Scope = { mode: 'all' | 'selected'; targets: Target[] };
export type Notifier = Identity & { deviceName: string; online: boolean; scope: Scope };
export type Ack = { requestId: string; phase?: string; success: boolean; reason?: string; command?: string; targetDeviceId?: string };
export type AlarmTimeZone = 'Asia/Shanghai' | 'UTC';
export type TimeStandard = { timeZone: AlarmTimeZone; serverTime: string };
export function parseTimeStandard(value: unknown): TimeStandard | null {
  if (!value || typeof value !== 'object') return null;
  const item = value as Partial<TimeStandard>;
  return (item.timeZone === 'Asia/Shanghai' || item.timeZone === 'UTC') && typeof item.serverTime === 'string' && Number.isFinite(Date.parse(item.serverTime))
    ? { timeZone: item.timeZone, serverTime: item.serverTime } : null;
}
export function timeStandardLabel(timeZone: AlarmTimeZone): string {
  return timeZone === 'Asia/Shanghai' ? '北京时间（UTC+8）' : 'UTC（UTC+0）';
}
export function formatTime(value: string, timeZone: AlarmTimeZone): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return '时间未知';
  const parts = new Intl.DateTimeFormat('zh-CN', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(p => p.type === type)?.value;
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}`;
}
export function websocketURL(origin: string): string {
  const url = new URL('/ws', origin);
  const parts = url.hostname.split('.').map(Number);
  const privateIPv4 = /^\d+\.\d+\.\d+\.\d+$/.test(url.hostname) && parts.every(n => n >= 0 && n <= 255)
    && (parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168));
  if (url.protocol === 'https:') url.protocol = 'wss:';
  else if (url.protocol === 'http:' && (['127.0.0.1','localhost','[::1]'].includes(url.hostname) || privateIPv4)) url.protocol = 'ws:';
  else throw new Error('请通过 HTTPS 打开控制台');
  return url.href;
}
// getRandomValues is available on LAN HTTP; randomUUID requires a secure context.
export function createRequestId(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('');
}
export function mergeDevices(live: Device[], registered: Identity[]): Device[] {
  return [...live, ...registered.filter(id => !live.some(d => d.deviceId === id.deviceId)).map(id => ({ ...id, deviceName: id.deviceName || id.deviceId, online: false, isMonitoring: false, isReady: false, capabilities: [], sources: [] }))];
}
export function mergeAlerts(existing: Alert[], incoming: Alert[]): Alert[] {
  const events = new Map(existing.map(alert => [alert.alertId, alert]));
  for (const alert of incoming) {
    const old = events.get(alert.alertId);
    events.set(alert.alertId, { ...old, ...alert, hasScreenshot: old?.hasScreenshot || alert.hasScreenshot, screenshotUrl: alert.screenshotUrl || old?.screenshotUrl });
  }
  return [...events.values()].sort((a,b) => Date.parse(b.timestamp)-Date.parse(a.timestamp)).slice(0,100);
}
export function eventLabel(kind: string) { return ({ 'visual-detection': '视觉检测', 'sensor-detection': '传感器检测', 'connection-lost': '连接中断', 'detection-interrupted': '检测中断' } as Record<string,string>)[kind] ?? '检测事件'; }
export function typeLabel(kind: string) { return ({ visual: '视觉节点', sensor: '传感器节点', notification: '通知节点' } as Record<string,string>)[kind] ?? '节点'; }
