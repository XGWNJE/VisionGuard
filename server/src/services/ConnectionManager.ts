// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionManager.ts  current                          │
// │ 角色：WebSocket 连接管理 (按 role 独立 Map 跟踪)          │
// │ 职责：认证、心跳、设备列表广播、报警广播(含截图推送)      │
// │ 对外 API：handleConnection(), broadcastAlert(),           │
// │          getConnectionCount()                             │
// └─────────────────────────────────────────────────────────┘

import WebSocket from 'ws';
import fs from 'fs';
import { config } from '../config';
import crypto from 'node:crypto';
import path from 'node:path';
import { accountStore, accountDirectory, type AccountSession } from './AccountStore';
import { mediaRelay } from './MediaRelay';
import { NotificationScopeStore, parseNotificationScope, scopeAccepts } from './NotificationScopeStore';
import { TimeStandardStore, validAlarmTimeZone } from './TimeStandardStore';
import { authenticateNode, clientType, allowedCapabilities, validateEvent, registeredNodes, REALTIME_TTL_MS, DETECTION_STALL_MS, type NodeIdentity, type NodeRole } from './NodeProtocol';
import { addAlert, getAlertById, markAlertScreenshot, type AddAlertResult } from '../services/AlertStore';
import { isValidSetConfigKey, validateSetConfigValue } from '../services/ControlProtocol';
import { getSafeScreenshotPath, isSafeAlertId, validateAlertMeta, validateImageMagic } from '../utils/security';
import type {
  WsAuthMessage, WsHeartbeat, WsHeartbeatAndroid, WsCommand, WsSetConfig,
  DetectorClient, ReceiverClient, WsAlertPush, WsScreenshotDataPush,
  DeviceStatus, WsCommandRelay, WsSetConfigRelay, WsCommandAck,
  WsDisconnectReason, WsSessionInfo, WsResidentHeartbeat, ResidentClient,
  AlertRecord, SourceStatus,
} from '../models/types';

