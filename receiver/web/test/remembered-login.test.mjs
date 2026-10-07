import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';
import ts from 'typescript';
const compiled=ts.transpileModule(readFileSync(new URL('../src/rememberedLogin.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
// Control IndexedDB commit timing while exercising the production AES-GCM code with real WebCrypto.
function vault(origin='https://console.example.test', shared=new Map(), blocked=false) {
  const indexedDB={open(){const request={};queueMicrotask(()=>{
    if(blocked){request.onerror?.();return;}
    request.result={createObjectStore(){},close(){},transaction(){
      const tx={objectStore(){return{
        get(key){const req={};queueMicrotask(()=>{req.result=shared.get(key);req.onsuccess?.();});return req;},
        put(value,key){shared.set(key,structuredClone(value));queueMicrotask(()=>tx.oncomplete?.());},
        delete(key){shared.delete(key);queueMicrotask(()=>tx.oncomplete?.());},
      };}};return tx;
    }};request.onupgradeneeded?.();request.onsuccess?.();
  });return request;}};
  const module={exports:{}};
  new vm.Script(compiled).runInContext(vm.createContext({module,exports:module.exports,indexedDB,crypto:webcrypto,TextEncoder,TextDecoder,Uint8Array,location:{origin}}));
  return{...module.exports,shared};
}
const credentials={username:'test-user',password:'private-fixture-密码'};
test('opt-in credentials reload encrypted, stay origin-bound, and are removed including their nonextractable key',async()=>{
  const v=vault();assert.equal(await v.readRememberedLogin(),null);
  await v.saveRememberedLogin(credentials);
  const stored=v.shared.get('login');assert.equal(stored.key.extractable,false);
  await assert.rejects(webcrypto.subtle.exportKey('raw',stored.key));
  assert.ok(!new TextDecoder().decode(stored.ciphertext).includes(credentials.password));
  assert.ok(!JSON.stringify(stored).includes(credentials.username));
  const reloaded=vault('https://console.example.test',v.shared);
  assert.equal((await reloaded.readRememberedLogin()).password,credentials.password);
  assert.equal(await vault('https://other.example.test',v.shared).readRememberedLogin(),null);
  await reloaded.clearRememberedLogin();assert.equal(v.shared.size,0);assert.equal(await v.readRememberedLogin(),null);
});
test('opting out after an in-flight save cannot leave remembered credentials behind',async()=>{
  const v=vault();const saving=v.saveRememberedLogin(credentials),clearing=v.clearRememberedLogin();
  await Promise.all([saving,clearing]);assert.equal(await v.readRememberedLogin(),null);
});
test('tampering fails closed; replacement uses a fresh key and IV',async()=>{
  const v=vault();await v.saveRememberedLogin(credentials);const first=v.shared.get('login');
  new Uint8Array(first.ciphertext)[0]^=1;assert.equal(await v.readRememberedLogin(),null);
  await v.saveRememberedLogin({...credentials,password:'another-fixture-password'});const second=v.shared.get('login');
  assert.notDeepEqual(first.iv,second.iv);assert.notEqual(first.key,second.key);
  assert.equal((await v.readRememberedLogin()).password,'another-fixture-password');
});
test('credential bounds and storage failures are explicit rather than silently saving plaintext',async()=>{
  const v=vault();for(const value of [{...credentials,username:'x'.repeat(65)},{...credentials,password:'x'.repeat(257)},{...credentials,password:''}])assert.throws(()=>v.saveRememberedLogin(value));
  const blocked=vault('https://console.example.test',new Map(),true);
  await assert.rejects(blocked.saveRememberedLogin(credentials),/存储权限/);
  await assert.rejects(blocked.clearRememberedLogin(),/存储权限/);
});
