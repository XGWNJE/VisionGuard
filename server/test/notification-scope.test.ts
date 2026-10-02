import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { NotificationScopeStore, parseNotificationScope, scopeAccepts } from '../src/services/NotificationScopeStore';

test('source selection receives its node outage but excludes other sources and sensors', () => {
  const scope = parseNotificationScope({ mode: 'selected', targets: [{ deviceId: 'visual', sourceId: 'front' }] })!;
  assert.equal(scopeAccepts(scope, { deviceId: 'visual', sourceId: 'front', eventKind: 'visual-detection' }), true);
  assert.equal(scopeAccepts(scope, { deviceId: 'visual', eventKind: 'connection-lost' }), true);
  assert.equal(scopeAccepts(scope, { deviceId: 'visual', sourceId: 'side', eventKind: 'detection-interrupted' }), false);
  assert.equal(scopeAccepts(scope, { deviceId: 'sensor', eventKind: 'sensor-detection' }), false);
  assert.equal(scopeAccepts({ mode: 'selected', targets: [] }, { deviceId: 'visual' }), false);
  for (const value of [null, [], { mode: 'all', targets: [{}] }, { mode: 'selected', targets: [{ deviceId: '../node' }] },
    { mode: 'selected', targets: [{ deviceId: 'visual', sourceId: '' }] }]) assert.equal(parseNotificationScope(value), undefined);
});

test('scope survives restart and rejected disk write leaves its previous policy effective', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-scope-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'scopes.json');
  const store = new NotificationScopeStore(file);
  assert.equal(store.get('notifier').mode, 'all');
  store.set('notifier', { mode: 'selected', targets: [{ deviceId: 'sensor' }] });
  assert.deepEqual(new NotificationScopeStore(file).get('notifier'), store.get('notifier'));
  const rename = fs.renameSync;
  try {
    fs.renameSync = (() => { throw new Error('Storage unavailable'); }) as typeof fs.renameSync;
    assert.throws(() => store.set('notifier', { mode: 'all', targets: [] }));
  } finally { fs.renameSync = rename; }
  assert.equal(store.get('notifier').mode, 'selected');
  assert.equal(new NotificationScopeStore(file).get('notifier').mode, 'selected');
  assert.equal(fs.existsSync(`${file}.tmp`), false);
});
