import { Router } from 'express';
import { accountAuth } from '../middleware/auth';
import { mediaRelay } from '../services/MediaRelay';
import { accountFailure } from './account';
const router = Router();
router.get('/api/streams', accountAuth, (_req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const accountId = res.locals.session.account.accountId;
  // bindings 是「发布端 → 目标推理节点」的绑定；streams 是发布端每个来源一路，来源身份由发布端声明。
  res.json({ ok: true, streams: mediaRelay.streams(accountId), bindings: mediaRelay.bindings(accountId) });
});
router.post('/api/streams/bind', accountAuth, (req, res) => {
  try { res.json({ ok: true, stream: mediaRelay.bind(res.locals.session, req.body?.publisherDeviceId, req.body?.targetDeviceId) ?? null }); } catch (error) { accountFailure(res, error); }
});
// 解除绑定：控制台、发布端自己，或被推的被动方都能解。
router.post('/api/streams/unbind', accountAuth, (req, res) => {
  try { mediaRelay.unbind(res.locals.session, req.body?.publisherDeviceId); res.json({ ok: true }); } catch (error) { accountFailure(res, error); }
});
export default router;
