// ┌─────────────────────────────────────────────────────────┐
// │ types.ts                                                │
// │ 角色：所有 TypeScript 接口/类型定义                       │
// │ 覆盖：HTTP 请求/响应、WebSocket 消息、内部数据结构        │
// └─────────────────────────────────────────────────────────┘

// ── HTTP ──────────────────────────────────────────────────

/** POST /api/alert 的 meta 字段 JSON 结构 */
export interface AlertMeta {
  eventKind?: import('../services/NodeProtocol').EventKind;
  expiresAt?: string;
  summary?: string;
  deviceId: string;
  deviceName: string;
  /** Stable source identity and event-time display-name snapshot. */
  sourceId?: string;
  sourceName?: string;
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
  eventKind?: import('../services/NodeProtocol').EventKind;
  expiresAt?: string;
  summary?: string;
  nodeType?: import('../services/NodeProtocol').NodeType;
  alertId: string;
  deviceId: string;
  deviceName: string;
  sourceId?: string;
  sourceName?: string;
  timestamp: string;
  detections: Detection[];
  screenshotPath?: string;
  createdAt: number;          // Date.now()
}

// ── WebSocket 消息 ─────────────────────────────────────────

/** 客户端 → 服务器：认证 */
export interface WsAuthMessage {
  type: 'auth';
  token: string;
  version?: string;
}

/** 检测端 → 服务器：心跳 (每 3 秒) */
export interface WsHeartbeat {
  type: 'heartbeat';
  deviceId: string;
  deviceName?: string;
  isMonitoring: boolean;
  isReady: boolean;
  cooldown?: number;
  confidence?: number;
  targets?: string;
  targetSamplingRate?: number;
  modelKey?: string;
  modelOptions?: string[];
  canSwitchModelWhileMonitoring?: boolean;
  hasPendingConfigChanges?: boolean;
  capabilities?: string[];
  components?: Record<string, string>;
  sources?: SourceStatus[];
  monitoringExpected?: boolean;
  lastProgressAt?: string;
}

/** 控制台 → 服务器：心跳 (每 30 秒) */
export interface WsHeartbeatAndroid {
  type: 'heartbeat-console';
  deviceId: string;
}

export interface WsResidentHeartbeat {
  type: 'resident-heartbeat';
  components?: Record<string, string>;
}

export interface SourceStatus {
  sourceId: string;
  sourceName: string;
  isMonitoring: boolean;
  isReady: boolean;
  modelKey: string;
  actualFps?: number;
  error?: string;
  activeBackend?: string;
  performanceWarning?: string;
  cooldown?: number;
  confidence?: number;
  targets?: string;
  targetSamplingRate?: number;
  monitoringExpected?: boolean;
  lastProgressAt?: string;
}

/** 服务器 → 控制台：报警元数据；截图走独立 screenshot-data 消息。 */
export interface WsAlertPush {
  eventKind?: import('../services/NodeProtocol').EventKind;
  expiresAt?: string;
  summary?: string;
  nodeType?: import('../services/NodeProtocol').NodeType;
  type: 'alert';
  alertId: string;
  deviceId: string;
  deviceName: string;
  sourceId?: string;
  sourceName?: string;
  timestamp: string;
  detections: Detection[];
  createdAt?: number;
  screenshotUrl?: string;
  timings?: Record<string, number>;
  /** 兼容字段；帧时间使用 capturedAt。 */
  wsSentAt?: string;
  /** 检测端捕获帧的 NTP 时间戳 (ISO8601) */
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
  sourceId?: string;
  sourceName?: string;
  imageBase64: string;
  width?: number;
  height?: number;
}

export interface DeviceStatus {
  component: import('../services/AccountStore').Component;
  role: import('../services/NodeProtocol').NodeRole;
  nodeType: import('../services/NodeProtocol').NodeType;
  platform: string;
  deviceId: string;
  deviceName: string;
  online: boolean;
  isMonitoring: boolean;
  isReady: boolean;
  lastSeen: string;
  cooldown: number;
  confidence: number;
  targets: string;
  targetSamplingRate: number;
  modelKey: string;
  modelOptions: string[];
  canSwitchModelWhileMonitoring: boolean;
  hasPendingConfigChanges: boolean;
  clientType: string;
  capabilities: string[];
  components: Record<string, string>;
  sources: SourceStatus[];
  /** 服务端持有的来源上限；接收端据此解释设备为什么只有这些来源。 */
  maxSources: number;
  /** 最近一次心跳的 sources 因超过上限被整组拒绝；接收端需要看到该状态，而不是静默的旧快照。 */
  sourceLimitExceeded: boolean;
}

/** 接收端 → 服务器：反向控制命令 */
export interface WsCommand {
  type: 'command';
  requestId?: string;
  targetDeviceId: string;
  targetSourceId?: string;
  command: 'pause' | 'resume' | 'stop-alarm' | 'open-detector' | 'close-detector';
}

/** 接收端 → 服务器：参数调整 */
export interface WsSetConfig {
  type: 'set-config';
  requestId?: string;
  targetDeviceId: string;
  targetSourceId?: string;
  key: string;
  value: string;
}

/** 服务器 → 检测端：转发命令 */
export interface WsCommandRelay {
  type: 'command';
  requestId?: string;
  command: 'pause' | 'resume' | 'stop-alarm' | 'open-detector' | 'close-detector';
  targetDeviceId: string;
  targetSourceId?: string;
}

/** 服务器 → 检测端：转发参数调整 */
export interface WsSetConfigRelay {
  type: 'set-config';
  requestId?: string;
  key: string;
  value: string;
  targetDeviceId: string;
  targetSourceId?: string;
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
  requestId?: string;
  phase?: 'forwarded' | 'completed';
  targetDeviceId: string;
  targetSourceId?: string;
  command: string;
  success: boolean;
  reason: string;
}

// ── 内部连接管理 ───────────────────────────────────────────

import type WebSocket from 'ws';

export interface DetectorClient {
  identity: import('../services/NodeProtocol').NodeIdentity;
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
  targetSamplingRate: number;
  modelKey: string;
  modelOptions: string[];
  canSwitchModelWhileMonitoring: boolean;
  hasPendingConfigChanges: boolean;
  capabilities: string[];
  components: Record<string, string>;
  sources: SourceStatus[];
  /** 最近一次心跳的 sources 因超过服务端上限被整组拒绝。 */
  sourceLimitExceeded: boolean;
  monitoringExpected?: boolean;
  lastProgressAt?: string;
}

export interface ReceiverClient {
  ws: WebSocket;
  deviceId: string;
  lastSeen: Date;
  identity?: import('../services/NodeProtocol').NodeIdentity;
  deviceName?: string;
}

export interface ResidentClient {
  identity: import('../services/NodeProtocol').NodeIdentity;
  ws: WebSocket;
  deviceId: string;
  deviceName: string;
  lastSeen: Date;
  components: Record<string, string>;
}
