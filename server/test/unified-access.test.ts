import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';
import { NotificationSession } from '../src/services/NotificationSession';

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-access-'));
process.env.API_KEY = 'test-http-admin-key';
process.env.VISIONGUARD_CHANNEL = 'access-test';
process.env.ALERT_STORE_PATH = path.join(temporary, 'alerts.json');
process.env.VISIONGUARD_DATA_DIR = temporary;
process.env.VISIONGUARD_IDENTITIES_FILE = path.join(temporary, 'identities.json');
const identities = [
  { deviceId: 'visual', role: 'detector', nodeType: 'visual', platform: 'windows', apiKey: 'visual-test-credential' },
  { deviceId: 'sensor', role: 'detector', nodeType: 'sensor', platform: 'embedded', apiKey: 'sensor-test-credential' },
  { deviceId: 'console', role: 'console', nodeType: 'console', platform: 'android', apiKey: 'console-test-credential' },
  { deviceId: 'notifier', role: 'notifier', nodeType: 'notification', platform: 'linux', apiKey: 'notifier-test-credential' },
  { deviceId: 'visual', role: 'lifecycle', nodeType: 'resident', platform: 'windows', apiKey: 'resident-test-credential' },
];
fs.writeFileSync(process.env.VISIONGUARD_IDENTITIES_FILE, JSON.stringify(identities));
test.after(() => fs.rmSync(temporary, { recursive: true, force: true }));
const { handleConnection, maintainRealtime } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { authenticateNode, allowedCapabilities, validateEvent, loadCredentials } = require('../src/services/NodeProtocol') as typeof import('../src/services/NodeProtocol');
const { getAlertById } = require('../src/services/AlertStore') as typeof import('../src/services/AlertStore');

class Peer {
  messages: any[] = [];
  constructor(readonly ws: WebSocket) { ws.on('message', raw => this.messages.push(JSON.parse(raw.toString()))); }
  send(message: object): void { this.ws.send(JSON.stringify(message)); }
  async take(predicate: (message: any) => boolean, timeout = 2500): Promise<any> {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const index = this.messages.findIndex(predicate);
      if (index >= 0) return this.messages.splice(index, 1)[0];
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    throw new Error('Expected message not received');
  }
}

async function fixture(t: any) {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  server.on('connection', handleConnection);
  await new Promise<void>(resolve => server.once('listening', resolve));
  const peers: Peer[] = [];
  const url = `ws://127.0.0.1:${(server.address() as any).port}`;
  t.after(() => { peers.forEach(peer => peer.ws.terminate()); server.close(); });
  async function connect(identity: typeof identities[number], override = {}) {
    const ws = new WebSocket(url);
    const peer = new Peer(ws); peers.push(peer);
    await new Promise<void>(resolve => ws.once('open', resolve));
    peer.send({ type: 'auth', channel: 'access-test', deviceName: identity.deviceId, ...identity, ...override });
    return { peer, auth: await peer.take(message => message.type === 'auth-result') };
  }
  return { connect, url };
}

function sensorEvent() {
  const now = Date.now();
  return { type: 'alert', alertId: crypto.randomUUID(), eventKind: 'sensor-detection', summary: '有人经过',
    timestamp: new Date(now).toISOString(), expiresAt: new Date(now + 30_000).toISOString(), detections: [] };
}

test('provisioned identity binds role, type, platform and ID; shared HTTP key grants no WS identity', () => {
  assert.ok(authenticateNode(identities[0]));
  for (const override of [{ role: 'console' }, { role: '__proto__' }, { role: 'constructor' }, { deviceId: 'other' }, { nodeType: 'sensor' }, { platform: 'android' }, { apiKey: process.env.API_KEY }]) {
    assert.equal(authenticateNode({ ...identities[0], ...override }), undefined);
  }
  assert.deepEqual(allowedCapabilities(identities[1] as any, ['source-control', 'screenshot-on-demand', 'monitor-control']), ['monitor-control']);
  const duplicate = path.join(temporary, 'duplicate.json');
  fs.writeFileSync(duplicate, JSON.stringify([identities[0], identities[0]]));
  assert.throws(() => loadCredentials(duplicate), /Duplicate/);
});

