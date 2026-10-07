import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';
import test from 'node:test';
import ts from 'typescript';
import * as protocol from '../src/protocol.ts';

const source=readFileSync(new URL('../src/useRelay.ts',import.meta.url),'utf8');
const compiled=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const diagnosticsSource=readFileSync(new URL('../src/connectionDiagnostics.ts',import.meta.url),'utf8');
const diagnosticsCompiled=ts.transpileModule(diagnosticsSource,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const login={token:'a'.repeat(32),expiresAt:'2026-12-01T00:00:00Z',channel:'test',account:{accountId:'one',username:'one'},device:{deviceId:'console-one',deviceName:'Console',role:'console',nodeType:'console',platform:'web',component:'web-console'}};

// Execute the production transport hook with controlled effect commits and socket/HTTP timing.
// No DOM or clock sleeps are needed to reproduce a response arriving after a new account renders.
function harness(fetcher){
  const slots=[],effects=new Map(),pending=[],sockets=[],logs=[],timers=new Map();let cursor=0, now=0, timerId=0;
  const schedule=(callback,delay,interval=false)=>{const id=++timerId;timers.set(id,{callback,at:now+delay,interval:interval?delay:0});return id;};
  const storage=new Map();
  const logConsole={info:(_tag,value)=>logs.push(JSON.parse(value))};
  const sessionStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)};
  const diagnosticsModule={exports:{}};
  new vm.Script(diagnosticsCompiled).runInContext(vm.createContext({module:diagnosticsModule,exports:diagnosticsModule.exports,console:logConsole,sessionStorage}));
  const react={
    useState(initial){const i=cursor++;if(!Object.hasOwn(slots,i))slots[i]=initial;return[slots[i],next=>{slots[i]=typeof next==='function'?next(slots[i]):next;}];},
    useRef(initial){const i=cursor++;if(!Object.hasOwn(slots,i))slots[i]={current:initial};return slots[i];},
    useEffect(callback,dependencies){const i=cursor++,previous=effects.get(i);if(!previous||dependencies.some((value,j)=>value!==previous.dependencies[j])){const effect={dependencies,cleanup:previous?.cleanup};effects.set(i,effect);pending.push(()=>{effect.cleanup?.();effect.cleanup=callback();});}},
  };
  class Socket{
    static OPEN=1;
    readyState=0; sent=[];
    constructor(url){this.url=url;this.createdAt=now;sockets.push(this);}
    send(value){this.sent.push(JSON.parse(value));}
    close(){this.readyState=3;this.onclose?.({code:1000});}
    open(){this.readyState=1;this.onopen?.();}
    receive(message){this.onmessage?.({data:JSON.stringify(message)});}
    revoked(){this.readyState=3;this.onclose?.({code:4001});}
    serverClose(code,reason){this.readyState=3;this.onclose?.({code,reason,wasClean:true});}
  }
  const module={exports:{}};
  const context=vm.createContext({module,exports:module.exports,require:name=>name==='react'?react:name==='./connectionDiagnostics'?diagnosticsModule.exports:protocol,performance:{now:()=>now},location:{origin:'http://127.0.0.1:3100'},URL,console:logConsole,crypto:webcrypto,WebSocket:Socket,fetch:fetcher,AbortController,setTimeout:(fn,ms)=>schedule(fn,ms),clearTimeout:id=>timers.delete(id),setInterval:(fn,ms)=>schedule(fn,ms,true),clearInterval:id=>timers.delete(id)});
  new vm.Script(compiled).runInContext(context);
  const commit=()=>{while(pending.length)pending.shift()();};
  return{
    sockets,logs,storage,commit, advance(ms){
      const end=now+ms;
      while(true){
        const due=[...timers].filter(([,timer])=>timer.at<=end).sort((a,b)=>a[1].at-b[1].at||a[0]-b[0])[0];
        if(!due)break;
        const[id,timer]=due;now=timer.at;
        if(timer.interval)timer.at+=timer.interval;else timers.delete(id);
        timer.callback();
      }
      now=end;
    },
    activeTimers:()=>timers.size,
    render(session=login,commitEffects=true){cursor=0;const value=module.exports.useRelay(session);if(commitEffects)commit();return value;},
    stop(){for(const effect of effects.values())effect.cleanup?.();},
  };
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('losing the transport invalidates live status while retaining registered identities and notification scopes',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}),{status:200})));
  t.after(()=>h.stop());h.render();const socket=h.sockets[0];socket.open();socket.receive({type:'auth-result',success:true});
  const camera={deviceId:'camera-one',deviceName:'Camera',nodeType:'visual',platform:'android',role:'detector',component:'android-camera'};
  const notifier={deviceId:'notifier-one',deviceName:'Notifier',nodeType:'notification',platform:'android',role:'notifier',online:true,scope:{mode:'selected',targets:[{deviceId:camera.deviceId}]}};
  socket.receive({type:'notification-scopes',detectors:[camera],notifiers:[notifier]});
  socket.receive({type:'device-list',devices:[{...camera,online:true,isMonitoring:true,isReady:true,capabilities:['video-publish'],sources:[]}]});
  socket.receive({type:'stream-list',streams:[{streamId:'stream-one',publisherDeviceId:camera.deviceId,publisherName:camera.deviceName,sourceName:'Camera',isStreaming:true}]});
  assert.equal(h.render().devices.length,1);assert.equal(h.render().streams.length,1);assert.equal(h.render().notifiers[0].online,true);
  socket.close();
  const disconnected=h.render();
  assert.equal(disconnected.connected,false);assert.equal(disconnected.authExpired,false);
  assert.equal(disconnected.devices.length,0);assert.equal(disconnected.streams.length,0);
  assert.equal(disconnected.registered[0].deviceId,camera.deviceId);
  assert.equal(disconnected.notifiers[0].online,false);assert.equal(disconnected.notifiers[0].scope.targets[0].deviceId,camera.deviceId);
  const nodes=protocol.mergeDevices(disconnected.devices,[...disconnected.registered,...disconnected.notifiers]);
  assert.equal(nodes.length,2);assert.ok(nodes.every(node=>!node.online&&!node.isMonitoring&&node.capabilities.length===0));
  assert.equal(disconnected.send({type:'command',targetDeviceId:camera.deviceId,command:'resume'}),'');
  assert.equal(socket.sent.filter(message=>message.type==='command').length,0);
  socket.receive({type:'device-list',devices:[{...camera,online:true}]});
  assert.equal(h.render().devices.length,0);
});

