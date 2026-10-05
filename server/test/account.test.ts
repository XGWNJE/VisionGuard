import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import express from 'express';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-account-test-'));
process.env.VISIONGUARD_DATA_DIR = directory;
const { accountStore, AccountStore, accountDirectory } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const accountRouter = require('../src/routes/account').default;
const alertsRouter = require('../src/routes/alerts').default;
const screenshotRouter = require('../src/routes/screenshot').default;
const streamsRouter = require('../src/routes/streams').default;
const { handleConnection, broadcastScreenshotData } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { addAlert } = require('../src/services/AlertStore') as typeof import('../src/services/AlertStore');
accountStore.createAccount('account-a', 'private-test-password-a');
accountStore.createAccount('account-b', 'private-test-password-b');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));

let fixtureSequence = 0;
async function fixture(t: any) {
  const fixtureIp = `192.0.2.${++fixtureSequence}`;
  const app = express(); app.set('trust proxy', 'loopback'); app.use(express.json()); app.use(accountRouter); app.use(alertsRouter); app.use(screenshotRouter); app.use(streamsRouter);
  const server = http.createServer(app); const wss = new WebSocketServer({ server }); wss.on('connection', handleConnection);
  const peers: WebSocket[] = [];
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  t.after(async () => { peers.forEach(ws => ws.terminate()); wss.close(); await new Promise<void>(resolve => server.close(() => resolve())); });
  async function request(url: string, method = 'GET', body?: object, token?: string) {
    const response = await fetch(origin + url, { method, headers: { 'X-Forwarded-For': fixtureIp, ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const text = await response.text(); return { status: response.status, data: (() => { try { return JSON.parse(text); } catch { return text; } })() };
  }
  async function login(component = 'web-console', account = 'a', extra = {}) {
    const result = await request('/api/account/login', 'POST', { username: `account-${account}`, password: `private-test-password-${account}`, component, ...extra });
    assert.equal(result.status, 200); return result.data;
  }
  async function peer(token: string) {
    const ws = new WebSocket(origin.replace('http:', 'ws:') + '/ws'); peers.push(ws);
    const messages: any[] = []; ws.on('message', raw => messages.push(JSON.parse(raw.toString())));
    await new Promise<void>(resolve => ws.once('open', resolve));
    const send = (value: object) => ws.send(JSON.stringify(value));
    async function take(type: string, predicate = (_message: any) => true) {
      const deadline = Date.now() + 2500;
      while (Date.now() < deadline) { const i = messages.findIndex(message => message.type === type && predicate(message)); if (i >= 0) return messages.splice(i, 1)[0]; await new Promise(resolve => setTimeout(resolve, 10)); }
      throw new Error(`Missing ${type}`);
    }
    send({ type: 'auth', token, role: 'lifecycle', deviceId: 'spoofed', accountId: 'spoofed' });
    return { ws, send, take, messages, auth: await take('auth-result') };
  }
  return { origin, request, login, peer };
}

test('passwords and opaque credentials persist as hashes; malformed and duplicate accounts are rejected', () => {
  const file = path.join(directory, 'unit', 'accounts.json'), store = new AccountStore(file);
  assert.ok(store.createAccount('unit-account', 'private-unit-password').accountId);
  assert.throws(() => store.createAccount('unit-account', 'private-unit-password'), /exists/);
  assert.throws(() => store.createAccount('../invalid', 'private-unit-password'), /Invalid/);
  assert.throws(() => store.createAccount('valid-name', 'short'), /Invalid/);
  assert.equal(fs.readFileSync(file, 'utf8').includes('private-unit-password'), false);
});

test('expired sessions fail after restart and a failed revoke never changes the effective credentials', async t => {
  const file = path.join(directory, 'expiry', 'accounts.json'), store = new AccountStore(file);
  store.createAccount('expiry-owner', 'private-expiry-password');
  const login = await store.login({ username: 'expiry-owner', password: 'private-expiry-password', component: 'web-console' });
  const session = store.authenticate(login.token)!;
  const rename = fs.renameSync;
  try { fs.renameSync = (() => { throw new Error('Disk unavailable'); }) as typeof fs.renameSync; assert.throws(() => store.logout(session)); }
  finally { fs.renameSync = rename; }
  assert.ok(store.authenticate(login.token)); assert.ok(new AccountStore(file).authenticate(login.token));
  t.mock.method(Date, 'now', () => Date.parse(login.expiresAt) + 1);
  assert.equal(store.authenticate(login.token), undefined); assert.equal(new AccountStore(file).authenticate(login.token), undefined);
});

test('real login derives identity and HTTP permissions; no legacy key or anonymous test session exists', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/console/test-session', 'POST')).status, 404);
  assert.equal((await f.request('/api/account/login', 'POST', { username: 'account-a', password: 'incorrect', component: 'web-console' })).status, 401);
  assert.equal((await f.request('/api/account/login', 'POST', { username: 'account-a', password: 'private-test-password-a', component: 'constructor' })).status, 400);
  const login = await f.login('web-console', 'a', { role: 'detector', accountId: 'account-b' });
  assert.equal(login.device.role, 'console'); assert.equal(login.account.username, 'account-a');
  assert.equal((await f.request('/api/account/session', 'GET', undefined, login.token)).data.device.deviceId, login.device.deviceId);
  assert.equal((await f.request('/api/alerts')).status, 401);
  assert.equal((await fetch(f.origin + '/api/alerts', { headers: { 'X-API-Key': login.token } })).status, 401);
  assert.equal((await f.request('/api/alerts', 'GET', undefined, login.token)).status, 200);
  const peer = await f.peer(login.token);
  assert.equal(peer.auth.role, 'console'); assert.equal(peer.auth.deviceId, login.device.deviceId); assert.equal(peer.auth.component, 'web-console');
  assert.equal(fs.readFileSync(path.join(directory, 'accounts.json'), 'utf8').includes(login.token), false);
});

test('device ownership, lifecycle child, names and credential rotation are enforced', async t => {
  const f = await fixture(t), windows = await f.login('windows-inference'), other = await f.login('web-console', 'b');
  assert.equal(windows.resident.device.deviceId, windows.device.deviceId); assert.equal(windows.resident.device.role, 'lifecycle');
  assert.equal((await f.request('/api/devices/' + windows.device.deviceId, 'PATCH', { deviceName: 'stolen' }, other.token)).status, 404);
  assert.equal((await f.request('/api/devices/' + windows.device.deviceId, 'DELETE', undefined, other.token)).status, 404);
  assert.equal((await f.request('/api/account/login', 'POST', { username: 'account-b', password: 'private-test-password-b', component: 'windows-inference', deviceId: windows.device.deviceId })).status, 403);
  assert.equal((await f.request('/api/devices/' + windows.device.deviceId, 'PATCH', { deviceName: '门口视觉节点' }, windows.token)).status, 200);
  const next = await f.request('/api/account/refresh', 'POST', undefined, windows.token);
  assert.equal(next.status, 200); assert.equal(next.data.device.deviceName, '门口视觉节点');
  for (const token of [windows.token, windows.resident.token]) assert.equal((await f.request('/api/account/session', 'GET', undefined, token)).status, 401);
  const child = await f.request('/api/account/refresh', 'POST', undefined, next.data.resident.token);
  assert.equal(child.status, 200);
  assert.equal((await f.request('/api/account/logout', 'POST', undefined, next.data.token)).status, 200);
  assert.equal((await f.request('/api/account/session', 'GET', undefined, next.data.resident.token)).status, 401);
  assert.equal((await f.request('/api/account/session', 'GET', undefined, child.data.token)).status, 401);
});

test('independent web sessions sharing a device coexist; reconnect, rotation and logout affect only their owner', async t => {
  const f = await fixture(t), first = await f.login();
  const a = await f.peer(first.token);
  const second = await f.login('web-console', 'a', { deviceId: first.device.deviceId });
  const b = await f.peer(second.token);
  assert.notEqual(accountStore.authenticate(first.token)!.sessionId, accountStore.authenticate(second.token)!.sessionId);
  assert.ok(accountStore.authenticate(first.token));
  for (const p of [a, b]) { p.send({ type: 'heartbeat-console' }); assert.equal((await p.take('heartbeat-ack')).deviceId, first.device.deviceId); }
  const replaced = new Promise(resolve => a.ws.once('close', resolve));
  const replacement = await f.peer(first.token); await replaced;
  b.send({ type: 'heartbeat-console' }); await b.take('heartbeat-ack');
  const rotated = await f.request('/api/account/refresh', 'POST', {}, first.token);
  assert.equal(rotated.status, 200); assert.equal(accountStore.authenticate(first.token), undefined);
  assert.ok(accountStore.authenticate(second.token));
  const c = await f.peer(rotated.data.token);
  assert.equal((await f.request('/api/account/logout', 'POST', {}, rotated.data.token)).status, 200);
  b.send({ type: 'get-devices' }); await b.take('device-list');
  assert.equal(b.ws.readyState, WebSocket.OPEN);
  assert.ok(accountStore.authenticate(second.token));
  void replacement; void c;
});

test('hardware re-login still revokes its earlier credential and parent-bound resident', async t => {
  const f = await fixture(t), first = await f.login('windows-inference'), peer = await f.peer(first.token);
  const closed = new Promise(resolve => peer.ws.once('close', resolve));
  const second = await f.login('windows-inference', 'a', { deviceId: first.device.deviceId }); await closed;
  assert.equal(accountStore.authenticate(first.token), undefined); assert.equal(accountStore.authenticate(first.resident.token), undefined);
  assert.ok(accountStore.authenticate(second.token));
});

test('screenshot queues belong to each connection and stale timers cannot drain a replacement', async t => {
  const f = await fixture(t), first = await f.login();
  const second = await f.login('web-console', 'a', { deviceId: first.device.deviceId });
  const a = await f.peer(first.token), b = await f.peer(second.token);
  const oldIds = [crypto.randomUUID(), crypto.randomUUID(), crypto.randomUUID()];
  for (const alertId of oldIds) broadcastScreenshotData(first.account.accountId, { type: 'screenshot-data', alertId, deviceId: 'synthetic-visual', imageBase64: 'test-payload' });
  await a.take('screenshot-data', message => message.alertId === oldIds[0]);
  const replacement = await f.peer(first.token);
  const freshId = crypto.randomUUID();
  broadcastScreenshotData(first.account.accountId, { type: 'screenshot-data', alertId: freshId, deviceId: 'synthetic-visual', imageBase64: 'test-payload' });
  await replacement.take('screenshot-data', message => message.alertId === freshId);
  for (const alertId of oldIds) await b.take('screenshot-data', message => message.alertId === alertId);
  assert.equal(replacement.messages.some(message => message.type === 'screenshot-data' && oldIds.includes(message.alertId)), false);
  b.send({ type: 'heartbeat-console' }); await b.take('heartbeat-ack');
});

test('PATCH names remain authoritative after stale detector and resident heartbeats', async t => {
  const f = await fixture(t), console = await f.login(), windows = await f.login('windows-inference', 'a', { deviceName: 'Original name' });
  const observer = await f.peer(console.token), detector = await f.peer(windows.token), resident = await f.peer(windows.resident.token);
  assert.equal((await f.request('/api/devices/' + windows.device.deviceId, 'PATCH', { deviceName: 'Renamed node' }, console.token)).status, 200);
  assert.equal((await detector.take('device-updated')).device.deviceName, 'Renamed node');
  assert.equal((await resident.take('device-updated')).device.deviceName, 'Renamed node');
  await observer.take('device-list', message => message.devices.some((device: any) => device.deviceId === windows.device.deviceId && device.deviceName === 'Renamed node'));
  detector.send({ type: 'heartbeat', deviceName: 'Original name', isMonitoring: false, isReady: true });
  resident.send({ type: 'resident-heartbeat', deviceName: 'Original name', components: { resident: 'running', detectorApp: 'running' } });
  await detector.take('heartbeat-ack'); await resident.take('heartbeat-ack');
  const stored = accountStore.device(windows.account.accountId, windows.device.deviceId);
  assert.equal(stored?.deviceName, 'Renamed node');
  assert.equal((await f.request('/api/account/session', 'GET', undefined, windows.resident.token)).data.device.deviceName, 'Renamed node');
  assert.equal((await f.request('/api/devices', 'GET', undefined, console.token)).data.devices.find((device: any) => device.deviceId === windows.device.deviceId).deviceName, 'Renamed node');
});

test('events, screenshots, WS lists, controls and time standards stay inside the authenticated account', async t => {
  const f = await fixture(t), a = await f.login(), b = await f.login('web-console', 'b'), visual = await f.login('windows-inference');
  const pa = await f.peer(a.token), pb = await f.peer(b.token), detector = await f.peer(visual.token);
  detector.send({ type: 'heartbeat', isMonitoring: false, isReady: true, capabilities: ['monitor-control'], sources: [{ sourceId: 'front', sourceName: 'Front', modelKey: '', isMonitoring: false, isReady: true }] });
  await detector.take('heartbeat-ack');
  await pa.take('device-list', message => message.devices.some((device: any) => device.deviceId === visual.device.deviceId));
  pb.send({ type: 'get-devices' }); assert.equal((await pb.take('device-list')).devices.some((device: any) => device.deviceId === visual.device.deviceId), false);
  for (const message of [
    { type: 'command', requestId: 'foreign-command-123', targetDeviceId: visual.device.deviceId, command: 'pause' },
    { type: 'set-config', requestId: 'foreign-config-1234', targetDeviceId: visual.device.deviceId, key: 'confidence', value: '.5' },
    { type: 'request-screenshot', requestId: 'foreign-picture-123', targetDeviceId: visual.device.deviceId, alertId: crypto.randomUUID() },
  ]) { pb.send(message); assert.equal((await pb.take('command-ack', reply => reply.requestId === message.requestId)).success, false); }
  pa.send({ type: 'set-time-standard', requestId: 'account-time-1234', timeZone: 'UTC' }); assert.equal((await pa.take('time-standard-result')).success, true);
  pb.send({ type: 'get-time-standard' }); assert.equal((await pb.take('time-standard')).timeZone, 'Asia/Shanghai');
  const alertId = crypto.randomUUID(), screenshotPath = path.join(accountDirectory(a.account.accountId), 'screenshots', `${alertId}.png`);
  fs.mkdirSync(path.dirname(screenshotPath), { recursive: true }); fs.writeFileSync(screenshotPath, Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB', 'base64'));
  addAlert(a.account.accountId, { alertId, deviceId: visual.device.deviceId, deviceName: 'Visual', timestamp: new Date().toISOString(), detections: [], createdAt: Date.now(), screenshotPath });
  assert.equal((await f.request('/api/alerts', 'GET', undefined, a.token)).data.alerts.some((alert: any) => alert.alertId === alertId), true);
  assert.equal((await f.request('/api/alerts?deviceId=' + visual.device.deviceId, 'GET', undefined, b.token)).data.alerts.length, 0);
  assert.equal((await fetch(f.origin + `/screenshots/${alertId}.png`, { headers: { Authorization: `Bearer ${a.token}` } })).status, 200);
  assert.equal((await fetch(f.origin + `/screenshots/${alertId}.png`, { headers: { Authorization: `Bearer ${b.token}` } })).status, 404);
  assert.equal((await f.request('/api/devices', 'GET', undefined, b.token)).data.devices.some((device: any) => device.deviceId === visual.device.deviceId), false);
});

test('unbind revokes device and child connections; password change revokes every account session', async t => {
  const f = await fixture(t), console = await f.login(), windows = await f.login('windows-inference'), peer = await f.peer(windows.token);
  const closed = new Promise(resolve => peer.ws.once('close', resolve));
  assert.equal((await f.request('/api/devices/' + windows.device.deviceId, 'DELETE', undefined, console.token)).status, 200); await closed;
  for (const token of [windows.token, windows.resident.token]) assert.equal((await f.request('/api/account/session', 'GET', undefined, token)).status, 401);
  assert.equal((await f.request('/api/account/password', 'POST', { currentPassword: 'wrong', newPassword: 'next-private-password' }, console.token)).status, 401);
  assert.equal((await f.request('/api/account/password', 'POST', { currentPassword: 'private-test-password-a', newPassword: 'next-private-password' }, console.token)).status, 200);
  assert.equal((await f.request('/api/account/session', 'GET', undefined, console.token)).status, 401);
  assert.equal((await f.request('/api/account/login', 'POST', { username: 'account-a', password: 'private-test-password-a', component: 'web-console' })).status, 401);
  const next = await f.request('/api/account/login', 'POST', { username: 'account-a', password: 'next-private-password', component: 'web-console' }); assert.equal(next.status, 200);
  assert.equal(new AccountStore(path.join(directory, 'accounts.json')).authenticate(next.data.token)?.account.username, 'account-a');
});