function createAccountConnections(accountId: string) {
// Roles route by responsibility; platform and node type stay on the registered identity.
const detectorClients = new Map<string, DetectorClient>();
const notifierClients = new Map<string, ReceiverClient>();
const receiverClients = new Map<string, ReceiverClient>();
const residentWindowsClients = new Map<string, ResidentClient>();
const pendingDetectorRemoval = new Map<string, NodeJS.Timeout>();
const DETECTOR_RECONNECT_GRACE_MS = 10_000;
const COMMAND_TIMEOUT_MS = 15_000;
const COMPLETED_REQUEST_TTL_MS = 60_000;

interface PendingControlRequest {
  senderWs: WebSocket;
  targetWs: WebSocket;
  targetDeviceId: string;
  command: string;
  targetSourceId?: string;
  timer: NodeJS.Timeout;
}

const pendingControlRequests = new Map<string, PendingControlRequest>();
const completedControlRequests = new Map<string, number>();
const notificationScopes = new NotificationScopeStore(path.join(accountDirectory(accountId), 'notification-scopes.json'));
const timeStandard = new TimeStandardStore(path.join(accountDirectory(accountId), 'time-standard.json'));

function rememberCompletedRequest(requestId: string): void {
  const now = Date.now();
  completedControlRequests.set(requestId, now + COMPLETED_REQUEST_TTL_MS);
  for (const [id, expiresAt] of completedControlRequests) {
    if (expiresAt <= now) completedControlRequests.delete(id);
  }
}

function associateScreenshotPayload(
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

function getConnectionCount(): number {
  return detectorClients.size + notifierClients.size + residentWindowsClients.size + receiverClients.size;
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
  clientType: string,
  deviceId: string,
  ws: WebSocket,
): void {
  clearPendingDetectorRemoval(clientType, deviceId);
  const timer = setTimeout(() => {
    pendingDetectorRemoval.delete(detectorRemovalKey(clientType, deviceId));
    const existing = clients.get(deviceId);
    if (!existing || existing.ws !== ws) return;
    emitFault(existing, 'connection-lost');
    clients.delete(deviceId);
    _heartbeatCounter.delete(deviceId);
    scheduleBroadcast();
  }, DETECTOR_RECONNECT_GRACE_MS);
  timer.unref();
  pendingDetectorRemoval.set(detectorRemovalKey(clientType, deviceId), timer);
}

// ── 输入校验 ────────────────────────────────────────────────

const MAX_TARGETS_LENGTH = 500;
const MAX_MODEL_OPTIONS = 16;
const MAX_COMPONENTS = 8;
const DETECTOR_COMMANDS = new Set(['pause', 'resume', 'stop-alarm']);
// Windows 只剩一个检测端，生命周期命令统一为 detector。
const RESIDENT_COMMANDS = new Set(['open-detector', 'close-detector']);

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
  if (value.length > config.maxSourcesPerDetector) return undefined;
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const sourceId = typeof item.sourceId === 'string' ? item.sourceId.trim() : '';
    const sourceName = typeof item.sourceName === 'string' ? item.sourceName.trim() : '';
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(sourceId) || !sourceName || sourceName.length > 64 || ids.has(sourceId)) continue;
    ids.add(sourceId);
    const actualFps = typeof item.actualFps === 'number' && isFinite(item.actualFps)
      ? Math.max(0, Math.min(240, item.actualFps)) : undefined;
    const error = typeof item.error === 'string' && item.error.trim()
      ? item.error.trim().slice(0, 256) : undefined;
    const activeBackend = typeof item.activeBackend === 'string' && /^(Cpu|DirectML|NNAPI|Unavailable)$/.test(item.activeBackend)
      ? item.activeBackend : undefined;
    const performanceWarning = typeof item.performanceWarning === 'string' && item.performanceWarning.trim()
      ? item.performanceWarning.trim().slice(0, 256) : undefined;
    const cooldown = typeof item.cooldown === 'number' && Number.isInteger(item.cooldown)
      ? Math.max(1, Math.min(300, item.cooldown)) : undefined;
    const confidence = typeof item.confidence === 'number' && isFinite(item.confidence)
      ? Math.max(0.1, Math.min(0.95, item.confidence)) : undefined;
    const targets = typeof item.targets === 'string'
      ? item.targets.trim().slice(0, 256) : undefined;
    const targetSamplingRate = typeof item.targetSamplingRate === 'number' && Number.isInteger(item.targetSamplingRate)
      ? Math.max(1, Math.min(5, item.targetSamplingRate)) : undefined;
    sources.push({
      monitoringExpected: typeof item.monitoringExpected === 'boolean' ? item.monitoringExpected : undefined,
      lastProgressAt: validProgress(item.lastProgressAt) ? item.lastProgressAt : undefined,
      sourceId, sourceName, isMonitoring: !!item.isMonitoring, isReady: !!item.isReady,
      modelKey: sanitizeModelKey(item.modelKey) ?? '', actualFps, error, activeBackend, performanceWarning,
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
  const targetWs = (RESIDENT_COMMANDS.has(command) ? residentWindowsClients.get(targetDeviceId) : findDetector(targetDeviceId))!.ws;
  pendingControlRequests.set(requestId, { senderWs, targetWs, targetDeviceId, command, targetSourceId, timer });
  return true;
}

function createDetectorClient(
  ws: WebSocket,
  msg: NodeIdentity,
  clientType: string,
): DetectorClient {
  return {
    identity: msg,
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
    sourceLimitExceeded: false,
  };
}

// ── 控制台 Session 追踪 ─────────────────────────────────────
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

// ── 截图推送队列 (协议分离: 截图独立异步,按控制台串行 500ms stagger) ──
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

    fs.mkdirSync(path.join(accountDirectory(accountId), 'screenshots'), { recursive: true });
    const target = getSafeScreenshotPath(path.join(accountDirectory(accountId), 'screenshots'), payload.alertId, contentType);
    if (!target) {
      console.warn(`[ws] screenshot backup rejected: alertId=${payload.alertId} reason=invalid-alert-id`);
      return false;
    }
    fs.writeFileSync(target.filePath, bytes);
    const linked = markAlertScreenshot(accountId, payload.alertId, target.filePath);
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

// ── 辅助：查找检测节点 ─────────────────────────────────────
function findDetector(deviceId: string): DetectorClient | undefined {
  return detectorClients.get(deviceId);
}

// ════════════════════════════════════════════════════════════
// 公开 API
// ════════════════════════════════════════════════════════════

function handleConnection(ws: WebSocket): void {
  let authenticated = false;
  let role: NodeRole | null = null;
  let identity: NodeIdentity | undefined;
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
    const token = socketTokens.get(ws);
    if (authenticated && !accountStore.authenticate(token)) { ws.close(4001, 'session revoked'); return; }
    let msg: any;
    try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return;

    if (!authenticated) {
      if (msg.type === 'auth') {
        handleAuth(ws, msg as WsAuthMessage, authTimer, (r, d) => {
          authenticated = true;
          role = r;
          identity = authenticateNode(msg);
          deviceId = d;
        });
      }
      return;
    }
    const authenticatedDeviceId = deviceId;
    if (!authenticatedDeviceId) return;
    const owner = role === 'detector' ? detectorClients.get(authenticatedDeviceId) : role === 'lifecycle' ? residentWindowsClients.get(authenticatedDeviceId) : role === 'notifier' ? notifierClients.get(authenticatedDeviceId) : receiverClients.get(authenticatedDeviceId);
    if (owner?.ws !== ws) return;

    switch (msg.type) {
      case 'heartbeat':
        if (role === 'detector') {
          handleHeartbeat({ ...(msg as WsHeartbeat), deviceId: authenticatedDeviceId });
        }
        break;
      case 'heartbeat-console':
        if (role === 'console') handleHeartbeatReceiver({ ...(msg as WsHeartbeatAndroid), deviceId: authenticatedDeviceId });
        break;
      case 'heartbeat-notifier':
        if (role === 'notifier') {
          const client = notifierClients.get(authenticatedDeviceId);
          if (client?.ws === ws) { client.lastSeen = new Date(); sendJson(ws, { type: 'heartbeat-ack', serverTime: new Date().toISOString() }); }
        }
        break;
      case 'notification-receipt':
        if (role === 'notifier') acknowledgeDelivery(authenticatedDeviceId, msg.alertId);
        break;
      case 'resident-heartbeat':
        if (role === 'lifecycle') handleResidentHeartbeat(authenticatedDeviceId, msg as WsResidentHeartbeat);
        break;
      case 'alert':
        if (role === 'detector') {
          let alert = msg as WsAlertPush;
          if (!isSafeAlertId(alert.alertId)) break;
          const client = findDetector(authenticatedDeviceId);
          const event = identity && validateEvent(alert, identity);
          if (!event) { sendJson(ws, { type: 'alert-ack', alertId: alert.alertId, accepted: false, reason: 'invalid-or-expired-event' }); break; }
          if (identity?.nodeType === 'visual' && !client?.sources.some(source => source.sourceId === alert.sourceId)) {
            sendJson(ws, { type: 'alert-ack', alertId: alert.alertId, accepted: false, reason: 'unknown-source' }); break;
          }
          const metaResult = validateAlertMeta({
            ...event,
            deviceId: authenticatedDeviceId,
            deviceName: client?.deviceName || alert.deviceName || authenticatedDeviceId,
            sourceId: alert.sourceId,
            sourceName: alert.sourceName,
            timestamp: alert.timestamp,
            detections: alert.detections,
          });
          if (!metaResult.ok || !metaResult.value) {
            sendJson(ws, { type: 'alert-ack', alertId: alert.alertId, accepted: false, reason: 'invalid-or-expired-event' }); break;
          }
          const source = client?.sources.find(item => item.sourceId === alert.sourceId);
          alert = { type: 'alert', alertId: alert.alertId, ...event, nodeType: identity!.nodeType,
            deviceId: metaResult.value.deviceId, deviceName: metaResult.value.deviceName,
            timestamp: metaResult.value.timestamp, detections: metaResult.value.detections,
            sourceId: source?.sourceId, sourceName: source?.sourceName,
            capturedAt: typeof alert.capturedAt === 'string' && validProgress(alert.capturedAt) ? alert.capturedAt : undefined,
            timings: alert.timings && typeof alert.timings === 'object' && !Array.isArray(alert.timings)
              ? Object.fromEntries(Object.entries(alert.timings).slice(0, 32).filter(([key, value]) => /^[A-Za-z0-9_-]{1,64}$/.test(key) && typeof value === 'number' && Number.isFinite(value) && value >= 0)) : undefined };
          const createdAt = Date.now();
          alert.serverReceivedAt = new Date(createdAt).toISOString();
          alert.createdAt = createdAt;
          let insertResult: AddAlertResult;
          try {
            insertResult = addAlert(accountId, {
              ...event, nodeType: identity!.nodeType,
              alertId: alert.alertId,
              deviceId: alert.deviceId,
              deviceName: alert.deviceName,
              sourceId: alert.sourceId,
              sourceName: alert.sourceName,
              timestamp: alert.timestamp,
              detections: alert.detections,
              createdAt,
            });
          } catch (error: any) {
            console.error(`[ws] 报警持久化失败: alertId=${alert.alertId} error=${error?.message ?? error}`);
            sendJson(ws, {
              type: 'alert-ack', alertId: alert.alertId, accepted: false, duplicate: false,
              reason: 'storage-failed', serverReceivedAt: alert.serverReceivedAt,
            }, `alert-ack:${alert.alertId}`);
            break;
          }
          if (insertResult === 'conflict') {
            sendJson(ws, {
              type: 'alert-ack', alertId: alert.alertId, accepted: false, duplicate: false,
              reason: 'alert-id-conflict', serverReceivedAt: alert.serverReceivedAt,
            }, `alert-ack:${alert.alertId}`);
            break;
          }
          if (insertResult === 'stored') broadcastAlert(alert);
          sendJson(ws, {
            type: 'alert-ack', alertId: alert.alertId, accepted: true,
            duplicate: insertResult === 'duplicate', reason: insertResult,
            serverReceivedAt: alert.serverReceivedAt,
          }, `alert-ack:${alert.alertId}`);
        }
        break;
      case 'screenshot-data':
        if (role === 'detector') {
          const payload = msg as WsScreenshotDataPush;
          if (identity?.nodeType !== 'visual' || !isSafeAlertId(payload.alertId) || typeof payload.imageBase64 !== 'string') break;
          const associatedPayload = associateScreenshotPayload(payload, authenticatedDeviceId, getAlertById(accountId, payload.alertId));
          if (!associatedPayload) {
            console.warn(`[ws] screenshot rejected: alertId=${payload.alertId} reason=alert-device-mismatch-or-missing`);
            break;
          }
          if (!backupScreenshot(associatedPayload)) break;
          broadcastScreenshotData(associatedPayload);
        }
        break;
      case 'command':
        if (role === 'console') handleCommand(ws, msg as WsCommand);
        break;
      case 'set-config':
        if (role === 'console') handleSetConfig(ws, msg as WsSetConfig);
        break;
      case 'get-streams':
        if (identity) sendJson(ws, { type: 'stream-list', streams: mediaRelay.streams(accountId) });
        break;
      case 'get-devices':
        if (role === 'console') sendJson(ws, { type: 'device-list', devices: buildDeviceList() });
        break;
      case 'get-notification-scopes':
        if (role === 'console') sendJson(ws, notificationScopeList());
        break;
      case 'set-notification-scope':
        if (role === 'console') handleNotificationScope(ws, msg);
        break;
      case 'get-time-standard':
        if (role === 'console') sendJson(ws, { type: 'time-standard', ...timeStandard.get() });
        break;
      case 'set-time-standard':
        if (role === 'console') handleTimeStandard(ws, msg);
        break;
      case 'request-screenshot':
        if (role === 'console') handleRequestScreenshot(ws, msg);
        break;
      case 'command-ack':
        if (role === 'detector' || role === 'lifecycle') handleCommandAck(msg as WsCommandAck, deviceId!, ws);
        break;
      case 'disconnect-reason':
        if (role === 'console') handleDisconnectReason(msg as WsDisconnectReason, role, deviceId);
        break;
      case 'session-info':
        // 会话信息只对控制台有意义，且必须绑定认证身份，不能由消息自称 deviceId。
        if (role === 'console' && deviceId) handleSessionInfo(msg as WsSessionInfo, deviceId);
        break;
    }
  });

  ws.on('close', (code) => {
    clearTimeout(authTimer);
    const ts2 = new Date().toISOString();
    const codeName = getCloseCodeName(code);
    if (deviceId) {
      if (role === 'detector') {
        const existing = detectorClients.get(deviceId);
        if (existing?.ws === ws) scheduleDetectorRemoval(detectorClients, existing.clientType, deviceId, ws);
      } else if (role === 'console') {
        const existing = receiverClients.get(deviceId);
        if (existing && existing.ws === ws) {
          const endReason = closeCodeToSessionEndReason(code, deviceId);
          const session = androidSessions.get(deviceId);
          if (session) {
            session.lastSessionEndReason = endReason;
            session.lastSessionDurationMs = Date.now() - session.connectedAt;
          }
          receiverClients.delete(deviceId);
          console.log(`[ws][${ts2}] 控制台 断开: ${deviceId} code=${code}(${codeName}) 推断原因=${endReason} 控制台在线=${receiverClients.size}`);
        } else {
          console.log(`[ws][${ts2}] 控制台 旧连接关闭: ${deviceId} code=${code}(${codeName})`);
        }
      } else if (role === 'notifier') {
        if (notifierClients.get(deviceId)?.ws === ws) notifierClients.delete(deviceId);
        dropDeliveries(deviceId, ws);
      } else if (role === 'lifecycle') {
        const existing = residentWindowsClients.get(deviceId);
        if (existing?.ws === ws) {
          residentWindowsClients.delete(deviceId);
          scheduleBroadcast();
          console.log(`[ws][${ts2}] 视觉驻留 断开: ${deviceId}`);
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

function broadcastAlert(alert: WsAlertPush): void {
  if (!alert.expiresAt || Date.parse(alert.expiresAt) <= Date.now()) return;
  for (const [id, client] of notifierClients) {
    if (!scopeAccepts(notificationScopes.get(id), alert)) continue;
    const key = `${id}:${alert.alertId}`;
    if (deliveries.has(key)) continue;
    deliveries.set(key, { notifierId: id, ws: client.ws, alert, lastSent: Date.now() });
    sendJson(client.ws, alert, 'notification-delivery');
  }
  alert.serverRelayedAt = new Date().toISOString();
  // 协议分离: alert 元数据 <1KB,永远并行广播,不入串行队列
  const result = broadcastToReceivers(alert, `alert:${alert.alertId}`);
  console.log(`[ws][${new Date().toISOString()}] 报警广播: alertId=${alert.alertId} 控制台=${receiverClients.size} 成功=${result.success}/${result.success + result.failed}`);
}

/**
 * 截图独立异步广播 — 走 500ms 串行队列,防止多控制台并发下行拥塞。
 * 协议分离后,alert 已先行送达,本函数仅负责补传 BigPicture。
 */
function broadcastScreenshotData(payload: WsScreenshotDataPush): void {
  for (const [rid] of receiverClients) {
    enqueueScreenshotPush(rid, payload.alertId, payload);
  }
  console.log(`[ws][${new Date().toISOString()}] 截图广播入队: alertId=${payload.alertId} 控制台=${receiverClients.size}`);
}

// ════════════════════════════════════════════════════════════
// 认证
// ════════════════════════════════════════════════════════════

function handleAuth(
  ws: WebSocket, msg: WsAuthMessage, authTimer: NodeJS.Timeout,
  onSuccess: (role: NodeRole, deviceId: string) => void,
): void {
  clearTimeout(authTimer);
  const identity = authenticateNode(msg);
  if (!identity || identity.accountId !== accountId) {
    sendJson(ws, { type: 'auth-result', success: false, reason: 'invalid session' });
    ws.close(); return;
  }
  const map = identity.role === 'detector' ? detectorClients : identity.role === 'console' ? receiverClients
    : identity.role === 'notifier' ? notifierClients : residentWindowsClients;
  const existing = map.get(identity.deviceId);
  if (existing) { map.delete(identity.deviceId); sendJson(existing.ws, { type: 'kicked', reason: 'duplicate connection' }); existing.ws.terminate(); }
  if (identity.role === 'detector') {
    clearPendingDetectorRemoval(clientType(identity), identity.deviceId);
    const client = createDetectorClient(ws, identity, clientType(identity));
    client.identity = identity;
    detectorClients.set(identity.deviceId, client);
    const resident = residentWindowsClients.get(identity.deviceId);
    if (resident) resident.deviceName = identity.deviceName;
  } else if (identity.role === 'lifecycle') {
    residentWindowsClients.set(identity.deviceId, { ws, identity, deviceId: identity.deviceId,
      deviceName: detectorClients.get(identity.deviceId)?.deviceName || identity.deviceName,
      lastSeen: new Date(), components: { resident: 'running', detectorApp: 'stopped' } });
  } else {
    (identity.role === 'console' ? receiverClients : notifierClients).set(identity.deviceId, { ws, deviceId: identity.deviceId, deviceName: identity.deviceName, identity, lastSeen: new Date() });
  }
  sendJson(ws, { type: 'auth-result', success: true, identity, account: accountStore.authenticate(msg.token)!.account, ...identity, channel: config.channelId, maxSources: config.maxSourcesPerDetector,
    timeStandard: timeStandard.get(),
    ...(identity.role === 'notifier' ? { notificationScope: notificationScopes.get(identity.deviceId) } : {}),
    realtimeTtlMs: REALTIME_TTL_MS, heartbeatIntervalMs: identity.role === 'console' ? 30_000 : 3000, connectionTimeoutMs: 45_000 });
  onSuccess(identity.role, identity.deviceId);
  socketTokens.set(ws, msg.token);
  mediaRelay.ensureAccount(accountId);
  sendJson(ws, { type: 'stream-list', streams: mediaRelay.streams(accountId) });
  if (identity.role === 'console') {
    sendJson(ws, { type: 'device-list', devices: buildDeviceList() });
    sendJson(ws, notificationScopeList());
  }
  scheduleBroadcast();
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
  if (typeof msg.isMonitoring !== 'boolean' || typeof msg.isReady !== 'boolean') {
    sendJson(client.ws, { type: 'heartbeat-ack', accepted: false, reason: 'invalid-state' }); return;
  }

  const sanitizedSources = msg.sources === undefined ? undefined : sanitizeSources(msg.sources);
  const sourcesRejected = msg.sources !== undefined && sanitizedSources === undefined;
  // 超限会让 sources 整组被拒、客户端保留旧快照，因此必须把这个状态也告诉控制台。
  const sourceOverLimit = Array.isArray(msg.sources) && msg.sources.length > config.maxSourcesPerDetector;
  const sourceLimitChanged = msg.sources !== undefined && client.sourceLimitExceeded !== sourceOverLimit;
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
    sourceLimitChanged;

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
  if (msg.capabilities !== undefined) client.capabilities = allowedCapabilities(client.identity, msg.capabilities) ?? client.capabilities;
  if (msg.components !== undefined) client.components = sanitizeComponents(msg.components) ?? client.components;
  if (typeof msg.monitoringExpected === 'boolean') client.monitoringExpected = msg.monitoringExpected;
  if (validProgress(msg.lastProgressAt)) client.lastProgressAt = msg.lastProgressAt;
  if (sanitizedSources !== undefined) client.sources = sanitizedSources;
  if (msg.sources !== undefined) client.sourceLimitExceeded = sourceOverLimit;

  const count = (_heartbeatCounter.get(msg.deviceId) ?? 0) + 1;
  _heartbeatCounter.set(msg.deviceId, count % 60 === 0 ? 0 : count);
  if (count === 1 || count % 60 === 0) {
    const silentSec = Math.round((Date.now() - client.lastSeen.getTime()) / 1000);
    const roleLabel = client.identity.component === 'android-camera' ? '镜头推流（Android）' : client.identity.nodeType === 'sensor' ? '传感器节点' : '视觉节点（Windows）';
    console.log(`[ws][${new Date().toISOString()}] ${roleLabel} 心跳: ${client.deviceName} (${msg.deviceId}) monitoring=${msg.isMonitoring} 静默${silentSec}s`);
  }

  client.lastSeen = new Date();
  sendJson(client.ws, {
    type: 'heartbeat-ack',
    accepted: !sourcesRejected,
    reason: sourcesRejected ? 'source-limit-exceeded' : undefined,
    maxSources: config.maxSourcesPerDetector,
    deviceId: msg.deviceId,
    serverTime: client.lastSeen.toISOString(),
  }, `heartbeat-ack:${msg.deviceId}`);
  if (changed) scheduleBroadcast();
}

function handleHeartbeatReceiver(msg: WsHeartbeatAndroid): void {
  const client = receiverClients.get(msg.deviceId);
  if (!client) {
    console.warn(`[ws][${new Date().toISOString()}] 控制台心跳但客户端不存在: deviceId=${msg.deviceId}`);
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
  if (deviceId && role === 'console') {
    const session = androidSessions.get(deviceId);
    if (session) {
      session.lastSessionEndReason = msg.reason;
      session.lastSessionDurationMs = Date.now() - session.connectedAt;
    }
  }
}

function handleSessionInfo(msg: WsSessionInfo, authenticatedDeviceId: string): void {
  const ts = new Date().toISOString();
  const durationSec = msg.lastSessionDurationMs >= 0 ? `${Math.round(msg.lastSessionDurationMs / 1000)}s` : '未知';
  const reasonDesc = SessionEndReasonNames[msg.lastSessionEndReason] ?? msg.lastSessionEndReason;
  console.log(`[ws][${ts}] 控制台 Session 上报: deviceId=${authenticatedDeviceId} isReconnect=${msg.isReconnect} 上次结束原因=${reasonDesc} 上次持续=${durationSec}`);
  const session = androidSessions.get(authenticatedDeviceId);
  if (session) {
    session.lastSessionEndReason = msg.lastSessionEndReason;
    if (msg.lastSessionDurationMs >= 0) session.lastSessionDurationMs = msg.lastSessionDurationMs;
  }
}

// ════════════════════════════════════════════════════════════
// 命令中继
// ════════════════════════════════════════════════════════════

function handleCommand(senderWs: WebSocket, msg: WsCommand): void {
  const residentCommand = RESIDENT_COMMANDS.has(msg.command);
  const detectorCommand = DETECTOR_COMMANDS.has(msg.command);
  if (!residentCommand && !detectorCommand) {
    sendJson(senderWs, {
      type: 'command-ack', requestId: msg.requestId, phase: 'completed',
      targetDeviceId: msg.targetDeviceId, targetSourceId: msg.targetSourceId,
      command: String(msg.command ?? ''), success: false, reason: '无效的命令',
    }, 'command-ack->sender');
    return;
  }
  const target = residentCommand ? residentWindowsClients.get(msg.targetDeviceId) : findDetector(msg.targetDeviceId);

  const ack: WsCommandAck = {
    type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
    targetSourceId: msg.targetSourceId, command: msg.command, success: false, reason: '',
  };

  if (!isValidRequestId(msg.requestId)) {
    ack.phase = 'completed';
    ack.reason = '无效的 requestId';
    sendJson(senderWs, ack, 'command-ack->sender');
    return;
  }

  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    // 只有驻留在线的设备仍算在线（生命周期命令可用），因此不能说"设备离线"。
    ack.reason = residentCommand ? '驻留组件离线'
      : residentWindowsClients.has(msg.targetDeviceId) ? '该设备当前没有检测端在线' : '设备离线';
    sendJson(senderWs, ack, 'command-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 命令路由失败: target=${msg.targetDeviceId} command=${msg.command} reason=${ack.reason}`);
    return;
  }

  if (detectorCommand && !(target as DetectorClient).capabilities.includes('monitor-control')) {
    ack.phase = 'completed'; ack.reason = '目标不支持监控控制';
    sendJson(senderWs, ack); return;
  }

  if (msg.targetSourceId !== undefined && !/^[A-Za-z0-9_-]{1,64}$/.test(msg.targetSourceId)) {
    ack.phase = 'completed'; ack.reason = '无效的 targetSourceId';
    sendJson(senderWs, ack, 'command-ack->sender'); return;
  }
  if (msg.targetSourceId && (residentCommand || !(target as DetectorClient).capabilities.includes('source-control'))) {
    ack.phase = 'completed'; ack.reason = '目标不支持逐来源控制';
    sendJson(senderWs, ack, 'command-ack->sender'); return;
  }
  if (msg.targetSourceId && !(target as DetectorClient).sources.some(source => source.sourceId === msg.targetSourceId)) {
    ack.phase = 'completed'; ack.reason = '目标来源不存在';
    sendJson(senderWs, ack, 'command-ack->sender'); return;
  }
  // 设备级命令（无 targetSourceId）由检测端解释为“全部来源”，与界面上的“启动已配置/全部停止”一致；
  // 检测端不得把它静默落到第一路。逐来源控制由 targetSourceId 表达，两者语义分离。
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
  if (!isValidRequestId(msg.requestId)) {
    sendJson(senderWs, {
      type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      command: `set-config:${msg.key}`, success: false, reason: '无效的 requestId',
    }, 'set-config-ack->sender');
    return;
  }
  if (!isValidSetConfigKey(msg.key)) {
    sendJson(senderWs, {
      type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      command: `set-config:${msg.key}`, success: false, reason: `无效的配置项: ${msg.key}`,
    }, 'set-config-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 配置更新拒绝: target=${msg.targetDeviceId} key=${msg.key} reason=invalid-key`);
    return;
  }

  const target = findDetector(msg.targetDeviceId);
  if (!target || target.ws.readyState !== WebSocket.OPEN) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId, command: `set-config:${msg.key}`, success: false, reason: '设备离线' }, 'set-config-ack->sender');
    console.warn(`[ws][${new Date().toISOString()}] 配置更新路由失败: target=${msg.targetDeviceId} key=${msg.key} reason=设备离线`);
    return;
  }

  if (!target.capabilities.includes('config-control') || (target.identity.nodeType === 'sensor' && !['cooldown', 'confidence'].includes(msg.key))) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      command: `set-config:${msg.key}`, success: false, reason: '目标不支持该配置' }); return;
  }

  const validation = validateSetConfigValue(msg.key, msg.value, target.modelOptions);
  if (!validation.ok) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId, command: `set-config:${msg.key}`, success: false, reason: validation.reason }, 'set-config-ack->sender');
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
  if (msg.targetSourceId && !target.sources.some(source => source.sourceId === msg.targetSourceId)) {
    sendJson(senderWs, { type: 'command-ack', requestId: msg.requestId, phase: 'completed', targetDeviceId: msg.targetDeviceId,
      targetSourceId: msg.targetSourceId, command: `set-config:${msg.key}`, success: false, reason: '目标来源不存在' }, 'set-config-ack->sender');
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
  // 成功路径没有回执：检测端把截图作为 screenshot-data 异步广播，请求者按 alertId 关联。
  // 失败必须回结构化回执，否则严格解析请求者（务必带 requestId/phase）看不到任何反馈。
  const requestId = typeof msg?.requestId === 'string' ? msg.requestId : undefined;
  if (!isSafeAlertId(alertId) || !targetDeviceId) {
    sendJson(senderWs, {
      type: 'command-ack',
      requestId,
      phase: 'completed',
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
      requestId,
      phase: 'completed',
      targetDeviceId,
      command: 'request-screenshot',
      success: false,
      reason: '设备离线',
    }, 'request-screenshot-ack->sender');
    return;
  }

  if (target.identity.nodeType !== 'visual' || !target.capabilities.includes('screenshot-on-demand')) {
    sendJson(senderWs, { type: 'command-ack', requestId, phase: 'completed', targetDeviceId,
      command: 'request-screenshot', success: false, reason: '目标不支持截图' }); return;
  }

  sendJson(target.ws, {
    type: 'request-screenshot',
    alertId,
    targetDeviceId,
  }, `request-screenshot->${targetDeviceId}`);
}

function handleCommandAck(ack: WsCommandAck, detectorDeviceId: string, ws: WebSocket): void {
  const enriched = { ...ack, targetDeviceId: detectorDeviceId };
  if (ack.requestId) {
    const pending = pendingControlRequests.get(ack.requestId);
    if (!pending || pending.targetWs !== ws || pending.targetDeviceId !== detectorDeviceId || pending.command !== ack.command || pending.targetSourceId !== ack.targetSourceId || ack.phase !== 'completed' || typeof ack.success !== 'boolean') {
      console.warn(`[ws][${new Date().toISOString()}] 忽略无法关联的命令回执: requestId=${ack.requestId} target=${detectorDeviceId} command=${ack.command}`);
      return;
    }
    clearTimeout(pending.timer);
    pendingControlRequests.delete(ack.requestId);
    rememberCompletedRequest(ack.requestId);
    sendJson(pending.senderWs, { ...enriched, phase: 'completed' }, `command-ack:${ack.command}->requester`);
    return;
  }


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
  broadcastToReceivers(notificationScopeList());
}

function buildDeviceList(): DeviceStatus[] {
  const now = Date.now();
  const devices: DeviceStatus[] = [];
  for (const clients of [detectorClients]) {
    for (const c of clients.values()) {
      devices.push({
        role: c.identity.role, nodeType: c.identity.nodeType, platform: c.identity.platform, component: c.identity.component,
        deviceId: c.deviceId,
        deviceName: c.deviceName,
        online: c.ws.readyState === WebSocket.OPEN && (now - c.lastSeen.getTime()) < config.deviceOfflineMs,
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
        maxSources: config.maxSourcesPerDetector,
        sourceLimitExceeded: c.sourceLimitExceeded,
      });
    }
  }
  for (const r of residentWindowsClients.values()) {
    if (devices.some(d => d.deviceId === r.deviceId)) continue;
    devices.push({
      role: 'detector', nodeType: 'visual', platform: 'windows', component: 'windows-inference',
      deviceId: r.deviceId, deviceName: r.deviceName,
      online: (now - r.lastSeen.getTime()) < config.deviceOfflineMs,
      isMonitoring: false, isReady: false, lastSeen: r.lastSeen.toISOString(),
      cooldown: 5, confidence: 0.45, targets: '', targetSamplingRate: 3,
      modelKey: '', modelOptions: [], canSwitchModelWhileMonitoring: false,
      hasPendingConfigChanges: false, clientType: 'windows',
      capabilities: ['app-lifecycle-control'], components: { ...r.components, resident: 'running' },
      sources: [],
      maxSources: config.maxSourcesPerDetector,
      sourceLimitExceeded: false,
    });
  }
  for (const client of notifierClients.values()) {
    devices.push({ role: 'notifier', nodeType: 'notification', platform: client.identity!.platform, component: client.identity!.component,
      deviceId: client.deviceId, deviceName: client.deviceName!, online: client.ws.readyState === WebSocket.OPEN,
      isMonitoring: false, isReady: true, lastSeen: client.lastSeen.toISOString(), cooldown: 5, confidence: 0.45,
      targets: '', targetSamplingRate: 3, modelKey: '', modelOptions: [], canSwitchModelWhileMonitoring: false,
      hasPendingConfigChanges: false, clientType: 'notification', capabilities: ['notification-receipt', 'connection-watchdog'],
      components: {}, sources: [], maxSources: 0, sourceLimitExceeded: false });
  }
  return devices;
}

function sendJson(ws: WebSocket, obj: object, context?: string): boolean {
  const token = socketTokens.get(ws);
  if (token && !accountStore.authenticate(token)) { ws.close(4001, 'session revoked'); return false; }
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

function validProgress(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value)) && Date.parse(value) <= Date.now() + 5000;
}

