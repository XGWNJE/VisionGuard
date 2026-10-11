import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypescriptModule, sampleJpeg } from './helpers.mjs';

const protocol = loadTypescriptModule('..', 'src/protocol.ts');

test('bounded size keeps the 1280×720 and 640×480 upper bounds without upscaling', () => {
  assert.deepEqual(protocol.boundedSize(1920, 1080, 1280, 720), { width: 1280, height: 720 });
  assert.deepEqual(protocol.boundedSize(1920, 1080, 640, 480), { width: 640, height: 360 });
  assert.deepEqual(protocol.boundedSize(320, 240, 1280, 720), { width: 320, height: 240 });
  assert.deepEqual(protocol.boundedSize(640, 480, 640, 480), { width: 640, height: 480 });
});

test('jpeg helpers reject non-JPEG payloads and trim trailing bytes back to EOI', () => {
  const jpeg = sampleJpeg();
  assert.deepEqual(protocol.jpegDimensions(jpeg), { width: 16, height: 16 });
  assert.equal(protocol.trimJpeg(new Uint8Array([1, 2, 3])), undefined);
  const padded = new Uint8Array(jpeg.length + 3);
  padded.set(jpeg, 0); padded.set([0, 0, 0], jpeg.length);
  const trimmed = protocol.trimJpeg(padded);
  assert.equal(trimmed.length, jpeg.length);
  assert.deepEqual(protocol.jpegDimensions(trimmed), { width: 16, height: 16 });
  const truncated = jpeg.subarray(0, jpeg.length - 6);
  assert.equal(protocol.trimJpeg(truncated), undefined);
});

test('camera configuration validation mirrors the Android camera bounds', () => {
  assert.deepEqual(protocol.validateSetConfig('cameraResolution', '720p'), { ok: true, value: '720p' });
  assert.equal(protocol.validateSetConfig('cameraResolution', '1080p').ok, false);
  assert.deepEqual(protocol.validateSetConfig('cameraHidePreview', 'false'), { ok: true, value: 'false' });
  assert.equal(protocol.validateSetConfig('cameraDimScreen', '1').ok, false);
  assert.equal(protocol.validateSetConfig('soundLoopCount', '3').ok, false);
});

test('media and control messages are only accepted when they match the live session', () => {
  assert.deepEqual(protocol.parseMediaReady({ sessionId: 's', streamId: 'f', targetSamplingRate: 3 }),
    { sessionId: 's', streamId: 'f', targetDeviceId: undefined, sourceId: undefined, targetSamplingRate: 3 });
  assert.equal(protocol.parseMediaReady({ sessionId: '', streamId: 'f' }), undefined);
  assert.equal(protocol.parseMediaReady({ sessionId: 's' }), undefined);
  assert.equal(protocol.parseMediaReady({ sessionId: 's', streamId: 'f', targetSamplingRate: 9 }).targetSamplingRate, 5);
  assert.equal(protocol.parseRate({ streamId: 'f', sessionId: 's', targetSamplingRate: 4 }, 's', 'f'), 4);
  assert.equal(protocol.parseRate({ streamId: 'other', sessionId: 's', targetSamplingRate: 4 }, 's', 'f'), undefined);
  assert.deepEqual(protocol.parseFrameAck({ streamId: 'f', sessionId: 's', sequence: 2, accepted: false, dropReason: 'stale', stats: { dropped: 7 } }, 's', 'f'),
    { sequence: 2, accepted: false, dropReason: 'stale', relayDropped: 7 });
  assert.equal(protocol.parseFrameAck({ streamId: 'f', sessionId: 'old', sequence: 2 }, 's', 'f'), undefined);
  assert.equal(protocol.parseFrameAck({ streamId: 'f', sessionId: 's', sequence: -1 }, 's', 'f'), undefined);
});

test('remote settings and login responses must be complete and match the web-camera identity', () => {
  const settings = { cameraResolution: '480p', cameraDimScreen: false, cameraHidePreview: true };
  assert.deepEqual(protocol.parseRemoteSettings(settings), settings);
  assert.equal(protocol.parseRemoteSettings({ ...settings, cameraDimScreen: 'false' }), undefined);
  assert.equal(protocol.parseRemoteSettings({ cameraResolution: '1080p' }), undefined);
  const login = { token: 't'.repeat(40), expiresAt: new Date(Date.now() + 1000).toISOString(), channel: 'vnext',
    account: { accountId: 'a', username: 'u' }, device: { deviceId: 'd', deviceName: '相机推流节点 · 浏览器', component: 'web-camera', platform: 'web', role: 'detector', nodeType: 'visual' } };
  assert.equal(protocol.parseLogin(login).device.component, 'web-camera');
  assert.equal(protocol.parseLogin({ ...login, device: { ...login.device, component: 'android-camera' } }), undefined);
  assert.equal(protocol.parseLogin({ ...login, device: { ...login.device, platform: 'android' } }), undefined);
  assert.equal(protocol.parseLogin({ ...login, token: 'short' }), undefined);
  assert.deepEqual(protocol.parseStreams([{ streamId: 's1', publisherDeviceId: 'd', targetDeviceId: 'x', sourceId: 'y', sourceName: 'cam', isStreaming: true },
    { streamId: 's2', publisherDeviceId: 'other', isStreaming: true }], 'd').map(stream => stream.streamId), ['s1']);
});

test('camera pages refuse plain HTTP except on the local host', () => {
  assert.equal(protocol.cameraContextIssue({ protocol: 'https:', hostname: 'visionguard.xgwnje.cn' }), undefined);
  assert.equal(protocol.cameraContextIssue({ protocol: 'http:', hostname: 'localhost' }), undefined);
  assert.match(protocol.cameraContextIssue({ protocol: 'http:', hostname: '192.168.1.20' }), /HTTPS/);
  assert.equal(protocol.websocketURL('https://visionguard.xgwnje.cn/console/', '/media/ws'), 'wss://visionguard.xgwnje.cn/media/ws');
  assert.equal(protocol.websocketURL('http://127.0.0.1:4318/', '/ws'), 'ws://127.0.0.1:4318/ws');
  assert.throws(() => protocol.websocketURL('https://visionguard.xgwnje.cn/console/', '/ws').replace('wss', 'ws') && protocol.websocketURL('http://example.com/', '/ws'));
});