test('two slow handshakes and a recovered third attempt have distinct correlated phase logs without credentials',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
  assert.equal(h.render().status,'正在连接');h.advance(11999);assert.equal(h.sockets.length,1);
  h.advance(1);assert.equal(h.render().status,'网络连接超时，等待重试');assert.equal(h.render().authExpired,false);
  h.advance(999);assert.equal(h.sockets.length,1);h.advance(1);assert.equal(h.sockets.length,2);
  h.advance(12000);h.advance(1999);assert.equal(h.sockets.length,2);h.advance(1);
  const third=h.sockets[2];third.open();h.advance(100);third.receive({type:'auth-result',success:true});
  assert.equal(h.render().connected,true);
  assert.deepEqual(h.sockets.map(socket=>socket.createdAt),[0,13000,27000]);
  const starts=h.logs.filter(entry=>entry.event==='connect-start');
  assert.equal(starts.length,3);assert.equal(new Set(starts.map(entry=>entry.attemptId)).size,3);
  assert.deepEqual(h.logs.filter(entry=>entry.event==='timeout').map(entry=>entry.reason),['connect-timeout','connect-timeout']);
  assert.deepEqual(h.logs.filter(entry=>entry.event==='retry-scheduled').map(entry=>entry.retryInMs),[1000,2000]);
  const success=h.logs.find(entry=>entry.event==='auth-success');
  assert.equal(success.attempt,3);assert.equal(success.phase,'authenticating');assert.equal(success.openElapsedMs,100);
  assert.equal(new URL(third.url).searchParams.get('traceId'),success.attemptId);
  assert.ok(!JSON.stringify(h.logs).includes(login.token));
  assert.ok(!h.storage.get('visionguard.connection-log').includes(login.token));
});

test('a socket opened without an auth response is diagnosed separately from a slow handshake',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
  h.advance(11000);h.sockets[0].open();assert.equal(h.render().status,'正在认证');
  h.advance(11999);assert.equal(h.logs.some(entry=>entry.event==='timeout'),false);
  h.advance(1);assert.equal(h.render().status,'认证响应超时，等待重试');assert.equal(h.render().authExpired,false);
  const timeout=h.logs.find(entry=>entry.event==='timeout');
  assert.equal(timeout.reason,'auth-timeout');assert.equal(timeout.phase,'authenticating');
  assert.equal(timeout.elapsedMs,23000);assert.equal(timeout.openElapsedMs,12000);
  assert.equal(h.sockets[0].sent[0].type,'auth');
  assert.equal(h.logs.filter(entry=>entry.event==='auth-sent').length,1);
});

