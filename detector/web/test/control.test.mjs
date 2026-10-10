import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypescriptModule } from './helpers.mjs';

const controlModule = loadTypescriptModule('..', 'src/control.ts');
const deviceModule = loadTypescriptModule('..', 'src/device.ts');

const SESSION = { token: 't'.repeat(40), expiresAt: new Date(Date.now() + 3600_000).toISOString(), channel: 'vnext',
  account: { accountId: 'account', username: 'owner' },
  device: { deviceId: 'device-1', deviceName: '相机推流节点 · 浏览器', component: 'web-camera', platform: 'web', role: 'detector', nodeType: 'visual' } };

function harness() {
  let now = 1_000_000;
  const timers = new Map();
  const sockets = [];
  let nextHandle = 1;
  const saved = [];
  const entries = new Map();
  const settings = new deviceModule.CameraSettingsStore({ read: key => entries.get(key) ?? null, write: (key, value) => entries.set(key, value) });
  const ports = {
    origin: 'https://camera.example', openSocket: () => { const socket = createSocket(); socket.readyState = 1; return socket; },
    now: () => now,
    setTimer: callback => { const handle = nextHandle++; timers.set(handle, callback); return handle; },
    clearTimer: handle => timers.delete(handle),
    fetchJson: async (requestPath, init) => {
      if (requestPath === '/api/devices') return { ok: true, devices: [
        { deviceId: 'inference-1', deviceName: '视觉推理节点', component: 'windows-inference', platform: 'windows' },
        { deviceId: 'camera-2', deviceName: '另一台相机', component: 'web-camera', platform: 'web' }] };
      if (requestPath === '/api/streams') return { ok: true, streams: [
        { streamId: 'stream-1', publisherDeviceId: 'device-1', targetDeviceId: 'inference-1', sourceId: 'source-1', sourceName: '相机推流节点 · 浏览器', isStreaming: false },
        { streamId: 'stream-2', publisherDeviceId: 'other', isStreaming: true }] };
      if (requestPath === '/api/streams/bind') { saved.push(JSON.parse(init.body)); return { ok: true }; }
      throw new Error('连接失败，请检查网络后重试');
    },
    onStreamCommand: (command, done) => { ports.lastCommand = command; ports.complete = done; },
    saveConfig: (key, value) => settings.save(key, value),
    cameraState: () => ({ cameraApp: 'foreground', cameraPermission: 'granted' }),
    onState: () => {}, onUnauthorized: () => { ports.unauthorized = true; },
    lastCommand: undefined, complete: undefined, unauthorized: false,
  };
  function createSocket() {
    const socket = { readyState: 0, sent: [], onopen: null, onmessage: null, onclose: null, onerror: null,
      send(data) { socket.sent.push(data); }, close() { socket.readyState = 3; } };
    sockets.push(socket);
    return socket;
  }
  const client = new controlModule.ControlClient(SESSION, settings, ports, () => {});
  return { client, ports, sockets, settings, saved,
    socket: () => sockets[sockets.length - 1],
    receive(value) { sockets[sockets.length - 1].onmessage?.({ data: JSON.stringify(value) }); },
    advance(steps) { for (let index = 0; index < steps; index++) { now += 1000; for (const callback of [...timers.values()]) callback(); } } };
}

async function authenticated(h) {
  h.client.start();
  const socket = h.socket();
  socket.onopen?.({});
  h.receive({ type: 'auth-result', success: true });
  await new Promise(resolve => setTimeout(resolve, 0));
  return socket;
}

test('control client authenticates, then reports its own stream and the inference targets', async () => {
  const h = harness();
  const socket = await authenticated(h);
  assert.deepEqual(JSON.parse(socket.sent[0]), { type: 'auth', token: SESSION.token });
  assert.equal(h.client.state.connected, true);
  assert.equal(h.client.state.stream.streamId, 'stream-1');
  assert.equal(h.client.state.stream.targetDeviceId, 'inference-1');
  assert.deepEqual(h.client.state.targets.map(target => target.deviceId), ['inference-1']);
  assert.equal(h.client.state.targetsLoading, false);
});

