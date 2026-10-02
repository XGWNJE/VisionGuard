import { Router } from 'express';
import { accountAuth } from '../middleware/auth';
import { mediaRelay } from '../services/MediaRelay';
import { accountFailure } from './account';
const router = Router();
router.get('/api/streams', accountAuth, (_req, res) => { res.setHeader('Cache-Control', 'no-store'); res.json({ ok: true, streams: mediaRelay.streams(res.locals.session.account.accountId) }); });
router.post('/api/streams/bind', accountAuth, (req, res) => {
  try { res.json({ ok: true, stream: mediaRelay.bind(res.locals.session, req.body?.publisherDeviceId, req.body?.targetDeviceId) }); } catch (error) { accountFailure(res, error); }
});
export default router;
