import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';
import {
  getSafeScreenshotPath,
  validateAlertMeta,
  validateImageMagic,
} from '../src/utils/security';

test('getSafeScreenshotPath rejects traversal alert ids', () => {
  const baseDir = path.resolve('data', 'screenshots');

  assert.equal(getSafeScreenshotPath(baseDir, '../outside', 'image/jpeg'), null);
  assert.equal(getSafeScreenshotPath(baseDir, '..\\outside', 'image/jpeg'), null);
});

test('getSafeScreenshotPath accepts uuid-like ids inside screenshot directory', () => {
  const baseDir = path.resolve('data', 'screenshots');
  const result = getSafeScreenshotPath(baseDir, '550e8400-e29b-41d4-a716-446655440000', 'image/jpeg');

  assert.ok(result);
  assert.equal(result!.filename, '550e8400-e29b-41d4-a716-446655440000.jpg');
  assert.equal(path.dirname(result!.filePath), baseDir);
});

test('validateImageMagic only accepts png and jpeg payloads', () => {
  assert.equal(validateImageMagic(Buffer.from([0xff, 0xd8, 0xff, 0x00])), 'image/jpeg');
  assert.equal(validateImageMagic(Buffer.from([0x89, 0x50, 0x4e, 0x47])), 'image/png');
  assert.equal(validateImageMagic(Buffer.from('not an image')), null);
});

test('validateAlertMeta rejects malformed alert payloads', () => {
  assert.equal(validateAlertMeta({ deviceId: 'dev', deviceName: 'name', timestamp: 'now', detections: [] }).ok, false);
  assert.equal(validateAlertMeta({ deviceId: 'dev', deviceName: 'name', timestamp: 'now', detections: [{ label: 'person', confidence: 1.4, bbox: { x: 0, y: 0, w: 1, h: 1 } }] }).ok, false);
  assert.equal(validateAlertMeta({ deviceId: 'dev', deviceName: 'name', timestamp: 'now', detections: [{ label: 'person', confidence: 0.9, bbox: { x: 0, y: 0, w: 1, h: 1 } }] }).ok, true);
});
