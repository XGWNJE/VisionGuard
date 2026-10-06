import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

process.env.VISIONGUARD_CHANNEL = 'test-vnext';
process.env.MAX_SOURCES_PER_DETECTOR = '4';
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-control-accounts-'));
process.env.VISIONGUARD_DATA_DIR = temporary;
test.after(() => fs.rmSync(temporary, { recursive: true, force: true }));
const { AccountFixture } = require('./helpers/accounts') as typeof import('./helpers/accounts');
const fixtures = new AccountFixture([{"name": "foreign-channel-receiver", "component": "web-console"}, {"name": "alert-retry-detector", "component": "windows-inference"}, {"name": "alert-retry-receiver", "component": "web-console"}, {"name": "detector-control-test", "component": "windows-inference"}, {"name": "receiver-control-test", "component": "web-console"}, {"name": "other-receiver-control-test", "component": "web-console"}, {"name": "resident-control-test", "component": "windows-resident"}, {"name": "resident-receiver-test", "component": "web-console"}, {"name": "resident-name-test", "component": "windows-resident"}, {"name": "resident-name-test", "component": "windows-inference"}, {"name": "resident-name-receiver", "component": "web-console"}]);

const { associateScreenshotPayload, handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');
const { accountStore } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');

test('camera and notifier controls require declared capabilities, route only inside account and correlate actual completion', async t => {
  const owner = new AccountFixture([{name:'camera-command',component:'android-camera'}, {name:'notifier-command',component:'android-notifier'},
    {name:'console-command',component:'web-console'}], 'control-capability-owner');
  const foreign = new AccountFixture([{name:'foreign-console-command',component:'web-console'}], 'control-capability-other');
  await Promise.all([owner.ready,foreign.ready]);
  const wss = new WebSocketServer({host:'127.0.0.1',port:0}); wss.on('connection',handleConnection);
  await new Promise<void>(resolve=>wss.once('listening',resolve));
  const port = (wss.address() as {port:number}).port;
  const peers:WebSocket[]=[]; t.after(()=>{peers.forEach(ws=>ws.terminate());wss.close();});
  async function peer(name:string, fixture=owner) {
    const ws=await connect(port); peers.push(ws);
    const auth=waitForMessage(ws,m=>m.type==='auth-result'); ws.send(JSON.stringify(fixture.auth(name))); await auth; return ws;
  }
  const console=await peer('console-command'), camera=await peer('camera-command'), notifier=await peer('notifier-command'), other=await peer('foreign-console-command',foreign);
  async function reject(ws:WebSocket,command:string,target:string) {
    const requestId=crypto.randomUUID(), reply=waitForMessage(ws,m=>m.type==='command-ack'&&m.requestId===requestId);
    ws.send(JSON.stringify({type:'command',requestId,command,targetDeviceId:owner.id(target)}));
    assert.equal((await reply).success,false);
  }
  await reject(console,'start-stream','camera-command'); await reject(console,'stop-alarm','notifier-command');
  for(const [ws,message] of [[camera,{type:'heartbeat',isMonitoring:false,isReady:true,components:{cameraApp:'foreground',cameraPermission:'granted',invalid:'foreground'},capabilities:['video-publish','stream-control','request-correlation'],sources:[]}],
    [notifier,{type:'heartbeat-notifier',capabilities:['alarm-control','request-correlation','monitor-control']}]] as const) {
    const response=waitForMessage(ws,m=>m.type==='heartbeat-ack'); ws.send(JSON.stringify(message));assert.notEqual((await response).accepted,false);
  }
  const list=waitForMessage(console,m=>m.type==='device-list'); console.send(JSON.stringify({type:'get-devices'}));
  assert.deepEqual((await list).devices.find((d:any)=>d.deviceId===owner.id('camera-command')).components,{cameraApp:'foreground',cameraPermission:'granted'});
  await reject(other,'stop-stream','camera-command');await reject(other,'stop-alarm','notifier-command');await reject(console,'start-stream','notifier-command');
  for(const [command,name,node] of [['start-stream','camera-command',camera],['stop-stream','camera-command',camera],['stop-alarm','notifier-command',notifier]] as const) {
    const requestId=crypto.randomUUID(), forwarded=waitForMessage(console,m=>m.type==='command-ack'&&m.requestId===requestId),
      relay=waitForMessage(node,m=>m.type==='command'&&m.requestId===requestId);
    console.send(JSON.stringify({type:'command',requestId,command,targetDeviceId:owner.id(name)}));
    const routed = await forwarded;
    assert.equal(routed.success,true, `${command}: ${routed.reason}`);
    await relay;
    const completed=waitForMessage(console,m=>m.type==='command-ack'&&m.requestId===requestId&&m.phase==='completed');
    node.send(JSON.stringify({type:'command-ack',requestId,command,phase:'completed',success:true,reason:'Node executed'}));
    assert.equal((await completed).reason,'Node executed');
  }
});

test('associates screenshot identity from the authoritative alert record', () => {
  const alert = {
    alertId: 'alert-12345678', deviceId: 'detector-1', deviceName: 'Detector',
    sourceId: 'window-2', sourceName: 'Back Door', timestamp: 'now', detections: [], createdAt: 1,
  };
  const payload = {
    type: 'screenshot-data' as const, alertId: alert.alertId, deviceId: 'spoofed',
    sourceId: 'spoofed-source', sourceName: 'Spoofed', imageBase64: 'image',
  };

  assert.equal(associateScreenshotPayload(payload, 'other-detector', alert), null);
  const associated = associateScreenshotPayload(payload, 'detector-1', alert);
  assert.equal(associated?.deviceId, 'detector-1');
  assert.equal(associated?.sourceId, 'window-2');
  assert.equal(associated?.sourceName, 'Back Door');
});

test('acknowledges durable alerts and suppresses retry duplicates', async (t) => {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  wss.on('connection', handleConnection);
  await new Promise<void>((resolve) => wss.once('listening', resolve));
  const address = wss.address();
  assert.ok(address && typeof address === 'object');

  const detector = await connect(address.port);
  const receiver = await connect(address.port);
  const foreign = await connect(address.port);
  t.after(() => { detector.terminate(); receiver.terminate(); foreign.terminate(); wss.close(); });

  const foreignAuth = waitForMessage(foreign, msg => msg.type === 'auth-result');
  foreign.send(JSON.stringify(fixtures.auth('foreign-channel-receiver', 'console', false)));
  assert.deepEqual(await foreignAuth, { type: 'auth-result', success: false, reason: 'invalid session' });

  const detectorAuth = waitForMessage(detector, msg => msg.type === 'auth-result');
  detector.send(JSON.stringify(fixtures.auth('alert-retry-detector', 'detector')));
  assert.equal((await detectorAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify(fixtures.auth('alert-retry-receiver', 'console')));
  assert.equal((await receiverAuth).success, true);

  const sourceAck = waitForMessage(detector, msg => msg.type === 'heartbeat-ack');
  detector.send(JSON.stringify({ type: 'heartbeat', isMonitoring: true, isReady: true, sources: [{ sourceId: 'front', sourceName: 'Front', isMonitoring: true, isReady: true, modelKey: '' }] }));
  await sourceAck;
  const alertId = crypto.randomUUID();
  const alert = {
    type: 'alert', alertId, eventKind: 'visual-detection', summary: 'Person detected', expiresAt: new Date(Date.now() + 30_000).toISOString(), deviceId: 'spoofed', deviceName: 'Spoofed',
    sourceId: 'front', sourceName: 'Front', timestamp: new Date().toISOString(),
    detections: [{ label: 'person', confidence: 0.9, bbox: { x: 1, y: 2, w: 3, h: 4 } }],
  };
  const firstDelivery = waitForMessage(receiver, msg => msg.type === 'alert' && msg.alertId === alertId);
  const firstAck = waitForMessage(detector, msg => msg.type === 'alert-ack' && msg.alertId === alertId);
  detector.send(JSON.stringify(alert));
  assert.equal((await firstDelivery).deviceId, fixtures.id('alert-retry-detector'));
  const stored = await firstAck;
  assert.equal(stored.type, 'alert-ack');
  assert.equal(stored.alertId, alertId);
  assert.equal(stored.accepted, true);
  assert.equal(stored.duplicate, false);
  assert.equal(stored.reason, 'stored');
  assert.match(stored.serverReceivedAt, /^\d{4}-\d{2}-\d{2}T/);

  const noDuplicateDelivery = expectNoMessage(receiver, msg => msg.type === 'alert' && msg.alertId === alertId);
  const duplicateAck = waitForMessage(detector, msg => msg.type === 'alert-ack' && msg.alertId === alertId);
  detector.send(JSON.stringify(alert));
  const duplicate = await duplicateAck;
  assert.equal(duplicate.accepted, true);
  assert.equal(duplicate.duplicate, true);
  assert.equal(duplicate.reason, 'duplicate');
  await noDuplicateDelivery;

  const conflictAck = waitForMessage(detector, msg => msg.type === 'alert-ack' && msg.alertId === alertId);
  detector.send(JSON.stringify({ ...alert, timestamp: new Date(Date.now() + 1000).toISOString() }));
  const conflict = await conflictAck;
  assert.equal(conflict.accepted, false);
  assert.equal(conflict.reason, 'alert-id-conflict');
});

function waitForMessage(ws: WebSocket, predicate: (message: any) => boolean, waitMs = 3000): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      reject(new Error('timed out waiting for WebSocket message'));
    }, waitMs);
    const onMessage = (raw: WebSocket.RawData) => {
      const message = JSON.parse(raw.toString());
      if (!predicate(message)) return;
      clearTimeout(timer);
      ws.off('message', onMessage);
      resolve(message);
    };
    ws.on('message', onMessage);
  });
}

