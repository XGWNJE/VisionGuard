// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionManager.ts  v4.0.0                             │
// │ 角色：WebSocket 连接管理 (按 role 独立 Map 跟踪)          │
// │ 职责：认证、心跳、设备列表广播、报警广播(含截图推送)      │
// │ 对外 API：handleConnection(), broadcastAlert(),           │
// │          getConnectionCount()                             │
// └─────────────────────────────────────────────────────────┘

import WebSocket from 'ws';
import fs from 'fs';
import path from 'path';
import { config } from '../config';
import { validateApiKey } from '../middleware/auth';
import { addAlert, getAlertById, markAlertScreenshot } from '../services/AlertStore';
import { isValidSetConfigKey, validateSetConfigValue } from '../services/ControlProtocol';
import { getSafeScreenshotPath, isSafeAlertId, validateAlertMeta, validateImageMagic } from '../utils/security';
import type {
  WsAuthMessage, WsHeartbeat, WsHeartbeatAndroid, WsCommand, WsSetConfig,
  DetectorClient, ReceiverClient, WsAlertPush, WsScreenshotDataPush,
  DeviceStatus, WsCommandRelay, WsSetConfigRelay, WsCommandAck,
  WsDisconnectReason, WsSessionInfo, WsResidentHeartbeat, ResidentClient,
  AlertRecord, SourceStatus,
} from '../models/types';

// ── 三角色独立 Map (v4.0.0) ─────────────────────────────────
const detectorWindowsClients = new Map<string, DetectorClient>();
const detectorAndroidClients = new Map<string, DetectorClient>();
const receiverClients = new Map<string, ReceiverClient>();
const residentWindowsClients = new Map<string, ResidentClient>();
const pendingDetectorRemoval = new Map<string, NodeJS.Timeout>();
const DETECTOR_RECONNECT_GRACE_MS = 10_000;
const COMMAND_TIMEOUT_MS = 15_000;
const COMPLETED_REQUEST_TTL_MS = 60_000;

interface PendingControlRequest {
  senderWs: WebSocket;
  targetDeviceId: string;
  command: string;
  targetSourceId?: string;
  timer: NodeJS.Timeout;
}

const pendingControlRequests = new Map<string, PendingControlRequest>();
const completedControlRequests = new Map<string, number>();

function rememberCompletedRequest(requestId: string): void {
  const now = Date.now();
  completedControlRequests.set(requestId, now + COMPLETED_REQUEST_TTL_MS);
  for (const [id, expiresAt] of completedControlRequests) {
    if (expiresAt <= now) completedControlRequests.delete(id);
  }
}

export function associateScreenshotPayload(
  payload: WsScreenshotDataPush,
  authenticatedDeviceId: string,
  alertRecord: AlertRecord | undefined,
): WsScreenshotDataPush | null {
  if (!alertRecord || alertRecord.deviceId !== authenticatedDeviceId || alertRecord.alertId !== payload.alertId) {
    return null;
  }
  return {
    ...payload,
    deviceId: authenticatedDeviceId,
    sourceId: alertRecord.sourceId,
    sourceName: alertRecord.sourceName,
  };
}

export function getConnectionCount(): number {
  return detectorWindowsClients.size + detectorAndroidClients.size + residentWindowsClients.size + receiverClients.size;
}

function detectorRemovalKey(clientType: string, deviceId: string): string {
  return `${clientType}:${deviceId}`;
}

function clearPendingDetectorRemoval(clientType: string, deviceId: string): void {
  const key = detectorRemovalKey(clientType, deviceId);
  const timer = pendingDetectorRemoval.get(key);
  if (!timer) return;
  clearTimeout(timer);
  pendingDetectorRemoval.delete(key);
}

function scheduleDetectorRemoval(
  clients: Map<string, DetectorClient>,
  clientType: 'windows' | 'android-detector',
  deviceId: string,
  ws: WebSocket,
): void {
  clearPendingDetectorRemoval(clientType, deviceId);
  const timer = setTimeout(() => {
    pendingDetectorRemoval.delete(detectorRemovalKey(clientType, deviceId));
    const existing = clients.get(deviceId);
    if (!existing || existing.ws !== ws) return;
    clients.delete(deviceId);
    _heartbeatCounter.delete(deviceId);
    scheduleBroadcast();
  }, DETECTOR_RECONNECT_GRACE_MS);
  timer.unref();
  pendingDetectorRemoval.set(detectorRemovalKey(clientType, deviceId), timer);
}

// ── 输入校验 ────────────────────────────────────────────────

const MAX_DEVICE_ID_LENGTH = 128;
const MAX_DEVICE_NAME_LENGTH = 64;
const MAX_TARGETS_LENGTH = 500;
const MAX_MODEL_OPTIONS = 16;
const MAX_CAPABILITIES = 32;
const MAX_COMPONENTS = 8;
const MAX_SOURCES = 3;

function validateDetection(d: any): boolean {
  if (!d || typeof d !== 'object') return false;
  if (typeof d.label !== 'string' || d.label.length > 64) return false;
  if (typeof d.confidence !== 'number' || !isFinite(d.confidence) || d.confidence < 0 || d.confidence > 1) return false;
  const b = d.bbox;
  if (!b || typeof b !== 'object') return false;
  if (typeof b.x !== 'number' || !isFinite(b.x)) return false;
  if (typeof b.y !== 'number' || !isFinite(b.y)) return false;
  if (typeof b.w !== 'number' || !isFinite(b.w) || b.w < 0) return false;
  if (typeof b.h !== 'number' || !isFinite(b.h) || b.h < 0) return false;
  return true;
}

function sanitizeHeartbeatCooldown(v: any): number | undefined {
  if (v === undefined || v === null) return undefined;
  const n = Number(v);
  if (!isFinite(n)) return undefined;
  return Math.max(1, Math.min(300, Math.round(n)));
}

function sanitizeHeartbeatConfidence(v: any): number | undefined {
  if (v === undefined || v === null) return undefined;
  const n = Number(v);
  if (!isFinite(n)) return undefined;
  return Math.max(0.01, Math.min(1.0, n));
}

function sanitizeHeartbeatTargets(v: any): string | undefined {
  if (v === undefined || v === null) return undefined;
  const s = String(v);
  return s.length > MAX_TARGETS_LENGTH ? s.slice(0, MAX_TARGETS_LENGTH) : s;
}

