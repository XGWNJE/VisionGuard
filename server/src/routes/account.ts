import { Router, type Response } from 'express';
import rateLimit, { ipKeyGenerator } from 'express-rate-limit';
import { accountAuth } from '../middleware/auth';
import { accountStore, AccountError, type AccountSession } from '../services/AccountStore';
import { config } from '../config';
import { accountDeviceList } from '../services/ConnectionManager';

export function sessionResponse(session: AccountSession & { token?: string; resident?: AccountSession & { token: string } }): object {
  const { accountId: ignored, ...device } = session.device;
  const resident = session.resident;
  return { ok: true, ...(session.token ? { token: session.token } : {}), expiresAt: session.expiresAt, channel: config.channelId,
    account: session.account, device, ...(resident ? { resident: { token: resident.token, expiresAt: resident.expiresAt, device: (({ accountId: _account, ...rest }) => rest)(resident.device) } } : {}) };
}
export function accountFailure(res: Response, error: unknown): void {
  if (error instanceof AccountError) res.status(error.status).json({ ok: false, error: error.message });
  else { console.error('[account] operation failed'); res.status(500).json({ ok: false, error: 'Account operation failed' }); }
}
const router = Router();
router.use('/api/account', (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
const loginLimiter = rateLimit({ windowMs: 60_000, max: 20, standardHeaders: true, legacyHeaders: false,
  keyGenerator: req => ipKeyGenerator(req.socket.remoteAddress || 'unknown'),
  message: { ok: false, error: 'Too many login attempts' } });
router.post('/api/account/login', loginLimiter, async (req, res) => {
  try { res.json(sessionResponse(await accountStore.login(req.body))); } catch (error) { accountFailure(res, error); }
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