function expectNoMessage(ws: WebSocket, predicate: (message: any) => boolean, waitMs = 250): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      resolve();
    }, waitMs);
    const onMessage = (raw: WebSocket.RawData) => {
      const message = JSON.parse(raw.toString());
      if (!predicate(message)) return;
      clearTimeout(timer);
      ws.off('message', onMessage);
      reject(new Error(`unexpected message: ${raw.toString()}`));
    };
    ws.on('message', onMessage);
  });
}

async function connect(port: number): Promise<WebSocket> {
  await fixtures.ready;
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  await new Promise<void>((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
  return ws;
}

test('correlates detector completion with the requesting receiver', async (t) => {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  wss.on('connection', handleConnection);
  await new Promise<void>((resolve) => wss.once('listening', resolve));
  const address = wss.address();
  assert.ok(address && typeof address === 'object');

  const detector = await connect(address.port);
  const receiver = await connect(address.port);
  const otherReceiver = await connect(address.port);
  t.after(() => {
    detector.terminate();
    receiver.terminate();
    otherReceiver.terminate();
    wss.close();
  });

  const detectorAuth = waitForMessage(detector, msg => msg.type === 'auth-result');
  detector.send(JSON.stringify(fixtures.auth('detector-control-test', 'detector')));
  assert.equal((await detectorAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify(fixtures.auth('receiver-control-test', 'console')));
  assert.equal((await receiverAuth).success, true);

  const otherReceiverAuth = waitForMessage(otherReceiver, msg => msg.type === 'auth-result');
  otherReceiver.send(JSON.stringify(fixtures.auth('other-receiver-control-test', 'console')));
  assert.equal((await otherReceiverAuth).success, true);

  const capabilityListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === fixtures.id('detector-control-test') && device.capabilities?.includes('request-correlation')));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: fixtures.id('detector-control-test'), deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'config-control', 'screenshot-on-demand', 'monitor-control', 'request-correlation', 'source-control'],
    components: { detectorApp: 'running', invalidComponent: 'invented-state' },
    sources: [
      { sourceId: 'front', sourceName: 'Front Door', isMonitoring: true, isReady: true, modelKey: 'yolo26n_320', actualFps: 3.2,
        activeBackend: 'Cpu', performanceWarning: '当前运行路数超过容量提示值',
        cooldown: 999, confidence: 0.7, targets: 'person,car', targetSamplingRate: 9 },
      { sourceId: 'side', sourceName: 'Side Door', isMonitoring: true, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 'garage', sourceName: 'Garage', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 'fourth', sourceName: 'Fourth Source', isMonitoring: true, isReady: false, modelKey: 'yolo26n_640', error: 'window missing' },
    ],
  }));
  const capabilityDevice = (await capabilityListPromise).devices
    .find((device: any) => device.deviceId === fixtures.id('detector-control-test'));
  assert.deepEqual(capabilityDevice.capabilities, ['monitor-control', 'config-control', 'screenshot-on-demand', 'request-correlation', 'source-control']);
  assert.deepEqual(capabilityDevice.components, { detectorApp: 'running' });
  // 接收端必须能解释“来源为什么只有这些”，因此上限与超限状态都要下发。
  assert.equal(capabilityDevice.maxSources, 4);
  assert.equal(capabilityDevice.sourceLimitExceeded, false);
  assert.deepEqual(capabilityDevice.sources, [
    { sourceId: 'front', sourceName: 'Front Door', isMonitoring: true, isReady: true, modelKey: 'yolo26n_320', actualFps: 3.2,
      activeBackend: 'Cpu', performanceWarning: '当前运行路数超过容量提示值',
      cooldown: 300, confidence: 0.7, targets: 'person,car', targetSamplingRate: 5 },
    { sourceId: 'side', sourceName: 'Side Door', isMonitoring: true, isReady: true, modelKey: 'yolo26n_320' },
    { sourceId: 'garage', sourceName: 'Garage', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
    { sourceId: 'fourth', sourceName: 'Fourth Source', isMonitoring: true, isReady: false, modelKey: 'yolo26n_640', error: 'window missing' },
  ]);

  const requestId = 'request-12345678';
  const relayPromise = waitForMessage(detector, msg => msg.type === 'command');
  const forwardedPromise = waitForMessage(receiver, msg => msg.type === 'command-ack' && msg.phase === 'forwarded');
  receiver.send(JSON.stringify({
    type: 'command', requestId, targetDeviceId: fixtures.id('detector-control-test'), command: 'pause',
  }));

  const relay = await relayPromise;
  assert.equal(relay.requestId, requestId);
  assert.equal((await forwardedPromise).requestId, requestId);

  const completedPromise = waitForMessage(receiver, msg => msg.type === 'command-ack' && msg.phase === 'completed');
  detector.send(JSON.stringify({
    type: 'command-ack', phase: 'completed', requestId, targetDeviceId: fixtures.id('detector-control-test'),
    command: 'pause', success: true, reason: '监控已停止',
  }));

  const completed = await completedPromise;
  assert.equal(completed.requestId, requestId);
  assert.equal(completed.success, true);
  assert.equal(completed.reason, '监控已停止');

  const sourceRequestId = 'source-request-12345678';
  const sourceRelayPromise = waitForMessage(detector, msg => msg.type === 'command' && msg.requestId === sourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: sourceRequestId, targetDeviceId: fixtures.id('detector-control-test'), targetSourceId: 'front', command: 'pause',
  }));
  const sourceRelay = await sourceRelayPromise;
  assert.equal(sourceRelay.targetSourceId, 'front');
  const sourceCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === sourceRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', phase: 'completed', requestId: sourceRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'wrong-source', command: 'pause', success: true, reason: '错误来源回执',
  }));
  detector.send(JSON.stringify({
    type: 'command-ack', phase: 'completed', requestId: sourceRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'front', command: 'pause', success: true, reason: '正确来源回执',
  }));
  const sourceCompleted = await sourceCompletedPromise;
  assert.equal(sourceCompleted.targetSourceId, 'front');
  assert.equal(sourceCompleted.reason, '正确来源回执');

  const fourthRequestId = 'fourth-source-request-1234';
  const fourthRelayPromise = waitForMessage(detector, msg => msg.type === 'command' && msg.requestId === fourthRequestId);
  const fourthForwardedPromise = waitForMessage(receiver, msg => msg.type === 'command-ack' && msg.phase === 'forwarded' && msg.requestId === fourthRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: fourthRequestId, targetDeviceId: fixtures.id('detector-control-test'), targetSourceId: 'fourth', command: 'resume',
  }));
  assert.equal((await fourthRelayPromise).targetSourceId, 'fourth');
  assert.equal((await fourthForwardedPromise).success, true);

  const isolatedCompletionPromise = expectNoMessage(otherReceiver, msg =>
    msg.type === 'command-ack' && msg.requestId === fourthRequestId);
  const fourthCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === fourthRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', phase: 'completed', requestId: fourthRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'fourth', command: 'resume', success: true, reason: '第四路已启动',
  }));
  assert.equal((await fourthCompletedPromise).reason, '第四路已启动');
  await isolatedCompletionPromise;

  const unknownSourceRequestId = 'unknown-source-request-1234';
  const unknownSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownSourceRequestId, targetDeviceId: fixtures.id('detector-control-test'), targetSourceId: 'missing', command: 'pause',
  }));
  const unknownSourceAck = await unknownSourceAckPromise;
  assert.equal(unknownSourceAck.success, false);
  assert.equal(unknownSourceAck.reason, '目标来源不存在');

  const reusedSourceRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === unknownSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownSourceRequestId, targetDeviceId: fixtures.id('detector-control-test'), targetSourceId: 'fourth', command: 'pause',
  }));
  assert.equal((await reusedSourceRelayPromise).targetSourceId, 'fourth');

  const unknownCommandRequestId = 'unknown-command-request-1234';
  const unknownCommandAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownCommandRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownCommandRequestId, targetDeviceId: fixtures.id('detector-control-test'), command: 'run-shell',
  }));
  const unknownCommandAck = await unknownCommandAckPromise;
  assert.equal(unknownCommandAck.success, false);
  assert.equal(unknownCommandAck.reason, '无效的命令');

  const reusedCommandRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === unknownCommandRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownCommandRequestId, targetDeviceId: fixtures.id('detector-control-test'), targetSourceId: 'fourth', command: 'pause',
  }));
  assert.equal((await reusedCommandRelayPromise).command, 'pause');

  const lifecycleToDetectorRequestId = 'lifecycle-to-detector-1234';
  const lifecycleToDetectorAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.requestId === lifecycleToDetectorRequestId);
  const lifecycleNotRelayedPromise = expectNoMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === lifecycleToDetectorRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: lifecycleToDetectorRequestId,
    targetDeviceId: fixtures.id('detector-control-test'), command: 'open-detector',
  }));
  assert.equal((await lifecycleToDetectorAckPromise).reason, '驻留组件离线');
  await lifecycleNotRelayedPromise;

  const configRequestId = 'source-config-12345678';
  const configRelayPromise = waitForMessage(detector, msg => msg.type === 'set-config' && msg.requestId === configRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: configRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'front', key: 'confidence', value: '0.7',
  }));
  const configRelay = await configRelayPromise;
  assert.equal(configRelay.targetSourceId, 'front');
  assert.equal(configRelay.value, '0.7');

  const configCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === configRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', phase: 'completed', requestId: configRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'front', command: 'set-config:confidence', success: true, reason: '已更新',
  }));
  const configCompleted = await configCompletedPromise;
  assert.equal(configCompleted.targetSourceId, 'front');
  assert.equal(configCompleted.success, true);

  const unknownConfigSourceRequestId = 'unknown-config-source-1234';
  const unknownConfigSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownConfigSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: unknownConfigSourceRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'missing', key: 'confidence', value: '0.65',
  }));
  const unknownConfigSourceAck = await unknownConfigSourceAckPromise;
  assert.equal(unknownConfigSourceAck.success, false);
  assert.equal(unknownConfigSourceAck.reason, '目标来源不存在');

  const reusedConfigRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'set-config' && msg.requestId === unknownConfigSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: unknownConfigSourceRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'fourth', key: 'confidence', value: '0.65',
  }));
  assert.equal((await reusedConfigRelayPromise).targetSourceId, 'fourth');

  const replayAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === configRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: configRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'front', key: 'confidence', value: '0.8',
  }));
  const replayAck = await replayAckPromise;
  assert.equal(replayAck.success, false);
  assert.equal(replayAck.reason, 'requestId 重复');

  const reusableRequestId = 'invalid-source-reuse-1234';
  const invalidSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === reusableRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: reusableRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: '../bad', key: 'confidence', value: '0.6',
  }));
  const invalidSourceAck = await invalidSourceAckPromise;
  assert.equal(invalidSourceAck.success, false);
  assert.equal(invalidSourceAck.reason, '无效的 targetSourceId');

  const reusedRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'set-config' && msg.requestId === reusableRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: reusableRequestId, targetDeviceId: fixtures.id('detector-control-test'),
    targetSourceId: 'front', key: 'confidence', value: '0.6',
  }));
  const reusedRelay = await reusedRelayPromise;
  assert.equal(reusedRelay.targetSourceId, 'front');

  // 超限心跳：sources 整组被拒并保留旧快照，但接收端必须看到超限状态，而不是静默的旧来源。
  const overLimitListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === fixtures.id('detector-control-test') && device.sourceLimitExceeded === true));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: fixtures.id('detector-control-test'), deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'config-control', 'screenshot-on-demand', 'monitor-control', 'request-correlation', 'source-control'],
    sources: [
      { sourceId: 's1', sourceName: 'S1', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's2', sourceName: 'S2', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's3', sourceName: 'S3', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's4', sourceName: 'S4', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's5', sourceName: 'S5', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
    ],
  }));
  const overLimitDevice = (await overLimitListPromise).devices
    .find((device: any) => device.deviceId === fixtures.id('detector-control-test'));
  assert.equal(overLimitDevice.maxSources, 4);
  assert.equal(overLimitDevice.sourceLimitExceeded, true);
  assert.deepEqual(overLimitDevice.sources.map((source: any) => source.sourceId),
    ['front', 'side', 'garage', 'fourth']);

  // 正常心跳必须清除超限状态，否则接收端会一直显示过期的告警。
  const recoveredListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === fixtures.id('detector-control-test') && device.sourceLimitExceeded === false));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: fixtures.id('detector-control-test'), deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'config-control', 'screenshot-on-demand', 'monitor-control', 'request-correlation', 'source-control'],
    sources: [{ sourceId: 'front', sourceName: 'Front Door', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' }],
  }));
  assert.equal((await recoveredListPromise).devices
    .find((device: any) => device.deviceId === fixtures.id('detector-control-test')).sources.length, 1);
});