test('successful auth cancels its deadline and keeps the existing three-second heartbeat',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
  h.advance(11000);const socket=h.sockets[0];socket.open();h.advance(11000);socket.receive({type:'auth-result',success:true});
  const before=socket.sent.filter(message=>message.type==='heartbeat-console').length;
  h.advance(2000);assert.equal(socket.sent.filter(message=>message.type==='heartbeat-console').length,before+1);
  assert.equal(h.render().status,'已连接');assert.equal(h.sockets.length,1);assert.equal(h.logs.some(entry=>entry.event==='timeout'),false);
});

test('server auth timeout reports a transient failure and retries the same credential for message and close paths',async t=>{
  for(const path of ['message','close']){
    const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
    const first=h.sockets[0];first.open();h.advance(5000);
    if(path==='message')first.receive({type:'auth-result',success:false,reason:'auth timeout'});
    else first.serverClose(4001,'auth timeout');
    assert.equal(h.render().status,'认证响应超时，等待重试');assert.equal(h.render().authExpired,false);
    h.advance(999);assert.equal(h.sockets.length,1);h.advance(1);assert.equal(h.sockets.length,2);
    h.sockets[1].open();assert.equal(h.sockets[1].sent[0].token,login.token);
    h.sockets[1].receive({type:'auth-result',success:true});h.advance(12000);
    assert.equal(h.render().connected,true);assert.equal(h.sockets.length,2);
  }
});

test('actual session rejection remains terminal and cannot schedule a retry',async t=>{
  for(const path of ['message','close','revoked']){
    const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();h.sockets[0].open();
    if(path==='message')h.sockets[0].receive({type:'auth-result',success:false,reason:'invalid session'});
    else if(path==='close')h.sockets[0].serverClose(4001,'invalid session');
    else h.sockets[0].receive({type:'session-revoked'});
    assert.equal(h.render().authExpired,true);assert.equal(h.render().status,'登录已失效，请重新登录');
    h.advance(60000);assert.equal(h.sockets.length,1);assert.equal(h.logs.some(entry=>entry.event==='retry-scheduled'),false);
  }
});

test('backoff caps at thirty seconds and resets after successful authentication',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
  for(const delay of [1000,2000,4000,8000,16000,30000,30000]){h.sockets.at(-1).onerror();h.advance(delay);}
  assert.deepEqual(h.logs.filter(entry=>entry.event==='retry-scheduled').map(entry=>entry.retryInMs),[1000,2000,4000,8000,16000,30000,30000]);
  const recovered=h.sockets.at(-1);recovered.open();recovered.receive({type:'auth-result',success:true});recovered.close();
  const count=h.sockets.length;h.advance(999);assert.equal(h.sockets.length,count);h.advance(1);assert.equal(h.sockets.length,count+1);
});

test('logout, account replacement and credential rotation cancel queued retries and ignore stale callbacks',async t=>{
  for(const action of ['logout','replace','rotate','unmount']){
    const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();
    const old=h.sockets[0],lateOpen=old.onopen,lateMessage=old.onmessage;old.onerror();
    const replacement={...login,token:'e'.repeat(32),account:{accountId:'two',username:'two'}};
    if(action==='logout')h.render(null);
    else if(action==='replace')h.render(replacement);
    else if(action==='rotate')h.render().suspend();
    else h.stop();
    lateOpen();lateMessage({data:JSON.stringify({type:'auth-result',success:true})});
    h.advance(1000);assert.equal(h.sockets.length,action==='replace'?2:1);assert.equal(old.sent.length,0);
    if(action==='unmount')assert.equal(h.activeTimers(),0);
    if(action==='replace'){h.sockets[1].open();h.sockets[1].receive({type:'auth-result',success:true});assert.equal(h.render(replacement).connected,true);}
  }
});

test('unexpected server rejection and close text are not copied into diagnostic storage',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();h.sockets[0].open();
  h.sockets[0].receive({type:'auth-result',success:false,reason:`token=${login.token} password=private`});
  assert.equal(h.render().authExpired,true);
  assert.equal(h.logs.find(entry=>entry.event==='auth-rejected').reason,'server-rejected');
  assert.ok(!JSON.stringify(h.logs).includes(login.token));assert.ok(!JSON.stringify(h.logs).includes('password='));
  assert.equal(h.logs.filter(entry=>entry.event==='retry-scheduled').length,0);
});

