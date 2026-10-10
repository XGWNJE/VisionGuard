import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypescriptModule } from './helpers.mjs';

const device = loadTypescriptModule('..', 'src/device.ts');
const cameraSource = loadTypescriptModule('..', 'src/cameraSource.ts');

test('browser device identity is reused when valid and created once when missing', async () => {
  let stored;
  const port = { read: async () => stored, write: async value => { stored = value; } };
  const identity = await device.readOrCreateDeviceIdentity(port);
  assert.match(identity, /^[a-f0-9]{64}$/);
  assert.equal(await device.readOrCreateDeviceIdentity(port), identity);
  const replaced = await device.readOrCreateDeviceIdentity({ read: async () => 'not-an-identity', write: async value => { stored = value; } });
  assert.match(replaced, /^[a-f0-9]{64}$/);
});

test('device model keeps a bounded printable label', () => {
  assert.equal(device.browserDeviceModel('Android 14'), 'Android 14');
  assert.equal(device.browserDeviceModel(undefined), '浏览器');
  assert.equal(device.browserDeviceModel(''), '浏览器');
  assert.equal(device.browserDeviceModel('a'.repeat(60)).length, 48);
  assert.equal(device.browserDeviceModel('broken\u0000model'), 'broken model');
  assert.equal(device.browserDeviceModel('x'.repeat(47) + '😀'), 'x'.repeat(47), 'a dangling high surrogate is removed');
});

test('display names follow the shared 1–64 character contract', () => {
  assert.equal(device.normalizeDisplayName('  巡检相机  '), '巡检相机');
  assert.throws(() => device.normalizeDisplayName('   '));
  assert.throws(() => device.normalizeDisplayName('line\nbreak'));
  assert.throws(() => device.normalizeDisplayName('x'.repeat(65)));
});

test('camera settings persist locally and reject out-of-contract values', () => {
  const entries = new Map();
  const store = new device.CameraSettingsStore({ read: key => entries.get(key) ?? null, write: (key, value) => entries.set(key, value) });
  assert.deepEqual(store.load(), { cameraResolution: '480p', cameraDimScreen: false, cameraHidePreview: false });
  assert.equal(store.save('cameraResolution', '720p'), undefined);
  assert.equal(store.save('cameraHidePreview', 'true'), undefined);
  assert.deepEqual(store.current, { cameraResolution: '720p', cameraDimScreen: false, cameraHidePreview: true });
  assert.match(store.save('cameraResolution', '1080p'), /480p|720p/);
  assert.equal(store.current.cameraResolution, '720p');
  const restored = new device.CameraSettingsStore({ read: key => entries.get(key) ?? null, write: () => {} });
  assert.deepEqual(restored.load(), { cameraResolution: '720p', cameraDimScreen: false, cameraHidePreview: true });
  const corrupt = new device.CameraSettingsStore({ read: () => '{not json', write: () => {} });
  assert.deepEqual(corrupt.load(), { cameraResolution: '480p', cameraDimScreen: false, cameraHidePreview: false });
  const unavailable = new device.CameraSettingsStore({ read: () => null, write: () => { throw new Error('quota'); } });
  assert.match(unavailable.save('cameraResolution', '720p'), /存储不可用/);
});

test('camera capture targets follow the resolution ceiling and screen orientation', () => {
  assert.deepEqual(cameraSource.targetSize(1920, 1080, '720p'), { width: 1280, height: 720 });
  assert.deepEqual(cameraSource.targetSize(1920, 1080, '480p'), { width: 640, height: 360 });
  assert.deepEqual(cameraSource.targetSize(640, 480, '720p'), { width: 640, height: 480 });
  assert.equal(cameraSource.rotationFromOrientation(90), 90);
  assert.equal(cameraSource.rotationFromOrientation(270), 270);
  assert.equal(cameraSource.rotationFromOrientation(45), 0);
  assert.equal(cameraSource.rotationFromOrientation(-90), 270);
  assert.deepEqual(cameraSource.preferredConstraints(undefined, '720p'), { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } });
  assert.deepEqual(cameraSource.preferredConstraints('camera-2', '480p'), { deviceId: { exact: 'camera-2' }, width: { ideal: 640 }, height: { ideal: 480 } });
});
