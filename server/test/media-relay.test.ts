import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
// 数据目录必须在加载 helpers（进而加载 config）之前确定：config 在求值时固化 dataDir。
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-media-test-'));
process.env.VISIONGUARD_DATA_DIR = directory;

import { registration } from './helpers/accounts';
import WebSocket, { WebSocketServer } from 'ws';

const { AccountFixture } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const owner = new AccountFixture([{ name: 'camera', component: 'android-camera' }, { name: 'inference', component: 'windows-inference' }, { name: 'console', component: 'web-console' }], 'media-owner');
const foreign = new AccountFixture([{ name: 'foreign-inference', component: 'windows-inference' }, { name: 'foreign-console', component: 'web-console' }], 'media-other');
const androidOnly = new AccountFixture([{ name: 'android-only-camera', component: 'android-camera' }, { name: 'android-only-inference', component: 'android-inference' }], 'media-android-owner');
const webOwner = new AccountFixture([{ name: 'web-camera', component: 'web-camera' }, { name: 'web-inference', component: 'windows-inference' }, { name: 'web-console', component: 'web-console' }], 'media-web-owner');
const { mediaRelay, parseFrame, framePacket, jpegDimensions } = require('../src/services/MediaRelay') as typeof import('../src/services/MediaRelay');
const { accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const { websocketLimits } = require('../src/services/WebSocketLimits') as typeof import('../src/services/WebSocketLimits');
const { allowedCapabilities, mediaDirections } = require('../src/services/NodeProtocol') as typeof import('../src/services/NodeProtocol');
const jpeg = Buffer.from('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCnRRRX0x88f//Z', 'base64');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));

async function fixture(t: any) {
  await Promise.all([owner.ready, foreign.ready, androidOnly.ready, webOwner.ready]);
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0, ...websocketLimits }); server.on('connection', ws => mediaRelay.handleConnection(ws));
  await new Promise<void>(resolve => server.once('listening', resolve));
  const sockets: WebSocket[] = [];
  t.after(() => { sockets.forEach(ws => ws.terminate()); server.close(); });
  async function connect(token: string, direction: string, sourceId = 'default', sourceName = '来源') {
    const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}/media/ws`); sockets.push(ws);
    const messages: any[] = []; ws.on('message', (raw, binary) => messages.push(binary ? { type: 'frame', frame: parseFrame(Buffer.from(raw as Buffer)) } : JSON.parse(raw.toString())));
    await new Promise<void>(resolve => ws.once('open', resolve));
    const closed = new Promise<number>(resolve => ws.once('close', code => resolve(code)));
    async function take(type: string) { const deadline = Date.now() + 2500; while (Date.now() < deadline) { const i = messages.findIndex(message => message.type === type); if (i >= 0) return messages.splice(i, 1)[0]; await new Promise(resolve => setTimeout(resolve, 5)); } throw new Error(`Missing ${type}`); }
    const send = (value: object) => ws.send(JSON.stringify(value));
    // 推流态的推理节点必须声明来源：一台发布端每个来源一条连接、一条流；相机忽略来源字段。
    send({ type: 'media-auth', token, direction, sourceId, sourceName });
    return { ws, closed, take, send, messages };
  }
  return { connect };
}
function packet(ready: any, sequence: number, extra = {}) { return framePacket({ streamId: ready.streamId, sessionId: ready.sessionId, sequence, capturedAt: Date.now(), width: 16, height: 16, rotation: 0, ...extra }, jpeg); }

test('binary framing checks JPEG dimensions, frame/header bounds and metadata', () => {
  assert.deepEqual(jpegDimensions(jpeg), { width: 16, height: 16 });
  const ready = { streamId: 'test-stream', sessionId: 'test-session' };
  assert.ok(parseFrame(packet(ready, 1)));
  for (const override of [{ width: 1281 }, { height: 721 }, { width: 17 }, { sequence: -1 }, { capturedAt: 0 }, { rotation: 45 }]) assert.equal(parseFrame(packet(ready, 1, override)), undefined);
  assert.equal(parseFrame(Buffer.alloc(2)), undefined);
  const truncated = packet(ready, 1).subarray(0, 32); assert.equal(parseFrame(truncated), undefined);
});

test('tiny fragmented messages hit a structural bound before media authentication', async t => {
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0, ...websocketLimits });
  server.on('connection', ws => { ws.on('error', () => {}); });
  await new Promise<void>(resolve => server.once('listening', resolve));
  const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}`);
  t.after(() => { ws.terminate(); server.close(); });
  await new Promise<void>(resolve => ws.once('open', resolve));
  const closed = new Promise<number>(resolve => ws.once('close', code => resolve(code)));
  for (let i = 0; i <= websocketLimits.maxFragments; i++) ws.send(Buffer.from([1]), { fin: false });
  assert.equal(await closed, 1008);
});

