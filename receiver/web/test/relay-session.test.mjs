import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import vm from 'node:vm';
import test from 'node:test';
import ts from 'typescript';
import * as protocol from '../src/protocol.ts';

const source=readFileSync(new URL('../src/useRelay.ts',import.meta.url),'utf8');
const compiled=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const login={token:'a'.repeat(32),expiresAt:'2026-12-01T00:00:00Z',channel:'test',account:{accountId:'one',username:'one'},device:{deviceId:'console-one',deviceName:'Console',role:'console',nodeType:'console',platform:'web',component:'web-console'}};

// Execute the production transport hook with controlled effect commits and socket/HTTP timing.
// No DOM or clock sleeps are needed to reproduce a response arriving after a new account renders.
function harness(fetcher){
  const slots=[],effects=new Map(),pending=[],sockets=[];let cursor=0;
  const react={
    useState(initial){const i=cursor++;if(!Object.hasOwn(slots,i))slots[i]=initial;return[slots[i],next=>{slots[i]=typeof next==='function'?next(slots[i]):next;}];},
    useRef(initial){const i=cursor++;if(!Object.hasOwn(slots,i))slots[i]={current:initial};return slots[i];},
    useEffect(callback,dependencies){const i=cursor++,previous=effects.get(i);if(!previous||dependencies.some((value,j)=>value!==previous.dependencies[j])){const effect={dependencies,cleanup:previous?.cleanup};effects.set(i,effect);pending.push(()=>{effect.cleanup?.();effect.cleanup=callback();});}},
  };
  class Socket{
    static OPEN=1;
    readyState=0; sent=[];
    constructor(){sockets.push(this);}
    send(value){this.sent.push(JSON.parse(value));}
    close(){this.readyState=3;this.onclose?.({code:1000});}
    open(){this.readyState=1;this.onopen?.();}
    receive(message){this.onmessage?.({data:JSON.stringify(message)});}
    revoked(){this.readyState=3;this.onclose?.({code:4001});}
  }
  const module={exports:{}};
  const context=vm.createContext({module,exports:module.exports,require:name=>name==='react'?react:protocol,performance,location:{origin:'http://127.0.0.1:3100'},WebSocket:Socket,fetch:fetcher,AbortController,setInterval:()=>1,clearInterval:()=>{}});
  new vm.Script(compiled).runInContext(context);
  const commit=()=>{while(pending.length)pending.shift()();};
  return{
    sockets,commit,
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