function sanitizeHeartbeatSamplingRate(v: any): number | undefined {
  if (v === undefined || v === null) return undefined;
  const n = Number(v);
  if (!Number.isInteger(n) || n < 1 || n > 5) return undefined;
  return n;
}

function sanitizeModelKey(v: any): string | undefined {
  if (v === undefined || v === null) return undefined;
  const s = String(v).trim();
  return /^[A-Za-z0-9_-]{1,64}$/.test(s) ? s : undefined;
}

function sanitizeModelOptions(v: any): string[] | undefined {
  if (v === undefined || v === null) return undefined;
  if (!Array.isArray(v)) return undefined;
  const options = v
    .map(sanitizeModelKey)
    .filter((s): s is string => !!s);
  return Array.from(new Set(options)).slice(0, MAX_MODEL_OPTIONS);
}

function sanitizeCapabilities(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return Array.from(new Set(value
    .filter((item): item is string => typeof item === 'string' && /^[a-z0-9-]{1,48}$/.test(item))))
    .slice(0, MAX_CAPABILITIES);
}

function sanitizeComponents(value: unknown): Record<string, string> | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const result: Record<string, string> = {};
  for (const [key, state] of Object.entries(value).slice(0, MAX_COMPONENTS)) {
    if (/^[a-z][A-Za-z0-9]{0,31}$/.test(key) && typeof state === 'string' && /^(running|stopped|starting|error|unavailable)$/.test(state)) {
      result[key] = state;
    }
  }
  return result;
}

function sanitizeSources(value: unknown): SourceStatus[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const ids = new Set<string>();
  const sources: SourceStatus[] = [];
  for (const item of value.slice(0, MAX_SOURCES)) {
    if (!item || typeof item !== 'object') continue;
    const sourceId = typeof item.sourceId === 'string' ? item.sourceId.trim() : '';
    const sourceName = typeof item.sourceName === 'string' ? item.sourceName.trim() : '';
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(sourceId) || !sourceName || sourceName.length > 64 || ids.has(sourceId)) continue;
    ids.add(sourceId);
    const actualFps = typeof item.actualFps === 'number' && isFinite(item.actualFps)
      ? Math.max(0, Math.min(240, item.actualFps)) : undefined;
    const error = typeof item.error === 'string' && item.error.trim()
      ? item.error.trim().slice(0, 256) : undefined;
    const cooldown = typeof item.cooldown === 'number' && Number.isInteger(item.cooldown)
      ? Math.max(1, Math.min(300, item.cooldown)) : undefined;
    const confidence = typeof item.confidence === 'number' && isFinite(item.confidence)
      ? Math.max(0.1, Math.min(0.95, item.confidence)) : undefined;
    const targets = typeof item.targets === 'string'
      ? item.targets.trim().slice(0, 256) : undefined;
    const targetSamplingRate = typeof item.targetSamplingRate === 'number' && Number.isInteger(item.targetSamplingRate)
      ? Math.max(1, Math.min(5, item.targetSamplingRate)) : undefined;
    sources.push({
      sourceId, sourceName, isMonitoring: !!item.isMonitoring, isReady: !!item.isReady,
      modelKey: sanitizeModelKey(item.modelKey) ?? '', actualFps, error,
      cooldown, confidence, targets, targetSamplingRate,
    });
  }
  return sources;
}

function isValidRequestId(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{8,128}$/.test(value);
}

function registerPendingControlRequest(
  requestId: string,
  senderWs: WebSocket,
  targetDeviceId: string,
  command: string,
  targetSourceId?: string,
): boolean {
  const completedUntil = completedControlRequests.get(requestId);
  if (completedUntil !== undefined && completedUntil <= Date.now()) completedControlRequests.delete(requestId);
  if (pendingControlRequests.has(requestId) || completedControlRequests.has(requestId)) return false;
  const timer = setTimeout(() => {
    const pending = pendingControlRequests.get(requestId);
    if (!pending) return;
    pendingControlRequests.delete(requestId);
    rememberCompletedRequest(requestId);
    sendJson(pending.senderWs, {
      type: 'command-ack', requestId, phase: 'completed',
      targetDeviceId: pending.targetDeviceId, targetSourceId: pending.targetSourceId, command: pending.command,
      success: false, reason: '执行超时',
    }, 'command-timeout->sender');
  }, COMMAND_TIMEOUT_MS);
  timer.unref();
  pendingControlRequests.set(requestId, { senderWs, targetDeviceId, command, targetSourceId, timer });
  return true;
}

function createDetectorClient(
  ws: WebSocket,
  msg: WsAuthMessage,
  clientType: 'windows' | 'android-detector',
): DetectorClient {
  return {
    ws,
    deviceId: msg.deviceId,
    deviceName: msg.deviceName,
    clientType,
    isMonitoring: false,
    isReady: false,
    lastSeen: new Date(),
    cooldown: 5,
    confidence: 0.45,
    targets: '',
    targetSamplingRate: 3,
    modelKey: '',
    modelOptions: [],
    canSwitchModelWhileMonitoring: clientType === 'android-detector',
    hasPendingConfigChanges: false,
    capabilities: [],
    components: { detectorApp: 'running' },
    sources: [],
  };
}

// ── 接收端 Session 追踪 ─────────────────────────────────────
interface AndroidSession {
  connectedAt: number;
  lastSessionEndReason: string;
  lastSessionDurationMs: number;
}
const androidSessions = new Map<string, AndroidSession>();

const SessionEndReasonNames: Record<string, string> = {
  'user-close': '用户主动关闭',
  'network-lost': '网络中断（被系统杀后台/锁屏休眠）',
  'server-kick': '服务器主动断开',
  'app-killed': '应用被强制停止',
  'unknown': '未知原因',
};

// ── 广播防抖 ────────────────────────────────────────────────
let _broadcastTimer: NodeJS.Timeout | null = null;
function scheduleBroadcast(): void {
  if (_broadcastTimer) return;
  _broadcastTimer = setTimeout(() => {
    _broadcastTimer = null;
    broadcastDeviceList();
  }, 50);
  _broadcastTimer.unref();
}

// ── 截图推送队列 (协议分离: 截图独立异步,按接收端串行 500ms stagger) ──
const screenshotQueues = new Map<string, Array<{ alertId: string; payload: WsScreenshotDataPush }>>();
const screenshotProcessing = new Map<string, boolean>();

