import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequestId, formatTime, parseTimeStandard, websocketURL, mergeDevices, mergeAlerts } from '../src/protocol.ts';

test('credentials only travel over same-origin WSS except local development', () => {
  assert.equal(websocketURL('https://relay.example/console/'), 'wss://relay.example/ws');
  assert.equal(websocketURL('http://127.0.0.1:4318/console/'), 'ws://127.0.0.1:4318/ws');
  assert.equal(websocketURL('http://[::1]:4318/console/'), 'ws://[::1]:4318/ws');
  for (const origin of ['http://relay.example', 'http://192.168.1.2', 'file:///console', 'http://localhost.example']) {
    assert.throws(() => websocketURL(origin), /HTTPS/);
  }
});

test('explicit test login allows private LAN HTTP while public HTTP remains blocked', () => {
  for (const ip of ['10.0.0.1', '172.16.0.1', '172.31.255.254', '192.168.31.58']) {
    assert.throws(() => websocketURL(`http://${ip}:4318`), /HTTPS/);
    assert.equal(websocketURL(`http://${ip}:4318`, true), `ws://${ip}:4318/ws`);
  }
  for (const host of ['172.15.0.1', '172.32.0.1', '192.169.0.1', '8.8.8.8', 'relay.example', 'localhost.example']) {
    assert.throws(() => websocketURL(`http://${host}`, true), /HTTPS/);
  }
});

test('control correlation works without the secure-context randomUUID API', () => {
  const original = globalThis.crypto;
  const getRandomValues = original.getRandomValues.bind(original);
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues } });
  try {
    const ids = Array.from({ length: 100 }, createRequestId);
    assert.equal(new Set(ids).size, ids.length);
    assert.ok(ids.every(id => /^[a-f0-9]{32}$/.test(id)));
  } finally { Object.defineProperty(globalThis, 'crypto', { configurable: true, value: original }); }
});

test('alarm display uses the service standard across dates and equivalent ISO offsets', () => {
  const original = process.env.TZ;
  process.env.TZ = 'America/Los_Angeles';
  try {
    assert.equal(formatTime('2026-10-01T18:03:04Z', 'Asia/Shanghai'), '2026-10-02 02:03:04');
    assert.equal(formatTime('2026-10-02T02:03:04+08:00', 'UTC'), '2026-10-01 18:03:04');
    assert.equal(formatTime('2026-10-01T16:00:00Z', 'Asia/Shanghai'), '2026-10-02 00:00:00');
    assert.equal(formatTime('bad-time', 'Asia/Shanghai'), '时间未知');
  } finally { if (original === undefined) delete process.env.TZ; else process.env.TZ = original; }
});

test('malformed time standards cannot silently switch the clock display', () => {
  assert.equal(parseTimeStandard({ timeZone: 'UTC', serverTime: 'bad-time' }), null);
  assert.equal(parseTimeStandard({ timeZone: 'bad-zone', serverTime: new Date().toISOString() }), null);
  assert.equal(parseTimeStandard({ timeZone: 'Asia/Shanghai', serverTime: '2026-10-02T00:00:00Z' })?.timeZone, 'Asia/Shanghai');
});

test('disconnected registered nodes remain visible without stale control capabilities', () => {
  const identity = { deviceId: 'visual-1', role: 'detector', nodeType: 'visual', platform: 'windows' };
  const live = { ...identity, deviceName: '门口', online: true, isMonitoring: true, isReady: true, capabilities: ['pause'], sources: [] };
  assert.deepEqual(mergeDevices([live], [identity]), [live]);
  const [offline] = mergeDevices([], [identity]);
  assert.equal(offline.online, false);
  assert.equal(offline.isMonitoring, false);
  assert.deepEqual(offline.capabilities, []);
});

test('late screenshot metadata updates an open event without dropping newer live events', () => {
  const original = { alertId: 'event-1', timestamp: '2026-10-02T00:00:00Z', summary: '检测事件' };
  const newer = { alertId: 'event-2', timestamp: '2026-10-02T00:00:01Z', summary: '新事件' };
  const attached = { ...original, hasScreenshot: true, screenshotUrl: '/screenshots/event-1.png' };
  const result = mergeAlerts([original, newer], [attached]);
  assert.equal(result[0].alertId, 'event-2');
  assert.equal(result[1].screenshotUrl, attached.screenshotUrl);
  assert.equal(mergeAlerts(result, [original])[1].hasScreenshot, true);
});
