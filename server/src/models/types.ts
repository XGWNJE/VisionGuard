// ┌─────────────────────────────────────────────────────────┐
// │ types.ts                                                │
// │ 角色：所有 TypeScript 接口/类型定义                       │
// │ 覆盖：HTTP 请求/响应、WebSocket 消息、内部数据结构        │
// │ 版本：4.0.0                                              │
// └─────────────────────────────────────────────────────────┘

// ── HTTP ──────────────────────────────────────────────────

/** POST /api/alert 的 meta 字段 JSON 结构 */
export interface AlertMeta {
  deviceId: string;
  deviceName: string;
  timestamp: string;          // ISO 8601
  detections: Detection[];
}

export interface Detection {
  label: string;
  confidence: number;
  bbox: { x: number; y: number; w: number; h: number };
}

/** 存储在 AlertStore 中的完整报警记录 */
export interface AlertRecord {
  alertId: string;
  deviceId: string;
  deviceName: string;
  timestamp: string;
  detections: Detection[];
  screenshotPath?: string;
  createdAt: number;          // Date.now()
}

// ── WebSocket 消息 ─────────────────────────────────────────

/** 客户端 → 服务器：认证 */
export interface WsAuthMessage {
  type: 'auth';
  apiKey: string;
  role: 'windows' | 'android' | 'android-detector';
  deviceId: string;
  deviceName: string;
  version?: string;
}

/** 检测端 → 服务器：心跳 (每 15 秒) */
export interface WsHeartbeat {
  type: 'heartbeat';
  deviceId: string;
  deviceName?: string;
  isMonitoring: boolean;
  isReady: boolean;
  cooldown?: number;
  confidence?: number;
  targets?: string;
}

/** 接收端 → 服务器：心跳 (每 20 秒) */
export interface WsHeartbeatAndroid {
  type: 'heartbeat-android';
  deviceId: string;
}

/** 服务器 → 接收端：报警推送 (v4.0.0+: 元数据 only, 截图走独立 screenshot-data 消息) */
export interface WsAlertPush {
  type: 'alert';
  alertId: string;
  deviceId: string;
  deviceName: string;
  timestamp: string;
  detections: Detection[];
  screenshotUrl?: string;
  timings?: Record<string, number>;
  /** @deprecated since 4.0.0 — 使用 capturedAt 替代 */
  wsSentAt?: string;
  /** v4.0.0: 检测端捕获帧的 NTP 时间戳 (ISO8601) */
  capturedAt?: string;
  serverReceivedAt?: string;
  serverRelayedAt?: string;
}

/**
 * 检测端 → 服务器 → 接收端：截图独立异步推送 (协议分离: alert 元数据先行,截图后到)
 * 接收端收到后用同一 alertId 静默更新已弹出的通知 BigPicture。
 */
export interface WsScreenshotDataPush {
  type: 'screenshot-data';
  alertId: string;
  deviceId: string;
  imageBase64: string;
  width?: number;
  height?: number;
}

export interface DeviceStatus {
  deviceId: string;
  deviceName: string;
  online: boolean;
  isMonitoring: boolean;
  isReady: boolean;
  lastSeen: string;
  cooldown: number;
  confidence: number;
  targets: string;
  clientType: string;
}

/** 接收端 → 服务器：反向控制命令 */
export interface WsCommand {
  type: 'command';
  targetDeviceId: string;
  command: 'pause' | 'resume' | 'stop-alarm';
}

/** 接收端 → 服务器：参数调整 */
export interface WsSetConfig {
  type: 'set-config';
  targetDeviceId: string;
  key: string;
  value: string;
}

/** 服务器 → 检测端：转发命令 */
export interface WsCommandRelay {
  type: 'command';
  command: 'pause' | 'resume' | 'stop-alarm';
  targetDeviceId: string;
}

/** 服务器 → 检测端：转发参数调整 */
export interface WsSetConfigRelay {
  type: 'set-config';
  key: string;
  value: string;
  targetDeviceId: string;
}

/** 客户端 → 服务器：主动断开原因 */
export interface WsDisconnectReason {
  type: 'disconnect-reason';
  reason: 'user-close' | 'network-lost' | 'server-kick' | 'app-killed' | 'server-unreachable' | 'auth-failed' | 'unknown';
  detail?: string;
}

/** 接收端 → 服务器：重连时上报上次 Session 信息 */
export interface WsSessionInfo {
  type: 'session-info';
  deviceId: string;
  lastSessionEndReason: 'user-close' | 'network-lost' | 'server-kick' | 'app-killed' | 'unknown';
  lastSessionDurationMs: number;
  isReconnect: boolean;
}

/** 服务器 → 接收端：命令确认 */
export interface WsCommandAck {
  type: 'command-ack';
  targetDeviceId: string;
  command: string;
  success: boolean;
  reason: string;
}

// ── 内部连接管理 ───────────────────────────────────────────

import type WebSocket from 'ws';

export interface DetectorClient {
  ws: WebSocket;
  deviceId: string;
  deviceName: string;
  clientType: string;       // 'windows' | 'android-detector'
  isMonitoring: boolean;
  isReady: boolean;
  lastSeen: Date;
  cooldown: number;
  confidence: number;
  targets: string;
}

export interface ReceiverClient {
  ws: WebSocket;
  deviceId: string;
  lastSeen: Date;
}
