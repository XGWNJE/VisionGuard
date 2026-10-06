// Local simulated clients only. Credentials stay in the ignored private directory.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');
const HERE = path.join(root, '.local/console-preview');
fs.mkdirSync(HERE, {recursive:true});
const WebSocket = require(path.join(root, 'server/node_modules/ws'));
const http = require('node:http');
const zlib = require('node:zlib');
const origin = 'http://127.0.0.1:4318';
const account = JSON.parse(fs.readFileSync(path.join(root, '.local/e2e-server/console-preview/test-accounts.json'), 'utf8')).find(a => a.username === 'vg-test');
const manifestPath = path.join(HERE, 'preview-manifest.json');
const previous = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, 'utf8')) : {devices:{}, alertIds:[]};
const manifest = {devices:{}, alertIds:previous.alertIds};
const clients = [];
let controlServer;
let currentScene='normal';
const originalSources = new Map();
const sessions = [];
const source = (id, name, mode='running', fps=4.8) => ({sourceId:id, sourceName:name, isMonitoring:mode==='running', isReady:mode!=='error', modelKey:'yolo26n_320', actualFps:mode==='running'?fps:0, cooldown:20, confidence:0.65, targets:'person', targetSamplingRate:5, activeBackend:'DirectML', ...(mode==='error'?{error:'模拟：视频来源暂时不可用'}:{})});
const specs = [
  {key:'entrance', component:'windows-inference', name:'【模拟】门厅视觉节点', sources:[source('front','正门入口'),source('desk','前台区域', 'running',4.6),source('hall','一楼走廊','running',4.9),source('lift','电梯大厅','paused'),source('side','侧门通道','error')]},
  {key:'warehouse', component:'windows-inference', name:'【模拟】仓库视觉节点', sources:[source('stock','仓库通道','running',4.4),source('loading','装卸区域','running',4.2),source('rear','后门入口','paused')]},
  {key:'offline', component:'windows-inference', name:'【模拟】停车场视觉节点（离线）', offline:true, sources:[]},
  {key:'camera', component:'android-camera', name:'【模拟】移动巡检相机', sources:[]},
  {key:'phone', component:'android-notifier', name:'【模拟】值班手机'},
  {key:'tablet', component:'android-notifier', name:'【模拟】备用平板（离线）', offline:true},
];
if (!Object.keys(previous.devices).length) {
  const store=JSON.parse(fs.readFileSync(path.join(root,'.local/e2e-server/console-preview/accounts.json'),'utf8'));
  const accountId=store.accounts.find(a=>a.username===account.username).accountId;
  for(const spec of specs){const device=store.devices.find(d=>d.accountId===accountId&&d.deviceName===spec.name&&d.component===spec.component);if(device)previous.devices[spec.key]=device.deviceId;}
  const controller=store.devices.find(d=>d.accountId===accountId&&d.component==='web-console'&&d.deviceName.startsWith('preview-controller-'));if(controller)previous.devices.controller=controller.deviceId;
}
async function api(route, session, body, method) {
  const options={method:method||(body===undefined?'GET':'POST'), headers:{...(session?{Authorization:'Bearer '+session.token}:{}), ...(body===undefined?{}:{'Content-Type':'application/json'})}, ...(body===undefined?{}:{body:JSON.stringify(body)})};
  let response = await fetch(origin+route,options);
  if(response.status===429 && route==='/api/account/login') {
    const retry=Number(response.headers.get('retry-after'));
    if(Number.isFinite(retry)&&retry>0&&retry<=60){await new Promise(r=>setTimeout(r,(retry+1)*1000));response=await fetch(origin+route,options);}
  }
  const data = await response.json();
  if (!response.ok) throw new Error(`${route}: HTTP ${response.status}`);
  return data;
}
class Peer {
  constructor(session, spec) {
    this.session=session; this.spec=spec; this.messages=[];
    this.ws=new WebSocket('ws://127.0.0.1:4318/ws');
    this.ws.on('message', raw => {
      const msg=JSON.parse(raw.toString()); this.messages.push(msg); if(this.messages.length>150)this.messages.shift();
      if(msg.type==='alert' && spec?.component==='android-notifier')this.send({type:'notification-receipt',alertId:msg.alertId});
      if(spec && ['command','set-config'].includes(msg.type))this.control(msg);
      if(spec && msg.type==='request-screenshot')this.picture(msg.alertId);
    });
    this.ws.on('error', () => { console.error('A local preview client disconnected.'); });
  }
  send(msg) { if(this.ws.readyState===WebSocket.OPEN)this.ws.send(JSON.stringify(msg)); }
  async take(predicate) {
    const deadline=Date.now()+6000;
    while(Date.now()<deadline) { const index=this.messages.findIndex(predicate); if(index>=0)return this.messages.splice(index,1)[0]; await new Promise(r=>setTimeout(r,25)); }
    throw new Error('Local preview response timed out: '+predicate.toString()+'; received types: '+this.messages.slice(-10).map(m=>m.type).join(','));
  }
  async ready() {
    await new Promise((resolve,reject)=>{this.ws.once('open',resolve);this.ws.once('error',reject);});
    this.send({type:'auth',token:this.session.token});
    assert.equal((await this.take(m=>m.type==='auth-result')).success,true);
    this.heartbeat();
    this.timer=setInterval(()=>this.heartbeat(),3000);
    clients.push(this); return this;
  }
  heartbeat() {
    if(!this.spec) return this.send({type:'heartbeat-console'});
    if(this.spec.component==='android-notifier')return this.send({type:'heartbeat-notifier'});
    const sources=this.spec.sources||[];
    this.send({type:'heartbeat',deviceId:this.session.device.deviceId,isMonitoring:sources.some(s=>s.isMonitoring),isReady:true,cooldown:20,confidence:0.65,targets:'person',targetSamplingRate:5,modelKey:'yolo26n_320',modelOptions:['yolo26n_320','yolo26s_320'],modelLabels:{yolo26n_320:[{value:'person',label:'人员'},{value:'car',label:'车辆'}],yolo26s_320:[{value:'person',label:'人员'},{value:'car',label:'车辆'}]},canSwitchModelWhileMonitoring:true,capabilities:this.spec.component==='android-camera'?['video-publish','request-correlation']:['monitor-control','config-control','source-control','screenshot-on-demand','request-correlation','directml','video-subscribe','visual-inference'],components:{detectorApp:'running'},sources:sources.map(s=>({...s,monitoringExpected:s.isMonitoring,lastProgressAt:new Date().toISOString()}))});
  }
  control(msg) {
    const selected=msg.targetSourceId?this.spec.sources.filter(s=>s.sourceId===msg.targetSourceId):this.spec.sources;
    let success=true;
    if(msg.type==='command' && ['pause','resume'].includes(msg.command))for(const s of selected){s.isMonitoring=msg.command==='resume';s.isReady=true;delete s.error;s.actualFps=s.isMonitoring?4.8:0;}
    else if(msg.type==='set-config')for(const s of selected){const key=msg.key==='samplingRate'?'targetSamplingRate':msg.key;s[key]=['cooldown','confidence','targetSamplingRate'].includes(key)?Number(msg.value):msg.value;}
    else if(msg.command!=='stop-alarm')success=false;
    this.heartbeat();
    this.send({type:'command-ack',requestId:msg.requestId,phase:'completed',targetDeviceId:this.session.device.deviceId,targetSourceId:msg.targetSourceId,command:msg.type==='set-config'?`set-config:${msg.key}`:msg.command,success,reason:success?'模拟节点已执行':'模拟节点不支持此操作'});
  }
  picture(alertId) { this.send({type:'screenshot-data',alertId,deviceId:this.session.device.deviceId,imageBase64:fs.readFileSync(path.join(HERE,'sample-frame.png')).toString('base64'),width:960,height:540}); }
}
async function login(component, key) {
  const session=await api('/api/account/login',null,{...account,component,deviceIdentity:require('node:crypto').createHash('sha256').update('ui-preview|'+key).digest('hex'),deviceModel:'模拟设备',...(previous.devices[key]?{deviceId:previous.devices[key]}:{})});
  sessions.push(session);return session;
}
async function main() {
  const health=await api('/health');assert.equal(health.channel,'console-preview');
  const consoleSession=await login('web-console','controller');
  const consolePeer=await new Peer(consoleSession).ready();
  const administrator=JSON.parse(fs.readFileSync(path.join(root,'.local/e2e-server/console-preview/initial-administrator.json'),'utf8'));
  const adminSession=await api('/api/account/login',null,{username:administrator.username,password:administrator.password,component:'web-console',deviceIdentity:require('node:crypto').createHash('sha256').update('ui-preview-admin').digest('hex'),deviceModel:'模拟浏览器',...(previous.devices.admin?{deviceId:previous.devices.admin}:{})});
  manifest.devices.admin=adminSession.device.deviceId;
  try {
    const existing=(await api('/api/admin/accounts',adminSession)).accounts;
    for(const row of [{username:('ui-preview-'+ 'maximum-username0123456789'.repeat(4)).slice(0,64),enabled:true},{username:'ui-preview-disabled',enabled:false}]) {
      if(existing.some(a=>a.username===row.username))continue;
      const created=await api('/api/admin/accounts',adminSession,{username:row.username,password:crypto.randomBytes(24).toString('base64'),isAdmin:false});
      if(!row.enabled)await api('/api/admin/accounts/'+created.account.accountId,adminSession,{enabled:false},'PATCH');
    }
  } finally { await api('/api/account/logout',adminSession,{},'POST'); }

  for(const spec of specs) {
    originalSources.set(spec.key, structuredClone(spec.sources||[]));
    spec.session=await login(spec.component,spec.key);manifest.devices[spec.key]=spec.session.device.deviceId;fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2));
    await api('/api/devices/'+spec.session.device.deviceId,consoleSession,{deviceName:spec.name},'PATCH');
    if(!spec.offline)spec.peer=await new Peer(spec.session,spec).ready();
  }
  manifest.devices.controller=consoleSession.device.deviceId;fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2));
  const mainNode=specs[0], warehouse=specs[1];
  console.log('Preview devices registered.');
  await api('/api/streams/bind',consoleSession,{publisherDeviceId:specs[3].session.device.deviceId,targetDeviceId:mainNode.session.device.deviceId});
  const scopeRequest=crypto.randomUUID();
  consolePeer.send({type:'set-notification-scope',requestId:scopeRequest,targetNotifierId:specs[5].session.device.deviceId,scope:{mode:'selected',targets:[{deviceId:warehouse.session.device.deviceId,sourceId:'stock'}]}});
  assert.equal((await consolePeer.take(m=>m.type==='notification-scope-result'&&m.requestId===scopeRequest)).success,true);
  console.log('Preview notification scopes configured.');
  if(!manifest.alertIds.length) {
    for(let i=0;i<12;i++) {
      const node=i%3===0?warehouse:mainNode, src=node.sources[i%3], alertId=crypto.randomUUID(), now=Date.now();
      node.peer.send({type:'alert',alertId,eventKind:'visual-detection',sourceId:src.sourceId,sourceName:src.sourceName,summary:`【模拟预览】${src.sourceName}检测到${i%4===0?'车辆':'人员'}${i%5===0?'，2 个目标':''}`,timestamp:new Date(now-(12-i)*1200).toISOString(),expiresAt:new Date(now+12000).toISOString(),detections:[{label:i%4===0?'car':'person',confidence:0.83+(i%5)*0.025,bbox:{x:0.39,y:0.27,w:0.17,h:0.48}},...(i%5===0?[{label:'person',confidence:0.88,bbox:{x:0.65,y:0.31,w:0.12,h:0.4}}]:[])]});
      const ack=await node.peer.take(m=>m.type==='alert-ack'&&m.alertId===alertId);assert.equal(ack.accepted,true);node.peer.picture(alertId);manifest.alertIds.push(alertId);
    }
  }
  fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2));
  await new Promise(r=>setTimeout(r,700));
  let devices=(await api('/api/devices',consoleSession)).devices;
  const current=new Set(Object.values(manifest.devices));
  for(const d of devices.filter(d=>d.deviceName.startsWith('【模拟】')&&!current.has(d.deviceId)))await api('/api/devices/'+d.deviceId,consoleSession,undefined,'DELETE');
  devices=(await api('/api/devices',consoleSession)).devices;
  const alerts=(await api('/api/alerts?limit=100',consoleSession)).alerts.filter(a=>manifest.alertIds.includes(a.alertId));
  assert.equal(specs.filter(s=>devices.some(d=>d.deviceId===s.session.device.deviceId)).length,6);
  assert.ok(alerts.length>=12);assert.equal(alerts.filter(a=>a.hasScreenshot).length,alerts.length);
  const scopeList=await consolePeer.take(m=>m.type==='notification-scopes'&&m.notifiers?.some(n=>n.deviceId===specs[5].session.device.deviceId&&n.scope.mode==='selected'));
  const evidence={account:'vg-test',simulated:true,nodes:6,onlineNodes:4,offlineNodes:2,sources:8,alerts:alerts.length,screenshots:alerts.filter(a=>a.hasScreenshot).length,uiFixturesReady:true,notificationScopes:scopeList.notifiers.map(n=>({deviceName:n.deviceName,online:n.online,mode:n.scope.mode})),checkedAt:new Date().toISOString()};
  fs.writeFileSync(path.join(HERE,'verification.json'),JSON.stringify(evidence,null,2));console.log(JSON.stringify(evidence));
  startControls();
  console.log('UI preview scenarios: http://127.0.0.1:4319/ . Simulated data only.');
}
process.on('SIGINT',()=>{controlServer?.close();clients.forEach(c=>{clearInterval(c.timer);c.ws.close();});setTimeout(()=>process.exit(),200);});

