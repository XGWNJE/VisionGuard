import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-media-test-'));
process.env.VISIONGUARD_DATA_DIR = directory;
const { AccountFixture } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const owner = new AccountFixture([{ name: 'camera', component: 'android-camera' }, { name: 'inference', component: 'windows-inference' }, { name: 'console', component: 'web-console' }], 'media-owner');
const foreign = new AccountFixture([{ name: 'foreign-inference', component: 'windows-inference' }, { name: 'foreign-console', component: 'web-console' }], 'media-other');
const { mediaRelay, parseFrame, framePacket, jpegDimensions } = require('../src/services/MediaRelay') as typeof import('../src/services/MediaRelay');
const { accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
const { websocketLimits } = require('../src/services/WebSocketLimits') as typeof import('../src/services/WebSocketLimits');
const jpeg = Buffer.from('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCnRRRX0x88f//Z', 'base64');
test.after(() => fs.rmSync(directory, { recursive: true, force: true }));

async function fixture(t: any) {
  await Promise.all([owner.ready, foreign.ready]);
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0, ...websocketLimits }); server.on('connection', ws => mediaRelay.handleConnection(ws));
  await new Promise<void>(resolve => server.once('listening', resolve));
  const sockets: WebSocket[] = [];
  t.after(() => { sockets.forEach(ws => ws.terminate()); server.close(); });
  async function connect(token: string, direction: string) {
    const ws = new WebSocket(`ws://127.0.0.1:${(server.address() as any).port}/media/ws`); sockets.push(ws);
    const messages: any[] = []; ws.on('message', (raw, binary) => messages.push(binary ? { type: 'frame', frame: parseFrame(Buffer.from(raw as Buffer)) } : JSON.parse(raw.toString())));
    await new Promise<void>(resolve => ws.once('open', resolve));
    const closed = new Promise<number>(resolve => ws.once('close', code => resolve(code)));
    async function take(type: string) { const deadline = Date.now() + 2500; while (Date.now() < deadline) { const i = messages.findIndex(message => message.type === type); if (i >= 0) return messages.splice(i, 1)[0]; await new Promise(resolve => setTimeout(resolve, 5)); } throw new Error(`Missing ${type}`); }
    const send = (value: object) => ws.send(JSON.stringify(value));
    send({ type: 'media-auth', token, direction });
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
  assert.equal(mediaRelay.streams(foreign.accountId).length, 0);
  const session = accountStore.authenticate(owner.session('console').token)!;
  assert.throws(() => mediaRelay.bind(session, owner.id('camera'), foreign.id('foreign-inference')), /not found/);
  const denied = await f.connect(owner.session('console').token, 'publish'); assert.equal(await denied.closed, 4001);
  const wrongDirection = await f.connect(owner.session('camera').token, 'subscribe'); assert.equal(await wrongDirection.closed, 4001);
});

test('media relays only to the bound inference; slow consumers receive the newest frame with bounded credit', async t => {
  const f = await fixture(t);
  const producer = await f.connect(owner.session('camera').token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  const other = await f.connect(foreign.session('foreign-inference').token, 'subscribe'); await other.take('media-ready');
  producer.ws.send(packet(ready, 1)); assert.equal((await producer.take('frame-ack')).sequence, 1);
  const first = (await consumer.take('frame')).frame!; assert.equal(first.header.sequence, 1); assert.ok(first.header.receivedAt); assert.deepEqual(first.image, jpeg);
  for (const sequence of [2, 3]) { producer.ws.send(packet(ready, sequence)); await producer.take('frame-ack'); }
  const stats = mediaRelay.streams(owner.accountId)[0].stats!;
  assert.equal(stats.received, 3); assert.equal(stats.replaced, 1); assert.equal(stats.dropped, 1); assert.equal(stats.confirmed, 0);
  await new Promise(resolve => setTimeout(resolve, 30)); assert.equal(consumer.messages.some(message => message.type === 'frame'), false); assert.equal(other.messages.some(message => message.type === 'frame'), false);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 1 });
  assert.equal((await consumer.take('frame')).frame.header.sequence, 3);
  assert.equal(mediaRelay.streams(owner.accountId)[0].stats!.confirmed, 1);
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
  const session = await accountStore.login({ username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'expiry-camera' });
  mediaRelay.bind(accountStore.authenticate(owner.session('console').token)!, session.device.deviceId, owner.id('inference'));
  const producer = await f.connect(session.token, 'publish'), ready = await producer.take('media-ready');
  const consumer = await f.connect(owner.session('inference').token, 'subscribe'); await consumer.take('media-ready');
  producer.ws.send(packet(ready, 1)); await producer.take('frame-ack'); await consumer.take('frame');
  producer.ws.send(packet(ready, 2)); await producer.take('frame-ack');
  await new Promise(resolve => setTimeout(resolve, 2700));
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 1 });
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  assert.equal(consumer.messages.some(message => message.type === 'frame'), false);
  assert.ok(mediaRelay.streams(owner.accountId).find(item => item.streamId === ready.streamId)!.stats!.stale >= 1);

  // Separately cover maintenance winning the race: the publisher stops, the
  // cached frame is counted as unavailable, and a late acknowledgement does not revive it.
  producer.ws.send(packet(ready, 3)); await producer.take('frame-ack'); await consumer.take('frame');
  producer.ws.send(packet(ready, 4)); await producer.take('frame-ack');
  t.mock.restoreAll();
  mediaRelay.maintain(performance.now() + 2700);
  consumer.send({ type: 'frame-received', streamId: ready.streamId, sessionId: ready.sessionId, sequence: 3 });
  consumer.send({ type: 'media-heartbeat' }); await consumer.take('media-heartbeat-ack');
  const stopped = mediaRelay.streams(owner.accountId).find(item => item.streamId === ready.streamId)!;
  assert.equal(consumer.messages.some(message => message.type === 'frame'), false);
  assert.equal(stopped.stopReason, 'frame-stalled');
  assert.equal(stopped.stats!.received, 4);
  assert.equal(stopped.stats!.stale, 1);
  assert.equal(stopped.stats!.unavailable, 1);
  assert.equal(stopped.stats!.dropped, 2);
});

test('multiple inference nodes require selection, bindings stay stable, and clock skew is not treated as frame freshness', async t => {
  const f = await fixture(t);
  const camera = await accountStore.login({ username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'second-camera' });
  const extra = await accountStore.login({ username: 'media-owner', password: 'private-fixture-password', component: 'windows-inference', deviceName: 'second-inference' });
  const third = await accountStore.login({ username: 'media-owner', password: 'private-fixture-password', component: 'android-camera', deviceName: 'third-camera' });
  assert.equal(mediaRelay.streams(owner.accountId).find(stream => stream.publisherDeviceId === third.device.deviceId)!.targetDeviceId, undefined);
  const session = accountStore.authenticate(owner.session('console').token)!;
  const binding = mediaRelay.bind(session, camera.device.deviceId, extra.device.deviceId);
  assert.equal(mediaRelay.bind(session, camera.device.deviceId, extra.device.deviceId).sourceId, binding.sourceId);
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