function backupScreenshot(payload: WsScreenshotDataPush): boolean {
  try {
    const imageBase64 = payload.imageBase64.includes(',')
      ? payload.imageBase64.substring(payload.imageBase64.indexOf(',') + 1)
      : payload.imageBase64;
    const bytes = Buffer.from(imageBase64, 'base64');
    if (bytes.length === 0) return false;
    const contentType = validateImageMagic(bytes);
    if (!contentType) {
      console.warn(`[ws] screenshot backup rejected: alertId=${payload.alertId} reason=invalid-image`);
      return false;
    }

    fs.mkdirSync(config.screenshotDir, { recursive: true });
    const target = getSafeScreenshotPath(config.screenshotDir, payload.alertId, contentType);
    if (!target) {
      console.warn(`[ws] screenshot backup rejected: alertId=${payload.alertId} reason=invalid-alert-id`);
      return false;
    }
    fs.writeFileSync(target.filePath, bytes);
    const linked = markAlertScreenshot(payload.alertId, target.filePath);
    console.log(`[ws][${new Date().toISOString()}] screenshot backed up: alertId=${payload.alertId} bytes=${bytes.length} linked=${linked}`);
    return linked;
  } catch (err: any) {
    console.warn(`[ws] screenshot backup failed: alertId=${payload.alertId} ${err.message}`);
    return false;
  }
}

function enqueueScreenshotPush(receiverId: string, alertId: string, payload: WsScreenshotDataPush): void {
  let q = screenshotQueues.get(receiverId);
  if (!q) { q = []; screenshotQueues.set(receiverId, q); }
  // 队列上限 32，超出丢弃最旧的
  if (q.length >= 32) q.shift();
  q.push({ alertId, payload });
  if (!screenshotProcessing.get(receiverId)) {
    processScreenshotQueue(receiverId);
  }
}

function processScreenshotQueue(receiverId: string): void {
  const q = screenshotQueues.get(receiverId);
  if (!q || q.length === 0) { screenshotProcessing.set(receiverId, false); return; }
  screenshotProcessing.set(receiverId, true);
  const item = q.shift()!;
  const client = receiverClients.get(receiverId);
  if (client?.ws.readyState === WebSocket.OPEN) {
    try { client.ws.send(JSON.stringify(item.payload)); } catch { /* ignore */ }
  }
  // 500ms 后推送下一条
  const timer = setTimeout(() => processScreenshotQueue(receiverId), 500);
  timer.unref();
}

// ── Close Code 翻译 ─────────────────────────────────────────
const CloseCodeNames: Record<number, string> = {
  1000: '正常关闭', 1001: '服务器关闭 (Going Away)', 1002: '协议错误',
  1003: '不支持的数据类型', 1005: '无状态码', 1006: '异常断开 (网络中断/服务器崩溃)',
  1007: '消息格式错误', 1008: '消息内容违反策略', 1009: '消息过大',
  1010: '必要扩展未协商', 1011: '服务器内部错误', 1015: 'TLS 握手失败',
};

function getCloseCodeName(code: number): string {
  return CloseCodeNames[code] ?? `未知错误 (code=${code})`;
}

function closeCodeToSessionEndReason(code: number, deviceId: string): string {
  if (code === 1000) return 'user-close';
  if (code === 1001) return 'server-kick';
  if (code === 1006) {
    const session = androidSessions.get(deviceId);
    if (session && session.lastSessionDurationMs > 0 && session.lastSessionDurationMs < 5 * 60 * 1000) {
      return 'app-killed';
    }
    return 'network-lost';
  }
  return 'unknown';
}

// ── 辅助：查找检测端（双 Map） ─────────────────────────────
function findDetector(deviceId: string): DetectorClient | undefined {
  return detectorWindowsClients.get(deviceId) ?? detectorAndroidClients.get(deviceId);
}

// ════════════════════════════════════════════════════════════
// 公开 API
// ════════════════════════════════════════════════════════════