test('camera automatically binds to its account inference; foreign ownership and role escalation are denied', async t => {
  const f = await fixture(t), stream = mediaRelay.streams(owner.accountId)[0];
  assert.equal(stream.targetDeviceId, owner.id('inference')); assert.ok(stream.sourceId);
  // 跨账号不共享流：别的账号里不会出现本账号相机的推流记录。
  assert.equal(mediaRelay.streams(foreign.accountId).some(item => item.publisherDeviceId === owner.id('camera')), false);
  const session = accountStore.authenticate(owner.session('console').token)!;
  assert.throws(() => mediaRelay.bind(session, owner.id('camera'), foreign.id('foreign-inference')), /not found/);
  const denied = await f.connect(owner.session('console').token, 'publish'); assert.equal(await denied.closed, 4001);
  const wrongDirection = await f.connect(owner.session('camera').token, 'subscribe'); assert.equal(await wrongDirection.closed, 4001);
});

test('media pipelines eight frames without receipts; stalled consumers keep only the latest and cumulative receipts release the window', async t => {
  const f = await fixture(t);
  const producer = await f.connect(owner.session('camera').token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  const other = await f.connect(foreign.session('foreign-inference').token, 'subscribe'); await other.take('media-ready');
  producer.ws.send(packet(ready, 1)); assert.equal((await producer.take('frame-ack')).sequence, 1);
  const first = (await consumer.take('frame')).frame!; assert.equal(first.header.sequence, 1); assert.ok(first.header.receivedAt); assert.deepEqual(first.image, jpeg);
  // Burst without reading any publisher receipts, then delay all consumer receipts.
  for (let sequence = 2; sequence <= 10; sequence++) producer.ws.send(packet(ready, sequence));
  for (let sequence = 2; sequence <= 10; sequence++) assert.equal((await producer.take('frame-ack')).sequence, sequence);
  for (let sequence = 2; sequence <= 8; sequence++) assert.equal((await consumer.take('frame')).frame.header.sequence, sequence);
  const stats = mediaRelay.streams(owner.accountId)[0].stats!;
  assert.equal(stats.received, 10); assert.equal(stats.replaced, 1); assert.equal(stats.dropped, 1); assert.equal(stats.confirmed, 0);
  await new Promise(resolve => setTimeout(resolve, 30)); assert.equal(consumer.messages.some(message => message.type === 'frame'), false); assert.equal(other.messages.some(message => message.type === 'frame'), false);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: 'old-session', sequence: 8 });
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 999 });
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  assert.equal(mediaRelay.streams(owner.accountId)[0].stats!.confirmed, 0);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 8 });
  assert.equal((await consumer.take('frame')).frame.header.sequence, 10);
  assert.equal(mediaRelay.streams(owner.accountId)[0].stats!.confirmed, 8);
  producer.send({ type: 'stream-stop', reason: 'background' }); await producer.closed;
  assert.equal(mediaRelay.streams(owner.accountId)[0].isStreaming, false); assert.equal(mediaRelay.streams(owner.accountId)[0].stopReason, 'background');
});

test('duplicate live binding does not issue a new media-ready or reset publisher sequence', async t => {
  const f = await fixture(t), producer = await f.connect(owner.session('camera').token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); await producer.take('frame-ack'); await consumer.take('frame');
  mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, owner.id('camera'), owner.id('inference'));
  producer.send({ type: 'media-heartbeat' }); await producer.take('media-heartbeat-ack');
  assert.equal(producer.messages.some(message => message.type === 'media-ready'), false);
  producer.ws.send(packet(ready, 2)); await producer.take('frame-ack');
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 1 });
  assert.equal((await consumer.take('frame')).frame.header.sequence, 2);
});

