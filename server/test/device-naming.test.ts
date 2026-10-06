import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { AccountStore } from '../src/services/AccountStore';
import { validateAlertMeta } from '../src/utils/security';

test('custom names and event snapshots share the UTF-16 boundary and reject invalid edits without changing identity', async t => {
  const {store,credentials}=fixture(t);
  const session=await store.login({...credentials,component:'windows-inference',deviceCode:'门'.repeat(40)});
  assert.ok(session.device.deviceName.length<=64);
  const meta={deviceId:session.device.deviceId,deviceName:'门'.repeat(64),sourceName:'😀'.repeat(32),sourceId:'front',timestamp:new Date().toISOString(),detections:[{label:'person',confidence:0.9,bbox:{x:0,y:0,w:1,h:1}}]};
  assert.equal(validateAlertMeta(meta).ok,true);
  for(const name of ['门'.repeat(64),'A'.repeat(64),'😀'.repeat(32)]) {store.rename(session.account.accountId,session.device.deviceId,name);assert.equal(store.device(session.account.accountId,session.device.deviceId)!.deviceName,name);}
  for(const name of ['门'.repeat(65),'😀'.repeat(33),'  ','门\n','a\0b']) {
    assert.throws(()=>store.rename(session.account.accountId,session.device.deviceId,name),/Invalid device name/);
    assert.equal(store.device(session.account.accountId,session.device.deviceId)!.deviceName,'😀'.repeat(32));
    assert.equal(validateAlertMeta({...meta,deviceName:name}).ok,false);
    assert.equal(validateAlertMeta({...meta,sourceName:name}).ok,false);
  }
});

function fixture(t: test.TestContext) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-naming-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'accounts.json');
  const store = new AccountStore(file);
  store.createAccount('naming-owner', 'private-naming-password');
  const credentials = { username: 'naming-owner', password: 'private-naming-password' };
  return { file, store, credentials };
}

test('concurrent registration allocates one sequence per account across components', async t => {
  const { store, credentials } = fixture(t);
  const sessions = await Promise.all(['android-camera', 'android-notifier', 'windows-inference', 'web-console'].map(component =>
    store.login({ ...credentials, component, deviceCode: 'dada', deviceName: 'ignored login name' })));
  assert.deepEqual(sessions.map(s => Number(s.device.deviceName.split('-').slice(-1)[0])).sort(), [1, 2, 3, 4]);
  for (const session of sessions) assert.match(session.device.deviceName, /^dada-(相机|通知|视觉|控制)-00[1-4]$/);
  const windows = sessions.find(s => s.device.component === 'windows-inference')!;
  assert.equal(windows.resident!.device.deviceName, windows.device.deviceName);
  store.createAccount('second-owner', 'private-naming-password');
  assert.equal((await store.login({ ...credentials, username: 'second-owner', component: 'android-camera', deviceCode: 'yili' })).device.deviceName, 'yili-相机-001');
});

test('rename and relogin preserve identity; unbind and restart never reuse a sequence', async t => {
  const { store, file, credentials } = fixture(t);
  const first = await store.login({ ...credentials, component: 'android-camera', deviceCode: 'dada' });
  store.rename(first.account.accountId, first.device.deviceId, '门口相机');
  const again = await store.login({ ...credentials, component: 'android-camera', deviceId: first.device.deviceId, deviceCode: 'different', deviceName: 'stale name' });
  assert.equal(again.device.deviceName, '门口相机');
  assert.equal(again.device.deviceId, first.device.deviceId);
  store.unbind(first.account.accountId, first.device.deviceId);
  const restored = new AccountStore(file);
  const replacement = await restored.login({ ...credentials, component: 'android-camera', deviceCode: 'dada' });
  assert.equal(replacement.device.deviceName, 'dada-相机-002');
});

test('invalid device codes are rejected without consuming a number', async t => {
  const { store, credentials } = fixture(t);
  for (const deviceCode of [' ', 'a\nb', 'a'.repeat(41), 123]) await assert.rejects(store.login({ ...credentials, component: 'android-notifier', deviceCode }), /Invalid device code/);
  assert.equal((await store.login({ ...credentials, component: 'android-notifier', deviceCode: 'yili' })).device.deviceName, 'yili-通知-001');
});