export function handleConnection(ws: WebSocket): void {
  let authenticated = false;
  let role: 'windows' | 'android' | 'android-detector' | 'windows-resident' | null = null;
  let deviceId: string | null = null;
  const ts = new Date().toISOString();
  const remoteIp = (ws as any).socket?.remoteAddress ?? 'unknown';

  console.log(`[ws][${ts}] 新连接 ← ${remoteIp} (等待认证, 超时 ${config.wsAuthTimeoutMs}ms)`);

  const authTimer = setTimeout(() => {
    if (!authenticated) {
      console.log(`[ws][${new Date().toISOString()}] 认证超时关闭 ← ${remoteIp}`);
      sendJson(ws, { type: 'auth-result', success: false, reason: 'auth timeout' });
      ws.close();
    }
  }, config.wsAuthTimeoutMs);

  ws.on('message', (raw) => {
    let msg: any;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (!authenticated) {
      if (msg.type === 'auth') {
        handleAuth(ws, msg as WsAuthMessage, authTimer, (r, d) => {
          authenticated = true;
          role = r;
          deviceId = d;
        });
      }
      return;
    }
    const authenticatedDeviceId = deviceId;
    if (!authenticatedDeviceId) return;

    switch (msg.type) {
      case 'heartbeat':
        if (role === 'windows' || role === 'android-detector') {
          handleHeartbeat({ ...(msg as WsHeartbeat), deviceId: authenticatedDeviceId });
        }
        break;
      case 'heartbeat-android':
        if (role === 'android') handleHeartbeatReceiver({ ...(msg as WsHeartbeatAndroid), deviceId: authenticatedDeviceId });
        break;
      case 'resident-heartbeat':
        if (role === 'windows-resident') handleResidentHeartbeat(authenticatedDeviceId, msg as WsResidentHeartbeat);
        break;
      case 'alert':
        if (role === 'windows' || role === 'android-detector') {
          const alert = msg as WsAlertPush;
          if (!isSafeAlertId(alert.alertId)) break;
          const client = findDetector(authenticatedDeviceId);
          const metaResult = validateAlertMeta({
            deviceId: authenticatedDeviceId,
            deviceName: client?.deviceName || alert.deviceName || authenticatedDeviceId,
            sourceId: alert.sourceId,
            sourceName: alert.sourceName,
            timestamp: alert.timestamp,
            detections: alert.detections,
          });
          if (!metaResult.ok || !metaResult.value) break;
          alert.deviceId = metaResult.value.deviceId;
          alert.deviceName = metaResult.value.deviceName;
          alert.detections = metaResult.value.detections;
          const createdAt = Date.now();
          alert.serverReceivedAt = new Date(createdAt).toISOString();
          alert.createdAt = createdAt;
          addAlert({
            alertId: alert.alertId,
            deviceId: alert.deviceId,
            deviceName: alert.deviceName,
            sourceId: alert.sourceId,
            sourceName: alert.sourceName,
            timestamp: alert.timestamp,
            detections: alert.detections,
            createdAt,
          });
          broadcastAlert(alert);
        }
        break;
      case 'screenshot-data':
        if (role === 'windows' || role === 'android-detector') {
          const payload = msg as WsScreenshotDataPush;
          if (!isSafeAlertId(payload.alertId) || !payload.imageBase64) break;
          const associatedPayload = associateScreenshotPayload(payload, authenticatedDeviceId, getAlertById(payload.alertId));
          if (!associatedPayload) {
            console.warn(`[ws] screenshot rejected: alertId=${payload.alertId} reason=alert-device-mismatch-or-missing`);
            break;
          }
          if (!backupScreenshot(associatedPayload)) break;
          broadcastScreenshotData(associatedPayload);
        }
        break;
      case 'command':
        if (role === 'android') handleCommand(ws, msg as WsCommand);
        break;
      case 'set-config':
        if (role === 'android') handleSetConfig(ws, msg as WsSetConfig);
        break;
      case 'request-screenshot':
        if (role === 'android') handleRequestScreenshot(ws, msg);
        break;
      case 'command-ack':
        if (role === 'windows' || role === 'android-detector' || role === 'windows-resident') handleCommandAck(msg as WsCommandAck, deviceId!);
        break;
      case 'disconnect-reason':
        handleDisconnectReason(msg as WsDisconnectReason, role, deviceId);
        break;
      case 'session-info':
        handleSessionInfo(msg as WsSessionInfo);
        break;
    }
  });

  ws.on('close', (code) => {
    clearTimeout(authTimer);
    const ts2 = new Date().toISOString();
    const codeName = getCloseCodeName(code);
    if (deviceId) {
      if (role === 'windows') {
        const existing = detectorWindowsClients.get(deviceId);
        if (existing && existing.ws === ws) {
          console.log(`[ws][${ts2}] Windows 断开: ${deviceId} code=${code}(${codeName}) Win检测端在线=${detectorWindowsClients.size}`);
          scheduleDetectorRemoval(detectorWindowsClients, 'windows', deviceId, ws);
        } else {
          console.log(`[ws][${ts2}] Windows 旧连接关闭（已被新连接替代）: ${deviceId} code=${code}(${codeName})`);
        }
      } else if (role === 'android-detector') {
        const existing = detectorAndroidClients.get(deviceId);
        if (existing && existing.ws === ws) {
          console.log(`[ws][${ts2}] Android检测端 断开: ${deviceId} code=${code}(${codeName}) 安卓检测端在线=${detectorAndroidClients.size}`);
          scheduleDetectorRemoval(detectorAndroidClients, 'android-detector', deviceId, ws);
        } else {
          console.log(`[ws][${ts2}] Android检测端 旧连接关闭: ${deviceId} code=${code}(${codeName})`);
        }
      } else if (role === 'android') {
        const existing = receiverClients.get(deviceId);
        if (existing && existing.ws === ws) {
          const endReason = closeCodeToSessionEndReason(code, deviceId);
          const session = androidSessions.get(deviceId);
          if (session) {
            session.lastSessionEndReason = endReason;
            session.lastSessionDurationMs = Date.now() - session.connectedAt;
          }
          receiverClients.delete(deviceId);
          console.log(`[ws][${ts2}] 接收端 断开: ${deviceId} code=${code}(${codeName}) 推断原因=${endReason} 接收端在线=${receiverClients.size}`);
        } else {
          console.log(`[ws][${ts2}] 接收端 旧连接关闭: ${deviceId} code=${code}(${codeName})`);
        }
      } else if (role === 'windows-resident') {
        const existing = residentWindowsClients.get(deviceId);
        if (existing?.ws === ws) {
          residentWindowsClients.delete(deviceId);
          scheduleBroadcast();
          console.log(`[ws][${ts2}] Windows驻留 断开: ${deviceId}`);
        }
      }
    } else {
      console.log(`[ws][${ts2}] 未认证连接关闭 code=${code}(${codeName})`);
    }
  });

  ws.on('error', (err) => {
    const ts3 = new Date().toISOString();
    console.error(`[ws][${ts3}] 连接错误 deviceId=${deviceId ?? 'unauthenticated'} role=${role ?? '?'} remoteIp=${remoteIp}: ${err.message}`);
  });
}

export function broadcastAlert(alert: WsAlertPush): void {
  alert.serverRelayedAt = new Date().toISOString();
  // 协议分离: alert 元数据 <1KB,永远并行广播,不入串行队列
  const result = broadcastToReceivers(alert, `alert:${alert.alertId}`);
  console.log(`[ws][${new Date().toISOString()}] 报警广播: alertId=${alert.alertId} 接收端=${receiverClients.size} 成功=${result.success}/${result.success + result.failed}`);
}

/**
 * 截图独立异步广播 — 走 500ms 串行队列,防止多接收端并发下行拥塞。
 * 协议分离后,alert 已先行送达,本函数仅负责补传 BigPicture。
 */
export function broadcastScreenshotData(payload: WsScreenshotDataPush): void {
  for (const [rid] of receiverClients) {
    enqueueScreenshotPush(rid, payload.alertId, payload);
  }
  console.log(`[ws][${new Date().toISOString()}] 截图广播入队: alertId=${payload.alertId} 接收端=${receiverClients.size}`);
}

// ════════════════════════════════════════════════════════════
// 认证
// ════════════════════════════════════════════════════════════

