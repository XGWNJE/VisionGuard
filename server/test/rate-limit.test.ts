import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';
import { registration } from './helpers/accounts';

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-rate-limit-'));
process.env.VISIONGUARD_DATA_DIR = directory;
const { accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const { createApiLimiter } = require('../src/middleware/rateLimit') as typeof import('../src/middleware/rateLimit');
const accountRouter = require('../src/routes/account').default;
const alertsRouter = require('../src/routes/alerts').default;
accountStore.createAccount('rate-owner', 'private-rate-test-password');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));
type Tokens = { token: string; resident: { token: string } };

async function fixture(t: any) {
  const app = express(); app.set('trust proxy', 1); app.use(express.json());
  app.use('/api', createApiLimiter()); app.use(accountRouter); app.use(alertsRouter);
  const server = http.createServer(app);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  t.after(() => new Promise<void>(resolve => server.close(() => resolve())));
  const request = (route: string, token?: string, body?: object, ip = '192.0.2.10') => fetch(origin + route, {
    method: body ? 'POST' : 'GET', headers: { 'X-Forwarded-For': ip,
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const login = async (component: string) => {
    const response = await request('/api/account/login', undefined,
      { username: 'rate-owner', password: 'private-rate-test-password', component, ...registration(component) });
    assert.equal(response.status, 200); return await response.json() as Tokens;
  };
  return { request, login };
}

test('normal same-IP node maintenance and console history exceed the old shared budget without 429', async t => {
  const f = await fixture(t), node = await f.login('windows-inference'), console = await f.login('web-console');
  for (let index = 0; index < 110; index++) {
    for (const [route, token] of [['/api/account/session', node.token], ['/api/account/session', node.resident.token], ['/api/alerts?limit=100', console.token]]) {
      const response = await f.request(route, token);
      assert.equal(response.status, 200, `${route} failed at ${index}`); await response.text();
    }
  }
});

test('authenticated budget stays bounded across token rotation and cannot block other components or login', async t => {
  const f = await fixture(t), node = await f.login('windows-inference');
  for (let index = 0; index < 299; index++) {
    const response = await f.request('/api/account/session', node.token); assert.equal(response.status, 200); await response.text();
  }
  const refresh = await f.request('/api/account/refresh', node.token, {});
  assert.equal(refresh.status, 200); const rotated = await refresh.json() as Tokens;
  const blocked = await f.request('/api/account/session', rotated.token);
  assert.equal(blocked.status, 429); assert.ok(Number(blocked.headers.get('retry-after')) > 0); await blocked.text();
  const resident = await f.request('/api/account/session', rotated.resident.token);
  assert.equal(resident.status, 200); await resident.text();
  const console = await f.login('web-console');
  const history = await f.request('/api/alerts', console.token); assert.equal(history.status, 200); await history.text();
});

test('unverified changing tokens consume the anonymous IP budget while a valid session remains usable', async t => {
  const f = await fixture(t), node = await f.login('windows-inference');
  for (let index = 0; index < 99; index++) {
    const response = await f.request('/api/account/session', `fabricated-${index}`); assert.equal(response.status, 401); await response.text();
  }
  const blocked = await f.request('/api/account/session', 'another-fabricated-token');
  assert.equal(blocked.status, 429); await blocked.text();
  const valid = await f.request('/api/account/session', node.token); assert.equal(valid.status, 200); await valid.text();
});

test('login attempts use the forwarded client IP rather than sharing the reverse proxy socket budget', async t => {
  const f = await fixture(t), body = { username: 'rate-owner', password: 'incorrect-test-password', component: 'web-console' };
  for (let index = 0; index < 20; index++) {
    const response = await f.request('/api/account/login', undefined, body, '192.0.2.20'); assert.equal(response.status, 401); await response.text();
  }
  const blocked = await f.request('/api/account/login', undefined, body, '192.0.2.20'); assert.equal(blocked.status, 429); await blocked.text();
  const other = await f.request('/api/account/login', undefined, body, '192.0.2.21'); assert.equal(other.status, 401); await other.text();
});
