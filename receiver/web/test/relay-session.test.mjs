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
  const slots=[],effects=new Map(),pending=[],sockets=[],logs=[];let cursor=0, now=0, timer;
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
    constructor(url){this.url=url;sockets.push(this);}
    send(value){this.sent.push(JSON.parse(value));}
    close(){this.readyState=3;this.onclose?.({code:1000});}
    open(){this.readyState=1;this.onopen?.();}
    receive(message){this.onmessage?.({data:JSON.stringify(message)});}
    revoked(){this.readyState=3;this.onclose?.({code:4001});}
  }
  const module={exports:{}};
  const context=vm.createContext({module,exports:module.exports,require:name=>name==='react'?react:name==='./connectionDiagnostics'?diagnosticsModule.exports:protocol,performance:{now:()=>now},location:{origin:'http://127.0.0.1:3100'},URL,console:logConsole,crypto:webcrypto,WebSocket:Socket,fetch:fetcher,AbortController,setInterval:fn=>{timer=fn;return 1;},clearInterval:()=>{timer=null;}});
  new vm.Script(compiled).runInContext(context);
  const commit=()=>{while(pending.length)pending.shift()();};
  return{
    sockets,logs,storage,commit, advance(ms){now+=ms;timer?.();},
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
  h.advance(15000);h.advance(3000);h.advance(15000);h.advance(3000);
  const third=h.sockets[2];third.open();h.advance(100);third.receive({type:'auth-result',success:true});
  assert.equal(h.render().connected,true);
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
  h.advance(2000);h.sockets[0].open();h.advance(11000);
  const timeout=h.logs.find(entry=>entry.event==='timeout');
  assert.equal(timeout.reason,'auth-timeout');assert.equal(timeout.phase,'authenticating');
  assert.equal(timeout.elapsedMs,13000);assert.equal(timeout.openElapsedMs,11000);
  assert.equal(h.sockets[0].sent[0].type,'auth');
  assert.equal(h.logs.filter(entry=>entry.event==='auth-sent').length,1);
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