function handleAuth(
  ws: WebSocket,
  msg: WsAuthMessage,
  authTimer: NodeJS.Timeout,
  onSuccess: (role: 'windows' | 'android' | 'android-detector' | 'windows-resident', deviceId: string) => void,
): void {
  clearTimeout(authTimer);
  const ts = new Date().toISOString();

  if (!validateApiKey(msg.apiKey)) {
    console.log(`[ws][${ts}] 认证失败: API Key 无效 role=${msg.role} deviceId=${msg.deviceId}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid api key' });
    ws.close();
    return;
  }

  if (!msg.deviceId || msg.deviceId.length > MAX_DEVICE_ID_LENGTH) {
    console.log(`[ws][${ts}] 认证失败: deviceId 无效 role=${msg.role}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid deviceId' });
    ws.close();
    return;
  }
  if (msg.deviceName && msg.deviceName.length > MAX_DEVICE_NAME_LENGTH) {
    console.log(`[ws][${ts}] 认证失败: deviceName 过长 role=${msg.role} deviceId=${msg.deviceId}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'deviceName too long' });
    ws.close();
    return;
  }

  if (msg.role === 'windows') {
    clearPendingDetectorRemoval('windows', msg.deviceId);
    const existing = detectorWindowsClients.get(msg.deviceId);
    if (existing) {
      console.log(`[ws][${ts}] Windows 重复连接: ${msg.deviceName} (${msg.deviceId}) 踢掉旧连接`);
      detectorWindowsClients.delete(msg.deviceId);
      sendJson(existing.ws, { type: 'kicked', reason: 'duplicate connection' });
      existing.ws.terminate();
    }
    const client = createDetectorClient(ws, msg, 'windows');
    detectorWindowsClients.set(msg.deviceId, client);
    console.log(`[ws][${ts}] Windows 上线: ${msg.deviceName} (${msg.deviceId}) | Win:${detectorWindowsClients.size} AdrDet:${detectorAndroidClients.size} Recv:${receiverClients.size}`);
  } else if (msg.role === 'android-detector') {
    clearPendingDetectorRemoval('android-detector', msg.deviceId);
    const existing = detectorAndroidClients.get(msg.deviceId);
    if (existing) {
      console.log(`[ws][${ts}] Android检测端 重复连接: ${msg.deviceName} (${msg.deviceId}) 踢掉旧连接`);
      detectorAndroidClients.delete(msg.deviceId);
      sendJson(existing.ws, { type: 'kicked', reason: 'duplicate connection' });
      existing.ws.terminate();
    }
    const client = createDetectorClient(ws, msg, 'android-detector');
    detectorAndroidClients.set(msg.deviceId, client);
    console.log(`[ws][${ts}] Android检测端 上线: ${msg.deviceName} (${msg.deviceId}) | Win:${detectorWindowsClients.size} AdrDet:${detectorAndroidClients.size} Recv:${receiverClients.size}`);
  } else if (msg.role === 'android') {
    const existing = receiverClients.get(msg.deviceId);
    if (existing) {
      console.log(`[ws][${ts}] 接收端 重复连接: ${msg.deviceId} 踢掉旧连接`);
      receiverClients.delete(msg.deviceId);
      sendJson(existing.ws, { type: 'kicked', reason: 'duplicate connection' });
      existing.ws.terminate();
    }
    const pingDeviceId = msg.deviceId;
    const client: ReceiverClient = { ws, deviceId: msg.deviceId, lastSeen: new Date() };
    receiverClients.set(msg.deviceId, client);

    const prevSession = androidSessions.get(msg.deviceId);
    const now = Date.now();
    const session: AndroidSession = {
      connectedAt: now,
      lastSessionEndReason: prevSession?.lastSessionEndReason ?? 'unknown',
      lastSessionDurationMs: prevSession ? now - prevSession.connectedAt : -1,
    };
    androidSessions.set(msg.deviceId, session);

    if (prevSession) {
      const durationSec = Math.round((now - prevSession.connectedAt) / 1000);
      const reasonDesc = SessionEndReasonNames[prevSession.lastSessionEndReason] ?? `code=${prevSession.lastSessionEndReason}`;
      console.log(`[ws][${ts}] 接收端 重连诊断: deviceId=${msg.deviceId} 上次持续${durationSec}s | 结束原因: ${reasonDesc} | 接收端在线=${receiverClients.size}`);
    } else {
      console.log(`[ws][${ts}] 接收端 首次连接: ${msg.deviceId} | 接收端在线=${receiverClients.size}`);
    }

    console.log(`[ws][${ts}] 接收端 上线: ${msg.deviceId}`);
  } else if (msg.role === 'windows-resident') {
    const existing = residentWindowsClients.get(msg.deviceId);
    if (existing) {
      residentWindowsClients.delete(msg.deviceId);
      sendJson(existing.ws, { type: 'kicked', reason: 'duplicate connection' });
      existing.ws.terminate();
    }
    residentWindowsClients.set(msg.deviceId, {
      ws, deviceId: msg.deviceId, deviceName: msg.deviceName || msg.deviceId,
      lastSeen: new Date(), components: { resident: 'running', wpfApp: 'stopped', winFormsApp: 'stopped' },
    });
    console.log(`[ws][${ts}] Windows驻留 上线: ${msg.deviceName} (${msg.deviceId})`);
  } else {
    console.log(`[ws][${ts}] 认证失败: 无效 role=${msg.role}`);
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid role' });
    ws.close();
    return;
  }

  console.log(`[ws][${ts}] 认证成功: role=${msg.role} deviceId=${msg.deviceId} deviceName=${msg.deviceName ?? 'n/a'}`);
  sendJson(ws, { type: 'auth-result', success: true });
  onSuccess(msg.role, msg.deviceId);
  sendJson(ws, { type: 'device-list', devices: buildDeviceList() });
  if (msg.role === 'windows' || msg.role === 'android-detector' || msg.role === 'windows-resident') {
    scheduleBroadcast();
  }
}

function handleResidentHeartbeat(deviceId: string, msg: WsResidentHeartbeat): void {
  const client = residentWindowsClients.get(deviceId);
  if (!client) return;
  const components = sanitizeComponents(msg.components) ?? client.components;
  const changed = JSON.stringify(components) !== JSON.stringify(client.components);
  client.components = components;
  client.lastSeen = new Date();
  sendJson(client.ws, { type: 'heartbeat-ack', deviceId, serverTime: client.lastSeen.toISOString() });
  if (changed) scheduleBroadcast();
}

// ════════════════════════════════════════════════════════════
// 心跳
// ════════════════════════════════════════════════════════════

const _heartbeatCounter = new Map<string, number>();

function handleHeartbeat(msg: WsHeartbeat): void {
  const client = findDetector(msg.deviceId);
  if (!client) {
    console.warn(`[ws][${new Date().toISOString()}] 心跳但客户端不存在: deviceId=${msg.deviceId}`);
    return;
  }

  const nameChanged = msg.deviceName !== undefined && msg.deviceName !== client.deviceName;
  const changed =
    client.isMonitoring !== msg.isMonitoring ||
    client.isReady !== msg.isReady ||
    client.cooldown !== (msg.cooldown ?? client.cooldown) ||
    client.confidence !== (msg.confidence ?? client.confidence) ||
    client.targets !== (msg.targets ?? client.targets) ||
    client.targetSamplingRate !== (msg.targetSamplingRate ?? client.targetSamplingRate) ||
    client.modelKey !== (msg.modelKey ?? client.modelKey) ||
    JSON.stringify(client.modelOptions) !== JSON.stringify(msg.modelOptions ?? client.modelOptions) ||
    client.canSwitchModelWhileMonitoring !== (msg.canSwitchModelWhileMonitoring ?? client.canSwitchModelWhileMonitoring) ||
    client.hasPendingConfigChanges !== (msg.hasPendingConfigChanges ?? client.hasPendingConfigChanges) ||
    JSON.stringify(client.capabilities) !== JSON.stringify(msg.capabilities ?? client.capabilities) ||
    JSON.stringify(client.components) !== JSON.stringify(msg.components ?? client.components) ||
    JSON.stringify(client.sources) !== JSON.stringify(msg.sources ?? client.sources) ||
    nameChanged;

  client.isMonitoring = msg.isMonitoring;
  client.isReady = msg.isReady ?? false;
  if (msg.cooldown !== undefined) client.cooldown = sanitizeHeartbeatCooldown(msg.cooldown) ?? client.cooldown;
  if (msg.confidence !== undefined) client.confidence = sanitizeHeartbeatConfidence(msg.confidence) ?? client.confidence;
  if (msg.targets !== undefined) client.targets = sanitizeHeartbeatTargets(msg.targets) ?? client.targets;
  if (msg.targetSamplingRate !== undefined) client.targetSamplingRate = sanitizeHeartbeatSamplingRate(msg.targetSamplingRate) ?? client.targetSamplingRate;
  if (msg.modelKey !== undefined) client.modelKey = sanitizeModelKey(msg.modelKey) ?? client.modelKey;
  if (msg.modelOptions !== undefined) client.modelOptions = sanitizeModelOptions(msg.modelOptions) ?? client.modelOptions;
  if (msg.canSwitchModelWhileMonitoring !== undefined) client.canSwitchModelWhileMonitoring = !!msg.canSwitchModelWhileMonitoring;
  if (msg.hasPendingConfigChanges !== undefined) client.hasPendingConfigChanges = !!msg.hasPendingConfigChanges;
  if (msg.capabilities !== undefined) client.capabilities = sanitizeCapabilities(msg.capabilities) ?? client.capabilities;
  if (msg.components !== undefined) client.components = sanitizeComponents(msg.components) ?? client.components;
  if (msg.sources !== undefined) client.sources = sanitizeSources(msg.sources) ?? client.sources;
  if (nameChanged) {
    client.deviceName = msg.deviceName!;
    console.log(`[ws][${new Date().toISOString()}] 设备名称更新: ${client.deviceName} (${msg.deviceId})`);
  }

  const count = (_heartbeatCounter.get(msg.deviceId) ?? 0) + 1;
  _heartbeatCounter.set(msg.deviceId, count % 60 === 0 ? 0 : count);
  if (count === 1 || count % 60 === 0) {
    const silentSec = Math.round((Date.now() - client.lastSeen.getTime()) / 1000);
    const roleLabel = client.clientType === 'android-detector' ? 'Android检测端' : 'Windows';
    console.log(`[ws][${new Date().toISOString()}] ${roleLabel} 心跳: ${client.deviceName} (${msg.deviceId}) monitoring=${msg.isMonitoring} 静默${silentSec}s`);
  }

  client.lastSeen = new Date();
  sendJson(client.ws, {
    type: 'heartbeat-ack',
    deviceId: msg.deviceId,
    serverTime: client.lastSeen.toISOString(),
  }, `heartbeat-ack:${msg.deviceId}`);
  if (changed) scheduleBroadcast();
}

function handleHeartbeatReceiver(msg: WsHeartbeatAndroid): void {
  const client = receiverClients.get(msg.deviceId);
  if (!client) {
    console.warn(`[ws][${new Date().toISOString()}] 接收端心跳但客户端不存在: deviceId=${msg.deviceId}`);
    return;
  }
  client.lastSeen = new Date();
  sendJson(client.ws, {
    type: 'heartbeat-ack',
    deviceId: msg.deviceId,
    serverTime: client.lastSeen.toISOString(),
  }, `heartbeat-ack:${msg.deviceId}`);
}

// ════════════════════════════════════════════════════════════
// 诊断消息
// ════════════════════════════════════════════════════════════

function handleDisconnectReason(msg: WsDisconnectReason, role: string | null, deviceId: string | null): void {
  const ts = new Date().toISOString();
  console.log(`[ws][${ts}] 客户端断开原因报告: deviceId=${deviceId ?? '?'} role=${role ?? '?'} reason=${msg.reason} detail=${msg.detail ?? 'n/a'}`);
  if (deviceId && role === 'android') {
    const session = androidSessions.get(deviceId);
    if (session) {
      session.lastSessionEndReason = msg.reason;
      session.lastSessionDurationMs = Date.now() - session.connectedAt;
    }
  }
}

function handleSessionInfo(msg: WsSessionInfo): void {
  const ts = new Date().toISOString();
  const durationSec = msg.lastSessionDurationMs >= 0 ? `${Math.round(msg.lastSessionDurationMs / 1000)}s` : '未知';
  const reasonDesc = SessionEndReasonNames[msg.lastSessionEndReason] ?? msg.lastSessionEndReason;
  console.log(`[ws][${ts}] 接收端 Session 上报: deviceId=${msg.deviceId} isReconnect=${msg.isReconnect} 上次结束原因=${reasonDesc} 上次持续=${durationSec}`);
  const session = androidSessions.get(msg.deviceId);
  if (session) {
    session.lastSessionEndReason = msg.lastSessionEndReason;
    if (msg.lastSessionDurationMs >= 0) session.lastSessionDurationMs = msg.lastSessionDurationMs;
  }
}

// ════════════════════════════════════════════════════════════
// 命令中继
// ════════════════════════════════════════════════════════════

function handleCommand(senderWs: WebSocket, msg: WsCommand): void {
  const residentCommand = /^(open|close)-(wpf|winforms)$/.test(msg.command);
  const target = residentCommand ? residentWindowsClients.get(msg.targetDeviceId) : findDetector(msg.targetDeviceId);

  const ack: WsCommandAck = {
    type: 'command-ack', targetDeviceId: msg.targetDeviceId,
    targetSourceId: msg.targetSourceId, requestId: msg.requestId, command: msg.command, success: false, reason: '',
  };

  if (msg.requestId !== undefined && !isValidRequestId(msg.requestId)) {
    ack.phase = 'completed';
    ack.reason = '无效的 requestId';
    sendJson(senderWs, ack, 'command-ack->sender');
    return;
  }

  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    ack.reason = residentCommand ? '驻留组件离线' : '设备离线';
    sendJson(senderWs, ack, 'command-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 命令路由失败: target=${msg.targetDeviceId} command=${msg.command} reason=设备离线`);
    return;
  }

  if (msg.targetSourceId !== undefined && !/^[A-Za-z0-9_-]{1,64}$/.test(msg.targetSourceId)) {
    ack.phase = 'completed'; ack.reason = '无效的 targetSourceId';
    sendJson(senderWs, ack, 'command-ack->sender'); return;
  }
  if (msg.targetSourceId && (residentCommand || !(target as DetectorClient).capabilities.includes('source-control'))) {
    ack.phase = 'completed'; ack.reason = '目标不支持逐来源控制';
    sendJson(senderWs, ack, 'command-ack->sender'); return;
  }
  if (msg.requestId && !registerPendingControlRequest(msg.requestId, senderWs, msg.targetDeviceId, msg.command, msg.targetSourceId)) {
    ack.phase = 'completed';
    ack.reason = 'requestId 重复';
    sendJson(senderWs, ack, 'command-ack->sender');
    return;
  }

  const relay: WsCommandRelay = { type: 'command', requestId: msg.requestId, command: msg.command, targetDeviceId: msg.targetDeviceId, targetSourceId: msg.targetSourceId };
  sendJson(target.ws, relay, `command->${msg.targetDeviceId}`);
  ack.success = true;
  ack.phase = 'forwarded';
  ack.reason = '已转发';
  sendJson(senderWs, ack, 'command-ack->sender');
}

function handleSetConfig(senderWs: WebSocket, msg: WsSetConfig): void {
  if (msg.requestId !== undefined && !isValidRequestId(msg.requestId)) {
    sendJson(senderWs, {
      type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      command: `set-config:${msg.key}`, success: false, reason: '无效的 requestId',
    }, 'set-config-ack->sender');
    return;
  }
  if (!isValidSetConfigKey(msg.key)) {
    sendJson(senderWs, {
      type: 'command-ack', targetDeviceId: msg.targetDeviceId,
      command: `set-config:${msg.key}`, success: false, reason: `无效的配置项: ${msg.key}`,
    }, 'set-config-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 配置更新拒绝: target=${msg.targetDeviceId} key=${msg.key} reason=invalid-key`);
    return;
  }

  const target = findDetector(msg.targetDeviceId);
  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    sendJson(senderWs, { type: 'command-ack', targetDeviceId: msg.targetDeviceId, command: `set-config:${msg.key}`, success: false, reason: '设备离线' }, 'set-config-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 配置更新路由失败: target=${msg.targetDeviceId} key=${msg.key} reason=设备离线`);
    return;
  }

  const validation = validateSetConfigValue(msg.key, msg.value, target.modelOptions);
  if (!validation.ok) {
    sendJson(senderWs, { type: 'command-ack', targetDeviceId: msg.targetDeviceId, command: `set-config:${msg.key}`, success: false, reason: validation.reason }, 'set-config-ack->sender');
    return;
  }

  const sanitizedValue = validation.value;
  const command = `set-config:${msg.key}`;
  if (msg.targetSourceId !== undefined && !/^[A-Za-z0-9_-]{1,64}$/.test(msg.targetSourceId)) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      targetSourceId: msg.targetSourceId, command: `set-config:${msg.key}`, success: false, reason: '无效的 targetSourceId' }, 'set-config-ack->sender');
    return;
  }
  if (msg.targetSourceId && !target.capabilities.includes('source-control')) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      targetSourceId: msg.targetSourceId, command: `set-config:${msg.key}`, success: false, reason: '目标不支持逐来源控制' }, 'set-config-ack->sender');
    return;
  }
  if (msg.requestId && !registerPendingControlRequest(msg.requestId, senderWs, msg.targetDeviceId, command, msg.targetSourceId)) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId, command, success: false, reason: 'requestId 重复' }, 'set-config-ack->sender');
    return;
  }
  const relay: WsSetConfigRelay = { type: 'set-config', requestId: msg.requestId, key: msg.key, value: sanitizedValue, targetDeviceId: msg.targetDeviceId, targetSourceId: msg.targetSourceId };
  sendJson(target.ws, relay, `set-config->${msg.targetDeviceId}`);
  sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'forwarded', targetDeviceId: msg.targetDeviceId, targetSourceId: msg.targetSourceId, command, success: true, reason: '已转发' }, 'set-config-ack->sender');
}

