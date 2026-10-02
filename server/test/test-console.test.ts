import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import express from 'express';
import WebSocket, { WebSocketServer } from 'ws';

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-test-console-'));
process.env.API_KEY = 'private-test-admin-key';
process.env.VISIONGUARD_CHANNEL = 'test-console';
process.env.VISIONGUARD_DATA_DIR = temporary;
process.env.ALERT_STORE_PATH = path.join(temporary, 'alerts.json');
process.env.VISIONGUARD_IDENTITIES_FILE = path.join(temporary, 'identities.json');
process.env.VISIONGUARD_TEST_CONSOLE_AUTOLOGIN = 'true';
fs.writeFileSync(process.env.VISIONGUARD_IDENTITIES_FILE, JSON.stringify([
  { deviceId: 'visual', role: 'detector', nodeType: 'visual', platform: 'windows', apiKey: 'private-test-visual-key' },
]));
const { config } = require('../src/config') as typeof import('../src/config');
const { default: router, isTestNetwork } = require('../src/routes/testConsole') as typeof import('../src/routes/testConsole');
const { default: alertsRouter } = require('../src/routes/alerts') as typeof import('../src/routes/alerts');
const { handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { authenticateNode, authenticateToken, createTestConsoleCredential } = require('../src/services/NodeProtocol') as typeof import('../src/services/NodeProtocol');
test.after(() => fs.rmSync(temporary, { recursive: true, force: true }));

test('test access is restricted to actual loopback or private IPv4 peers', () => {
  for (const ip of ['127.0.0.1', '::1', '::ffff:192.168.31.58', '10.0.2.2', '172.16.0.1', '172.31.255.254']) assert.equal(isTestNetwork(ip), true);
  for (const ip of [undefined, '8.8.8.8', '172.15.0.1', '172.32.0.1', '192.168.999.1', 'localhost', '2001:db8::1']) assert.equal(isTestNetwork(ip), false);
});

test('automatic login refuses startup without a separate test data directory', () => {
  const child = spawnSync(process.execPath, ['--require', 'ts-node/register', '-e', "require('./src/config').validateConfig()"], {
    cwd: path.resolve(__dirname, '..'), env: { ...process.env, VISIONGUARD_DATA_DIR: '' }, encoding: 'utf8',
  });
  assert.equal(child.status, 1);
  assert.match(child.stderr, /VISIONGUARD_DATA_DIR/);
});

test('automatic sessions authenticate HTTP and simultaneous WS consoles without role escalation', async t => {
  const app = express(); app.use(router); app.use(alertsRouter);
  const server = http.createServer(app);
  const wss = new WebSocketServer({ server }); wss.on('connection', handleConnection);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  const peers: WebSocket[] = [];
  t.after(async () => { peers.forEach(ws => ws.terminate()); wss.close(); await new Promise<void>(resolve => server.close(() => resolve())); });
  (config as any).testConsoleAutoLogin = false;
  try { assert.equal((await fetch(`${origin}/console/test-session`, { method: 'POST' })).status, 404); }
  finally { (config as any).testConsoleAutoLogin = true; }
  assert.equal((await fetch(`${origin}/console/test-session`, { method: 'POST', headers: { Origin: 'http://other.example' } })).status, 403);
  const response = await fetch(`${origin}/console/test-session`, { method: 'POST', headers: { Origin: origin } });
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  const first = await response.json() as any;
  const second = await (await fetch(`${origin}/console/test-session`, { method: 'POST' })).json() as any;
  assert.equal(first.testMode, true); assert.equal(first.channel, config.channelId);
  assert.notEqual(first.deviceId, second.deviceId); assert.notEqual(first.apiKey, second.apiKey);
  assert.notEqual(first.apiKey, process.env.API_KEY);
  const identity = { ...first, role: 'console', nodeType: 'console', platform: 'web' };
  assert.ok(authenticateNode(identity));
  assert.equal(authenticateNode({ ...identity, deviceId: 'visual', role: 'detector', nodeType: 'visual', platform: 'windows' }), undefined);
  assert.equal((await fetch(`${origin}/api/alerts`)).status, 401);
  assert.equal((await fetch(`${origin}/api/alerts`, { headers: { 'X-API-Key': first.apiKey } })).status, 200);
  for (const session of [first, second]) {
    const ws = new WebSocket(origin.replace('http:', 'ws:')); peers.push(ws);
    await new Promise<void>(resolve => ws.once('open', resolve));
    const result = new Promise<any>(resolve => ws.once('message', raw => resolve(JSON.parse(raw.toString()))));
    ws.send(JSON.stringify({ type: 'auth', ...session, role: 'console', nodeType: 'console', platform: 'web', deviceName: 'Test console' }));
    assert.equal((await result).success, true);
  }
  const heartbeat = new Promise<any>(resolve => peers[0].on('message', raw => { const m = JSON.parse(raw.toString()); if (m.type === 'heartbeat-ack') resolve(m); }));
  peers[0].send(JSON.stringify({ type: 'heartbeat-console', deviceId: first.deviceId }));
  await heartbeat;
  assert.ok(peers.every(ws => ws.readyState === WebSocket.OPEN));
});

test('temporary console credentials expire after 24 hours', t => {
  const credential = createTestConsoleCredential()!;
  assert.ok(authenticateToken(credential.apiKey));
  const later = Date.now() + 24 * 60 * 60 * 1000 + 1;
  t.mock.method(Date, 'now', () => later);
  assert.equal(authenticateToken(credential.apiKey), undefined);
  assert.ok(authenticateToken('private-test-visual-key'));
});
