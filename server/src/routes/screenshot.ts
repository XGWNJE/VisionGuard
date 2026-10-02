import { Router } from 'express';
import path from 'path';
import fs from 'fs';
import { config } from '../config';
import { httpAuth } from '../middleware/auth';

const router = Router();

/**
 * GET /screenshots/:filename
 * 通过 HTTP 下载报警截图（需 X-API-Key）
 */
router.get('/screenshots/:filename', httpAuth, (req, res) => {
  if (typeof req.params.filename !== 'string') {
    res.status(400).json({ ok: false, error: 'invalid filename' });
    return;
  }
  const filename = path.basename(req.params.filename);
  const filePath = path.join(config.screenshotDir, filename);

  // 防止目录遍历攻击
  const resolvedPath = path.resolve(filePath);
  const resolvedDir = path.resolve(config.screenshotDir);
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
