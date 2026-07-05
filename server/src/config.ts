// ┌─────────────────────────────────────────────────────────┐
// │ config.ts                                               │
// │ 角色：集中读取环境变量，提供类型安全的配置对象             │
// │ 对外 API：config (单例对象)                              │
// └─────────────────────────────────────────────────────────┘

import path from 'path';
import { parsePositiveIntEnv } from './utils/security';

export const config = {
  /** HTTP/WS 监听端口 */
  port: parsePositiveIntEnv('PORT', 3000, 1, 65535),

  /** 共享 API Key (所有端使用同一个) */
  apiKey: process.env.API_KEY || '',

  /** 截图存储目录 */
  screenshotDir: path.resolve(__dirname, '..', 'data', 'screenshots'),

  /** 截图过期时间 (小时)，默认 72 小时 */
  screenshotTtlHours: parsePositiveIntEnv('SCREENSHOT_TTL_HOURS', 72, 1, 24 * 365),

  /** 截图清理间隔 (毫秒)，默认 1 小时 */
  cleanupIntervalMs: parsePositiveIntEnv('CLEANUP_INTERVAL_MS', 3600000, 1000, 24 * 3600 * 1000),

  /** 上传大小限制 (字节)，默认 2MB */
  maxUploadBytes: parsePositiveIntEnv('MAX_UPLOAD_BYTES', 2097152, 1024, 20 * 1024 * 1024),

  /** WS 认证超时 (毫秒) */
  wsAuthTimeoutMs: 5000,

  /** 设备离线判定 / 幽灵清理阈值 (毫秒)。超过此时间无消息则终止连接并标记离线。
   *  检测端心跳 3s, 接收端心跳 30s, 统一取 45s 为安全阈值。 */
  deviceOfflineMs: 45_000,

  /** 每设备最大报警记录数 (循环缓冲) */
  maxAlertsPerDevice: 200,

  /** 是否接收检测端 HTTP POST 截图上传。false = 纯 WS 按需模型，截图仅存在检测端本地 */
  enableHttpScreenshotUpload: process.env.ENABLE_HTTP_SCREENSHOT_UPLOAD === 'true',

  /** 报警记录 TTL (小时)，默认 168 小时 = 7 天。与检测端本地截图缓存 TTL 对齐 */
  alertTtlHours: parsePositiveIntEnv('ALERT_TTL_HOURS', 168, 1, 24 * 365),

  /** WS 最大并发连接数 (所有角色合计)，防止连接洪水 */
  maxWsConnections: parsePositiveIntEnv('MAX_WS_CONNECTIONS', 100, 1, 10000),

  /** 接收端幽灵清理阈值 (毫秒)。心跳 30s，取 45s 为安全阈值。 */
  receiverGhostThresholdMs: 45_000,
} as const;

export function validateConfig(): void {
  if (!config.apiKey) {
    console.error('[config] ❌ API_KEY 未设置，服务器拒绝启动。请在 .env 中配置 API_KEY');
    process.exit(1);
  }
}
