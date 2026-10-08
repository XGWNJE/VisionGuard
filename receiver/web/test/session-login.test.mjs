import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';
import ts from 'typescript';
import { accountRequest, AccountRequestError, parseLogin } from '../src/account.ts';
const compiled=ts.transpileModule(readFileSync(new URL('../src/sessionLogin.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
// Control IndexedDB commit timing while exercising the production AES-GCM code with real WebCrypto.
function vault(origin='https://console.example.test', shared=new Map(), blocked=false, tab=new Map()) {
  const indexedDB={open(){const request={};queueMicrotask(()=>{
    if(blocked){request.onerror?.();return;}
    request.result={createObjectStore(){},close(){},transaction(){
      const tx={objectStore(){return{
        get(key){const req={};queueMicrotask(()=>{req.result=shared.get(key);req.onsuccess?.();});return req;},
        put(value,key){shared.set(key,structuredClone(value));setTimeout(()=>tx.oncomplete?.(),0);},
        delete(key){shared.delete(key);setTimeout(()=>tx.oncomplete?.(),0);},
        openCursor(){const req={},keys=[...shared.keys()];let index=0;const next=()=>queueMicrotask(()=>{while(index<keys.length&&!shared.has(keys[index]))index++;const key=keys[index++];req.result=key===undefined?null:{value:shared.get(key),delete(){shared.delete(key);},continue:next};req.onsuccess?.();});next();return req;},
      };}};return tx;
    }};request.onupgradeneeded?.();request.onsuccess?.();
  });return request;}};
  const module={exports:{}};
  const sessionStorage={getItem:key=>tab.get(key)??null,setItem:(key,value)=>tab.set(key,value),removeItem:key=>tab.delete(key)};
  new vm.Script(compiled).runInContext(vm.createContext({module,exports:module.exports,indexedDB,sessionStorage,crypto:webcrypto,TextEncoder,TextDecoder,Uint8Array,location:{origin},Date,require:()=>({accountRequest,AccountRequestError,parseLogin})}));
  return{...module.exports,shared,tab,record:()=>shared.get(tab.get('visionguard-console-session'))};
}
const login={token:'private-token-fixture-'.repeat(3),expiresAt:new Date(Date.now()+24*3600_000).toISOString(),channel:'isolated',account:{accountId:'one',username:'test-user',isAdmin:false},device:{deviceId:'console',deviceName:'控制台',role:'console',nodeType:'console',platform:'web',component:'web-console'}};

test('a page reload recovers the same encrypted session without credentials or extending its deadline',async()=>{
  const v=vault();await v.saveSessionLogin(login);const record=v.record();
  assert.equal(record.key.extractable,false);assert.ok(!new TextDecoder().decode(record.ciphertext).includes(login.token));
  await assert.rejects(webcrypto.subtle.exportKey('raw',record.key));
  const reloaded=vault('https://console.example.test',v.shared,false,v.tab);
  assert.deepEqual(JSON.parse(JSON.stringify(await reloaded.readSessionLogin())),login);
  assert.equal(await vault('https://other.example.test',v.shared,false,v.tab).readSessionLogin(),null);
  await reloaded.clearSessionLogin();assert.equal(await v.readSessionLogin(),null);assert.equal(v.shared.size,0);
});
test('expiry and tampering fail closed; a serialized logout cannot leave an in-flight save behind',async()=>{
  const v=vault();await v.saveSessionLogin({...login,expiresAt:new Date(Date.now()-1).toISOString()});
  assert.equal(await v.readSessionLogin(),null);assert.equal(v.shared.size,0);
  await v.saveSessionLogin(login);new Uint8Array(v.record().ciphertext)[0]^=1;
  assert.equal(await v.readSessionLogin(),null);
  await Promise.all([v.saveSessionLogin(login),v.clearSessionLogin()]);assert.equal(v.shared.size,0);
});
test('invalid sessions and storage failures never fall back to plaintext',async()=>{
  const v=vault();assert.throws(()=>v.saveSessionLogin({...login,device:{...login.device,component:'android-notifier'}}));
  const blocked=vault('https://console.example.test',new Map(),true);
  await assert.rejects(blocked.saveSessionLogin(login),/存储权限/);assert.equal(blocked.shared.size,0);
});

test('independent tabs cannot replace or clear another account on reload; expired closed-tab records are pruned',async()=>{
  const first=vault();await first.saveSessionLogin(login);
  const second=vault('https://console.example.test',first.shared);
  assert.equal(await second.readSessionLogin(),null);
  const other={...login,token:'another-private-token-'.repeat(3),account:{...login.account,accountId:'two',username:'second-user'}};
  await second.saveSessionLogin(other);
  assert.equal((await vault('https://console.example.test',first.shared,false,first.tab).readSessionLogin()).account.accountId,'one');
  await first.clearSessionLogin();assert.equal((await second.readSessionLogin()).account.accountId,'two');
  const closed=vault('https://console.example.test',first.shared);await closed.saveSessionLogin({...login,expiresAt:new Date(Date.now()-1).toISOString()});
  await second.saveSessionLogin(other);assert.equal(first.shared.size,1);
});

test('restoration checks server revocation without login or refresh; temporary failures preserve the absolute deadline',async()=>{
  const original=globalThis.fetch,v=vault();await v.saveSessionLogin(login);
  try {
    globalThis.fetch=async(url,options)=>{assert.equal(url,'/api/account/session');assert.equal(options.headers.Authorization,'Bearer '+login.token);return new Response(JSON.stringify({...login,token:undefined}),{status:200});};
    assert.equal((await v.restoreSessionLogin()).token,login.token);
    globalThis.fetch=async()=>{throw new TypeError('network offline');};
    assert.equal((await v.restoreSessionLogin()).expiresAt,login.expiresAt);
    globalThis.fetch=async()=>new Response(JSON.stringify({ok:false,error:'Session revoked'}),{status:401});
    assert.equal(await v.restoreSessionLogin(),null);assert.equal(v.shared.size,0);
  }finally{globalThis.fetch=original;}
});