function handleRequestScreenshot(senderWs: WebSocket, msg: any): void {
  const alertId = msg?.alertId;
  const targetDeviceId = typeof msg?.targetDeviceId === 'string' ? msg.targetDeviceId : '';
  if (!isSafeAlertId(alertId) || !targetDeviceId) {
    sendJson(senderWs, {
      type: 'command-ack',
      targetDeviceId,
      command: 'request-screenshot',
      success: false,
      reason: '无效的截图请求',
    }, 'request-screenshot-ack->sender');
    return;
  }

  const target = findDetector(targetDeviceId);
  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    sendJson(senderWs, {
      type: 'command-ack',
      targetDeviceId,
      command: 'request-screenshot',
      success: false,
      reason: '设备离线',
    }, 'request-screenshot-ack->sender');
    return;
  }

  sendJson(target.ws, {
    type: 'request-screenshot',
    alertId,
    targetDeviceId,
  }, `request-screenshot->${targetDeviceId}`);
}

function handleCommandAck(ack: WsCommandAck, detectorDeviceId: string): void {
  const enriched = { ...ack, targetDeviceId: detectorDeviceId };
  if (ack.requestId) {
    const pending = pendingControlRequests.get(ack.requestId);
    if (!pending || pending.targetDeviceId !== detectorDeviceId || pending.command !== ack.command || pending.targetSourceId !== ack.targetSourceId) {
      console.warn(`[ws][${new Date().toISOString()}] 忽略无法关联的命令回执: requestId=${ack.requestId} target=${detectorDeviceId} command=${ack.command}`);
      return;
    }
    clearTimeout(pending.timer);
    pendingControlRequests.delete(ack.requestId);
    rememberCompletedRequest(ack.requestId);
    sendJson(pending.senderWs, { ...enriched, phase: 'completed' }, `command-ack:${ack.command}->requester`);
    return;
  }

  // 旧 Windows 客户端不会回显 requestId。仅当目标和命令恰好匹配一个
  // 待处理请求时安全关联；有歧义时维持旧广播行为并让新请求按超时收敛。
  const legacyMatches = Array.from(pendingControlRequests.entries())
    .filter(([, pending]) => pending.targetDeviceId === detectorDeviceId && pending.command === ack.command);
  if (legacyMatches.length === 1) {
    const [requestId, pending] = legacyMatches[0];
    clearTimeout(pending.timer);
    pendingControlRequests.delete(requestId);
    rememberCompletedRequest(requestId);
    sendJson(pending.senderWs, { ...enriched, requestId, phase: 'completed' }, `legacy-command-ack:${ack.command}->requester`);
    return;
  }
  broadcastToReceivers(enriched, `command-ack:${ack.command}`);
}

