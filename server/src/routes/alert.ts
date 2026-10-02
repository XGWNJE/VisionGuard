// ┌─────────────────────────────────────────────────────────┐
// │ alert.ts                                                │
// │ 角色：POST /api/alert 路由 — 接收报警上传并广播          │
// │ 流程：multer 接收 → 解析 meta → 可选存截图 → 存记录 → 广播│
// │ 模式：ENABLE_HTTP_SCREENSHOT_UPLOAD=true 时接收截图；    │
// │       false 时仅接收 meta，截图由检测端本地缓存+按需推送 │
// └─────────────────────────────────────────────────────────┘

import { Router, Request, Response } from 'express';
import multer from 'multer';
import fs from 'fs';
import path from 'path';
import { config } from '../config';
import { accountDirectory } from '../services/AccountStore';
import { detectorHttpAuth } from '../middleware/auth';
import { validateEvent } from '../services/NodeProtocol';
import { addAlert, getAlertById } from '../services/AlertStore';
import { broadcastAlert } from '../services/ConnectionManager';
import type { AlertMeta, WsAlertPush } from '../models/types';
import { getSafeScreenshotPath, isSafeAlertId, validateAlertMeta, validateImageMagic } from '../utils/security';

const router = Router();

function removeTempUpload(req: Request): void {
  if (!req.file?.path) return;
  try { fs.unlinkSync(req.file.path); } catch { /* ignore */ }
}

// multer 配置：截图存到 data/screenshots/<alertId>.png
const storage = multer.diskStorage({
  destination: (req, _file, cb) => {
    const directory = path.join(accountDirectory(req.res!.locals.identity.accountId), 'screenshots');
    fs.mkdirSync(directory, { recursive: true });
    cb(null, directory);
  },
  filename: (_req, _file, cb) => {
    const tempName = `tmp_${Date.now()}_${Math.random().toString(36).slice(2)}${path.extname(_file.originalname) || '.png'}`;
    cb(null, tempName);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: config.maxUploadBytes },
  fileFilter: (_req, file, cb) => {
    // diskStorage 的 fileFilter 无法读取完整 buffer，这里只做早期粗筛；
    // 真正的魔数校验在 handler 读取临时文件后完成。
    if (file.mimetype === 'image/png' || file.mimetype === 'image/jpeg') {
      cb(null, true);
    } else {
      cb(new Error('只接受 PNG/JPEG 图片文件'));
    }
  },
});

/**
 * POST /api/alert
 * Body: multipart/form-data
 *   - "meta" (application/json): AlertMeta
 *   - "screenshot" (image/jpeg|png): 可选，二进制图片
 *
 * 当 ENABLE_HTTP_SCREENSHOT_UPLOAD=false 时，screenshot 字段可省略，
 * 服务器只存储报警 meta 并广播轻量 alert（无 screenshotUrl）。
 */
router.post(
  '/api/alert',
  detectorHttpAuth,
  upload.single('screenshot'),
  (req: Request, res: Response) => {
    try {
      const metaRaw = req.body?.meta;
      if (!metaRaw) {
        removeTempUpload(req);
        res.status(400).json({ ok: false, error: 'missing meta field' });
        return;
      }

      let parsedMeta: unknown;
      try {
        parsedMeta = typeof metaRaw === 'string' ? JSON.parse(metaRaw) : metaRaw;
      } catch {
        removeTempUpload(req);
        res.status(400).json({ ok: false, error: 'invalid meta JSON' });
        return;
      }

      const identity = res.locals.identity;
      const incoming = parsedMeta as any;
      const event = validateEvent(incoming, identity);
      if (identity.nodeType === 'sensor' && req.file) { removeTempUpload(req); res.status(400).json({ ok: false, error: 'sensor events have no screenshot' }); return; }
      if (!event || !isSafeAlertId(incoming.alertId)) {
        removeTempUpload(req); res.status(400).json({ ok: false, error: 'invalid-or-expired-event' }); return;
      }
      const metaResult = validateAlertMeta({ ...incoming, ...event, deviceId: identity.deviceId });
      if (!metaResult.ok || !metaResult.value) {
        removeTempUpload(req);
        res.status(400).json({ ok: false, error: metaResult.error || 'invalid meta' });
        return;
      }
      const meta: AlertMeta = metaResult.value;
      const alertId = incoming.alertId;

      // 截图处理：仅在开启上传开关且收到文件时保存
      const existing = getAlertById(identity.accountId, alertId);
      let screenshotPath: string | undefined = existing?.screenshotPath;
      if (existing && req.file) {
        // A retry or conflicting ID must never overwrite an existing event's picture.
        removeTempUpload(req);
      } else if (config.enableHttpScreenshotUpload && req.file) {
        const head = fs.readFileSync(req.file.path, { flag: 'r' }).subarray(0, 16);
        const contentType = validateImageMagic(head);
        const target = contentType ? getSafeScreenshotPath(path.join(accountDirectory(identity.accountId), 'screenshots'), alertId, contentType) : null;
        if (!target) {
          removeTempUpload(req);
          res.status(400).json({ ok: false, error: 'invalid screenshot' });
          return;
        }
        fs.renameSync(req.file.path, target.filePath);
        screenshotPath = target.filePath;
      } else if (req.file) {
        // 开关关闭但收到了文件，清理临时文件
        removeTempUpload(req);
      }

      const result = addAlert(identity.accountId, {
        ...event, nodeType: identity.nodeType,
        alertId,
        deviceId: meta.deviceId,
        deviceName: meta.deviceName,
        sourceId: meta.sourceId,
        sourceName: meta.sourceName,
        timestamp: meta.timestamp,
        detections: meta.detections,
        screenshotPath,
        createdAt: Date.now(),
      });
      if (result === 'conflict') { res.status(409).json({ ok: false, error: 'alert-id-conflict' }); return; }

      // 首次入库后广播给控制台与在线通知节点
      const push: WsAlertPush = {
        ...event, nodeType: identity.nodeType,
        type: 'alert',
        alertId,
        deviceId: meta.deviceId,
        deviceName: meta.deviceName,
        sourceId: meta.sourceId,
        sourceName: meta.sourceName,
        timestamp: meta.timestamp,
        detections: meta.detections,
        screenshotUrl: screenshotPath ? `/screenshots/${path.basename(screenshotPath)}` : '',
        ...(parsedMeta as any).timings ? { timings: (parsedMeta as any).timings } : {},
      };
      if (result === 'stored') broadcastAlert(identity.accountId, push);

      console.log(`[alert] 报警已接收: ${meta.deviceName} → ${meta.detections.length} 个目标 (${alertId}) screenshot=${screenshotPath ? 'saved' : 'detector-local'}`);
      res.json({ ok: true, alertId });
    } catch (err: any) {
      console.error('[alert] 处理失败:', err.message);
      res.status(500).json({ ok: false, error: 'internal error' });
    }
  },
);

export default router;
