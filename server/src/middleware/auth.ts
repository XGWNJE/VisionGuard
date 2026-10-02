// ┌─────────────────────────────────────────────────────────┐
// │ auth.ts                                                 │
// │ 角色：按账号会话校验 HTTP 读取与事件上传权限             │
// │ 对外 API：httpAuth, detectorHttpAuth                     │
// └─────────────────────────────────────────────────────────┘

import { Request, Response, NextFunction } from 'express';
import { accountStore } from '../services/AccountStore';

/**
 * Express middleware: authenticate an opaque Bearer session
 */
export function accountAuth(req: Request, res: Response, next: NextFunction): void {
  const authorization = req.headers.authorization;
  const session = authorization?.startsWith('Bearer ') ? accountStore.authenticate(authorization.slice(7)) : undefined;
  if (!session) {
    res.status(401).json({ ok: false, error: 'unauthorized' });
    return;
  }
  res.locals.session = session;
  res.locals.identity = session.device;
  next();
}
export function httpAuth(req: Request, res: Response, next: NextFunction): void {
  accountAuth(req, res, () => {
    if (res.locals.identity.role !== 'console') { res.status(403).json({ ok: false, error: 'console required' }); return; }
    next();
  });
}
export function detectorHttpAuth(req: Request, res: Response, next: NextFunction): void {
  accountAuth(req, res, () => {
    const identity = res.locals.identity;
    if (identity.role !== 'detector' || identity.component === 'android-camera') { res.status(403).json({ ok: false, error: 'inference identity required' }); return; }
    next();
  });
}