// ════════════════════════════════════════════════════════════
// 广播
// ════════════════════════════════════════════════════════════

let lastDeviceListSignature = '';

function buildDeviceListSignature(list: DeviceStatus[]): string {
  return JSON.stringify(list.map(({ lastSeen, ...stable }) => stable));
}

function broadcastDeviceList(): void {
  const list = buildDeviceList();
  const signature = buildDeviceListSignature(list);
  if (signature === lastDeviceListSignature) return;
  lastDeviceListSignature = signature;
  const msg = { type: 'device-list', devices: list };
  broadcastToReceivers(msg, 'device-list');
}

function buildDeviceList(): DeviceStatus[] {
  const now = Date.now();
  const devices: DeviceStatus[] = [];
  for (const clients of [detectorWindowsClients, detectorAndroidClients]) {
    for (const c of clients.values()) {
      devices.push({
        deviceId: c.deviceId,
        deviceName: c.deviceName,
        online: (now - c.lastSeen.getTime()) < config.deviceOfflineMs,
        isMonitoring: c.isMonitoring,
        isReady: c.isReady,
        lastSeen: c.lastSeen.toISOString(),
        cooldown: c.cooldown,
        confidence: c.confidence,
        targets: c.targets,
        targetSamplingRate: c.targetSamplingRate,
        modelKey: c.modelKey,
        modelOptions: c.modelOptions,
        canSwitchModelWhileMonitoring: c.canSwitchModelWhileMonitoring,
        hasPendingConfigChanges: c.hasPendingConfigChanges,
        clientType: c.clientType,
        capabilities: residentWindowsClients.has(c.deviceId)
          ? Array.from(new Set([...c.capabilities, 'app-lifecycle-control'])) : c.capabilities,
        components: residentWindowsClients.has(c.deviceId)
          ? { ...residentWindowsClients.get(c.deviceId)!.components, ...c.components, resident: 'running' } : c.components,
        sources: c.sources,
      });
    }
  }
  for (const r of residentWindowsClients.values()) {
    if (devices.some(d => d.deviceId === r.deviceId)) continue;
    devices.push({
      deviceId: r.deviceId, deviceName: r.deviceName,
      online: (now - r.lastSeen.getTime()) < config.deviceOfflineMs,
      isMonitoring: false, isReady: false, lastSeen: r.lastSeen.toISOString(),
      cooldown: 5, confidence: 0.45, targets: '', targetSamplingRate: 3,
      modelKey: '', modelOptions: [], canSwitchModelWhileMonitoring: false,
      hasPendingConfigChanges: false, clientType: 'windows',
      capabilities: ['app-lifecycle-control'], components: { ...r.components, resident: 'running' },
      sources: [],
    });
  }
  return devices;
}

