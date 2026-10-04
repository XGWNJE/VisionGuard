import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-administration-'));
process.env.VISIONGUARD_DATA_DIR = directory;
const { AccountStore, accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const router = require('../src/routes/account').default;
const { handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));

test('administrator bootstrap preserves the existing password and is idempotent', async () => {
  const file = path.join(directory, 'existing', 'accounts.json'), store = new AccountStore(file);
  const original = store.createAccount('xgwnje', 'existing-private-password');
  store.ensureAdministrator('xgwnje'); store.ensureAdministrator('xgwnje');
  assert.deepEqual(store.accounts(), [{ ...original, isAdmin: true, enabled: true }]);
  const login = await store.login({ username: 'xgwnje', password: 'existing-private-password', component: 'web-console' });
  assert.equal(login.account.isAdmin, true);
  assert.equal(fs.existsSync(path.join(path.dirname(file), 'initial-administrator.json')), false);
});

test('fresh service generates private initial credentials once and never resets them', async () => {
  const file = path.join(directory, 'fresh', 'accounts.json'), store = new AccountStore(file);
  store.ensureAdministrator('xgwnje');
  const secretFile = path.join(path.dirname(file), 'initial-administrator.json');
  const credentials = JSON.parse(fs.readFileSync(secretFile, 'utf8'));
  assert.equal(credentials.username, 'xgwnje'); assert.ok(credentials.password.length >= 32);
  assert.equal((await store.login({ ...credentials, component: 'android-console' })).account.isAdmin, true);
  const before = fs.readFileSync(secretFile, 'utf8');
  new AccountStore(file).ensureAdministrator('xgwnje');
  assert.equal(fs.readFileSync(secretFile, 'utf8'), before);
  assert.equal(fs.readFileSync(file, 'utf8').includes(credentials.password), false);
});

test('HTTP account administration enforces console privileges and revokes sessions on account changes', async t => {
  const owner = accountStore.createAccount('admin-owner', 'private-owner-password', true);
  accountStore.createAccount('ordinary-user', 'private-user-password');
  const app = express(); app.use(express.json()); app.use(router);
  const server = http.createServer(app), wss = new WebSocketServer({ server });
  wss.on('connection', handleConnection);
  const peers: WebSocket[] = [];
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  t.after(async () => { peers.forEach(peer => peer.terminate()); wss.close(); await new Promise<void>(resolve => server.close(() => resolve())); });
  async function request(url: string, method = 'GET', body?: object, token?: string) {
    const response = await fetch(origin + url, { method, headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, data: await response.json() as any, cache: response.headers.get('cache-control') };
  }
  async function login(username: string, password: string, component = 'web-console') {
    const response = await request('/api/account/login', 'POST', { username, password, component });
    assert.equal(response.status, 200); return response.data;
  }
  const admin = await login('admin-owner', 'private-owner-password');
  const ordinary = await login('ordinary-user', 'private-user-password');
  const camera = await login('admin-owner', 'private-owner-password', 'android-camera');
  assert.equal((await request('/api/admin/accounts')).status, 401);
  for (const token of [ordinary.token, camera.token]) {
    assert.equal((await request('/api/admin/accounts', 'GET', undefined, token)).status, 403);
    assert.equal((await request('/api/admin/accounts', 'POST', { username: 'forbidden-user', password: 'private-created-password' }, token)).status, 403);
    assert.equal((await request('/api/admin/accounts/' + owner.accountId, 'PATCH', { isAdmin: false }, token)).status, 403);
  }
  const androidConsole = await login('admin-owner', 'private-owner-password', 'android-console');
  assert.equal((await request('/api/admin/accounts', 'GET', undefined, androidConsole.token)).status, 200);
  const list = await request('/api/admin/accounts', 'GET', undefined, admin.token);
  assert.equal(list.cache, 'no-store');
  for (const account of list.data.accounts) assert.deepEqual(Object.keys(account).sort(), ['accountId', 'enabled', 'isAdmin', 'username']);
  for (const changes of [{ enabled: false }, { isAdmin: false }]) assert.equal((await request('/api/admin/accounts/' + owner.accountId, 'PATCH', changes, admin.token)).status, 409);
  assert.ok(accountStore.authenticate(admin.token));
  assert.equal((await request('/api/admin/accounts', 'POST', { username: 'bad-role', password: 'private-created-password', isAdmin: 'true' }, admin.token)).status, 400);
  const created = await request('/api/admin/accounts', 'POST', { username: 'created-user', password: 'private-created-password' }, admin.token);
  assert.equal(created.status, 201); assert.equal(created.data.account.isAdmin, false);
  assert.equal((await request('/api/admin/accounts', 'POST', { username: 'created-user', password: 'private-created-password' }, admin.token)).status, 409);
  const target = '/api/admin/accounts/' + created.data.account.accountId;
  for (const changes of [{}, { enabled: 'false' }, { password: 'short' }, { isAdmin: 1 }]) assert.equal((await request(target, 'PATCH', changes, admin.token)).status, 400);
  const beforeDisable = await login('created-user', 'private-created-password');
  const ws = new WebSocket(origin.replace('http:', 'ws:') + '/ws'); peers.push(ws);
  await new Promise<void>(resolve => ws.once('open', resolve));
  const authenticated = new Promise<void>((resolve, reject) => { ws.on('message', raw => { const data = JSON.parse(raw.toString()); if (data.type === 'auth-result') data.success ? resolve() : reject(new Error('WebSocket authentication failed')); }); });
  ws.send(JSON.stringify({ type: 'auth', token: beforeDisable.token })); await authenticated;
  const closed = new Promise<void>((resolve, reject) => { const timeout = setTimeout(() => reject(new Error('Revoked WebSocket remained open')), 3000); ws.once('close', () => { clearTimeout(timeout); resolve(); }); });
  assert.equal((await request(target, 'PATCH', { enabled: false }, admin.token)).status, 200);
  await closed;
  assert.equal((await request('/api/account/session', 'GET', undefined, beforeDisable.token)).status, 401);
  assert.equal((await request('/api/account/login', 'POST', { username: 'created-user', password: 'private-created-password', component: 'web-console' })).status, 401);
  assert.equal((await request(target, 'PATCH', { enabled: true, password: 'private-reset-password' }, admin.token)).status, 200);
  assert.equal((await request('/api/account/login', 'POST', { username: 'created-user', password: 'private-created-password', component: 'web-console' })).status, 401);
  const reset = await login('created-user', 'private-reset-password');
  assert.equal((await request(target, 'PATCH', { isAdmin: true }, admin.token)).status, 200);
  assert.equal((await request('/api/admin/accounts', 'GET', undefined, reset.token)).status, 401);
  const promoted = await login('created-user', 'private-reset-password');
  assert.equal((await request('/api/admin/accounts', 'GET', undefined, promoted.token)).status, 200);
  assert.equal((await request('/api/admin/accounts/' + owner.accountId, 'PATCH', { isAdmin: false }, promoted.token)).status, 200);
  assert.equal((await request('/api/admin/accounts', 'GET', undefined, admin.token)).status, 401);
  assert.equal((await request(target, 'PATCH', { enabled: false }, promoted.token)).status, 409);
  assert.equal((await request('/api/admin/accounts/unknown', 'PATCH', { enabled: false }, promoted.token)).status, 404);
});