test('idle media answers its own heartbeat and an old replaced callback cannot stop an active publisher', async t => {
  const f = await fixture(t), old = await f.connect(owner.session('camera').token, 'publish'); await old.take('media-ready');
  const replacement = await f.connect(owner.session('camera').token, 'publish'), ready = await replacement.take('media-ready'); await old.closed;
  replacement.send({ type: 'media-heartbeat' }); await replacement.take('media-heartbeat-ack');
  replacement.ws.send(packet(ready, 1)); const ack = await replacement.take('frame-ack');
  assert.equal(ack.accepted, false); assert.equal(ack.dropReason, 'consumer-unavailable'); assert.ok(ack.stats.unavailable >= 1);
  assert.equal(mediaRelay.streams(owner.accountId)[0].isStreaming, true);
});

test('sequence/session spoofing closes a publisher and account/device revocation closes media immediately', async t => {
  const f = await fixture(t), producer = await f.connect(owner.session('camera').token, 'publish'), ready = await producer.take('media-ready');
  producer.ws.send(packet(ready, 1, { sessionId: 'other-session' })); assert.equal(await producer.closed, 4002);
  const valid = await f.connect(owner.session('camera').token, 'publish'); await valid.take('media-ready');
  accountStore.logout(accountStore.authenticate(owner.session('camera').token)!); assert.equal(await valid.closed, 4001);
});

test('cached frames expire while a consumer waits and cannot be forwarded after an old acknowledgement', async t => {
  // Exercise delivery expiry first. Periodic publisher-stall cleanup can otherwise
  // discard this same frame as unavailable before the acknowledgement arrives.
  t.mock.method(mediaRelay, 'maintain', () => {});
  const f = await fixture(t);
  const session = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'expiry-camera' });
  mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, session.device.deviceId, owner.id('inference'));
  const producer = await f.connect(session.token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); await producer.take('frame-ack'); await consumer.take('frame');
  for (let sequence = 2; sequence <= 9; sequence++) {
    producer.ws.send(packet(ready, sequence)); await producer.take('frame-ack');
    if (sequence <= 8) await consumer.take('frame');
  }
  await new Promise(resolve => setTimeout(resolve, 2700));
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 8 });
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  assert.equal(consumer.messages.some(message => message.type === 'frame'), false);
  assert.ok(mediaRelay.streams(owner.accountId).find(item => item.streamId === ready.streamId)!.stats!.stale >= 1);

  // Separately cover maintenance winning the race: the publisher stops, the
  // cached frame is counted as unavailable, and a late acknowledgement does not revive it.
  for (let sequence = 10; sequence <= 18; sequence++) {
    producer.ws.send(packet(ready, sequence)); await producer.take('frame-ack');
    if (sequence <= 17) await consumer.take('frame');
  }
  t.mock.restoreAll();
  mediaRelay.maintain(performance.now() + 2700);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 17 });
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  const stopped = mediaRelay.streams(owner.accountId).find(item => item.streamId === ready.streamId)!;
  assert.equal(consumer.messages.some(message => message.type === 'frame'), false);
  assert.equal(stopped.stopReason, 'frame-stalled');
  assert.equal(stopped.stats!.received, 18);
  assert.equal(stopped.stats!.stale, 1);
  assert.equal(stopped.stats!.unavailable, 1);
  assert.equal(stopped.stats!.dropped, 2);
});

