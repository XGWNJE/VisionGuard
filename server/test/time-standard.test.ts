import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';
import { TimeStandardStore } from '../src/services/TimeStandardStore';

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-time-standard-'));
process.env.API_KEY = 'time-standard-admin-test';
process.env.VISIONGUARD_CHANNEL = 'time-standard';
process.env.VISIONGUARD_DATA_DIR = path.join(temporary, 'runtime');
process.env.ALERT_STORE_PATH = path.join(temporary, 'alerts.json');
process.env.VISIONGUARD_IDENTITIES_FILE = path.join(temporary, 'identities.json');
const identities = [
  { deviceId: 'console-one', role: 'console', nodeType: 'console', platform: 'web', apiKey: 'time-console-one-test' },
  { deviceId: 'console-two', role: 'console', nodeType: 'console', platform: 'web', apiKey: 'time-console-two-test' },
  { deviceId: 'notifier', role: 'notifier', nodeType: 'notification', platform: 'android', apiKey: 'time-notifier-test-key' },
];
fs.writeFileSync(process.env.VISIONGUARD_IDENTITIES_FILE, JSON.stringify(identities));
const { handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
test.after(() => fs.rmSync(temporary, { recursive: true, force: true }));

test('the display standard persists without changing the reference instant', () => {
  const file = path.join(temporary, 'saved', 'time-standard.json');
  const store = new TimeStandardStore(file);
  const before = Date.now();
  assert.equal(store.get().timeZone, 'Asia/Shanghai');
  store.set('UTC');
  const value = new TimeStandardStore(file).get();
  assert.equal(value.timeZone, 'UTC');
  assert.ok(Date.parse(value.serverTime) >= before && Date.parse(value.serverTime) <= Date.now());
  assert.throws(() => store.set('bad-zone' as any), /Invalid/);
  assert.equal(new TimeStandardStore(file).get().timeZone, 'UTC');
});

test('a failed save preserves the current standard and malformed stored settings fail clearly', () => {
  const blocker = path.join(temporary, 'not-a-directory'); fs.writeFileSync(blocker, 'blocked');
  const store = new TimeStandardStore(path.join(blocker, 'time-standard.json'));
  assert.throws(() => store.set('UTC'));
  assert.equal(store.get().timeZone, 'Asia/Shanghai');
  const badFile = path.join(temporary, 'bad-standard.json'); fs.writeFileSync(badFile, JSON.stringify({ timeZone: 'bad-zone' }));
  assert.throws(() => new TimeStandardStore(badFile), /Invalid/);
});

test('only consoles can save the shared standard; all consoles and notifiers receive it on save and reconnect', { timeout: 10_000 }, async t => {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 }); server.on('connection', handleConnection);
  await new Promise<void>(resolve => server.once('listening', resolve));
  const peers: WebSocket[] = [];
  t.after(() => { peers.forEach(ws => ws.terminate()); server.close(); });
  async function connect(identity: typeof identities[number]) {
    const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`); peers.push(ws);
    const messages: any[] = []; ws.on('message', raw => messages.push(JSON.parse(raw.toString())));
    await new Promise<void>(resolve => ws.once('open', resolve));
    const send = (message: object) => ws.send(JSON.stringify(message));
    async function take(type: string) {
      const deadline = Date.now() + 2000;
      while (Date.now() < deadline) {
        const i = messages.findIndex(m => m.type === type);
        if (i >= 0) return messages.splice(i, 1)[0];
        await new Promise(resolve => setTimeout(resolve, 10));
      }
      throw new Error(`Missing ${type}`);
    }
    send({ type: 'auth', channel: 'time-standard', deviceName: identity.deviceId, ...identity });
    return { ws, send, take, auth: await take('auth-result') };
  }
  const first = await connect(identities[0]), second = await connect(identities[1]), notifier = await connect(identities[2]);
  assert.equal(notifier.auth.timeStandard.timeZone, 'Asia/Shanghai');
  first.send({ type: 'set-time-standard', requestId: 'save-time-1234', timeZone: 'UTC' });
  assert.equal((await first.take('time-standard-result')).success, true);
  for (const peer of [first, second, notifier]) assert.equal((await peer.take('time-standard')).timeZone, 'UTC');
  notifier.send({ type: 'set-time-standard', requestId: 'forbidden-save', timeZone: 'Asia/Shanghai' });
  second.send({ type: 'get-time-standard' });
  assert.equal((await second.take('time-standard')).timeZone, 'UTC');
  first.send({ type: 'set-time-standard', requestId: 'invalid-time-1234', timeZone: 'bad-zone' });
  assert.equal((await first.take('time-standard-result')).success, false);
  const closed = new Promise<void>(resolve => first.ws.once('close', resolve)); first.ws.close(); await closed;
  const reopened = await connect(identities[0]);
  assert.equal(reopened.auth.timeStandard.timeZone, 'UTC');
});
