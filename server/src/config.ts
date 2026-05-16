// ┌─────────────────────────────────────────────────────────┐
// │ config.ts                                               │
// │ 角色：集中读取环境变量，提供类型安全的配置对象             │
// │ 对外 API：config (单例对象)                              │
// └─────────────────────────────────────────────────────────┘

import path from 'path';

export const config = {
  /** HTTP/WS 监听端口 */
  port: parseInt(process.env.PORT || '3000', 10),

  /** 共享 API Key (所有端使用同一个) */
  apiKey: process.env.API_KEY || '',

  /** 截图存储目录 */
  screenshotDir: path.resolve(__dirname, '..', 'data', 'screenshots'),

  /** 截图过期时间 (小时)，默认 72 小时 */
  screenshotTtlHours: parseInt(process.env.SCREENSHOT_TTL_HOURS || '72', 10),

  /** 截图清理间隔 (毫秒)，默认 1 小时 */
  cleanupIntervalMs: parseInt(process.env.CLEANUP_INTERVAL_MS || '3600000', 10),

  /** 上传大小限制 (字节)，默认 2MB */
  maxUploadBytes: parseInt(process.env.MAX_UPLOAD_BYTES || '2097152', 10),

  /** WS 认证超时 (毫秒) */
  wsAuthTimeoutMs: 5000,

  /** 设备离线判定 / 幽灵清理阈值 (毫秒)。超过此时间无消息则终止连接并标记离线。
   *  检测端心跳 3s, 接收端心跳 30s, 统一取 45s 为安全阈值。 */
  deviceOfflineMs: 45_000,

  /** 每设备最大报警记录数 (循环缓冲) */
  maxAlertsPerDevice: 200,

  /** 客户端最低版本要求 (语义化版本)。低于此版本的连接将在认证时被拒绝。 */
  /** TODO: 暂时禁用版本门控，修复 AutoUpdater 后重新启用 */
  minClientVersion: '0.0.0',

  /** 是否接收检测端 HTTP POST 截图上传。false = 纯 WS 按需模型，截图仅存在检测端本地 */
  enableHttpScreenshotUpload: process.env.ENABLE_HTTP_SCREENSHOT_UPLOAD === 'true',

  /** 报警记录 TTL (小时)，默认 168 小时 = 7 天。与检测端本地截图缓存 TTL 对齐 */
  alertTtlHours: parseInt(process.env.ALERT_TTL_HOURS || '168', 10),

  /** WS 最大并发连接数 (所有角色合计)，防止连接洪水 */
  maxWsConnections: parseInt(process.env.MAX_WS_CONNECTIONS || '100', 10),

  /** 接收端幽灵清理阈值 (毫秒)。心跳 30s，取 45s 为安全阈值。 */
  receiverGhostThresholdMs: 45_000,
} as const;

export function validateConfig(): void {
  if (!config.apiKey) {
    console.error('[config] ❌ API_KEY 未设置，服务器拒绝启动。请在 .env 中配置 API_KEY');
    process.exit(1);
  }
}
