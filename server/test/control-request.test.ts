import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

process.env.API_KEY = 'control-request-test-key';
process.env.VISIONGUARD_CHANNEL = 'test-vnext';
// 显式钉住 4 路上限：本文件要覆盖的是「超限心跳整组被拒 + 接收端看到 sourceLimitExceeded」，
// 不能跟着 config 默认值走（默认值已改为 16，否则 5 路心跳不再超限，这条覆盖会静默失效）。
process.env.MAX_SOURCES_PER_DETECTOR = '4';
const alertStorePath = path.join(os.tmpdir(), `visionguard-alert-store-${process.pid}.json`);
process.env.ALERT_STORE_PATH = alertStorePath;
test.after(() => { try { fs.rmSync(alertStorePath, { force: true }); } catch {} });
test.after(() => { try { fs.rmSync(`${alertStorePath}.tmp`, { force: true }); } catch {} });

const { associateScreenshotPayload, handleConnection } = require('../src/services/ConnectionManager') as typeof import('../src/services/ConnectionManager');

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
  foreign.send(JSON.stringify({
    type: 'auth', channel: 'legacy-live', apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'foreign-channel-receiver', deviceName: 'Foreign Receiver',
  }));
  assert.deepEqual(await foreignAuth, { type: 'auth-result', success: false, reason: 'channel mismatch' });

  const detectorAuth = waitForMessage(detector, msg => msg.type === 'auth-result');
  detector.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'windows',
    deviceId: 'alert-retry-detector', deviceName: 'Alert Retry Detector',
  }));
  assert.equal((await detectorAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'alert-retry-receiver', deviceName: 'Alert Retry Receiver',
  }));
  assert.equal((await receiverAuth).success, true);

  const alertId = crypto.randomUUID();
  const alert = {
    type: 'alert', alertId, deviceId: 'spoofed', deviceName: 'Spoofed',
    sourceId: 'front', sourceName: 'Front', timestamp: new Date().toISOString(),
    detections: [{ label: 'person', confidence: 0.9, bbox: { x: 1, y: 2, w: 3, h: 4 } }],
  };
  const firstDelivery = waitForMessage(receiver, msg => msg.type === 'alert' && msg.alertId === alertId);
  const firstAck = waitForMessage(detector, msg => msg.type === 'alert-ack' && msg.alertId === alertId);
  detector.send(JSON.stringify(alert));
  assert.equal((await firstDelivery).deviceId, 'alert-retry-detector');
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

