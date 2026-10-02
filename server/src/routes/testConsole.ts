import { Router } from 'express';
import { config } from '../config';
import { createTestConsoleCredential } from '../services/NodeProtocol';

export function isTestNetwork(address: string | undefined): boolean {
  if (!address) return false;
  if (address === '::1') return true;
  const ip = address.replace(/^::ffff:/, '');
  const parts = ip.split('.').map(Number);
  if (!/^\d+\.\d+\.\d+\.\d+$/.test(ip) || parts.some(n => n < 0 || n > 255)) return false;
  const [a, b] = parts;
  return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

const router = Router();
router.post('/console/test-session', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (!config.testConsoleAutoLogin) { res.sendStatus(404); return; }
  // Check the actual peer, never the forwarded IP supplied by a proxy or caller.
  if (!isTestNetwork(req.socket.remoteAddress)) { res.sendStatus(403); return; }
  if (req.headers.origin && req.headers.origin !== `${req.protocol}://${req.headers.host}`) { res.sendStatus(403); return; }
  const credential = createTestConsoleCredential();
  if (!credential) { res.status(429).json({ error: 'test session limit reached' }); return; }
  res.json({ channel: config.channelId, deviceId: credential.deviceId, apiKey: credential.apiKey, testMode: true });
});
export default router;