test('large packets hit the byte bound before the frame bound, and no receipt stalls the consumer', async t => {
  const f = await fixture(t);
  const camera = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'byte-bound-camera' });
  mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, camera.device.deviceId, owner.id('inference'));
  const producer = await f.connect(camera.token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  const large = Buffer.concat([jpeg.subarray(0, -2), Buffer.alloc(700_000), jpeg.subarray(-2)]);
  for (let sequence = 1; sequence <= 5; sequence++) {
    producer.ws.send(framePacket({ streamId: ready.streamId, sessionId: ready.sessionId, sequence, capturedAt: Date.now(), width: 16, height: 16, rotation: 0 }, large));
    await producer.take('frame-ack');
    if (sequence <= 2) await consumer.take('frame');
  }
  // Three packets exceed 2 MiB, leaving two sent and only the newest pending.
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  assert.equal(consumer.messages.some(message => message.type === 'frame'), false);
  const stats = mediaRelay.streams(owner.accountId).find(stream => stream.streamId === ready.streamId)!.stats!;
  assert.equal(stats.forwarded, 2); assert.equal(stats.replaced, 2);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 2 });
  assert.equal((await consumer.take('frame')).frame.header.sequence, 5);
  mediaRelay.maintain(performance.now() + 6000);
  assert.equal(await consumer.closed, 4002);
});

test('sampling follows each bound source and idle sources drop to one FPS without resetting sessions', async t => {
  const f = await fixture(t);
  const camera = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'rate-camera' });
  const stream = mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, camera.device.deviceId, owner.id('inference'))!;
  const producer = await f.connect(camera.token, 'publish'), ready = await producer.take('media-ready');
  for (const rate of [3, 1, 5]) {
    mediaRelay.updateSamplingRates(owner.accountId, owner.id('inference'), [{ sourceId: stream.sourceId!, isMonitoring: rate !== 1, targetSamplingRate: rate }]);
    const message = await producer.take('media-rate');
    assert.equal(message.targetSamplingRate, rate); assert.equal(message.sessionId, ready.sessionId);
  }
  mediaRelay.updateSamplingRates(owner.accountId, foreign.id('foreign-inference'), [{ sourceId: stream.sourceId!, isMonitoring: true, targetSamplingRate: 2 }]);
  producer.send({ type: 'media-heartbeat' }); await producer.take('media-heartbeat-ack');
  assert.equal(producer.messages.some(message => message.type === 'media-rate'), false);
});

test('android inference auto-binds as the only inference node and cannot publish while cameras cannot subscribe', async t => {
  const f = await fixture(t), stream = mediaRelay.streams(androidOnly.accountId)[0];
  assert.equal(stream.targetDeviceId, androidOnly.id('android-only-inference')); assert.ok(stream.sourceId);
  const producer = await f.connect(androidOnly.session('android-only-camera').token, 'publish'), ready = await producer.take('media-ready');
  assert.equal(ready.targetDeviceId, androidOnly.id('android-only-inference'));
  const consumer = await f.connect(androidOnly.session('android-only-inference').token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); assert.equal((await producer.take('frame-ack')).sequence, 1);
  assert.equal((await consumer.take('frame')).frame.header.sequence, 1);
  const publishing = await f.connect(androidOnly.session('android-only-inference').token, 'publish'); assert.equal(await publishing.closed, 4001);
  const subscribing = await f.connect(androidOnly.session('android-only-camera').token, 'subscribe'); assert.equal(await subscribing.closed, 4001);
});

test('web camera publishes and the web console can rebind it, while cameras cannot subscribe', async t => {
  const f = await fixture(t), stream = mediaRelay.streams(webOwner.accountId)[0];
  assert.equal(stream.publisherDeviceId, webOwner.id('web-camera'));
  assert.equal(stream.targetDeviceId, webOwner.id('web-inference')); assert.ok(stream.sourceId);
  const producer = await f.connect(webOwner.session('web-camera').token, 'publish'), ready = await producer.take('media-ready');
  assert.equal(ready.targetDeviceId, webOwner.id('web-inference'));
  const consumer = await f.connect(webOwner.session('web-inference').token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); assert.equal((await producer.take('frame-ack')).sequence, 1);
  assert.equal((await consumer.take('frame')).frame.header.sequence, 1);
  const session = accountStore.authenticate(webOwner.session('web-console').token)!;
  const rebound = mediaRelay.bind(session, webOwner.id('web-camera'), webOwner.id('web-inference'))!;
  assert.equal(rebound.sourceId, stream.sourceId);
  assert.throws(() => mediaRelay.bind(session, owner.id('camera'), webOwner.id('web-inference')), /not found/);
  const subscribing = await f.connect(webOwner.session('web-camera').token, 'subscribe'); assert.equal(await subscribing.closed, 4001);
});

