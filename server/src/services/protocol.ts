// ┌─────────────────────────────────────────────────────────┐
// │ protocol.ts                                             │
// │ 角色：协议版本闸门 —— 只服务与本版同版本的端点             │
// │ 覆盖：版本常量、闸门判定、旧版本杂音的聚合统计             │
// └─────────────────────────────────────────────────────────┘

import { config } from '../config';

/** 协议版本与本版同源；每次发版同步，旧版本端点直接出局。 */
export const PROTOCOL_VERSION = config.version;

/** 旧版本杂音聚合：只报数量与版本号，不逐条打印、不回包。 */
interface ProtocolNoise {
  connections: number;
  httpRequests: number;
  byVersion: Map<string, number>;
  firstSeen: number;
  lastSeen: number;
}

let noise: ProtocolNoise | null = null;
let reporter: NodeJS.Timeout | null = null;

/**
 * HTTP 闸门：非本版客户端不进入业务逻辑。
 * 判定只看声明版本；缺失（旧协议）同样出局。`gate` 由实例决定，本地/测试实例传 false 直接放行。
 */
export function createHttpVersionGate(gate: boolean): (req: any, res: any, next: any) => void {
  return (req, res, next) => {
    if (!gate) { next(); return; }
    const declared = req.headers?.['x-visionguard-version'];
    if (!isCurrentVersion(declared)) {
      recordProtocolNoise('httpRequests', declared);
      // 静默出局：不解释、不升级、不保留兼容分支。
      res.status(426).end();
      return;
    }
    next();
  };
}

export function isCurrentVersion(declared: unknown): boolean {
  return typeof declared === 'string' && declared.trim() === PROTOCOL_VERSION;
}

/** WS 闸门判定：返回是否放行，并记录出局版本。`gate` 为 false（本地/测试实例）时一律放行。 */
export function wsVersionGate(message: any, gate: boolean = config.protocolGate): boolean {
  if (!gate) return true;
  const declared = message?.version;
  if (isCurrentVersion(declared)) return true;
  recordProtocolNoise('connections', declared);
  return false;
}

export function recordProtocolNoise(kind: 'connections' | 'httpRequests', declared: unknown): void {
  const label = typeof declared === 'string' && declared.trim() ? declared.trim().slice(0, 24) : '(未声明)';
  if (!noise) noise = { connections: 0, httpRequests: 0, byVersion: new Map(), firstSeen: Date.now(), lastSeen: Date.now() };
  noise[kind] += 1;
  noise.byVersion.set(label, (noise.byVersion.get(label) ?? 0) + 1);
  noise.lastSeen = Date.now();
  ensureReporter();
}

/** 周期性汇总输出：存在杂音才会打印一行，避免逐条刷屏。 */
function ensureReporter(): void {
  if (reporter) return;
  reporter = setInterval(flushProtocolNoise, 300_000);
  reporter.unref();
}

export function flushProtocolNoise(): void {
  if (!noise || (noise.connections === 0 && noise.httpRequests === 0)) return;
  const byVersion = Object.fromEntries([...noise.byVersion.entries()].sort((a, b) => b[1] - a[1]));
  console.info('[Protocol]', JSON.stringify({
    event: 'legacy-protocol-dropped',
    servedVersion: PROTOCOL_VERSION,
    connections: noise.connections,
    httpRequests: noise.httpRequests,
    byVersion,
    windowMs: noise.lastSeen - noise.firstSeen
  }));
  noise = null;
  if (reporter) { clearInterval(reporter); reporter = null; }
}

export function protocolNoiseStats(): { connections: number; httpRequests: number; byVersion: Record<string, number> } | null {
  if (!noise) return null;
  return { connections: noise.connections, httpRequests: noise.httpRequests, byVersion: Object.fromEntries(noise.byVersion) };
}

export function resetProtocolNoise(): void {
  noise = null;
  if (reporter) { clearInterval(reporter); reporter = null; }
}
