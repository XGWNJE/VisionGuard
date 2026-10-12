// ┌─────────────────────────────────────────────────────────┐
// │ LanBeacon.ts                                            │
// │ 角色：本地服务实例的局域网发现信标                        │
// │ 职责：向广播地址周期发送 udp JSON，供 Android 端自动发现  │
// │       本地服务并拿到它的端口；生产实例不启用              │
// └─────────────────────────────────────────────────────────┘

import dgram from 'node:dgram';
import os from 'node:os';

export interface LanBeaconOptions {
  /** 信标目标 UDP 端口。 */
  port: number;
  /** 实例标签：客户端只认与自己构建时相同的 channel。 */
  channel: string;
  /** HTTP/WS 服务端口；客户端用报文来源 IP + 该端口组成服务地址。 */
  servicePort: number;
  version: string;
  /** 广播间隔（毫秒），默认 2000。 */
  intervalMs?: number;
  /** 覆盖广播目标；只给测试用，正常运行按 `beaconDestinations()` 计算。 */
  destinations?: string[];
}

/** 定向广播地址：IPv4 与掩码按位或。掩码非法时返回 null，不猜。 */
function directedBroadcast(address: string, netmask: string): string | null {
  const addressParts = address.split('.').map(Number);
  const maskParts = netmask.split('.').map(Number);
  if (addressParts.length !== 4 || maskParts.length !== 4) return null;
  if ([...addressParts, ...maskParts].some(value => !Number.isInteger(value) || value < 0 || value > 255)) return null;
  return addressParts.map((value, index) => (value | (~maskParts[index] & 255))).join('.');
}

/**
 * 广播目标：全局广播 + 每个非内部网卡的定向广播。
 * 只看全局广播会踩到虚拟网卡（Clash / VPN）抢默认路由的坑，定向广播保证真实网段一定能收到。
 */
export function beaconDestinations(): string[] {
  const targets = new Set<string>(['255.255.255.255']);
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family !== 'IPv4' || entry.internal) continue;
      const broadcast = directedBroadcast(entry.address, entry.netmask);
      if (broadcast) targets.add(broadcast);
    }
  }
  return [...targets];
}

/** 启动信标；返回停止函数。任何失败只告警，绝不拖垮服务本身。 */
export function startLanBeacon(options: LanBeaconOptions): () => void {
  const socket = dgram.createSocket('udp4');
  const payload = Buffer.from(JSON.stringify({
    service: 'visionguard-local',
    channel: options.channel,
    port: options.servicePort,
    version: options.version,
  }));
  let warned = false;
  const warn = (message: string) => {
    if (warned) return;
    warned = true;
    console.warn(`[beacon] 局域网信标不可用：${message}`);
  };
  socket.on('error', error => warn(error.message));

  const send = () => {
    for (const destination of options.destinations ?? beaconDestinations()) {
      // 回调必须给：send 失败会走 'error' 事件，不给回调会变成未捕获异常。
      socket.send(payload, options.port, destination, () => { });
    }
  };

  let timer: NodeJS.Timeout | null = null;
  socket.bind(() => {
    try {
      socket.setBroadcast(true);
      send();
      timer = setInterval(send, options.intervalMs ?? 2000);
      timer.unref();
      console.log(`[beacon] 局域网信标已开启：udp/${options.port} channel=${options.channel} servicePort=${options.servicePort}`);
    } catch (error) {
      warn((error as Error).message);
    }
  });

  return () => {
    if (timer) clearInterval(timer);
    try { socket.close(); } catch { /* 已关闭 */ }
  };
}
