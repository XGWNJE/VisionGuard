// ┌─────────────────────────────────────────────────────────┐
// │ auth.ts                                                 │
// │ 角色：按登记身份校验 HTTP 读取与事件上传权限             │
// │ 对外 API：httpAuth, detectorHttpAuth                     │
// └─────────────────────────────────────────────────────────┘

import { Request, Response, NextFunction } from 'express';
import { config } from '../config';
import { authenticateToken } from '../services/NodeProtocol';

/**
 * Express 中间件：校验 X-API-Key 请求头
 */
export function httpAuth(req: Request, res: Response, next: NextFunction): void {
  const key = req.headers['x-api-key'] as string | undefined;
  if (!key || (key !== config.apiKey && authenticateToken(key)?.role !== 'console')) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }
  next();
}

export function detectorHttpAuth(req: Request, res: Response, next: NextFunction): void {
  const identity = authenticateToken(req.headers['x-api-key']);
  if (identity?.role !== 'detector') { res.status(401).json({ ok: false, error: 'detector identity required' }); return; }
  res.locals.identity = identity;
  next();
}
