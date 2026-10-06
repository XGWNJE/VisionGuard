import type { RequestHandler } from 'express';
import rateLimit from 'express-rate-limit';
import { accountStore } from '../services/AccountStore';

// Normal node traffic must not consume a shared NAT/proxy login budget.
export function createApiLimiter(): RequestHandler {
  const common = { standardHeaders: true, legacyHeaders: false, message: { ok: false, error: 'too many requests' } };
  const anonymous = rateLimit({ ...common, windowMs: 15 * 60_000, limit: 100 });
  const authenticated = rateLimit({ ...common, windowMs: 60_000, limit: 300,
    keyGenerator: (_req, res) => res.locals.apiRateLimitKey });
  return (req, res, next) => {
    const header = req.headers.authorization;
    const session = header?.startsWith('Bearer ') ? accountStore.authenticate(header.slice(7)) : undefined;
    if (!session) { anonymous(req, res, next); return; }
    const device = session.device;
    res.locals.apiRateLimitKey = `${session.account.accountId}:${device.deviceId}:${device.component}`;
    authenticated(req, res, next);
  };
}