interface Delivery { notifierId: string; ws: WebSocket; alert: WsAlertPush; lastSent: number }
const deliveries = new Map<string, Delivery>();
const activeFaults = new Set<string>();
const unhealthySince = new Map<string, number>();
const faultOutbox = new Map<string, WsAlertPush>();

function acknowledgeDelivery(notifierId: string, alertId: unknown): void {
  if (typeof alertId !== 'string') return;
  const key = `${notifierId}:${alertId}`;
  const delivery = deliveries.get(key);
  if (!delivery || delivery.ws !== notifierClients.get(notifierId)?.ws) return;
  deliveries.delete(key);
  // Receipt proves application acceptance, never playback or that a human heard it.
  broadcastToReceivers({ type: 'notification-receipt', notifierId, alertId, receivedAt: new Date().toISOString() });
}

function dropDeliveries(notifierId: string, ws?: WebSocket): void {
  for (const [key, delivery] of deliveries) if (delivery.notifierId === notifierId && (!ws || delivery.ws === ws)) deliveries.delete(key);
}

function notificationScopeList(): object {
  return { type: 'notification-scopes', detectors: registeredNodes(accountId, 'detector'), notifiers: registeredNodes(accountId, 'notifier').map(identity => ({
    ...identity, deviceName: notifierClients.get(identity.deviceId)?.deviceName ?? identity.deviceName,
    online: notifierClients.get(identity.deviceId)?.ws.readyState === WebSocket.OPEN,
    scope: notificationScopes.get(identity.deviceId),
  })) };
}

