import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { AccountStore } from '../src/services/AccountStore';
import { validateAlertMeta } from '../src/utils/security';

test('custom names and event snapshots share the UTF-16 boundary and reject invalid edits without changing identity', async t => {
  const {store,credentials}=fixture(t);
  const session=await store.login({...credentials,component:'windows-inference',deviceModel:'门'.repeat(48)});
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
  const credentials = { username: 'naming-owner', password: 'private-naming-password', deviceIdentity: 'a'.repeat(64), deviceModel: '同型号设备' };
  return { file, store, credentials };
}

test('concurrent identical registration shares one node; models and components are not identity', async t => {
  const { store, credentials } = fixture(t);
  const sessions = await Promise.all(Array.from({length:8}, () => store.login({ ...credentials, component:'android-notifier' })));
  assert.equal(new Set(sessions.map(s=>s.device.deviceId)).size,1);
  assert.equal(store.devices(sessions[0].account.accountId).length,1);
  assert.equal(sessions[0].device.deviceName,'通知节点 · 同型号设备');
  assert.equal(sessions.filter(s=>store.authenticate(s.token)).length,1);
  const camera=await store.login({...credentials,component:'android-camera'});
  const another=await store.login({...credentials,component:'android-notifier',deviceIdentity:'b'.repeat(64)});
  assert.notEqual(camera.device.deviceId,sessions[0].device.deviceId);
  assert.notEqual(another.device.deviceId,sessions[0].device.deviceId);
  assert.equal(another.device.deviceName,sessions[0].device.deviceName);
  const windows=await store.login({...credentials,component:'windows-inference'});
  assert.equal(windows.resident!.device.deviceName, windows.device.deviceName);
  assert.equal(windows.resident!.device.deviceId,windows.device.deviceId);
  assert.equal('identityKey' in windows.device,false);
  assert.equal('identityKey' in windows.resident!.device,false);
  store.createAccount('second-owner', 'private-naming-password');
  const otherAccount=await store.login({...credentials,username:'second-owner',component:'android-camera'});
  assert.notEqual(otherAccount.device.deviceId,camera.device.deviceId);
});

test('lost local ID, model change, service restart and unbind/rebind never duplicate a stable node', async t => {
  const { store, file, credentials } = fixture(t);
  const first = await store.login({ ...credentials, component: 'android-camera' });
  store.rename(first.account.accountId, first.device.deviceId, '门口相机');
  const again = await new AccountStore(file).login({ ...credentials, component: 'android-camera', deviceModel:'新版系统型号', deviceName: 'stale name' });
  assert.equal(again.device.deviceName, '门口相机');
  assert.equal(again.device.deviceId, first.device.deviceId);
  const restored = new AccountStore(file);
  restored.unbind(first.account.accountId,first.device.deviceId);
  assert.equal(restored.authenticate(again.token),undefined);
  const replacement = await restored.login({ ...credentials, component: 'android-camera' });
  assert.equal(restored.devices(first.account.accountId).length,1);
  assert.equal(replacement.device.deviceName,'相机推流节点 · 同型号设备');
  assert.equal('identityKey' in restored.devices(first.account.accountId)[0],false);
});

test('invalid stable identities and model boundaries reject registration without creating nodes', async t => {
  const { store, credentials } = fixture(t);
  for(const deviceIdentity of [undefined,'','a'.repeat(63),'A'.repeat(64),123]) await assert.rejects(store.login({...credentials,component:'android-notifier',deviceIdentity}),/Invalid stable device identity/);
  for (const deviceModel of [' ', 'a\nb', 'a'.repeat(49), 123]) await assert.rejects(store.login({ ...credentials, component: 'android-notifier', deviceModel }), /Invalid device model/);
  const accepted=await store.login({...credentials,component:'android-notifier',deviceModel:'😀'.repeat(24)});
  assert.equal(accepted.device.deviceName,'通知节点 · '+'😀'.repeat(24));
  assert.equal(store.devices(accepted.account.accountId).length,1);
  await assert.rejects(store.login({...credentials,component:'android-notifier',deviceId:accepted.device.deviceId,deviceIdentity:'b'.repeat(64)}),/identity mismatch/);
});

test('known historical registration is claimed explicitly; unidentified duplicate rows are not guessed or deleted',async t=>{
  const {store,file,credentials}=fixture(t);
  const original=await store.login({...credentials,component:'windows-inference'});
  store.rename(original.account.accountId,original.device.deviceId,'保留配置的视觉节点');
  const data=JSON.parse(fs.readFileSync(file,'utf8'));
  for(const device of data.devices) delete device.identityKey;
  data.devices.push({...data.devices[0],deviceId:'unidentified-historical-duplicate'});
  fs.writeFileSync(file,JSON.stringify(data));
  const restored=new AccountStore(file);
  const claimed=await restored.login({...credentials,component:'windows-inference',deviceId:original.device.deviceId});
  assert.equal(claimed.device.deviceId,original.device.deviceId);
  assert.equal(claimed.device.deviceName,'保留配置的视觉节点');
  assert.equal(restored.devices(original.account.accountId).length,2);
  const missingLocalId=await restored.login({...credentials,component:'windows-inference'});
  assert.equal(missingLocalId.device.deviceId,original.device.deviceId);
});