test('unknown components cannot publish or subscribe on the media channel', async t => {
  const f = await fixture(t);
  const notifier = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-notifier', deviceName: 'forged-notifier-publisher' });
  const publishing = await f.connect(notifier.token, 'publish'); assert.equal(await publishing.closed, 4001);
  const subscribing = await f.connect(notifier.token, 'subscribe'); assert.equal(await subscribing.closed, 4001);
});

test('android inference receives per-source sampling updates only for its own account', async t => {
  const f = await fixture(t);
  const stream = mediaRelay.streams(androidOnly.accountId)[0];
  assert.equal(stream.targetDeviceId, androidOnly.id('android-only-inference'));
  const producer = await f.connect(androidOnly.session('android-only-camera').token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(androidOnly.session('android-only-inference').token, 'subscribe'); await consumer.take('media-ready');
  for (const rate of [4, 1, 5]) {
    mediaRelay.updateSamplingRates(androidOnly.accountId, androidOnly.id('android-only-inference'), [{ sourceId: stream.sourceId!, isMonitoring: rate !== 1, targetSamplingRate: rate }]);
    const message = await producer.take('media-rate');
    assert.equal(message.targetSamplingRate, rate); assert.equal(message.sessionId, ready.sessionId);
  }
  mediaRelay.updateSamplingRates(androidOnly.accountId, foreign.id('foreign-inference'), [{ sourceId: stream.sourceId!, isMonitoring: true, targetSamplingRate: 2 }]);
  mediaRelay.updateSamplingRates(owner.accountId, owner.id('inference'), [{ sourceId: stream.sourceId!, isMonitoring: true, targetSamplingRate: 2 }]);
  producer.send({ type: 'media-heartbeat' }); await producer.take('media-heartbeat-ack');
  assert.equal(producer.messages.some(message => message.type === 'media-rate'), false);
});

test('multiple inference nodes require selection, bindings stay stable, and clock skew is not treated as frame freshness', async t => {
  const f = await fixture(t);
  const camera = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'second-camera' });
  const extra = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'second-inference' });
  const third = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'third-camera' });
  assert.equal(mediaRelay.streams(owner.accountId).find(stream => stream.publisherDeviceId === third.device.deviceId)!.targetDeviceId, undefined);
  // 推理节点不会自动获得绑定：推流态的 Windows 节点必须由控制台显式绑定，避免多节点互相推流成环。
  // 推理节点的流要等它声明来源才出现，声明前连流行都没有。
  assert.equal(mediaRelay.streams(owner.accountId).some(stream => stream.publisherDeviceId === owner.id('inference')), false);
  assert.equal(mediaRelay.bindings(owner.accountId).some(item => item.publisherDeviceId === owner.id('inference')), false);
  const session = accountStore.authenticate(owner.session('console').token)!;
  const binding = mediaRelay.bind(session, camera.device.deviceId, extra.device.deviceId)!;
  assert.equal(mediaRelay.bind(session, camera.device.deviceId, extra.device.deviceId)!.sourceId, binding.sourceId);
  const producer = await f.connect(camera.token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(extra.token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1, { capturedAt: 100_000 })); await producer.take('frame-ack');
  assert.equal((await consumer.take('frame')).frame.header.capturedAt, 100_000);
  producer.ws.send(packet(ready, 2, { capturedAt: 10_000_000 })); assert.equal(await producer.closed, 4002);
  const renewed = await f.connect(camera.token, 'publish'), renewedReady = await renewed.take('media-ready');
  renewed.ws.send(packet(renewedReady, 1, { capturedAt: 100 })); await renewed.take('frame-ack');
  assert.equal((await consumer.take('frame')).frame.header.sessionId, renewedReady.sessionId);
  renewed.ws.send(packet(renewedReady, 2, { capturedAt: 1 })); assert.equal(await renewed.closed, 4002);
});

