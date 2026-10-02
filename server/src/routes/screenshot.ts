import { Router } from 'express';
import path from 'path';
import fs from 'fs';
import { accountDirectory } from '../services/AccountStore';
import { getAlertById } from '../services/AlertStore';
import { httpAuth } from '../middleware/auth';

const router = Router();

/**
 * GET /screenshots/:filename
 * 下载当前账号的报警截图（需 Bearer 会话与事件归属）
 */
router.get('/screenshots/:filename', httpAuth, (req, res) => {
  if (typeof req.params.filename !== 'string') {
    res.status(400).json({ ok: false, error: 'invalid filename' });
    return;
  }
  const filename = path.basename(req.params.filename);
  if (filename !== req.params.filename || !/^[A-Za-z0-9_-]{8,128}\.(?:jpg|jpeg|png)$/.test(filename)) { res.status(404).json({ ok: false, error: 'screenshot not found' }); return; }
  const accountId = res.locals.identity.accountId;
  const alert = getAlertById(accountId, path.parse(filename).name);
  const screenshotDir = path.join(accountDirectory(accountId), 'screenshots');
  const filePath = path.join(screenshotDir, filename);
  if (!alert?.screenshotPath || path.resolve(alert.screenshotPath) !== path.resolve(filePath)) { res.status(404).json({ ok: false, error: 'screenshot not found' }); return; }

  // 防止目录遍历攻击
  const resolvedPath = path.resolve(filePath);
  const resolvedDir = path.resolve(screenshotDir);
  const relative = path.relative(resolvedDir, resolvedPath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    res.status(403).json({ ok: false, error: 'access denied' });
    return;
  }

  if (!fs.existsSync(filePath)) {
    res.status(404).json({ ok: false, error: 'screenshot not found' });
    return;
  }

  const ext = path.extname(filename).toLowerCase();
  const contentType = ext === '.jpg' || ext === '.jpeg' ? 'image/jpeg' : 'image/png';
  res.setHeader('Content-Type', contentType);
  res.setHeader('Cache-Control', 'private, no-store');
  res.setHeader('Content-Disposition', `inline; filename="${filename}"`);
  const stream = fs.createReadStream(filePath);
  stream.pipe(res);
  stream.on('error', () => {
    if (!res.headersSent) {
      res.status(500).json({ ok: false, error: 'read failed' });
    }
  });
});

export default router;
