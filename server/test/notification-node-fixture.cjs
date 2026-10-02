// Local, disposable runtime fixture for the Vigil instrumentation and Web console.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const { spawn } = require('node:child_process');
const WebSocket = require('ws');
const root = path.resolve(__dirname, '../..');
const evidence = path.join(root,'.local/group2-verification');
fs.mkdirSync(evidence,{recursive:true});
fs.writeFileSync(path.join(evidence,'fixture.pid'),String(process.pid));
const identities = [
  ['visual-front','detector','visual','windows'],['sensor-entry','detector','sensor','embedded'],
  ['visual-offline','detector','visual','windows'],['vigil-tablet','notifier','notification','android'],
  ['web-tablet','console','console','web'],['fixture-observer','console','console','web']
].map(([deviceId,role,nodeType,platform]) => ({deviceId,role,nodeType,platform,apiKey:crypto.randomBytes(24).toString('hex')}));
fs.writeFileSync(path.join(evidence,'identities.json'),JSON.stringify(identities),{mode:0o600});
const serverLog = fs.openSync(path.join(evidence,'server.log'),'w');
let child;
function startServer() {
  if (child && child.exitCode === null && !child.killed) return;
  child = spawn(process.execPath,[path.join(root,'server/dist/index.js')],{cwd:path.join(root,'server'),windowsHide:true,stdio:['ignore',serverLog,serverLog],env:{...process.env,PORT:'4318',BIND_HOST:'127.0.0.1',API_KEY:crypto.randomBytes(24).toString('hex'),VISIONGUARD_CHANNEL:'group2-test',VISIONGUARD_DATA_DIR:path.join(evidence,'server-data'),ALERT_STORE_PATH:path.join(evidence,'server-data/alerts.json'),VISIONGUARD_IDENTITIES_FILE:path.join(evidence,'identities.json')}});
}
startServer();
const messages = [], commands = [], clients = new Map();
const state = {isMonitoring:false,isReady:true,cooldown:5,confidence:.45,targets:'person',targetSamplingRate:3,modelKey:'yolov8n',modelOptions:['yolov8n','yolov8s'],canSwitchModelWhileMonitoring:false};
const sources = ['front','side'].map((sourceId,i)=>({...state,sourceId,sourceName:i?'侧门摄像头':'前门摄像头'}));
function heartbeat(ws,identity) {
  if(ws.readyState !== WebSocket.OPEN || identity.role !== 'detector')return;
  ws.send(JSON.stringify({type:'heartbeat',...state,capabilities:['monitor-control','config-control','request-correlation',...(identity.nodeType==='visual'?['source-control']:[])],...(identity.nodeType === 'visual' ? {sources:sources.map(s=>({...s,monitoringExpected:s.isMonitoring,lastProgressAt:new Date().toISOString()}))} : {targets:'',modelKey:'',modelOptions:[]}),monitoringExpected:state.isMonitoring,lastProgressAt:new Date().toISOString()}));
}
function connect(identity) {
  const ws = new WebSocket('ws://127.0.0.1:4318/ws');clients.set(identity.deviceId,ws);
  ws.on('open',()=>ws.send(JSON.stringify({type:'auth',channel:'group2-test',...identity,deviceName:identity.nodeType==='visual'?'门口视觉节点':identity.nodeType==='sensor'?'入口传感器':'验收观察端',capabilities:identity.role==='detector'?['monitor-control','config-control','request-correlation',...(identity.nodeType==='visual'?['source-control']:[])]:[]})));
  ws.on('message',raw=>{
    const m=JSON.parse(raw);
    if(identity.role==='console')messages.push(m);
    if(m.type==='auth-result'&&m.success)heartbeat(ws,identity);
    if(m.type==='command'||m.type==='set-config') {
      commands.push(m);
      const target=m.targetSourceId?sources.find(s=>s.sourceId===m.targetSourceId):state;
      if(m.type==='command' && ['pause','resume'].includes(m.command)) target.isMonitoring=m.command==='resume';
      if(m.type==='set-config') target[m.key]=['targets','modelKey'].includes(m.key)?m.value:Number(m.value);
      if(!m.targetSourceId && m.type==='command')sources.forEach(s=>s.isMonitoring=state.isMonitoring);
      ws.send(JSON.stringify({type:'command-ack',requestId:m.requestId,phase:'completed',command:m.type==='set-config'?`set-config:${m.key}`:m.command,targetSourceId:m.targetSourceId,success:true,reason:'测试检测节点已执行'}));
      heartbeat(ws,identity);
    }
  });
  ws.on('error',()=>{});
  ws.on('close',()=>{if(!closing)setTimeout(()=>connect(identity),1_000)});
}
let closing=false;
setTimeout(()=>[identities[0],identities[1],identities[5]].forEach(connect),1_000);
const timer=setInterval(()=>{for(const identity of [identities[0],identities[1],identities[5]]){
  const ws=clients.get(identity.deviceId);if(!ws||ws.readyState!==WebSocket.OPEN)continue;
  if(identity.role==='console')ws.send(JSON.stringify({type:'heartbeat-console'}));else heartbeat(ws,identity);
}},3_000);
function emit(kind) {
  const identity=kind==='sensor'?identities[1]:identities[0];
  const ws=clients.get(identity.deviceId);
  const now=Date.now(),alertId=crypto.randomUUID();
  const event={type:'alert',alertId,eventKind:kind==='sensor'?'sensor-detection':'visual-detection',summary:kind==='sensor'?'有人经过入口':'检测到人员',timestamp:new Date(now).toISOString(),expiresAt:new Date(now+30_000).toISOString(),detections:kind==='sensor'?[]:[{label:'person',confidence:.99,bbox:{x:0,y:0,w:1,h:1}}],...(kind==='sensor'?{}:{sourceId:kind==='side'?'side':'front',sourceName:kind==='side'?'侧门摄像头':'前门摄像头'})};
  if(ws?.readyState!==WebSocket.OPEN)throw new Error('Detector not ready');
  ws.send(JSON.stringify(event));return alertId;
}
const qa=http.createServer(async(req,res)=>{
  res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');
  try {
    const url=new URL(req.url,'http://127.0.0.1');
    if(url.pathname==='/config')return res.end(JSON.stringify({endpoint:'ws://10.0.2.2:4318/ws',channel:'group2-test',deviceId:identities[3].deviceId,name:'平板通知节点',apiKey:identities[3].apiKey}));
    if(url.pathname==='/emit')return res.end(JSON.stringify({alertId:emit(url.searchParams.get('kind')||'front')}));
    if(url.pathname==='/scope'){
      const scope=url.searchParams.get('mode')==='all'?{mode:'all',targets:[]}:{mode:'selected',targets:url.searchParams.get('mode')==='none'?[]:[{deviceId:'visual-front',sourceId:'front'}]};
      clients.get('fixture-observer').send(JSON.stringify({type:'set-notification-scope',requestId:crypto.randomUUID(),targetNotifierId:'vigil-tablet',scope}));
      return res.end(JSON.stringify({ok:true}));
    }
    if(url.pathname==='/pause-server'){child.kill();return res.end(JSON.stringify({ok:true}));}
    if(url.pathname==='/resume-server'){startServer();return res.end(JSON.stringify({ok:true}));}
    if(url.pathname==='/state')return res.end(JSON.stringify({receipts:messages.filter(m=>m.type==='notification-receipt'),scopes:messages.filter(m=>m.type==='notification-scopes').slice(-1),commands}));
    res.statusCode=404;res.end('{}');
  }catch{res.statusCode=500;res.end('{"error":"fixture failure"}');}
});
qa.listen(4319,'127.0.0.1',()=>console.log('Local group2 fixture running; credentials remain in ignored files.'));
function cleanup(){closing=true;clearInterval(timer);for(const ws of clients.values())ws.terminate();qa.close();child?.kill();fs.writeFileSync(path.join(evidence,'runtime-report.json'),JSON.stringify({receipts:messages.filter(m=>m.type==='notification-receipt'),commands},null,2));setTimeout(()=>process.exit(),500);}
process.on('SIGINT',cleanup);process.on('SIGTERM',cleanup);
