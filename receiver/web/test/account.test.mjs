import assert from 'node:assert/strict';
import test from 'node:test';
import { parseLogin, accountRequest, rotateLogin } from '../src/account.ts';

const session = { token:'a'.repeat(32), expiresAt:'2026-12-01T00:00:00Z', channel:'isolated', account:{accountId:'one',username:'test'}, device:{deviceId:'console',deviceName:'Web 控制台',role:'console',nodeType:'console',platform:'web',component:'web-console'} };

test('console sessions cannot accept a camera or lifecycle credential as a console identity',()=>{
  assert.deepEqual(parseLogin(session),session);
  for(const device of [{...session.device,role:'detector'},{...session.device,component:'android-camera'},{...session.device,platform:'android'}]) assert.throws(()=>parseLogin({...session,device}),/登录响应/);
  assert.throws(()=>parseLogin({...session,expiresAt:'invalid'}),/登录响应/);
  assert.throws(()=>parseLogin({...session,account:undefined}),/登录响应/);
});

test('account HTTP sends credentials in the body and session token only as bearer',async()=>{
  const original=globalThis.fetch; const calls=[];
  globalThis.fetch=async(path,init)=>{calls.push({path,init});return new Response(JSON.stringify({ok:true}),{status:200,headers:{'Content-Type':'application/json'}});};
  try{
    await accountRequest('/api/account/login',undefined,{username:'test',password:'temporary'});
    await accountRequest('/api/devices/device',session.token,{deviceName:'门口'},'PATCH');
    assert.equal(calls[0].path.includes('temporary'),false);
    assert.equal(calls[0].init.headers.Authorization,undefined);
    assert.equal(calls[1].init.headers.Authorization,`Bearer ${session.token}`);
    assert.equal(calls[1].init.headers['X-API-Key'],undefined);
    assert.equal(calls[1].init.method,'PATCH');
  }finally{globalThis.fetch=original;}
});

test('revoked account credentials require login and unreadable responses fail visibly',async()=>{
  const original=globalThis.fetch;
  try{
    globalThis.fetch=async()=>new Response(JSON.stringify({ok:false,error:'unauthorized'}),{status:401});
    await assert.rejects(accountRequest('/api/account/session',session.token),/重新登录/);
    globalThis.fetch=async()=>new Response('<html>bad gateway</html>',{status:502});
    await assert.rejects(accountRequest('/api/account/session',session.token),/服务暂不可用/);
  }finally{globalThis.fetch=original;}
});

test('session rotation suspends the old transport before the server revokes its token, then adopts the new token',async()=>{
  const original=globalThis.fetch; const replacement={...session,token:'b'.repeat(32)};
  const valid=new Set([session.token]); let suspended=false, adopted=null;
  globalThis.fetch=async(path,init)=>{
    assert.equal(path,'/api/account/refresh'); assert.equal(suspended,true);
    assert.equal(valid.delete(init.headers.Authorization.slice(7)),true); valid.add(replacement.token);
    return new Response(JSON.stringify({...replacement,ok:true}),{status:200});
  };
  try{
    const result=await rotateLogin(session,next=>{adopted=next;return true;},()=>{suspended=true;});
    assert.deepEqual(result,{...replacement,ok:true}); assert.deepEqual(adopted,result);
    assert.equal(valid.has(session.token),false); assert.equal(valid.has(adopted.token),true);
  }finally{globalThis.fetch=original;}
});

test('logout or account switching during rotation revokes the late replacement without adopting it',async()=>{
  const original=globalThis.fetch; const replacement={...session,token:'c'.repeat(32)};
  const valid=new Set([session.token]); let resolveResponse, current=session;
  const pendingResponse=new Promise(resolve=>{resolveResponse=resolve;}); const calls=[];
  globalThis.fetch=async(path,init)=>{
    const token=init.headers.Authorization.slice(7); calls.push({path,token});
    if(path==='/api/account/refresh'){assert.equal(valid.delete(token),true);valid.add(replacement.token);return pendingResponse;}
    assert.equal(path,'/api/account/logout'); assert.equal(valid.delete(token),true);
    return new Response(JSON.stringify({ok:true}),{status:200});
  };
  try{
    const rotating=rotateLogin(session,next=>{if(current?.token!==session.token)return false;current=next;return true;},()=>{});
    current={...session,token:'d'.repeat(32),account:{accountId:'other',username:'other'}};
    resolveResponse(new Response(JSON.stringify({...replacement,ok:true}),{status:200}));
    assert.equal(await rotating,null); assert.equal(current.account.accountId,'other');
    assert.equal(valid.size,0); assert.deepEqual(calls.map(call=>call.token),[session.token,replacement.token]);
  }finally{globalThis.fetch=original;}
});