test('event deadline rejects expired, future and overlong sensor events without requiring a picture', () => {
  const event = sensorEvent();
  assert.ok(validateEvent(event, identities[1] as any));
  for (const override of [
    { expiresAt: new Date(Date.now() - 1).toISOString() },
    { expiresAt: new Date(Date.now() + 60_000).toISOString() },
    { timestamp: new Date(Date.now() + 10_000).toISOString() },
    { sourceId: 'fake-camera' }, { detections: [{}] },
  ]) assert.equal(validateEvent({ ...event, ...override }, identities[1] as any), undefined);
});

test('sensor event persists, retries only until deadline, and only its notifier can confirm receipt', async t => {
  const f = await fixture(t);
  const { peer: detector, auth } = await f.connect(identities[1]); assert.equal(auth.success, true);
  const { peer: console } = await f.connect(identities[2]);
  const { peer: notifier } = await f.connect(identities[3]);
  const event = sensorEvent();
  detector.send({ ...event, deviceId: 'spoofed' });
  const accepted = await detector.take(msg => msg.type === 'alert-ack');
  assert.equal(accepted.reason, 'stored');
  const received = await notifier.take(msg => msg.type === 'alert');
  assert.equal(received.deviceId, 'sensor'); assert.deepEqual(received.detections, []);
  assert.equal(getAlertById(event.alertId)?.eventKind, 'sensor-detection');
  console.send({ type: 'notification-receipt', alertId: event.alertId });
  maintainRealtime(Date.now() + 4000);
  assert.equal((await notifier.take(msg => msg.type === 'alert')).alertId, event.alertId);
  notifier.send({ type: 'notification-receipt', alertId: event.alertId });
  const receipt = await console.take(msg => msg.type === 'notification-receipt');
  assert.equal(receipt.notifierId, 'notifier');
  maintainRealtime(Date.now() + 8000);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(notifier.messages.some(msg => msg.type === 'alert'), false);

  const expiring = sensorEvent(); detector.send(expiring);
  await detector.take(msg => msg.type === 'alert-ack'); await notifier.take(msg => msg.type === 'alert');
  maintainRealtime(Date.parse(expiring.expiresAt) + 1);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(notifier.messages.some(msg => msg.type === 'alert'), false);
  const expired = { ...sensorEvent(), expiresAt: new Date(Date.now() - 1).toISOString() };
  detector.send(expired);
  assert.equal((await detector.take(msg => msg.type === 'alert-ack')).reason, 'invalid-or-expired-event');
  assert.equal(getAlertById(expired.alertId), undefined);
});

test('notification-node reconnect does not replay events from its previous session', async t => {
  const f = await fixture(t);
  const { peer: sensor } = await f.connect(identities[1]);
  const { peer: old } = await f.connect(identities[3]);
  const event = sensorEvent(); sensor.send(event);
  await sensor.take(msg => msg.type === 'alert-ack'); await old.take(msg => msg.type === 'alert');
  old.ws.close(); await new Promise(resolve => old.ws.once('close', resolve));
  const { peer: replacement } = await f.connect(identities[3]);
  maintainRealtime(Date.now() + 4000);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(replacement.messages.some(msg => msg.type === 'alert'), false);
});