function sendJson(ws: WebSocket, obj: object, context?: string): boolean {
  if (ws.readyState !== WebSocket.OPEN) {
    console.warn(`[ws][${new Date().toISOString()}] 消息发送失败 (连接未开放)${context ? ` [${context}]` : ''}`);
    return false;
  }
  try {
    ws.send(JSON.stringify(obj));
    return true;
  } catch (err: any) {
    console.error(`[ws][${new Date().toISOString()}] 消息发送异常${context ? ` [${context}]` : ''}: ${err.message}`);
    return false;
  }
}

function broadcastToReceivers(msg: object, context?: string): { success: number; failed: number } {
  let success = 0, failed = 0;
  for (const client of receiverClients.values()) {
    if (sendJson(client.ws, msg, context)) success++;
    else failed++;
  }
  return { success, failed };
}

// ════════════════════════════════════════════════════════════
// 定时维护：每 30s
// ════════════════════════════════════════════════════════════

const maintenanceTimer = setInterval(() => {
  const now = Date.now();
  const ts = new Date().toISOString();
  const detectorDeadline = now - config.deviceOfflineMs;
  const receiverDeadline = now - config.receiverGhostThresholdMs;
  let detectorCleaned = false;

  // 接收端幽灵清理（阈值更长，容忍移动网络抖动）
  for (const [id, client] of receiverClients) {
    if (client.lastSeen.getTime() <= receiverDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] 接收端幽灵清理: ${id} (静默 ${silentSec}s 阈值 ${config.receiverGhostThresholdMs / 1000}s)`);
      client.ws.terminate();
      receiverClients.delete(id);
      screenshotQueues.delete(id);
      screenshotProcessing.delete(id);
    }
  }

  // 检测端幽灵清理
  for (const clients of [detectorWindowsClients, detectorAndroidClients]) {
    for (const [id, client] of clients) {
      if (client.lastSeen.getTime() <= detectorDeadline) {
        const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
        const roleLabel = client.clientType === 'android-detector' ? 'Android检测端' : 'Windows';
        console.log(`[ws][${ts}] ${roleLabel} 幽灵清理: ${client.deviceName} (${id}) 静默 ${silentSec}s 阈值 ${config.deviceOfflineMs / 1000}s`);
        client.ws.terminate();
        clients.delete(id);
        _heartbeatCounter.delete(id);
        detectorCleaned = true;
      }
    }
  }

  if (detectorCleaned && (receiverClients.size > 0 || detectorWindowsClients.size > 0 || detectorAndroidClients.size > 0)) {
    broadcastDeviceList();
    console.log(`[ws][${ts}] 设备清理后推送 → 接收端:${receiverClients.size} / Windows:${detectorWindowsClients.size} / Android检测端:${detectorAndroidClients.size}`);
  }
}, 30_000);
maintenanceTimer.unref();
