import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

// 数据目录与闸门状态必须在加载应用模块前确定：config 在求值时固化两者。
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-version-directory-'));
process.env.VISIONGUARD_DATA_DIR = directory;
process.env.VISIONGUARD_PROTOCOL_GATE = '1';

const { registration } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const { accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const { PROTOCOL_VERSION, createHttpVersionGate } = require('../src/services/protocol') as typeof import('../src/services/protocol');
const { recordDroppedVersion } = require('../src/middleware/auth') as typeof import('../src/middleware/auth');
const accountRouter = require('../src/routes/account').default;
const { handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { mediaRelay } = require('../src/services/MediaRelay') as typeof import('../src/services/MediaRelay');

test.after(() => fs.rmSync(directory, { recursive: true, force: true }));
const OWNER = 'version-owner';
const PASSWORD = 'private-version-password';
accountStore.createAccount(OWNER, PASSWORD);

/** 一个声明了本版协议的设备；override 用来构造旧版本或未声明的端点。 */
async function device(component: string, override: object = {}) {
  return await accountStore.login({ ...registration(), username: OWNER, password: PASSWORD, component, ...override });
}

async function fixture(t: any) {
  // 与部署实例一致：HTTP 与 WS 都挂协议闸门，出局端点先落版本再切断。
  const app = express(); app.use(express.json()); app.use(createHttpVersionGate(true, recordDroppedVersion)); app.use(accountRouter);
  const server = http.createServer(app);
  const sockets = new WebSocketServer({ server }); sockets.on('connection', handleConnection);
  // 媒体通道单独一个端口：同端口挂两个 server 时无路径的那个会先吞掉升级请求。
  const media = new WebSocketServer({ host: '127.0.0.1', port: 0 }); media.on('connection', ws => mediaRelay.handleConnection(ws));
  // 先挂监听再等主服务的 listen：媒体 server 在 `new` 时就开始监听，中间 await 会让事件错过。
  const mediaReady = new Promise<void>(resolve => media.once('listening', resolve));
  const peers: WebSocket[] = [];
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  await mediaReady;
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  const mediaOrigin = `ws://127.0.0.1:${(media.address() as any).port}`;
  t.after(async () => { peers.forEach(ws => ws.terminate()); sockets.close(); media.close(); await new Promise<void>(resolve => server.close(() => resolve())); });
  async function request(url: string, token: string, version: string | undefined = PROTOCOL_VERSION) {
    return await new Promise<{ status: number; data: any }>((resolve, reject) => {
      const req = http.request(origin + url, { method: 'GET',
        headers: { ...(version === undefined ? {} : { 'X-VisionGuard-Version': version }), ...(token ? { Authorization: `Bearer ${token}` } : {}) } }, response => {
        const chunks: Buffer[] = []; response.on('data', chunk => chunks.push(chunk)); response.on('end', () => {
          const text = Buffer.concat(chunks).toString(); resolve({ status: response.statusCode!, data: (() => { try { return JSON.parse(text); } catch { return text; } })() });
        });
      });
      req.on('error', reject); req.end();
    });
  }
  async function directory(token: string) {
    const result = await request('/api/devices', token);
    assert.equal(result.status, 200);
    return result.data.devices as Array<{ deviceId: string; online: boolean }>;
  }
  async function declare(message: object, url = origin.replace('http:', 'ws:') + '/ws') {
    const ws = new WebSocket(url); peers.push(ws);
    const messages: any[] = []; ws.on('message', raw => messages.push(JSON.parse(raw.toString())));
    await new Promise<void>(resolve => ws.once('open', resolve));
    const closed = new Promise<number>(resolve => ws.once('close', code => resolve(code)));
    async function take(type: string) {
      const deadline = Date.now() + 2500;
      while (Date.now() < deadline) { const found = messages.find(item => item.type === type); if (found) return found; await new Promise(resolve => setTimeout(resolve, 10)); }
      throw new Error(`Missing ${type}`);
    }
    ws.send(JSON.stringify(message));
    return { closed, messages, take };
  }
  return { request, directory, declare, mediaOrigin };
}

test('the node directory only lists endpoints that declared the served version', async t => {
  const f = await fixture(t);
  const console = await device('web-console');
  const legacy = await device('windows-inference', { version: '0.0.1' });
  const silent = await device('android-camera', { version: undefined });

  const listed = await f.directory(console.token);
  assert.ok(listed.some(item => item.deviceId === console.device.deviceId));
  // 旧版本与缺声明的端点不进目录，且版本本身不是对外字段。
  assert.equal(listed.some(item => item.deviceId === legacy.device.deviceId), false);
  assert.equal(listed.some(item => item.deviceId === silent.device.deviceId), false);
  assert.equal('version' in listed[0], false);

  // 端点按本版协议重新声明后回到目录。
  accountStore.recordVersion(console.account.accountId, legacy.device.deviceId, 'windows-inference', PROTOCOL_VERSION);
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === legacy.device.deviceId));
});