function handleNotificationScope(ws: WebSocket, msg: any): void {
  const scope = parseNotificationScope(msg.scope);
  const requestId = typeof msg.requestId === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(msg.requestId) ? msg.requestId : undefined;
  const target = registeredNodes(accountId, 'notifier').find(identity => identity.deviceId === msg.targetNotifierId);
  const detectors = registeredNodes(accountId, 'detector');
  const invalidTarget = scope?.targets.some(item => {
    const identity = detectors.find(node => node.deviceId === item.deviceId);
    return !identity || (item.sourceId !== undefined && (identity.nodeType !== 'visual'
      || (detectorClients.has(item.deviceId) && !detectorClients.get(item.deviceId)!.sources.some(source => source.sourceId === item.sourceId))));
  });
  if (!scope || !requestId || !target || invalidTarget) {
    sendJson(ws, { type: 'notification-scope-result', requestId, success: false, reason: '无效的通知节点或接收范围' }); return;
  }
  try { notificationScopes.set(target.deviceId, scope); }
  catch { sendJson(ws, { type: 'notification-scope-result', requestId, success: false, reason: '接收范围保存失败' }); return; }
  // Remove pending retries outside the new scope immediately.
  for (const [key, delivery] of deliveries) {
    if (delivery.notifierId === target.deviceId && !scopeAccepts(scope, delivery.alert)) deliveries.delete(key);
  }
  const notifier = notifierClients.get(target.deviceId);
  if (notifier) sendJson(notifier.ws, { type: 'notification-scope', scope });
  sendJson(ws, { type: 'notification-scope-result', requestId, success: true });
  broadcastToReceivers(notificationScopeList());
}