test('detector health distinguishes intended pause, stalled frames, recovery and connection loss', async t => {
  const f = await fixture(t);
  const { peer: visual } = await f.connect(identities[0]);
  const { peer: console } = await f.connect(identities[2]);
  async function heartbeat(expected: boolean, progress: string) {
    visual.send({ type: 'heartbeat', isMonitoring: expected, isReady: true,
      sources: [{ sourceId: 'front', sourceName: 'Front', modelKey: '', isMonitoring: expected, isReady: true,
        monitoringExpected: expected, lastProgressAt: progress }] });
    await visual.take(msg => msg.type === 'heartbeat-ack');
  }
  await heartbeat(false, new Date(Date.now() - 60_000).toISOString());
  maintainRealtime();
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(console.messages.some(msg => msg.type === 'alert'), false);
  await heartbeat(true, new Date(Date.now() - 16_000).toISOString());
  maintainRealtime();
  const interruption = await console.take(msg => msg.type === 'alert' && msg.eventKind === 'detection-interrupted');
  assert.equal(interruption.sourceId, 'front');
  maintainRealtime(); await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(console.messages.some(msg => msg.type === 'alert'), false);
  await heartbeat(true, new Date().toISOString()); maintainRealtime();
  await heartbeat(true, new Date(Date.now() - 16_000).toISOString()); maintainRealtime();
  const next = await console.take(msg => msg.type === 'alert' && msg.eventKind === 'detection-interrupted');
  assert.notEqual(next.alertId, interruption.alertId);
  visual.ws.close();
  const offline = await console.take(msg => msg.type === 'alert' && msg.eventKind === 'connection-lost' && msg.deviceId === 'visual', 11_000);
  assert.equal(offline.deviceId, 'visual'); assert.deepEqual(offline.detections, []);
});

test('notification client watchdog works without service messages; retries do not repeat accepted alarms', async () => {
  let clock = 0, failures = 0, alarms = 0;
  const receipts: object[] = [];
  const session = new NotificationSession(msg => receipts.push(msg), async () => { alarms++; }, () => { failures++; }, () => clock);
  clock = 45_000; session.tick(); session.tick(); assert.equal(failures, 1);
  session.serviceResponded(); clock += 45_000; session.tick(); assert.equal(failures, 2);
  const event = sensorEvent();
  await session.event(event as any); await session.event(event as any);
  assert.equal(alarms, 1); assert.equal(receipts.length, 2);
  await session.event({ ...event, alertId: crypto.randomUUID(), expiresAt: new Date(Date.now() - 1).toISOString() } as any);
  assert.equal(alarms, 1);
  session.stop(); clock += 100_000; session.tick(); assert.equal(failures, 2);
});

test('generated interruption retries temporary storage failure within its original deadline', async t => {
  const f = await fixture(t);
  const { peer: sensor } = await f.connect(identities[1]);
  const { peer: console } = await f.connect(identities[2]);
  sensor.send({ type: 'heartbeat', isMonitoring: true, isReady: true, monitoringExpected: true,
    lastProgressAt: new Date(Date.now() - 16_000).toISOString() });
  await sensor.take(msg => msg.type === 'heartbeat-ack');
  const originalRename = fs.renameSync;
  try {
    fs.renameSync = (() => { throw new Error('Simulated temporary storage failure'); }) as typeof fs.renameSync;
    maintainRealtime();
  } finally { fs.renameSync = originalRename; }
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(console.messages.some(msg => msg.eventKind === 'detection-interrupted' && msg.deviceId === 'sensor'), false);
  maintainRealtime();
  const event = await console.take(msg => msg.eventKind === 'detection-interrupted' && msg.deviceId === 'sensor');
  assert.equal(getAlertById(event.alertId)?.eventKind, 'detection-interrupted');
  assert.equal(Date.parse(event.expiresAt) - Date.parse(event.timestamp), 30_000);
  maintainRealtime(); await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(console.messages.some(msg => msg.alertId === event.alertId), false);
});