test('windows inference publishes after an explicit binding and cannot bind itself or deliver to a camera', async t => {
  const f = await fixture(t);
  // 两个 Windows 节点：A 推流、B 接收。A 不自动绑定，显式绑定后帧按中继契约送达 B。
  const publisher = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'publisher-node' });
  const receiver = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'receiver-node' });
  const session = accountStore.authenticate(owner.session('console').token)!;
  mediaRelay.bind(session, publisher.device.deviceId, receiver.device.deviceId);
  // 绑定存在与流无关：发布端还没声明来源时也能先绑定。
  assert.equal(mediaRelay.bindings(owner.accountId).find(item => item.publisherDeviceId === publisher.device.deviceId)?.targetDeviceId, receiver.device.deviceId);
  // 不能绑定到自己（自环），也不能把不可订阅的相机当目标。
  assert.throws(() => mediaRelay.bind(session, publisher.device.deviceId, publisher.device.deviceId), /not found/);
  assert.throws(() => mediaRelay.bind(session, publisher.device.deviceId, owner.id('camera')), /not found/);
  // 节点本机的「推流目标」下拉走同一条绑定：发布端可以为自己绑定，别的设备不行。
  assert.equal(mediaRelay.bindings(owner.accountId).find(item => item.publisherDeviceId === publisher.device.deviceId)?.targetDeviceId, receiver.device.deviceId);
  assert.throws(() => mediaRelay.bind(accountStore.authenticate(receiver.token)!, publisher.device.deviceId, receiver.device.deviceId), /permission/);
  const producer = await f.connect(publisher.token, 'publish'), ready = await producer.take('media-ready');
  assert.ok(ready.streamId); assert.equal(ready.sourceId, 'default'); assert.equal(ready.targetDeviceId, receiver.device.deviceId);
  const consumer = await f.connect(receiver.token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); assert.equal((await producer.take('frame-ack')).sequence, 1);
  assert.equal((await consumer.take('frame')).frame.header.sequence, 1);
});

test('a camera cannot subscribe to the media channel', async t => {
  const f = await fixture(t);
  const cameraSubscribing = await f.connect(owner.session('camera').token, 'subscribe');
  assert.equal(await cameraSubscribing.closed, 4001);
});

test('a live subscriber cannot also publish on the same device', async t => {
  const f = await fixture(t);
  const receiver = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'direction-locked-receiver' });
  const session = accountStore.authenticate(owner.session('console').token)!;
  mediaRelay.bind(session, owner.id('camera'), receiver.device.deviceId);
  const subscribed = await f.connect(receiver.token, 'subscribe'); await subscribed.take('media-ready');
  const publishing = await f.connect(receiver.token, 'publish');
  assert.equal(await publishing.closed, 4001);
  // 反转方向也一样：先推流再订阅必须被拒。
  const publisher = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'direction-locked-publisher' });
  mediaRelay.bind(session, publisher.device.deviceId, owner.id('inference'));
  const streaming = await f.connect(publisher.token, 'publish'); await streaming.take('media-ready');
  const subscribing = await f.connect(publisher.token, 'subscribe');
  assert.equal(await subscribing.closed, 4001);
});

test('a media identity is publish or subscribe only', () => {
  const camera = mediaDirections({ deviceId: 'c', deviceName: 'c', role: 'detector', nodeType: 'visual', platform: 'android', component: 'android-camera' } as any);
  assert.deepEqual(camera, { publish: true, subscribe: false });
  const android = mediaDirections({ deviceId: 'a', deviceName: 'a', role: 'detector', nodeType: 'visual', platform: 'android', component: 'android-inference' } as any);
  assert.deepEqual(android, { publish: false, subscribe: true });
  // Windows 推理节点两种方向都能申请，但一次连接只能选一个方向；本机状态保证不会同时跑两条支路。
  const windows = mediaDirections({ deviceId: 'w', deviceName: 'w', role: 'detector', nodeType: 'visual', platform: 'windows', component: 'windows-inference' } as any);
  assert.deepEqual(windows, { publish: true, subscribe: true });
  const notifier = mediaDirections({ deviceId: 'n', deviceName: 'n', role: 'notifier', nodeType: 'notification', platform: 'android', component: 'android-notifier' } as any);
  assert.deepEqual(notifier, { publish: false, subscribe: false });
});