test('keeps resident identity separate and routes lifecycle commands only to it', async (t) => {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  wss.on('connection', handleConnection);
  await new Promise<void>((resolve) => wss.once('listening', resolve));
  const address = wss.address();
  assert.ok(address && typeof address === 'object');
  const resident = await connect(address.port);
  const receiver = await connect(address.port);
  t.after(() => { resident.terminate(); receiver.terminate(); wss.close(); });

  const residentAuth = waitForMessage(resident, msg => msg.type === 'auth-result');
  resident.send(JSON.stringify(fixtures.auth('resident-control-test', 'lifecycle')));
  assert.equal((await residentAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify(fixtures.auth('resident-receiver-test', 'console')));
  assert.equal((await receiverAuth).success, true);

  const listPromise = waitForMessage(receiver, msg => msg.type === 'device-list' &&
    msg.devices?.some((d: any) => d.deviceId === fixtures.id('resident-control-test') && d.components?.detectorApp === 'running'));
  resident.send(JSON.stringify({
    type: 'resident-heartbeat', components: { resident: 'running', detectorApp: 'running' },
  }));
  const listed = (await listPromise).devices.find((d: any) => d.deviceId === fixtures.id('resident-control-test'));
  assert.deepEqual(listed.capabilities, ['app-lifecycle-control']);
  assert.equal(listed.isMonitoring, false);

  const businessRequestId = 'business-to-resident-1234';
  const businessAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.requestId === businessRequestId);
  const businessNotRelayedPromise = expectNoMessage(resident, msg =>
    msg.type === 'command' && msg.requestId === businessRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: businessRequestId,
    targetDeviceId: fixtures.id('resident-control-test'), command: 'pause',
  }));
  // 只有驻留在线的设备仍算在线，业务命令必须给出比“设备离线”更准确的原因。
  assert.equal((await businessAckPromise).reason, '该设备当前没有检测端在线');
  await businessNotRelayedPromise;

  const requestId = 'resident-request-12345678';
  const relayPromise = waitForMessage(resident, msg => msg.type === 'command' && msg.requestId === requestId);
  receiver.send(JSON.stringify({ type: 'command', requestId, targetDeviceId: fixtures.id('resident-control-test'), command: 'close-detector' }));
  assert.equal((await relayPromise).command, 'close-detector');
});