test('a late old-account HTTP 401 cannot expire the next account or expose its cached events',async t=>{
  let resolveOld;const oldResponse=new Promise(resolve=>{resolveOld=resolve;});
  const h=harness((_path,init)=>init.headers.Authorization===`Bearer ${login.token}`?oldResponse:Promise.resolve(new Response(JSON.stringify({alerts:[]}),{status:200})));
  t.after(()=>h.stop());h.render();const first=h.sockets[0];first.open();first.receive({type:'auth-result',success:true});
  first.receive({type:'alert',alertId:'account-one-event',timestamp:'2026-10-03T00:00:00Z',deviceId:'one'});
  assert.equal(h.render().alerts.length,1);
  const other={...login,token:'b'.repeat(32),account:{accountId:'two',username:'two'},device:{...login.device,deviceId:'console-two'}};
  const beforeCommit=h.render(other,false);
  assert.equal(beforeCommit.alerts.length,0);assert.equal(beforeCommit.connected,false);
  h.commit();const second=h.sockets[1];second.open();second.receive({type:'auth-result',success:true});
  resolveOld(new Response(JSON.stringify({ok:false}),{status:401}));await tick();
  const current=h.render(other);
  assert.equal(current.authExpired,false);assert.equal(current.connected,true);assert.equal(current.alerts.length,0);
});

test('suspending for credential rotation removes old revocation callbacks before a new socket authenticates',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}),{status:200})));
  t.after(()=>h.stop());h.render();const first=h.sockets[0];first.open();first.receive({type:'auth-result',success:true});
  h.render().suspend();first.revoked();
  assert.equal(h.render().authExpired,false);assert.equal(h.render().connected,false);
  const replacement={...login,token:'c'.repeat(32)};
  h.render(replacement);const second=h.sockets[1];second.open();second.receive({type:'auth-result',success:true});await tick();
  assert.equal(h.render(replacement).connected,true);assert.equal(h.render(replacement).authExpired,false);
  second.revoked();assert.equal(h.render(replacement).authExpired,true);
});

test('a new session on the same device cannot expose data from the previous token before effect cleanup',async t=>{
  const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}),{status:200})));
  t.after(()=>h.stop());h.render();const first=h.sockets[0];first.open();first.receive({type:'auth-result',success:true});
  first.receive({type:'alert',alertId:'old-session-event',timestamp:'2026-10-05T00:00:00Z',deviceId:'one'});
  assert.equal(h.render().alerts.length,1);
  const next={...login,token:'d'.repeat(32)};
  const uncommitted=h.render(next,false);
  assert.equal(uncommitted.connected,false);assert.equal(uncommitted.alerts.length,0);
  h.commit();
  first.receive({type:'alert',alertId:'late-event',timestamp:'2026-10-05T00:00:00Z',deviceId:'one'});
  assert.equal(h.render(next).alerts.length,0);
});
test('operations deduplicate until execution or timeout and preserve uncertain results',async t=>{
 const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();const socket=h.sockets[0];socket.open();socket.receive({type:'auth-result',success:true});
 const m={type:'set-config',targetDeviceId:'node',targetSourceId:'source',key:'confidence',value:'.5'};
 const id=h.render().send(m);assert.equal(h.render().send(m),id);assert.equal(socket.sent.filter(m=>m.type==='set-config').length,1);
 socket.receive({type:'command-ack',requestId:id,success:true,phase:'forwarded'});assert.equal(h.render().acks[0].phase,'forwarded');assert.equal(h.render().send(m),id);
 h.advance(21000);assert.equal(h.render().acks[0].phase,'uncertain');const next=h.render().send(m);assert.notEqual(next,id);
 socket.receive({type:'command-ack',requestId:id,success:true,phase:'completed'});assert.equal(h.render().acks.find(a=>a.requestId===id).phase,'completed');assert.equal(h.render().send(m),next);
 socket.close();assert.equal(h.render().acks.find(a=>a.requestId===next).phase,'uncertain');
});
test('pending operations survive long completed history and failed forwarding unlocks retry',async t=>{
 const h=harness(()=>Promise.resolve(new Response(JSON.stringify({alerts:[]}))));t.after(()=>h.stop());h.render();const socket=h.sockets[0];socket.open();socket.receive({type:'auth-result',success:true});
 const m={type:'command',targetDeviceId:'node',command:'pause'},id=h.render().send(m);
 for(let i=0;i<110;i++)socket.receive({type:'command-ack',requestId:`history-${i}`,phase:'completed',success:true});
 assert.ok(h.render().acks.some(a=>a.requestId===id));socket.receive({type:'command-ack',requestId:id,phase:'forwarded',success:false});assert.equal(h.render().acks[0].phase,'completed');assert.notEqual(h.render().send(m),id);
});