test('windows inference cannot publish before an explicit binding and the relay reports the unbound drop', async t => {
  const f = await fixture(t);
  const publisher = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'unbound-publisher' });
  // 声明来源前连流都不存在；声明来源只建流，不会顺带绑定目标。
  assert.equal(mediaRelay.streams(owner.accountId).some(item => item.publisherDeviceId === publisher.device.deviceId), false);
  const producer = await f.connect(publisher.token, 'publish'), ready = await producer.take('media-ready');
  const stream = mediaRelay.streams(owner.accountId).find(item => item.streamId === ready.streamId)!;
  assert.ok(stream); assert.equal(stream.targetDeviceId, undefined); assert.equal(stream.sourceId, 'default');
  producer.ws.send(packet(ready, 1));
  const ack = await producer.take('frame-ack');
  assert.equal(ack.accepted, false); assert.equal(ack.dropReason, 'unbound');
});

test('one publisher pushes several sources to the same inference node and the passive side can unbind', async t => {
  const f = await fixture(t);
  const publisher = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'multi-source-publisher' });
  const receiver = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'multi-source-receiver' });
  const consoleSession = accountStore.authenticate(owner.session('console').token)!;
  mediaRelay.bind(consoleSession, publisher.device.deviceId, receiver.device.deviceId);
  const consumer = await f.connect(receiver.token, 'subscribe'); await consumer.take('media-ready');

  // 同一发布端的两个来源：各自一条连接、一条流，并行推给同一个目标。
  const first = await f.connect(publisher.token, 'publish', 'default', '画图');
  const firstReady = await first.take('media-ready');
  const second = await f.connect(publisher.token, 'publish', 'signal-2', '窗口 2');
  const secondReady = await second.take('media-ready');
  assert.notEqual(firstReady.streamId, secondReady.streamId);
  assert.equal(firstReady.sourceId, 'default'); assert.equal(secondReady.sourceId, 'signal-2');
  assert.equal(firstReady.targetDeviceId, receiver.device.deviceId);
  assert.equal(secondReady.targetDeviceId, receiver.device.deviceId);

  // 两条流互不干扰：序号各自从 1 开始，接收端按 streamId 分辨，两路都能送达。
  first.ws.send(packet(firstReady, 1)); assert.equal((await first.take('frame-ack')).accepted, true);
  second.ws.send(packet(secondReady, 1)); assert.equal((await second.take('frame-ack')).accepted, true);
  const delivered = [await consumer.take('frame'), await consumer.take('frame')].map(message => message.frame.header.streamId).sort();
  assert.deepEqual(delivered, [firstReady.streamId, secondReady.streamId].sort());
  const streams = mediaRelay.streams(owner.accountId).filter(stream => stream.publisherDeviceId === publisher.device.deviceId);
  assert.equal(streams.length, 2);
  assert.deepEqual(streams.map(stream => [stream.sourceId, stream.sourceName, stream.targetDeviceId]).sort(),
    [['default', '画图', receiver.device.deviceId], ['signal-2', '窗口 2', receiver.device.deviceId]].sort());

  // 被动方本机解除绑定：绑定消失、它自己的来源列表清空、发布端收到未绑定回执。
  mediaRelay.unbind(accountStore.authenticate(receiver.token)!, publisher.device.deviceId);
  assert.equal(mediaRelay.bindings(owner.accountId).some(item => item.publisherDeviceId === publisher.device.deviceId), false);
  assert.equal(mediaRelay.streams(owner.accountId).filter(stream => stream.targetDeviceId === receiver.device.deviceId).length, 0);
  assert.equal((await first.take('media-ready')).targetDeviceId, undefined);
  assert.equal((await second.take('media-ready')).targetDeviceId, undefined);
  first.ws.send(packet(firstReady, 2));
  assert.equal((await first.take('frame-ack')).dropReason, 'unbound');

  // 只有控制台、发布端自己和被推的被动方能解绑；控制台重新绑定后自己再解一次。
  const bystander = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'unbind-bystander' });
  mediaRelay.bind(consoleSession, publisher.device.deviceId, receiver.device.deviceId);
  assert.throws(() => mediaRelay.unbind(accountStore.authenticate(bystander.token)!, publisher.device.deviceId), /permission/);
  mediaRelay.unbind(consoleSession, publisher.device.deviceId);
  assert.equal(mediaRelay.bindings(owner.accountId).some(item => item.publisherDeviceId === publisher.device.deviceId), false);
  assert.throws(() => mediaRelay.unbind(consoleSession, publisher.device.deviceId), /not found/);
});