function handleTimeStandard(ws: WebSocket, msg: any): void {
  const result = { type: 'time-standard-result', requestId: msg.requestId, command: '统一时间', phase: 'completed', success: false, reason: '' };
  if (!isValidRequestId(msg.requestId) || !validAlarmTimeZone(msg.timeZone)) {
    sendJson(ws, { ...result, reason: '无效的时间标准' }); return;
  }
  try { timeStandard.set(msg.timeZone); }
  catch { sendJson(ws, { ...result, reason: '时间标准保存失败' }); return; }
  sendJson(ws, { ...result, success: true, reason: '已保存' });
  const update = { type: 'time-standard', ...timeStandard.get() };
  broadcastToReceivers(update);
  for (const notifier of notifierClients.values()) sendJson(notifier.ws, update);
}

function emitFault(client: DetectorClient, eventKind: 'connection-lost' | 'detection-interrupted', source?: SourceStatus): void {
  // Camera control connectivity does not imply inference intent. Media failures are
  // reported through the bound Windows source, while idle/background camera exits are normal.
  if (client.identity.component === 'android-camera') return;
  const key = `${client.deviceId}:${source?.sourceId ?? ''}:${eventKind}`;
  if (activeFaults.has(key)) return;
  const now = Date.now();
  const alert: WsAlertPush = { type: 'alert', alertId: crypto.randomUUID(), deviceId: client.deviceId,
    deviceName: client.deviceName, nodeType: client.identity.nodeType,
    sourceId: source?.sourceId, sourceName: source?.sourceName, eventKind,
    timestamp: new Date(now).toISOString(), expiresAt: new Date(now + REALTIME_TTL_MS).toISOString(),
    summary: eventKind === 'connection-lost' ? '节点连接中断' : '检测运行中断', detections: [], createdAt: now };
  activeFaults.add(key);
  faultOutbox.set(alert.alertId, alert);
  flushFaultOutbox(now);
}