test('real notification transport authenticates, accepts an event and returns receipt', async t => {
  const f = await fixture(t);
  const { peer: sensor } = await f.connect(identities[1]);
  const { peer: console } = await f.connect(identities[2]);
  const { NotificationNodeClient } = require('../src/services/NotificationNodeClient') as typeof import('../src/services/NotificationNodeClient');
  const alarms: any[] = [];
  const client = new NotificationNodeClient(f.url, { ...identities[3], channel: 'access-test', deviceName: 'Test Notifier' } as any,
    async event => { alarms.push(event); }, () => assert.fail('No outage expected'));
  t.after(() => client.stop());
  await console.take(msg => msg.type === 'device-list' && msg.devices.some((d: any) => d.deviceId === 'notifier'));
  const event = sensorEvent(); sensor.send(event);
  await console.take(msg => msg.type === 'notification-receipt' && msg.alertId === event.alertId);
  assert.equal(alarms.length, 1);
});

test('console persists notifier scopes and routing excludes unselected nodes and sources', async t => {
  const f = await fixture(t);
  const { peer: visual } = await f.connect(identities[0]);
  const { peer: sensor } = await f.connect(identities[1]);
  const { peer: console } = await f.connect(identities[2]);
  const { peer: notifier } = await f.connect(identities[3]);
  visual.send({ type: 'heartbeat', isMonitoring: false, isReady: true,
    sources: ['front', 'side'].map(sourceId => ({ sourceId, sourceName: sourceId, modelKey: '', isMonitoring: false, isReady: true })) });
  await visual.take(msg => msg.type === 'heartbeat-ack');
  console.send({ type: 'set-notification-scope', requestId: 'scope-selected', targetNotifierId: 'notifier',
    scope: { mode: 'selected', targets: [{ deviceId: 'visual', sourceId: 'front' }] } });
  assert.equal((await console.take(msg => msg.type === 'notification-scope-result' && msg.requestId === 'scope-selected')).success, true);
  assert.equal((await notifier.take(msg => msg.type === 'notification-scope')).scope.targets[0].sourceId, 'front');
  const stored = JSON.parse(fs.readFileSync(path.join(temporary, 'notification-scopes.json'), 'utf8'));
  assert.equal(stored.notifier.targets[0].sourceId, 'front');
  for (const sourceId of ['side', 'front']) {
    const event = { ...sensorEvent(), eventKind: 'visual-detection', sourceId,
      detections: [{ label: 'person', confidence: 0.9, bbox: { x: 0, y: 0, w: 1, h: 1 } }] };
    visual.send(event); assert.equal((await visual.take(msg => msg.type === 'alert-ack')).accepted, true);
    await console.take(msg => msg.type === 'alert' && msg.alertId === event.alertId);
    if (sourceId === 'front') assert.equal((await notifier.take(msg => msg.type === 'alert')).alertId, event.alertId);
  }
  sensor.send(sensorEvent()); await sensor.take(msg => msg.type === 'alert-ack');
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(notifier.messages.some(msg => msg.type === 'alert'), false);
  console.send({ type: 'set-notification-scope', requestId: 'scope-invalid', targetNotifierId: 'notifier',
    scope: { mode: 'selected', targets: [{ deviceId: 'sensor', sourceId: 'invented-camera' }] } });
  assert.equal((await console.take(msg => msg.type === 'notification-scope-result' && msg.requestId === 'scope-invalid')).success, false);
  console.send({ type: 'set-notification-scope', requestId: 'scope-empty', targetNotifierId: 'notifier', scope: { mode: 'selected', targets: [] } });
  assert.equal((await console.take(msg => msg.type === 'notification-scope-result' && msg.requestId === 'scope-empty')).success, true);
  // Switching scope must cancel pending retries from the previously selected source.
  maintainRealtime(Date.now() + 4000);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(notifier.messages.some(msg => msg.type === 'alert'), false);
  console.send({ type: 'set-notification-scope', requestId: 'scope-reset', targetNotifierId: 'notifier', scope: { mode: 'all', targets: [] } });
  assert.equal((await console.take(msg => msg.type === 'notification-scope-result' && msg.requestId === 'scope-reset')).success, true);
  assert.equal(JSON.stringify((await console.take(msg => msg.type === 'notification-scopes'))).includes('apiKey'), false);
});