test('the console node list hides endpoints that did not declare the served version', async t => {
  const f = await fixture(t);
  const console = await device('web-console');
  const current = await device('android-inference');
  const legacy = await device('windows-inference', { version: '0.0.1' });
  const silent = await device('android-notifier', { version: undefined });

  // 控制台的节点列表来自通知范围通道（detectors / notifiers），必须与节点目录同一口径，
  // 否则旧版本端点虽不在 /api/devices 里，仍会出现在控制台节点目录。
  const client = await f.declare({ type: 'auth', token: console.token, version: PROTOCOL_VERSION });
  assert.equal((await client.take('auth-result')).success, true);
  const scopes = await client.take('notification-scopes');
  const listed = [...scopes.detectors, ...scopes.notifiers].map((item: any) => item.deviceId);
  assert.ok(listed.includes(current.device.deviceId));
  assert.equal(listed.includes(legacy.device.deviceId), false);
  assert.equal(listed.includes(silent.device.deviceId), false);

  // 按本版协议重新声明即回到名册。
  accountStore.recordVersion(console.account.accountId, legacy.device.deviceId, 'windows-inference', PROTOCOL_VERSION);
  const refreshed = await f.declare({ type: 'auth', token: console.token, version: PROTOCOL_VERSION });
  assert.ok((await refreshed.take('notification-scopes')).detectors.some((item: any) => item.deviceId === legacy.device.deviceId));
});

test('a websocket auth declaring another version is cut and leaves the directory', async t => {
  const f = await fixture(t);
  const console = await device('web-console');
  const node = await device('windows-inference');
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === node.device.deviceId));

  const dropped = await f.declare({ type: 'auth', token: node.token, version: '0.0.1' });
  assert.equal(await dropped.closed, 4001);
  assert.deepEqual(dropped.messages, []); // 静默出局：不回 auth-result
  assert.equal((await f.directory(console.token)).some(item => item.deviceId === node.device.deviceId), false);

  // 同一端点按本版协议重新认证后立即回到目录并在线。
  const accepted = await f.declare({ type: 'auth', token: node.token, version: PROTOCOL_VERSION });
  assert.equal((await accepted.take('auth-result')).success, true);
  const back = (await f.directory(console.token)).find(item => item.deviceId === node.device.deviceId);
  assert.equal(back?.online, true);
});

test('an /api request declaring another version is answered 426 and hides its endpoint', async t => {
  const f = await fixture(t);
  const console = await device('web-console');
  const node = await device('android-notifier');
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === node.device.deviceId));

  assert.equal((await f.request('/api/devices', node.token, '0.0.1')).status, 426);
  assert.equal((await f.directory(console.token)).some(item => item.deviceId === node.device.deviceId), false);
  // 同一个端点按本版协议请求即恢复显示。
  assert.equal((await f.request('/api/devices', node.token)).status, 200);
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === node.device.deviceId));
});

test('media authentication judges the declared version too', async t => {
  const f = await fixture(t);
  const console = await device('web-console');
  const camera = await device('android-camera');
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === camera.device.deviceId));

  const dropped = await f.declare({ type: 'media-auth', token: camera.token, direction: 'publish', version: '0.0.1' }, f.mediaOrigin);
  assert.equal(await dropped.closed, 4001);
  assert.equal((await f.directory(console.token)).some(item => item.deviceId === camera.device.deviceId), false);
  // 按本版协议声明即可发布，且设备回到目录。
  const accepted = await f.declare({ type: 'media-auth', token: camera.token, direction: 'publish', version: PROTOCOL_VERSION }, f.mediaOrigin);
  assert.equal((await accepted.take('media-ready')).streamId.length > 0, true);
  assert.ok((await f.directory(console.token)).some(item => item.deviceId === camera.device.deviceId));
});