function flushFaultOutbox(now: number): void {
  for (const [id, alert] of faultOutbox) {
    if (Date.parse(alert.expiresAt!) <= now) { faultOutbox.delete(id); continue; }
    try {
      const result = addAlert(accountId, { ...alert, createdAt: alert.createdAt! });
      faultOutbox.delete(id);
      if (result === 'stored') broadcastAlert(alert);
    } catch { /* Retry temporary storage failure within the original event deadline. */ }
  }
}

/** Clock argument makes outage thresholds testable without long-running device operations. */
function maintainRealtime(now = Date.now()): void {
  revokeAndRefresh();
  flushFaultOutbox(now);
  for (const [key, delivery] of deliveries) {
    if (Date.parse(delivery.alert.expiresAt ?? '') <= now || notifierClients.get(delivery.notifierId)?.ws !== delivery.ws) {
      deliveries.delete(key); continue;
    }
    if (now - delivery.lastSent >= 3000) { sendJson(delivery.ws, delivery.alert); delivery.lastSent = now; }
  }
  for (const client of detectorClients.values()) {
    const online = client.ws.readyState === WebSocket.OPEN && now - client.lastSeen.getTime() < config.deviceOfflineMs;
    if (!online) continue;
    activeFaults.delete(`${client.deviceId}::connection-lost`);
    const states = client.identity.nodeType === 'visual' ? client.sources : [{ sourceId: '', sourceName: '', modelKey: '',
      isMonitoring: client.isMonitoring, isReady: client.isReady, monitoringExpected: client.monitoringExpected, lastProgressAt: client.lastProgressAt }];
    for (const source of states) {
      const key = `${client.deviceId}:${source.sourceId}:detection-interrupted`;
      const unhealthy = source.monitoringExpected === true && (!source.isMonitoring || !source.lastProgressAt || now - Date.parse(source.lastProgressAt) >= DETECTION_STALL_MS);
      if (!unhealthy) { unhealthySince.delete(key); activeFaults.delete(key); continue; }
      const since = unhealthySince.get(key) ?? (source.lastProgressAt ? Math.min(now, Date.parse(source.lastProgressAt)) : now);
      unhealthySince.set(key, since);
      if (now - since >= DETECTION_STALL_MS) emitFault(client, 'detection-interrupted', source.sourceId ? source : undefined);
    }
  }
  for (const [id, client] of notifierClients) {
    if (now - client.lastSeen.getTime() >= config.deviceOfflineMs) { client.ws.terminate(); notifierClients.delete(id); dropDeliveries(id); }
  }
}

