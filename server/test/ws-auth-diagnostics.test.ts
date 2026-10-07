import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-auth-diagnostics-'));
process.env.VISIONGUARD_CHANNEL = 'auth-diagnostics-test';
process.env.VISIONGUARD_DATA_DIR = temporary;
const { AccountFixture } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const fixtureAccount = new AccountFixture([{ name: 'console', component: 'web-console' }]);
const { handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
test.after(() => fs.rmSync(temporary, { recursive: true, force: true }));

async function fixture(t: any, trace = 'a'.repeat(32)) {
  const logs: any[] = [];
  t.mock.method(console, 'info', (tag: string, json: string) => { if (tag === '[WsAuth]') logs.push(JSON.parse(json)); });
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  let serverClose!: (code: number) => void;
  const serverClosed = new Promise<number>(resolve => { serverClose = resolve; });
  server.on('connection', (peer, req) => { handleConnection(peer, req); peer.once('close', serverClose); });
  await new Promise<void>(resolve => server.once('listening', resolve));
  const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}/ws?traceId=${trace}&token=private-query-token`);
  const messages: any[] = [];
  ws.on('message', raw => messages.push(JSON.parse(raw.toString())));
  const clientClosed = new Promise<number>(resolve => ws.once('close', code => resolve(code)));
  const closed = Promise.all([clientClosed, serverClosed]).then(([code]) => code);
  t.after(async () => { ws.terminate(); server.clients.forEach(peer => peer.terminate()); await new Promise<void>(resolve => server.close(() => resolve())); });
  await new Promise<void>(resolve => ws.once('open', resolve));
  return { ws, logs, messages, closed };
}

test('an unauthenticated socket is logged from upgrade through timeout and close', { timeout: 8000 }, async t => {
  const f = await fixture(t);
  assert.equal(await f.closed, 4001);
  assert.deepEqual(f.logs.map(item => item.event), ['socket-open','auth-timeout','socket-close']);
  assert.equal(f.logs[1].firstFrameReceived, false);
  assert.ok(f.logs[1].elapsedMs >= 4900);
  assert.ok(f.logs.every(item => item.traceId === 'a'.repeat(32) && item.connectionId === f.logs[0].connectionId));
  assert.ok(!JSON.stringify(f.logs).includes('private-query-token'));
});

test('a rejected token is distinguishable from no first frame without logging the auth message', async t => {
  const f = await fixture(t);
  f.ws.send(JSON.stringify({ type: 'auth', token: 'private-invalid-token', password: 'private-password' }));
  assert.equal(await f.closed, 4001);
  assert.deepEqual(f.logs.map(item => item.event), ['socket-open','first-frame','auth-rejected','socket-close']);
  assert.equal(f.logs[2].reason, 'invalid-session');assert.equal(f.logs[2].firstFrameReceived, true);
  assert.ok(!JSON.stringify(f.logs).includes('private-'));
});

test('a successful console auth logs its attempt and component, then its close code', async t => {
  await fixtureAccount.ready;
  const f = await fixture(t);
  const auth = fixtureAccount.auth('console') as { token: string };
  f.ws.send(JSON.stringify(auth));
  const deadline = Date.now() + 2500;
  while (!f.logs.some(item => item.event === 'auth-success') && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5));
  assert.ok(f.logs.some(item => item.event === 'auth-success'));
  f.ws.close(1000, 'private-client-close-text');await f.closed;
  const success = f.logs.find(item => item.event === 'auth-success');
  assert.equal(success.component, 'web-console');assert.equal(success.authenticated, true);
  assert.equal(f.messages.find(item => item.type === 'auth-result')?.success, true);
  assert.equal(f.logs[f.logs.length - 1].code, 1000);
  const text = JSON.stringify(f.logs);
  assert.ok(!text.includes(auth.token));assert.ok(!text.includes('private-'));
});

test('untrusted trace and malformed first auth JSON cannot inject logs or crash the handler', async t => {
  const f = await fixture(t, 'password%3Dprivate-trace');
  f.ws.send('null');assert.equal(await f.closed, 4001);
  assert.equal(f.logs.find(item => item.event === 'auth-rejected').reason, 'invalid-auth-message');
  assert.ok(f.logs.every(item => !Object.prototype.hasOwnProperty.call(item, 'traceId')));
  assert.ok(!JSON.stringify(f.logs).includes('private-'));
});
