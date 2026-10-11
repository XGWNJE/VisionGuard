import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypescriptModule, sampleJpeg } from './helpers.mjs';

const mediaModule = loadTypescriptModule('..', 'src/media.ts');

function harness() {
  let now = 1000;
  const timers = new Map();
  const sockets = [];
  let nextHandle = 1;
  const comments = [];
  const createSocket = () => {
    const socket = { readyState: 0, sent: [], onopen: null, onmessage: null, onclose: null, onerror: null,
      send(data) { socket.sent.push(data); },
      close() { socket.readyState = 3; socket.onclose?.({}); } };
    sockets.push(socket);
    return socket;
  };
  const ports = {
    origin: 'https://camera.example', openSocket: () => { const socket = createSocket(); socket.readyState = 1; return socket; },
    now: () => now,
    setTimer: callback => { const handle = nextHandle++; timers.set(handle, callback); return handle; },
    clearTimer: handle => timers.delete(handle),
    onChange: () => {}, onError: status => comments.push(status),
  };
  const publisher = new mediaModule.MediaPublisher(() => 'token-value', ports);
  return { publisher, sockets, timers, comments, socket: () => sockets[sockets.length - 1],
    receive(value) { sockets[sockets.length - 1].onmessage?.({ data: typeof value === 'string' ? value : JSON.stringify(value) }); },
    advance(milliseconds) { const end = now + milliseconds; while (now < end) { now = Math.min(now + 1000, end); for (const [handle, callback] of [...timers]) { if (timers.has(handle)) callback(); } } } };
}

test('media publisher authenticates, publishes, and acknowledges frames with the live session', () => {
  const h = harness();
  h.publisher.start();
  const socket = h.socket();
  assert.ok(socket, 'media socket must open');
  socket.onopen?.({});
  assert.deepEqual(JSON.parse(socket.sent[0]), { type: 'media-auth', token: 'token-value', direction: 'publish' });
  h.receive({ type: 'media-ready', sessionId: 'session-1', streamId: 'stream-1', targetSamplingRate: 3 });
  assert.equal(h.publisher.current.ready, true);
  assert.equal(h.publisher.current.status, '正在推流');
  assert.equal(h.publisher.targetSamplingRate, 3);
  assert.equal(h.publisher.due(1000), true, 'the first call adopts the sampling rate and is due at once');
  assert.equal(h.publisher.due(1000), false, 'a sampled interval is not due twice');
  const frame = { jpeg: sampleJpeg(), width: 16, height: 16, rotation: 90, capturedAt: 1000 };
  assert.equal(h.publisher.publish(frame), true);
  const packet = socket.sent[socket.sent.length - 1];
  const view = new DataView(packet.buffer, packet.byteOffset, packet.byteLength);
  const header = JSON.parse(new TextDecoder().decode(packet.subarray(4, 4 + view.getUint32(0))));
  assert.deepEqual({ streamId: header.streamId, sessionId: header.sessionId, sequence: header.sequence, rotation: header.rotation },
    { streamId: 'stream-1', sessionId: 'session-1', sequence: 1, rotation: 90 });
  assert.equal(h.publisher.current.sentFrames, 1);
  h.receive({ type: 'frame-ack', streamId: 'stream-1', sessionId: 'session-1', sequence: 1, accepted: true, stats: { dropped: 4 } });
  assert.equal(h.publisher.current.acknowledgedFrames, 1);
  assert.equal(h.publisher.current.relayDroppedFrames, 4);
  // Receipts for a replaced session or an unknown sequence are ignored.
  h.receive({ type: 'frame-ack', streamId: 'stream-1', sessionId: 'other', sequence: 2, accepted: true });
  assert.equal(h.publisher.current.acknowledgedFrames, 1);
  h.publisher.stop('user');
  assert.equal(h.publisher.current.ready, false);
  assert.equal(h.publisher.current.status, '推流已停止');
});

test('media publisher requires a bound target, tracks rate changes, and reconnects on silence', () => {
  const h = harness();
  h.publisher.start();
  const socket = h.socket();
  socket.onopen?.({});
  h.receive({ type: 'media-ready', sessionId: 'session-1', streamId: 'stream-1' });
  h.receive({ type: 'media-rate', streamId: 'stream-1', sessionId: 'session-1', targetSamplingRate: 4 });
  assert.equal(h.publisher.current.framesPerSecond, 4);
  h.receive({ type: 'media-rate', streamId: 'other', sessionId: 'session-1', targetSamplingRate: 2 });
  assert.equal(h.publisher.current.framesPerSecond, 4);
  h.advance(3000);
  assert.equal(socket.sent.filter(message => typeof message === 'string' && JSON.parse(message).type === 'media-heartbeat').length, 1);
  h.advance(9000);
  assert.equal(h.publisher.current.ready, false);
  assert.match(h.publisher.current.status, /媒体无响应/);
  assert.equal(socket.readyState, 3);
  h.advance(1100);
  assert.equal(h.sockets.length, 2, 'a replacement socket is opened after the retry delay');
});

test('media publisher drops frames older than 2.5 seconds and reconnects when sending stalls', () => {
  const h = harness();
  h.publisher.start();
  const socket = h.socket();
  socket.onopen?.({});
  h.receive({ type: 'media-ready', sessionId: 'session-1', streamId: 'stream-1' });
  const frame = { jpeg: sampleJpeg(), width: 16, height: 16, rotation: 0, capturedAt: 1000 };
  assert.equal(h.publisher.publish({ ...frame, capturedAt: -5000 }), false);
  assert.equal(h.publisher.current.droppedFrames, 1);
  assert.equal(h.publisher.publish(frame), true);
  h.advance(4000);
  assert.match(h.publisher.current.status, /超时|无响应/);
  assert.equal(h.publisher.current.ready, false);
});