// ════════════════════════════════════════════════════════════
// 定时维护：每 3s
// ════════════════════════════════════════════════════════════

const maintenanceTimer = setInterval(() => {
  const now = Date.now();
  maintainRealtime(now);
  const ts = new Date().toISOString();
  const detectorDeadline = now - config.deviceOfflineMs;
  const receiverDeadline = now - config.receiverGhostThresholdMs;
  let detectorCleaned = false;

  // 控制台幽灵清理（阈值更长，容忍移动网络抖动）
  for (const [id, client] of receiverClients) {
    if (client.lastSeen.getTime() <= receiverDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] 控制台幽灵清理: ${id} (静默 ${silentSec}s 阈值 ${config.receiverGhostThresholdMs / 1000}s)`);
      client.ws.terminate();
      receiverClients.delete(id);
      screenshotQueues.delete(id);
      screenshotProcessing.delete(id);
    }
  }

  // 检测端幽灵清理
  for (const clients of [detectorClients]) {
    for (const [id, client] of clients) {
      if (client.lastSeen.getTime() <= detectorDeadline) {
        const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
        const roleLabel = client.identity.component === 'android-camera' ? '镜头推流（Android）' : client.identity.nodeType === 'sensor' ? '传感器节点' : '视觉节点（Windows）';
        console.log(`[ws][${ts}] ${roleLabel} 幽灵清理: ${client.deviceName} (${id}) 静默 ${silentSec}s 阈值 ${config.deviceOfflineMs / 1000}s`);
        client.ws.terminate();
        emitFault(client, 'connection-lost');
        clients.delete(id);
        _heartbeatCounter.delete(id);
        detectorCleaned = true;
      }
    }
  }

  // 驻留程序幽灵清理：驻留条目会被合并进同设备检测端的 components，
  // 不清理就会让控制台一直显示"驻留运行中"，并给出必然失败的开/关按钮。
  for (const [id, client] of residentWindowsClients) {
    if (client.lastSeen.getTime() <= detectorDeadline) {
      const silentSec = Math.round((now - client.lastSeen.getTime()) / 1000);
      console.log(`[ws][${ts}] 驻留程序幽灵清理: ${client.deviceName} (${id}) 静默 ${silentSec}s 阈值 ${config.deviceOfflineMs / 1000}s`);
      client.ws.terminate();
      residentWindowsClients.delete(id);
      detectorCleaned = true;
    }
  }

  if (detectorCleaned && (receiverClients.size > 0 || detectorClients.size > 0 || detectorClients.size > 0)) {
    broadcastDeviceList();
    console.log(`[ws][${ts}] 设备清理后推送 → 控制台:${receiverClients.size} / 检测节点:${detectorClients.size} / 通知节点:${notifierClients.size}`);
  }
}, 3000);
maintenanceTimer.unref();

function revokeAndRefresh(): void {
  for (const clients of [detectorClients, receiverClients, notifierClients, residentWindowsClients]) {
    for (const [id, client] of clients) {
      const live = accountStore.authenticate(socketTokens.get(client.ws));
      if (!live) {
        clients.delete(id); clearPendingDetectorRemoval('windows', id); clearPendingDetectorRemoval('android-camera', id);
        screenshotQueues.delete(id); screenshotProcessing.delete(id); dropDeliveries(id);
        for (const [key, delivery] of deliveries) if (delivery.alert.deviceId === id) deliveries.delete(key);
        for (const [key, fault] of faultOutbox) if (fault.deviceId === id) faultOutbox.delete(key);
        for (const key of activeFaults) if (key.startsWith(`${id}:`)) activeFaults.delete(key);
        for (const key of unhealthySince.keys()) if (key.startsWith(`${id}:`)) unhealthySince.delete(key);
        for (const [requestId, pending] of pendingControlRequests) if (pending.senderWs === client.ws || pending.targetWs === client.ws) { clearTimeout(pending.timer); pendingControlRequests.delete(requestId); }
        client.ws.close(4001, 'session revoked');
      } else {
        const nameChanged = client.deviceName !== live.device.deviceName;
        client.deviceName = live.device.deviceName;
        client.identity = live.device;
        if (nameChanged) sendJson(client.ws, { type: 'device-updated', device: live.device });
      }
    }
  }
  scheduleBroadcast();
}
function sendStreams(): void {
  const message = { type: 'stream-list', streams: mediaRelay.streams(accountId) };
  broadcastToReceivers(message);
  for (const client of detectorClients.values()) sendJson(client.ws, message);
}
return { handleConnection, broadcastAlert, broadcastScreenshotData, getConnectionCount, maintainRealtime, revokeAndRefresh, sendStreams, buildDeviceList };
}
const contexts = new Map<string, ReturnType<typeof createAccountConnections>>();
const socketTokens = new WeakMap<WebSocket, string>();
function forAccount(accountId: string) {
  let context = contexts.get(accountId);
  if (!context) { context = createAccountConnections(accountId); contexts.set(accountId, context); }
  return context;
}
accountStore.onChange(() => { for (const context of contexts.values()) context.revokeAndRefresh(); });
mediaRelay.onChange(accountId => contexts.get(accountId)?.sendStreams());
export function handleConnection(ws: WebSocket): void {
  const timer = setTimeout(() => ws.close(4001, 'auth timeout'), config.wsAuthTimeoutMs); timer.unref();
  ws.once('message', (raw, binary) => {
    clearTimeout(timer);
    let message: any; try { message = JSON.parse(raw.toString()); } catch { ws.close(4001, 'invalid auth'); return; }
    const session = !binary && message.type === 'auth' ? accountStore.authenticate(message.token) : undefined;
    if (!session) { ws.send(JSON.stringify({ type: 'auth-result', success: false, reason: 'invalid session' })); ws.close(4001, 'invalid session'); return; }
    socketTokens.set(ws, message.token);
    forAccount(session.account.accountId).handleConnection(ws);
    ws.emit('message', raw, false);
  });
  ws.on('error', () => ws.terminate());
  ws.once('close', () => clearTimeout(timer));
}
export function broadcastAlert(accountId: string, alert: WsAlertPush): void { forAccount(accountId).broadcastAlert(alert); }
export function broadcastScreenshotData(accountId: string, payload: WsScreenshotDataPush): void { forAccount(accountId).broadcastScreenshotData(payload); }
export function getConnectionCount(): number { return [...contexts.values()].reduce((n, context) => n + context.getConnectionCount(), 0); }
export function maintainRealtime(now = Date.now()): void { for (const context of contexts.values()) context.maintainRealtime(now); }
export function accountDeviceList(session: AccountSession): object[] {
  const live = forAccount(session.account.accountId).buildDeviceList();
  return accountStore.devices(session.account.accountId).map(device => ({ ...device, online: false, ...live.find(item => item.deviceId === device.deviceId) }));
}
export function associateScreenshotPayload(payload: WsScreenshotDataPush, authenticatedDeviceId: string, alertRecord: AlertRecord | undefined): WsScreenshotDataPush | null {
  if (!alertRecord || alertRecord.deviceId !== authenticatedDeviceId || alertRecord.alertId !== payload.alertId) return null;
  return { ...payload, deviceId: authenticatedDeviceId, sourceId: alertRecord.sourceId, sourceName: alertRecord.sourceName };
}