test('control client heartbeats its capabilities and starts streaming only after a bound target', async () => {
  const h = harness();
  const socket = await authenticated(h);
  h.advance(1);
  const heartbeat = JSON.parse(socket.sent[socket.sent.length - 1]);
  assert.equal(heartbeat.type, 'heartbeat');
  assert.deepEqual(heartbeat.capabilities, ['video-publish', 'stream-control', 'camera-config', 'request-correlation']);
  assert.deepEqual(heartbeat.components, { cameraApp: 'foreground', cameraPermission: 'granted' });
  assert.deepEqual(heartbeat.remoteSettings, { cameraResolution: '480p', cameraDimScreen: false, cameraHidePreview: false });
  const refusal = await h.client.bind('inference-1');
  assert.equal(refusal, undefined);
  assert.deepEqual(h.saved, [{ publisherDeviceId: 'device-1', targetDeviceId: 'inference-1' }]);
});

test('control client routes remote stream control and answers with the real result', async () => {
  const h = harness();
  const socket = await authenticated(h);
  h.receive({ type: 'command', requestId: 'request-1', command: 'start-stream' });
  assert.equal(h.ports.lastCommand, 'start-stream');
  h.ports.complete(true, '摄像头已启动');
  assert.deepEqual(JSON.parse(socket.sent[socket.sent.length - 1]),
    { type: 'command-ack', requestId: 'request-1', targetDeviceId: 'device-1', command: 'start-stream', phase: 'completed', success: true, reason: '摄像头已启动' });
  h.receive({ type: 'command', requestId: 'request-2', command: 'pause' });
  assert.equal(h.ports.lastCommand, 'start-stream', 'unsupported commands are ignored');
});

test('control client persists remote camera configuration and reports validation failures', async () => {
  const h = harness();
  const socket = await authenticated(h);
  h.receive({ type: 'set-config', requestId: 'config-1', key: 'cameraResolution', value: '720p' });
  assert.equal(h.settings.current.cameraResolution, '720p');
  assert.deepEqual(JSON.parse(socket.sent[socket.sent.length - 1]),
    { type: 'command-ack', requestId: 'config-1', targetDeviceId: 'device-1', command: 'set-config:cameraResolution', phase: 'completed', success: true, reason: '配置已保存' });
  h.receive({ type: 'set-config', requestId: 'config-2', key: 'cameraResolution', value: '1080p' });
  const rejected = JSON.parse(socket.sent[socket.sent.length - 1]);
  assert.equal(rejected.success, false);
  assert.equal(h.settings.current.cameraResolution, '720p');
  h.receive({ type: 'set-config', requestId: 'config-3', key: 'cameraHidePreview', value: 'true' });
  assert.equal(h.settings.current.cameraHidePreview, true);
});

test('control client reconnects after a dropped socket and signs out on a revoked session', async () => {
  const h = harness();
  const socket = await authenticated(h);
  h.socket().onclose?.({});
  assert.equal(h.client.state.connected, false);
  assert.match(h.client.state.status, /重连/);
  h.advance(1);
  assert.equal(h.sockets.length, 2);
  h.socket().onopen?.({});
  h.receive({ type: 'auth-result', success: false });
  assert.equal(h.ports.unauthorized, true);
  h.receive({ type: 'kicked' });
  assert.equal(h.ports.unauthorized, true);
  assert.equal(socket.readyState, 3);
});

test('control client ignores malformed frames and keeps the last known state', async () => {
  const h = harness();
  const socket = await authenticated(h);
  const known = h.client.state.stream.streamId;
  socket.onmessage?.({ data: 'not json' });
  socket.onmessage?.({ data: JSON.stringify({ type: 'stream-list', streams: 'invalid' }) });
  assert.equal(h.client.state.stream.streamId, known);
  socket.onmessage?.({ data: JSON.stringify({ type: 'auth-result', success: true, extra: 'x'.repeat(2_000_000) }) });
  assert.equal(h.client.state.connected, true);
});
