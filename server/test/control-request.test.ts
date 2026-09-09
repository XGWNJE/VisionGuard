import assert from 'node:assert/strict';
import test from 'node:test';
import WebSocket, { WebSocketServer } from 'ws';

process.env.API_KEY = 'control-request-test-key';

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
  t.after(() => {
    detector.terminate();
    receiver.terminate();
    wss.close();
  });

  const detectorAuth = waitForMessage(detector, msg => msg.type === 'auth-result');
  detector.send(JSON.stringify({
    type: 'auth', apiKey: process.env.API_KEY, role: 'android-detector',
    deviceId: 'detector-control-test', deviceName: 'Detector', version: '4.4.4',
  }));
  assert.equal((await detectorAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify({
    type: 'auth', apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'receiver-control-test', deviceName: 'Receiver', version: '4.4.4',
  }));
  assert.equal((await receiverAuth).success, true);

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
        cooldown: 999, confidence: 0.7, targets: 'person,car', targetSamplingRate: 9 },
      { sourceId: '../bad', sourceName: 'Bad', isMonitoring: true, isReady: true, modelKey: 'bad key' },
    ],
  }));
  const capabilityDevice = (await capabilityListPromise).devices
    .find((device: any) => device.deviceId === 'detector-control-test');
  assert.deepEqual(capabilityDevice.capabilities, ['monitor-control', 'request-correlation', 'source-control']);
  assert.deepEqual(capabilityDevice.components, { detectorApp: 'running' });
  assert.deepEqual(capabilityDevice.sources, [
    { sourceId: 'front', sourceName: 'Front Door', isMonitoring: true, isReady: true, modelKey: 'yolo26n_320', actualFps: 3.2,
      cooldown: 300, confidence: 0.7, targets: 'person,car', targetSamplingRate: 5 },
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
    type: 'auth', apiKey: process.env.API_KEY, role: 'windows-resident',
    deviceId: 'resident-control-test', deviceName: 'Resident PC',
  }));
  assert.equal((await residentAuth).success, true);

  const receiverAuth = waitForMessage(receiver, msg => msg.type === 'auth-result');
  receiver.send(JSON.stringify({
    type: 'auth', apiKey: process.env.API_KEY, role: 'android',
    deviceId: 'resident-receiver-test', deviceName: 'Receiver',
  }));
  assert.equal((await receiverAuth).success, true);

  const listPromise = waitForMessage(receiver, msg => msg.type === 'device-list' &&
    msg.devices?.some((d: any) => d.deviceId === 'resident-control-test' && d.components?.wpfApp === 'running'));
  resident.send(JSON.stringify({
    type: 'resident-heartbeat', components: { resident: 'running', wpfApp: 'running', winFormsApp: 'stopped' },
  }));
  const listed = (await listPromise).devices.find((d: any) => d.deviceId === 'resident-control-test');
  assert.deepEqual(listed.capabilities, ['app-lifecycle-control']);
  assert.equal(listed.isMonitoring, false);

  const requestId = 'resident-request-12345678';
  const relayPromise = waitForMessage(resident, msg => msg.type === 'command' && msg.requestId === requestId);
  receiver.send(JSON.stringify({ type: 'command', requestId, targetDeviceId: 'resident-control-test', command: 'close-wpf' }));
  assert.equal((await relayPromise).command, 'close-wpf');
});