test('publisher source deletion removes only that stream and broadcasts the remaining directory', async t => {
  const f = await fixture(t);
  const publisher = await accountStore.login({ ...registration(), username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'source-delete-publisher' });
  mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, publisher.device.deviceId, owner.id('inference'));
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  const first = await f.connect(publisher.token, 'publish', 'delete-one', '删除一路'); await first.take('media-ready');
  const second = await f.connect(publisher.token, 'publish', 'keep-two', '保留一路'); const ready = await second.take('media-ready');
  mediaRelay.syncPublisherSources(owner.accountId, publisher.device.deviceId, [{ sourceId: 'delete-one', sourceName: '删除一路' }, { sourceId: 'keep-two', sourceName: '保留一路' }]);
  assert.equal(mediaRelay.streams(owner.accountId).filter(stream => stream.publisherDeviceId === publisher.device.deviceId).length, 2);
  mediaRelay.syncPublisherSources(owner.accountId, publisher.device.deviceId, [{ sourceId: 'keep-two', sourceName: '改名后' }]);
  assert.equal(await first.closed, 4000);
  const remaining = mediaRelay.streams(owner.accountId).filter(stream => stream.publisherDeviceId === publisher.device.deviceId);
  assert.deepEqual(remaining.map(stream => [stream.sourceId, stream.sourceName]), [['keep-two', '改名后']]);
  second.ws.send(packet(ready, 1)); assert.equal((await second.take('frame-ack')).accepted, true);
  assert.equal((await consumer.take('frame')).frame.header.streamId, ready.streamId);
  mediaRelay.syncPublisherSources(owner.accountId, publisher.device.deviceId, []);
  assert.equal(await second.closed, 4000);
  assert.equal(mediaRelay.streams(owner.accountId).some(stream => stream.publisherDeviceId === publisher.device.deviceId), false);
  assert.ok(mediaRelay.bindings(owner.accountId).some(binding => binding.publisherDeviceId === publisher.device.deviceId));
});

test('windows inference declares publish and subscribe capabilities while android inference stays subscribe-only', () => {
  const windows = allowedCapabilities({ deviceId: 'win-node', deviceName: 'win', role: 'detector', nodeType: 'visual', platform: 'windows', component: 'windows-inference' } as any,
    ['monitor-control', 'stream-control', 'video-publish', 'video-subscribe', 'visual-inference', 'camera-config']);
  assert.ok(windows.includes('video-publish')); assert.ok(windows.includes('stream-control')); assert.ok(windows.includes('video-subscribe'));
  assert.equal(windows.includes('camera-config'), false);
  const android = allowedCapabilities({ deviceId: 'android-node', deviceName: 'android', role: 'detector', nodeType: 'visual', platform: 'android', component: 'android-inference' } as any,
    ['video-publish', 'stream-control', 'video-subscribe', 'visual-inference']);
  assert.equal(android.includes('video-publish'), false); assert.equal(android.includes('stream-control'), false);
  assert.ok(android.includes('video-subscribe'));
});

test('explicit camera unbind survives directory refresh and permits a later manual bind', async t => {
  await fixture(t);
  const account = new AccountFixture([{ name: 'camera', component: 'android-camera' }, { name: 'inference', component: 'android-inference' }, { name: 'console', component: 'web-console' }], 'media-explicit-unbind');
  await account.ready;
  const session = accountStore.authenticate(account.session('console').token)!;
  const publisher = account.id('camera'), receiver = account.id('inference');
  assert.equal(mediaRelay.streams(account.accountId)[0].targetDeviceId, receiver);
  mediaRelay.unbind(session, publisher);
  for (let i = 0; i < 3; i++) {
    assert.equal(mediaRelay.streams(account.accountId)[0].targetDeviceId, undefined);
    assert.deepEqual(mediaRelay.bindings(account.accountId), []);
  }
  mediaRelay.bind(session, publisher, receiver);
  assert.equal(mediaRelay.streams(account.accountId)[0].targetDeviceId, receiver);
});
