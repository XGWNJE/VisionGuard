import { Router, type Request, type Response } from 'express';
import rateLimit, { ipKeyGenerator } from 'express-rate-limit';
import { accountAuth } from '../middleware/auth';
import { accountStore, AccountError, type AccountSession } from '../services/AccountStore';
import { config } from '../config';
import { accountDeviceList } from '../services/ConnectionManager';

export function sessionResponse(session: AccountSession & { token?: string; resident?: AccountSession & { token: string } }): object {
  const { accountId: ignored, ...device } = session.device;
  const resident = session.resident;
  return { ok: true, ...(session.token ? { token: session.token } : {}), expiresAt: session.expiresAt, channel: config.channelId, version: config.version,
    account: session.account, device, ...(resident ? { resident: { token: resident.token, expiresAt: resident.expiresAt, device: (({ accountId: _account, ...rest }) => rest)(resident.device) } } : {}) };
}
export function accountFailure(res: Response, error: unknown): void {
  if (error instanceof AccountError) res.status(error.status).json({ ok: false, error: error.message });
  else { console.error('[account] operation failed'); res.status(500).json({ ok: false, error: 'Account operation failed' }); }
}
const router = Router();
/** 回环请求判定：预览内置登录只服务本机实例，即使误在生产设置变量也不外泄。 */
const LOOPBACK_ADDRESSES = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
function isLoopbackRequest(req: Request): boolean {
  return LOOPBACK_ADDRESSES.has((req.socket.remoteAddress ?? '').trim());
}
router.use('/api/account', (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
const loginLimiter = rateLimit({ windowMs: 60_000, max: 20, standardHeaders: true, legacyHeaders: false,
  keyGenerator: req => ipKeyGenerator(req.ip || req.socket.remoteAddress || 'unknown'),
  message: { ok: false, error: 'Too many login attempts' } });
router.post('/api/account/login', loginLimiter, async (req, res) => {
  try {
    // 登录同时声明版本：请求头是 HTTP 端的声明通道，缺声明或旧版本记为空/旧值，端点不进节点目录。
    const declared = req.headers['x-visionguard-version'];
    res.json(sessionResponse(await accountStore.login({ ...req.body, version: declared ?? req.body?.version })));
  } catch (error) { accountFailure(res, error); }
});
// 本地预览的内置登录：只有隔离实例配了凭据、且请求来自回环地址才回应；其余一律当作不存在。
// 控制台与 Web 相机据此跳过手填账号，生产实例不设该变量，行为与以前一致。
router.get('/api/account/preview-signin', (req: Request, res) => {
  const preview = config.previewSignIn;
  if (!preview || !isLoopbackRequest(req)) { res.status(404).end(); return; }
  res.json({ ok: true, username: preview.username, password: preview.password });
});
router.get('/api/account/session', accountAuth, (_req, res) => res.json(sessionResponse(res.locals.session)));
router.post('/api/account/refresh', accountAuth, (_req, res) => { try { res.json(sessionResponse(accountStore.refresh(res.locals.session))); } catch (error) { accountFailure(res, error); } });
router.post('/api/account/logout', accountAuth, (_req, res) => { try { accountStore.logout(res.locals.session); res.json({ ok: true }); } catch (error) { accountFailure(res, error); } });
router.post('/api/account/password', accountAuth, async (req, res) => { try { await accountStore.changePassword(res.locals.session, req.body?.currentPassword, req.body?.newPassword); res.json({ ok: true }); } catch (error) { accountFailure(res, error); } });
router.use('/api/admin/accounts', accountAuth, (_req, res, next) => {
  if (!res.locals.session.account.isAdmin || res.locals.session.device.role !== 'console') { res.status(403).json({ ok: false, error: 'Administrator console required' }); return; }
  res.setHeader('Cache-Control', 'no-store'); next();
});
router.get('/api/admin/accounts', (_req, res) => res.json({ ok: true, accounts: accountStore.accounts() }));
router.post('/api/admin/accounts', (req, res) => {
  try {
    if (req.body?.isAdmin !== undefined && typeof req.body.isAdmin !== 'boolean') throw new AccountError(400, 'Invalid account role');
    const account = accountStore.createAccount(req.body?.username, req.body?.password, req.body?.isAdmin === true);
    res.status(201).json({ ok: true, account });
  } catch (error) { accountFailure(res, error); }
});
router.patch('/api/admin/accounts/:accountId', (req, res) => {
  try { accountStore.manageAccount(String(req.params.accountId), req.body); res.json({ ok: true }); } catch (error) { accountFailure(res, error); }
});
router.get('/api/devices', accountAuth, (_req, res) => { res.setHeader('Cache-Control', 'no-store'); res.json({ ok: true, devices: accountDeviceList(res.locals.session) }); });
router.patch('/api/devices/:deviceId', accountAuth, (req, res) => {
  try { accountStore.rename(res.locals.session.account.accountId, String(req.params.deviceId), req.body?.deviceName); res.json({ ok: true }); } catch (error) { accountFailure(res, error); }
});
router.delete('/api/devices/:deviceId', accountAuth, (req, res) => {
  try { accountStore.unbind(res.locals.session.account.accountId, String(req.params.deviceId)); res.json({ ok: true }); } catch (error) { accountFailure(res, error); }
});
export default router;