function testPng() {
  const crc=bytes=>{let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0;};
  const chunk=(name,data)=>{const t=Buffer.from(name),n=Buffer.alloc(4),c=Buffer.alloc(4);n.writeUInt32BE(data.length);c.writeUInt32BE(crc(Buffer.concat([t,data])));return Buffer.concat([n,t,data,c]);};
  const width=960,height=540,rows=Buffer.alloc(height*(1+width*3));
  for(let y=0;y<height;y++)for(let x=0;x<width;x++){const p=y*(1+width*3)+1+x*3;const box=x>=350&&x<550&&y>=130&&y<430;const color=box?[8,127,131]:[31,40,50];for(let c=0;c<3;c++)rows[p+c]=color[c];}
  const glyphs={U:['10001','10001','10001','10001','01110'],I:['111','010','010','010','111'],M:['10001','11011','10101','10001','10001'],O:['01110','10001','10001','10001','01110'],C:['01111','10000','10000','10000','01111'],K:['10001','10010','11100','10010','10001']};
  let left=30;for(const letter of 'UI MOCK'){if(letter===' '){left+=24;continue;}const g=glyphs[letter];for(let y=0;y<g.length;y++)for(let x=0;x<g[y].length;x++)if(g[y][x]==='1')for(let dy=0;dy<7;dy++)for(let dx=0;dx<7;dx++){const p=(30+y*7+dy)*(1+width*3)+1+(left+x*7+dx)*3;rows[p]=240;rows[p+1]=240;rows[p+2]=240;}left+=(g[0].length+1)*7;}
  const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('tEXt',Buffer.from('Description\0SIMULATED UI PREVIEW - NOT CAMERA OR INFERENCE EVIDENCE')),chunk('IDAT',zlib.deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]);
}
// Silent audio is disposable UI fixture data, not audible-alert evidence.
const ringtones=path.join(HERE,'ringtones');fs.mkdirSync(ringtones,{recursive:true});
const wav=Buffer.alloc(44+16000);wav.write('RIFF',0);wav.writeUInt32LE(wav.length-8,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(8000,24);wav.writeUInt32LE(16000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(16000,40);
for(const name of ['【模拟】门厅','【模拟】后门','【模拟】仓库','【模拟】备用',('【模拟】'+ '最长合法铃声名称'.repeat(10)).slice(0,64),('MOCK_'+ 'CONTINUOUS_LONG_AUDIO_NAME'.repeat(4)).slice(0,64)])fs.writeFileSync(path.join(ringtones,name+'.wav'),wav);
if(!fs.existsSync(path.join(HERE,'sample-frame.png')))fs.writeFileSync(path.join(HERE,'sample-frame.png'),testPng());
async function setScene(mode) {
  if(!['normal','limits','empty'].includes(mode))throw new Error('Unknown UI scene');
  const controller=clients.find(c=>!c.spec).session;
  for(const spec of specs){
    let name=spec.name;
    if(mode==='limits')name=('【模拟】'+(spec.key==='warehouse'?'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'.repeat(3):'合法最大长度的中文节点名称'.repeat(8))).slice(0,64);
    await api('/api/devices/'+spec.session.device.deviceId,controller,{deviceName:name},'PATCH');
    if(spec.component==='windows-inference'&&!spec.offline){
      spec.sources=mode==='empty'?[]:structuredClone(originalSources.get(spec.key));
      if(mode==='limits'){
        spec.sources=Array.from({length:16},(_,i)=>source('preview-'+i,('【模拟】'+(i%2?'LONG_CONTINUOUS_ENGLISH_SOURCE_NAME'.repeat(4):'合法最大长度的中文来源名称'.repeat(8))).slice(0,64),i%4===0?'paused':i%4===1?'error':'running'));
        spec.sources.forEach(s=>{if(s.error)s.error=('【模拟】用于检查异常详情的换行与滚动。'.repeat(30)).slice(0,256);s.cooldown=s.isMonitoring?1:300;s.confidence=s.isMonitoring?0.1:0.95;s.targetSamplingRate=s.isMonitoring?1:5;});
      }
      spec.peer.heartbeat();
    }
  }
  currentScene=mode;
  return {simulated:true,scene:mode,account:'vg-test',nodes:6,sources:specs.reduce((n,s)=>n+(s.sources?.length||0),0)};
}
function startControls(){
  const html=`<!doctype html><meta charset="utf-8"><title>VisionGuard UI 模拟场景</title><style>body{font:16px system-ui;max-width:800px;margin:40px auto;padding:24px;background:#f5f5f5;color:#202020}button{padding:12px;margin:8px;border:1px solid #ccc;border-radius:8px;background:white;cursor:pointer}pre{white-space:pre-wrap}</style><h1>VisionGuard UI 模拟场景</h1><p>仅用于界面显示检查。使用 vg-test 登录各组件；空节点和空事件使用 vg-isolation 账号。</p><button onclick="scene('normal')">普通状态</button><button onclick="scene('limits')">最大名称、每节点 16 路来源及数值边界</button><button onclick="scene('empty')">空来源</button><button onclick="event()">追加模拟报警（上限场景含 100 个目标）</button><p><a href="http://127.0.0.1:4318/console/" target="_blank">打开控制台</a></p><details><summary>可复制的名称边界</summary><p>64 字可保存</p><textarea readonly rows="3" cols="64">${'名称'.repeat(32)}</textarea><p>65 字须拒绝整段粘贴或保存</p><textarea readonly rows="3" cols="64">${'名称'.repeat(32)+'超'}</textarea></details><pre id="result">准备就绪</pre><script>async function scene(mode){const r=await fetch('/scene?mode='+mode);document.getElementById('result').textContent=await r.text()}async function event(){const r=await fetch('/event');document.getElementById('result').textContent=await r.text()}</script>`;
  controlServer=http.createServer(async(req,res)=>{
    try{
      const url=new URL(req.url,'http://127.0.0.1:4319');
      if(url.pathname==='/'){res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html);return;}
      res.setHeader('Content-Type','application/json; charset=utf-8');
      if(url.pathname==='/scene'){res.end(JSON.stringify(await setScene(url.searchParams.get('mode'))));return;}
      if(url.pathname==='/event'){
        const node=specs[0],src=node.sources.find(s=>s.isReady)||node.sources[0];if(!src)throw new Error('请先切换到普通或边界场景');
        const now=Date.now(),alertId=crypto.randomUUID();
        node.peer.send({type:'alert',alertId,eventKind:'visual-detection',sourceId:src.sourceId,sourceName:src.sourceName,summary:('【模拟UI预览】人员报警，不证明实际检测或声音。'.repeat(20)).slice(0,256),timestamp:new Date(now).toISOString(),expiresAt:new Date(now+30000).toISOString(),detections:Array.from({length:currentScene==='limits'?100:1},(_,i)=>({label:'person',confidence:0.95,bbox:{x:(i%10)/10,y:Math.floor(i/10)/10,w:0.08,h:0.08}}))});
        assert.equal((await node.peer.take(m=>m.type==='alert-ack'&&m.alertId===alertId)).accepted,true);node.peer.picture(alertId);manifest.alertIds.push(alertId);fs.writeFileSync(manifestPath,JSON.stringify(manifest,null,2));res.end(JSON.stringify({simulated:true,alertId}));return;
      }
      if(url.pathname==='/stop'){res.end(JSON.stringify({stopping:true}));process.emit('SIGINT');return;}
      res.statusCode=404;res.end('{}');
    }catch(error){res.statusCode=400;res.end(JSON.stringify({simulated:true,error:error.message}));}
  });
  controlServer.listen(4319,'127.0.0.1');
}

main().catch(error=>{console.error('Preview setup failed:',error.message);clients.forEach(c=>{clearInterval(c.timer);c.ws.terminate();});process.exitCode=1;});
