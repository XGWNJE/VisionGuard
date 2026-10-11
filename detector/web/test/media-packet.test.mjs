import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypescriptModule, sampleJpeg } from './helpers.mjs';

const mediaPacket = loadTypescriptModule('..', 'src/mediaPacket.ts');

test('frame packets use the 4-byte big-endian header length the service parses', () => {
  const header = { streamId: 'stream', sessionId: 'session', sequence: 3, capturedAt: 12_345, width: 16, height: 16, rotation: 0 };
  const packet = mediaPacket.encodePacket(header, sampleJpeg());
  const view = new DataView(packet.buffer, packet.byteOffset, packet.byteLength);
  const headerLength = view.getUint32(0);
  assert.equal(headerLength, Buffer.byteLength(JSON.stringify(header)));
  assert.deepEqual(JSON.parse(new TextDecoder().decode(packet.subarray(4, 4 + headerLength))), header);
  assert.deepEqual(packet.subarray(4 + headerLength), sampleJpeg());
  assert.throws(() => mediaPacket.encodePacket(header, new Uint8Array(0)), /2 MiB|帧头/);
  assert.throws(() => mediaPacket.encodePacket(header, new Uint8Array(mediaPacket.MAX_JPEG_BYTES + 1)), /2 MiB/);
});

test('frame headers reject fractional capture times, bad sequence and bad rotation before sending', () => {
  const base = { streamId: 'stream', sessionId: 'session', sequence: 1, capturedAt: 1234, width: 16, height: 16, rotation: 0 };
  const jpeg = sampleJpeg();
  // performance.now() 是小数；发出去会让服务端按非法帧断开，必须在组包前就拦住。
  assert.throws(() => mediaPacket.encodePacket({ ...base, capturedAt: 859.5999999940395 }, jpeg), /采集时间/);
  assert.throws(() => mediaPacket.encodePacket({ ...base, capturedAt: 0 }, jpeg), /采集时间/);
  assert.throws(() => mediaPacket.encodePacket({ ...base, sequence: -1 }, jpeg), /帧序号/);
  assert.throws(() => mediaPacket.encodePacket({ ...base, sequence: 2.5 }, jpeg), /帧序号/);
  assert.throws(() => mediaPacket.encodePacket({ ...base, rotation: 45 }, jpeg), /方向/);
  assert.ok(mediaPacket.encodePacket({ ...base, capturedAt: Math.round(859.6) }, jpeg));
});

test('frame window bounds in-flight frames by count and bytes and ignores duplicate receipts', () => {
  const window = new mediaPacket.FrameWindow();
  assert.equal(window.available(100), false);
  assert.equal(window.take(0, 100), undefined);
  window.reset('session');
  const sequences = [];
  for (let index = 0; index < 8; index++) sequences.push(window.take(index, 100));
  assert.deepEqual(sequences, [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.equal(window.take(9, 100), undefined);
  assert.equal(window.acknowledge('other-session', 1), false);
  assert.equal(window.acknowledge('session', 0), false);
  assert.equal(window.acknowledge('session', 99), false);
  assert.equal(window.acknowledge('session', 4), true);
  assert.equal(window.acknowledge('session', 4), false);
  assert.equal(window.inFlight, 4);
  assert.equal(window.take(10, 100), 9);
  assert.equal(window.available(mediaPacket.BYTE_LIMIT), false);
  window.reset('session');
  assert.equal(window.inFlight, 5, 'a repeated reset for the same session keeps in-flight frames');
  window.reset('session-2');
  assert.equal(window.inFlight, 0);
  assert.equal(window.take(11, 100), 1, 'a new session restarts the sequence');
});

test('frame window reports the age of an unacknowledged frame and stalls after three seconds', () => {
  const window = new mediaPacket.FrameWindow();
  window.reset('session');
  const sequence = window.take(1000, 50);
  assert.equal(window.acknowledgementAge('session', sequence, 1500), 500);
  assert.equal(window.acknowledgementAge('other', sequence, 1500), undefined);
  assert.equal(window.stalled(3999), false);
  assert.equal(window.stalled(4001), true);
});

test('frame pacer keeps a fixed cadence and never catches up in bursts', () => {
  const pacer = new mediaPacket.FramePacer();
  const sampled = [];
  for (let now = 0; now <= 4000; now += 40) {
    if (pacer.due(now, 5)) { pacer.sampled(now); sampled.push(now); }
  }
  assert.equal(sampled.length, 21);
  assert.throws(() => pacer.due(0, 0), /1–5/);
  assert.throws(() => pacer.due(0, 6), /1–5/);
  pacer.reset();
  assert.equal(pacer.due(10_000, 2), true);
  pacer.sampled(10_000);
  assert.equal(pacer.due(10_100, 2), false);
  assert.equal(pacer.due(10_600, 2), true);
});

test('media liveness expires twelve seconds after the last response, or after opening', () => {
  const liveness = new mediaPacket.MediaLiveness();
  liveness.opened(1000);
  assert.equal(liveness.expired(12_999), false);
  assert.equal(liveness.expired(13_000), true);
  liveness.responded(20_000);
  assert.equal(liveness.expired(31_999), false);
  assert.equal(liveness.expired(32_000), true);
});
