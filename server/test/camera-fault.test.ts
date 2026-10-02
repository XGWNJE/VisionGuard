import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-camera-fault-'));
process.env.VISIONGUARD_DATA_DIR = directory;
const { AccountFixture } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const names = ['idle', 'user', 'background', 'locked'];
const account = new AccountFixture([...names.map(name => ({ name, component: 'android-camera' as const })), { name: 'inference', component: 'windows-inference' }, { name: 'forged-stop-inference', component: 'windows-inference' }, { name: 'console', component: 'web-console' }]);
const { handleConnection, maintainRealtime } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { getAlerts } = require('../src/services/AlertStore') as typeof import('../src/services/AlertStore');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));

test('camera control exits never emit inference faults; Windows source interruption and disconnect still do', async t => {
  await account.ready;
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 }); server.on('connection', handleConnection);
  await new Promise<void>(resolve => server.once('listening', resolve));
  const peers: WebSocket[] = [], messages: any[] = [];
  t.after(() => { peers.forEach(ws => ws.terminate()); server.close(); });
  async function connect(name: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`); peers.push(ws);
    ws.on('message', raw => messages.push({ name, ...JSON.parse(raw.toString()) }));
    await new Promise<void>(resolve => ws.once('open', resolve)); ws.send(JSON.stringify(account.auth(name)));
    await take(message => message.name === name && message.type === 'auth-result'); return ws;
  }
  async function take(predicate: (message: any) => boolean, milliseconds = 2500) {
    const end = Date.now() + milliseconds;
    while (Date.now() < end) { const match = messages.find(predicate); if (match) return match; await new Promise(resolve => setTimeout(resolve, 10)); }
    throw new Error('Expected contract result missing');
  }
  await connect('console');
  for (const name of names) {
    const camera = await connect(name);
    camera.send(JSON.stringify({ type: 'heartbeat', isMonitoring: false, isReady: true, monitoringExpected: true,
      sources: [{ sourceId: 'untrusted-inference', sourceName: 'Untrusted', modelKey: '', isMonitoring: false, isReady: true, monitoringExpected: true, lastProgressAt: new Date(Date.now() - 16_000).toISOString() }] }));
    await take(message => message.name === name && message.type === 'heartbeat-ack');
    maintainRealtime();
    if (name !== 'idle') camera.send(JSON.stringify({ type: 'disconnect-reason', reason: name }));
    camera.close();
  }
  const inference = await connect('inference');
  inference.send(JSON.stringify({ type: 'heartbeat', isMonitoring: false, isReady: false,
    sources: [{ sourceId: 'remote-source', sourceName: 'Remote camera', modelKey: '', isMonitoring: false, isReady: false, monitoringExpected: true, lastProgressAt: new Date(Date.now() - 16_000).toISOString() }] }));
  await take(message => message.name === 'inference' && message.type === 'heartbeat-ack'); maintainRealtime();
  assert.equal((await take(message => message.name === 'console' && message.deviceId === account.id('inference') && message.eventKind === 'detection-interrupted')).sourceId, 'remote-source');
  const forged = await connect('forged-stop-inference');
  forged.send(JSON.stringify({ type: 'disconnect-reason', reason: 'background' })); forged.close();
  await take(message => message.name === 'console' && message.deviceId === account.id('forged-stop-inference') && message.eventKind === 'connection-lost', 12_000);
  const stored = getAlerts(account.accountId, undefined, undefined, 200);
  for (const name of names) assert.equal(stored.some(alert => alert.deviceId === account.id(name)), false, `${name} camera exit must not create an inference alarm`);
});
