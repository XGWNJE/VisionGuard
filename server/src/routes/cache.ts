import { Router } from 'express';
import { httpAuth } from '../middleware/auth';
import { maintainAccountCache, maintainLegacyCache } from '../services/CacheMaintenance';

const router = Router();
router.use('/api/cache', httpAuth, (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
router.all('/api/cache', (req, res) => {
  if (!['GET','POST'].includes(req.method)) { res.status(405).json({ok:false,error:'Method not allowed'}); return; }
  const value = req.method === 'GET' ? req.query : req.body;
  if (!value || Object.keys(value).some(key => key !== 'scope') || !['account','legacy'].includes(value.scope)) {
    res.status(400).json({ok:false,error:'Invalid cache scope'}); return;
  }
  const session = res.locals.session;
  if (value.scope === 'legacy' && !session.account.isAdmin) { res.status(403).json({ok:false,error:'Administrator required'}); return; }
  try {
    const report = value.scope === 'legacy' ? maintainLegacyCache(req.method === 'POST') : maintainAccountCache(session.account.accountId, req.method === 'POST');
    res.json({ok:true,...report});
  } catch { res.status(500).json({ok:false,error:'Cache maintenance failed; refresh and retry'}); }
});
export default router;
