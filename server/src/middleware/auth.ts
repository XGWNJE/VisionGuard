// ┌─────────────────────────────────────────────────────────┐
// │ auth.ts                                                 │
// │ 角色：按账号会话校验 HTTP 读取与事件上传权限             │
// │ 对外 API：accountAuth, httpAuth, detectorHttpAuth,      │
// │           recordDroppedVersion                          │
// └─────────────────────────────────────────────────────────┘

import { Request, Response, NextFunction } from 'express';
import { accountStore } from '../services/AccountStore';
import { isCameraComponent } from '../services/NodeComponents';

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
  // `/api` 请求头是 HTTP 端的版本声明通道：每次声明都刷新记录，值没变时不落盘。
  accountStore.recordVersion(session.account.accountId, session.device.deviceId, session.device.component, req.headers['x-visionguard-version']);
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
    if (identity.role !== 'detector' || isCameraComponent(identity.component)) { res.status(403).json({ ok: false, error: 'inference identity required' }); return; }
    next();
  });
}
/** 协议闸门出局回调：把端点声明的版本落到设备记录，节点目录随即不再显示它。 */
export function recordDroppedVersion(req: Request): void {
  const authorization = req.headers.authorization;
  if (typeof authorization === 'string' && authorization.startsWith('Bearer ')) {
    accountStore.recordDeclaredVersion(authorization.slice(7), req.headers['x-visionguard-version']);
  }
}
