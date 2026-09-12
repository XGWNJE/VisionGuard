// ┌─────────────────────────────────────────────────────────┐
// │ AlertStore.ts                                           │
// │ 角色：报警记录持久化存储（内存循环缓冲 + 文件持久化）     │
// │ 对外 API：addAlert(), getAlerts()                        │
// │ 策略：超出 maxAlertsPerDevice 时淘汰最旧；超 TTL 时清理  │
// │ 持久化：启动时从 data/alerts.json 加载；变更时自动保存   │
// └─────────────────────────────────────────────────────────┘

import fs from 'fs';
import path from 'path';
import { config } from '../config';
import type { AlertRecord } from '../models/types';

/** 持久化文件路径 */
const PERSIST_PATH = process.env.ALERT_STORE_PATH
  ? path.resolve(process.env.ALERT_STORE_PATH)
  : path.resolve(config.dataDir, 'alerts.json');

/** deviceId → AlertRecord[] (最多 maxAlertsPerDevice 条) */
const store = new Map<string, AlertRecord[]>();

const alertTtlMs = config.alertTtlHours * 3600 * 1000;

// 启动时加载历史记录
loadFromDisk();

/**
 * 从磁盘加载报警记录
 */
function loadFromDisk(): void {
  try {
    if (!fs.existsSync(PERSIST_PATH)) return;
    const raw = fs.readFileSync(PERSIST_PATH, 'utf-8');
    const data = JSON.parse(raw) as Record<string, AlertRecord[]>;
    for (const [deviceId, list] of Object.entries(data)) {
      if (Array.isArray(list)) {
        store.set(deviceId, list);
      }
    }
    const total = Array.from(store.values()).reduce((sum, l) => sum + l.length, 0);
    console.log(`[alert-store] 已从磁盘加载 ${total} 条报警记录`);
  } catch (err: any) {
    console.warn(`[alert-store] 磁盘加载失败: ${err.message}`);
  }
}

/**
 * 保存到磁盘（防抖：多次快速写入合并为一次）
 */
let saveTimer: NodeJS.Timeout | null = null;
function saveToDisk(): void {
  const data: Record<string, AlertRecord[]> = {};
  for (const [deviceId, list] of store) data[deviceId] = list;
  fs.mkdirSync(path.dirname(PERSIST_PATH), { recursive: true });
  const tempPath = `${PERSIST_PATH}.tmp`;
  const fd = fs.openSync(tempPath, 'w');
  try {
    fs.writeFileSync(fd, JSON.stringify(data), 'utf-8');
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(tempPath, PERSIST_PATH);
}

function scheduleSaveToDisk(): void {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try { saveToDisk(); }
    catch (err: any) { console.warn(`[alert-store] 磁盘保存失败: ${err.message}`); }
  }, 500);
}

/**
 * 添加报警记录，超出上限时淘汰最旧的
 */
export type AddAlertResult = 'stored' | 'duplicate' | 'conflict';

function isSameAlert(existing: AlertRecord, incoming: AlertRecord): boolean {
  return existing.deviceId === incoming.deviceId
    && existing.sourceId === incoming.sourceId
    && existing.timestamp === incoming.timestamp
    && JSON.stringify(existing.detections) === JSON.stringify(incoming.detections);
}

export function addAlert(record: AlertRecord): AddAlertResult {
  const existing = getAlertById(record.alertId);
  if (existing) {
    if (!isSameAlert(existing, record)) return 'conflict';
    saveToDisk();
    return 'duplicate';
  }

  if (!record.screenshotPath) {
    const backedUpScreenshot = path.join(config.screenshotDir, `${record.alertId}.jpg`);
    if (fs.existsSync(backedUpScreenshot)) {
      record.screenshotPath = backedUpScreenshot;
    }
  }

  const previous = store.get(record.deviceId);
  const next = [...(previous ?? []), record].slice(-config.maxAlertsPerDevice);
  store.set(record.deviceId, next);
  try {
    // alert-ack 只能在记录写入磁盘并完成 fsync 后发送，避免把内存接收误报为持久化成功。
    saveToDisk();
  } catch (error) {
    if (previous) store.set(record.deviceId, previous);
    else store.delete(record.deviceId);
    throw error;
  }
  return 'stored';
}

export function markAlertScreenshot(alertId: string, screenshotPath: string): boolean {
  for (const list of store.values()) {
    const record = list.find(r => r.alertId === alertId);
    if (record) {
      record.screenshotPath = screenshotPath;
      scheduleSaveToDisk();
      return true;
    }
  }
  return false;
}

export function getAlertById(alertId: string): AlertRecord | undefined {
  for (const list of store.values()) {
    const record = list.find(r => r.alertId === alertId);
    if (record) return record;
  }
  return undefined;
}

/**
 * 查询某设备的报警记录（可选时间过滤）
 * @param deviceId 设备 ID（为空则返回所有设备）
 * @param since 只返回 createdAt >= since 的记录
 * @param sourceId 只返回指定来源的记录
 * @param limit 最多返回条数
 */
export function getAlerts(deviceId?: string, since?: number, limit = 50, sourceId?: string): AlertRecord[] {
  const now = Date.now();
  const deadline = now - alertTtlMs;

  let all: AlertRecord[] = [];

  if (deviceId) {
    const list = store.get(deviceId);
    if (list) all = list.filter(r => r.createdAt >= deadline);
  } else {
    for (const list of store.values()) {
      all.push(...list.filter(r => r.createdAt >= deadline));
    }
  }

  // 按时间倒序
  all.sort((a, b) => b.createdAt - a.createdAt);

  // since 过滤
  if (since !== undefined) {
    all = all.filter(r => r.createdAt >= since);
  }

  if (sourceId) {
    all = all.filter(r => r.sourceId === sourceId);
  }

  return all.slice(0, limit);
}

/**
 * 定时清理：删除超过 TTL 的报警记录
 */
export function cleanupExpiredAlerts(): void {
  const now = Date.now();
  const deadline = now - alertTtlMs;
  let totalRemoved = 0;

  for (const [deviceId, list] of store) {
    const before = list.length;
    const filtered = list.filter(r => r.createdAt >= deadline);
    const removed = before - filtered.length;
    if (removed > 0) {
      store.set(deviceId, filtered);
      totalRemoved += removed;
    }
  }

  if (totalRemoved > 0) {
    console.log(`[alert-store] 清理 ${totalRemoved} 条过期报警记录 (TTL=${config.alertTtlHours}h)`);
    scheduleSaveToDisk();
  }
}