test('keeps the detector custom name after only the Windows resident remains online', async (t) => {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  wss.on('connection', handleConnection);
  await new Promise<void>((resolve) => wss.once('listening', resolve));
  const address = wss.address();
  assert.ok(address && typeof address === 'object');

  const resident = await connect(address.port);
  const detector = await connect(address.port);
  const receiver = await connect(address.port);
  t.after(() => { resident.terminate(); detector.terminate(); receiver.terminate(); wss.close(); });

  const residentAuth = waitForMessage(resident, msg => msg.type === 'auth-result');
  resident.send(JSON.stringify(fixtures.auth('resident-name-test', 'lifecycle')));
  assert.equal((await residentAuth).success, true);

  const detectorAuth = waitForMessage(detector, msg => msg.type === 'auth-result');
  detector.send(JSON.stringify(fixtures.auth('resident-name-test', 'detector')));
  assert.equal((await detectorAuth).success, true);

  const renamedList = waitForMessage(receiver, msg => msg.type === 'device-list' &&
    msg.devices?.some((d: any) => d.deviceId === fixtures.id('resident-name-test') && d.deviceName === '门口检测端'));
  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify(fixtures.auth('resident-name-receiver', 'console')));
  assert.equal((await receiverAuth).success, true);

  accountStore.rename(fixtures.accountId, fixtures.id('resident-name-test'), '门口检测端');
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: fixtures.id('resident-name-test'), deviceName: '旧客户端名称',
    isMonitoring: false, isReady: true,
  }));
  await renamedList;

  const residentOnlyList = waitForMessage(receiver, msg => msg.type === 'device-list' &&
    msg.devices?.some((d: any) => d.deviceId === fixtures.id('resident-name-test')
      && d.deviceName === '门口检测端' && d.components?.detectorApp === 'stopped'), 12_000);
  detector.close();
  const listed = (await residentOnlyList).devices.find((d: any) => d.deviceId === fixtures.id('resident-name-test'));
  assert.equal(listed.deviceName, '门口检测端');
  assert.deepEqual(listed.capabilities, ['app-lifecycle-control']);
});