function waitForMessage(ws: WebSocket, predicate: (message: any) => boolean): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      reject(new Error('timed out waiting for WebSocket message'));
    }, 3000);
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
  detector.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'android-detector',
    deviceId: 'detector-control-test', deviceName: 'Detector', version: '4.4.4',
  }));
  assert.equal((await detectorAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'receiver-control-test', deviceName: 'Receiver', version: '4.4.4',
  }));
  assert.equal((await receiverAuth).success, true);

  const otherReceiverAuth = waitForMessage(otherReceiver, msg => msg.type === 'auth-result');
  otherReceiver.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'other-receiver-control-test', deviceName: 'Other Receiver', version: '4.4.4',
  }));
  assert.equal((await otherReceiverAuth).success, true);

  const capabilityListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === 'detector-control-test' && device.capabilities?.includes('request-correlation')));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: 'detector-control-test', deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'request-correlation', 'source-control'],
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
    .find((device: any) => device.deviceId === 'detector-control-test');
  assert.deepEqual(capabilityDevice.capabilities, ['monitor-control', 'request-correlation', 'source-control']);
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
    type: 'command', requestId, targetDeviceId: 'detector-control-test', command: 'pause',
  }));

  const relay = await relayPromise;
  assert.equal(relay.requestId, requestId);
  assert.equal((await forwardedPromise).requestId, requestId);

  const completedPromise = waitForMessage(receiver, msg => msg.type === 'command-ack' && msg.phase === 'completed');
  detector.send(JSON.stringify({
    type: 'command-ack', requestId, targetDeviceId: 'detector-control-test',
    command: 'pause', success: true, reason: '监控已停止',
  }));

  const completed = await completedPromise;
  assert.equal(completed.requestId, requestId);
  assert.equal(completed.success, true);
  assert.equal(completed.reason, '监控已停止');

  const sourceRequestId = 'source-request-12345678';
  const sourceRelayPromise = waitForMessage(detector, msg => msg.type === 'command' && msg.requestId === sourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: sourceRequestId, targetDeviceId: 'detector-control-test', targetSourceId: 'front', command: 'pause',
  }));
  const sourceRelay = await sourceRelayPromise;
  assert.equal(sourceRelay.targetSourceId, 'front');
  const sourceCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === sourceRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', requestId: sourceRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'wrong-source', command: 'pause', success: true, reason: '错误来源回执',
  }));
  detector.send(JSON.stringify({
    type: 'command-ack', requestId: sourceRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'front', command: 'pause', success: true, reason: '正确来源回执',
  }));
  const sourceCompleted = await sourceCompletedPromise;
  assert.equal(sourceCompleted.targetSourceId, 'front');
  assert.equal(sourceCompleted.reason, '正确来源回执');

  const fourthRequestId = 'fourth-source-request-1234';
  const fourthRelayPromise = waitForMessage(detector, msg => msg.type === 'command' && msg.requestId === fourthRequestId);
  const fourthForwardedPromise = waitForMessage(receiver, msg => msg.type === 'command-ack' && msg.phase === 'forwarded' && msg.requestId === fourthRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: fourthRequestId, targetDeviceId: 'detector-control-test', targetSourceId: 'fourth', command: 'resume',
  }));
  assert.equal((await fourthRelayPromise).targetSourceId, 'fourth');
  assert.equal((await fourthForwardedPromise).success, true);

  const isolatedCompletionPromise = expectNoMessage(otherReceiver, msg =>
    msg.type === 'command-ack' && msg.requestId === fourthRequestId);
  const fourthCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === fourthRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', requestId: fourthRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'fourth', command: 'resume', success: true, reason: '第四路已启动',
  }));
  assert.equal((await fourthCompletedPromise).reason, '第四路已启动');
  await isolatedCompletionPromise;

  const unknownSourceRequestId = 'unknown-source-request-1234';
  const unknownSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownSourceRequestId, targetDeviceId: 'detector-control-test', targetSourceId: 'missing', command: 'pause',
  }));
  const unknownSourceAck = await unknownSourceAckPromise;
  assert.equal(unknownSourceAck.success, false);
  assert.equal(unknownSourceAck.reason, '目标来源不存在');

  const reusedSourceRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === unknownSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownSourceRequestId, targetDeviceId: 'detector-control-test', targetSourceId: 'fourth', command: 'pause',
  }));
  assert.equal((await reusedSourceRelayPromise).targetSourceId, 'fourth');

  const unknownCommandRequestId = 'unknown-command-request-1234';
  const unknownCommandAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownCommandRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownCommandRequestId, targetDeviceId: 'detector-control-test', command: 'run-shell',
  }));
  const unknownCommandAck = await unknownCommandAckPromise;
  assert.equal(unknownCommandAck.success, false);
  assert.equal(unknownCommandAck.reason, '无效的命令');

  const reusedCommandRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === unknownCommandRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: unknownCommandRequestId, targetDeviceId: 'detector-control-test', targetSourceId: 'fourth', command: 'pause',
  }));
  assert.equal((await reusedCommandRelayPromise).command, 'pause');

  const lifecycleToDetectorRequestId = 'lifecycle-to-detector-1234';
  const lifecycleToDetectorAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.requestId === lifecycleToDetectorRequestId);
  const lifecycleNotRelayedPromise = expectNoMessage(detector, msg =>
    msg.type === 'command' && msg.requestId === lifecycleToDetectorRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: lifecycleToDetectorRequestId,
    targetDeviceId: 'detector-control-test', command: 'open-detector',
  }));
  assert.equal((await lifecycleToDetectorAckPromise).reason, '驻留组件离线');
  await lifecycleNotRelayedPromise;

  const configRequestId = 'source-config-12345678';
  const configRelayPromise = waitForMessage(detector, msg => msg.type === 'set-config' && msg.requestId === configRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: configRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'front', key: 'confidence', value: '0.7',
  }));
  const configRelay = await configRelayPromise;
  assert.equal(configRelay.targetSourceId, 'front');
  assert.equal(configRelay.value, '0.7');

  const configCompletedPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === configRequestId);
  detector.send(JSON.stringify({
    type: 'command-ack', requestId: configRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'front', command: 'set-config:confidence', success: true, reason: '已更新',
  }));
  const configCompleted = await configCompletedPromise;
  assert.equal(configCompleted.targetSourceId, 'front');
  assert.equal(configCompleted.success, true);

  const unknownConfigSourceRequestId = 'unknown-config-source-1234';
  const unknownConfigSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === unknownConfigSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: unknownConfigSourceRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'missing', key: 'confidence', value: '0.65',
  }));
  const unknownConfigSourceAck = await unknownConfigSourceAckPromise;
  assert.equal(unknownConfigSourceAck.success, false);
  assert.equal(unknownConfigSourceAck.reason, '目标来源不存在');

  const reusedConfigRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'set-config' && msg.requestId === unknownConfigSourceRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: unknownConfigSourceRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'fourth', key: 'confidence', value: '0.65',
  }));
  assert.equal((await reusedConfigRelayPromise).targetSourceId, 'fourth');

  const replayAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === configRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: configRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'front', key: 'confidence', value: '0.8',
  }));
  const replayAck = await replayAckPromise;
  assert.equal(replayAck.success, false);
  assert.equal(replayAck.reason, 'requestId 重复');

  const reusableRequestId = 'invalid-source-reuse-1234';
  const invalidSourceAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.phase === 'completed' && msg.requestId === reusableRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: reusableRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: '../bad', key: 'confidence', value: '0.6',
  }));
  const invalidSourceAck = await invalidSourceAckPromise;
  assert.equal(invalidSourceAck.success, false);
  assert.equal(invalidSourceAck.reason, '无效的 targetSourceId');

  const reusedRelayPromise = waitForMessage(detector, msg =>
    msg.type === 'set-config' && msg.requestId === reusableRequestId);
  receiver.send(JSON.stringify({
    type: 'set-config', requestId: reusableRequestId, targetDeviceId: 'detector-control-test',
    targetSourceId: 'front', key: 'confidence', value: '0.6',
  }));
  const reusedRelay = await reusedRelayPromise;
  assert.equal(reusedRelay.targetSourceId, 'front');

  // 超限心跳：sources 整组被拒并保留旧快照，但接收端必须看到超限状态，而不是静默的旧来源。
  const overLimitListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === 'detector-control-test' && device.sourceLimitExceeded === true));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: 'detector-control-test', deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'request-correlation', 'source-control'],
    sources: [
      { sourceId: 's1', sourceName: 'S1', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's2', sourceName: 'S2', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's3', sourceName: 'S3', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's4', sourceName: 'S4', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
      { sourceId: 's5', sourceName: 'S5', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' },
    ],
  }));
  const overLimitDevice = (await overLimitListPromise).devices
    .find((device: any) => device.deviceId === 'detector-control-test');
  assert.equal(overLimitDevice.maxSources, 4);
  assert.equal(overLimitDevice.sourceLimitExceeded, true);
  assert.deepEqual(overLimitDevice.sources.map((source: any) => source.sourceId),
    ['front', 'side', 'garage', 'fourth']);

  // 正常心跳必须清除超限状态，否则接收端会一直显示过期的告警。
  const recoveredListPromise = waitForMessage(receiver, msg =>
    msg.type === 'device-list' && msg.devices?.some((device: any) =>
      device.deviceId === 'detector-control-test' && device.sourceLimitExceeded === false));
  detector.send(JSON.stringify({
    type: 'heartbeat', deviceId: 'detector-control-test', deviceName: 'Detector',
    isMonitoring: false, isReady: true,
    capabilities: ['monitor-control', 'request-correlation', 'source-control'],
    sources: [{ sourceId: 'front', sourceName: 'Front Door', isMonitoring: false, isReady: true, modelKey: 'yolo26n_320' }],
  }));
  assert.equal((await recoveredListPromise).devices
    .find((device: any) => device.deviceId === 'detector-control-test').sources.length, 1);
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
  resident.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'windows-resident',
    deviceId: 'resident-control-test', deviceName: 'Resident PC',
  }));
  assert.equal((await residentAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify({
    type: 'auth', channel: process.env.VISIONGUARD_CHANNEL, apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'resident-receiver-test', deviceName: 'Receiver',
  }));
  assert.equal((await receiverAuth).success, true);

  const listPromise = waitForMessage(receiver, msg => msg.type === 'device-list' &&
    msg.devices?.some((d: any) => d.deviceId === 'resident-control-test' && d.components?.detectorApp === 'running'));
  resident.send(JSON.stringify({
    type: 'resident-heartbeat', components: { resident: 'running', detectorApp: 'running' },
  }));
  const listed = (await listPromise).devices.find((d: any) => d.deviceId === 'resident-control-test');
  assert.deepEqual(listed.capabilities, ['app-lifecycle-control']);
  assert.equal(listed.isMonitoring, false);

  const businessRequestId = 'business-to-resident-1234';
  const businessAckPromise = waitForMessage(receiver, msg =>
    msg.type === 'command-ack' && msg.requestId === businessRequestId);
  const businessNotRelayedPromise = expectNoMessage(resident, msg =>
    msg.type === 'command' && msg.requestId === businessRequestId);
  receiver.send(JSON.stringify({
    type: 'command', requestId: businessRequestId,
    targetDeviceId: 'resident-control-test', command: 'pause',
  }));
  // 只有驻留在线的设备仍算在线，业务命令必须给出比“设备离线”更准确的原因。
  assert.equal((await businessAckPromise).reason, '该设备当前没有检测端在线');
  await businessNotRelayedPromise;

  const requestId = 'resident-request-12345678';
  const relayPromise = waitForMessage(resident, msg => msg.type === 'command' && msg.requestId === requestId);
  receiver.send(JSON.stringify({ type: 'command', requestId, targetDeviceId: 'resident-control-test', command: 'close-detector' }));
  assert.equal((await relayPromise).command, 'close-detector');
});
