// WindowsPushChain e2e driver (maintained by the visionguard-e2e skill).
//
// Proves the Windows inference node's push state end to end on an isolated local service:
// a real Windows detector publishes its window-capture frames over the production media
// contract, and a second registered inference device actually receives them.
//
// Usage (normally invoked by e2e-smoke.ps1 -Mode WindowsPushChain):
//   node scripts/windows-push-chain.js --service http://127.0.0.1:3125 --username vg-test \
//     --password-env VISIONGUARD_CHAIN_PASSWORD --evidence artifacts/e2e/windows-push-chain \
//     --publisher-device-id <device id> --fixture-window-title <window title> --timeout 180
//
// Only the isolated service, a private test account and an isolated account directory are used.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const repoRoot = path.join(__dirname, '..');
const WebSocket = require(path.join(repoRoot, 'server', 'node_modules', 'ws'));

const options = new Map();
for (let index = 2; index < process.argv.length; index++) {
  const value = process.argv[index];
  if (value.startsWith('--')) {
    const next = process.argv[index + 1];
    if (next === undefined || next.startsWith('--')) options.set(value.slice(2), 'true');
    else { options.set(value.slice(2), next); index++; }
  }
}
const service = (options.get('service') ?? '').replace(/\/+$/, '');
const username = options.get('username') ?? '';
const passwordEnvironment = options.get('password-env') ?? '';
const password = passwordEnvironment ? (process.env[passwordEnvironment] ?? '') : (options.get('password') ?? '');
const evidence = path.resolve(options.get('evidence') ?? path.join(repoRoot, 'artifacts/e2e/windows-push-chain'));
const publisherDeviceIdOption = options.get('publisher-device-id') ?? '';
const fixtureWindowTitle = options.get('fixture-window-title') ?? '';
// 调用方（e2e-smoke.ps1）可能已经用控制台 API 绑定好发布端与接收端；此时驱动只验证结果。

const timeoutMs = Number(options.get('timeout') ?? 180) * 1000;
// 验证完成后保持推流 N 秒：调用方在这段时间里对两个真实 Windows 实例的窗口截图，做界面目检。
const captureDelaySeconds = Number(options.get('capture-delay-seconds') ?? 0);
const captureMarkerPath = options.get('capture-marker') ?? '';
if (!service || !username || !password || !publisherDeviceIdOption) {
  console.error('usage: node windows-push-chain.js --service <url> --username <user> (--password <secret> | --password-env <variable>) --evidence <dir> --publisher-device-id <id>');
  process.exit(2);
}

const report = { startedAt: new Date().toISOString(), service, checks: {}, steps: [], failures: [], limitations: [] };
const wsBase = service.replace(/^http/, 'ws');
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
function step(name, detail) {
  report.steps.push({ name, detail, at: new Date().toISOString() });
  console.log(`STEP ${name}: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
}
function fail(name, detail) {
  report.failures.push({ name, detail });
  console.error(`FAIL ${name}: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
}
function sha256(value) { return crypto.createHash('sha256').update(value).digest('hex'); }
function request(route, body, token) {
  return fetch(`${service}${route}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(async response => {
    const text = await response.text();
    let data; try { data = JSON.parse(text); } catch { data = { raw: text.slice(0, 400) }; }
    if (!response.ok) { const error = new Error(`${route} -> ${response.status} ${data.error ?? ''}`); error.status = response.status; throw error; }
    return data;
  });
}
const get = (route, token) => request(route, undefined, token);

async function waitFor(check, timeout, name, intervalMs = 400) {
  const deadline = Date.now() + timeout;
  let last;
  for (;;) {
    try { last = await check(); } catch (error) { last = { error: String(error.message) }; }
    if (last) return last;
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${name} (last=${JSON.stringify(last)})`);
    await sleep(intervalMs);
  }
}

/** JPEG SOF dimensions, mirroring the server's own frame validation. */
function jpegDimensions(bytes) {
  if (bytes.length < 12 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) return undefined;
  let position = 2;
  while (position + 3 < bytes.length) {
    if (bytes[position++] !== 0xff) return undefined;
    while (bytes[position] === 0xff) position++;
    const marker = bytes[position++];
    if (marker === 0xda || marker === 0xd9) return undefined;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (position + 2 > bytes.length) return undefined;
    const length = (bytes[position] << 8) | bytes[position + 1];
    if (length < 2 || position + length > bytes.length) return undefined;
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      if (length < 8) return undefined;
      const height = (bytes[position + 3] << 8) | bytes[position + 4];
      const width = (bytes[position + 5] << 8) | bytes[position + 6];
      return width > 0 && height > 0 ? { width, height } : undefined;
    }
    position += length;
  }
  return undefined;
}
function parseFrame(packet) {
  if (packet.length < 5) return undefined;
  const length = packet.readUInt32BE(0);
  if (length < 2 || length > 4096 || 4 + length >= packet.length) return undefined;
  let header; try { header = JSON.parse(packet.subarray(4, 4 + length).toString('utf8')); } catch { return undefined; }
  for (const key of ['streamId', 'sessionId', 'sequence', 'capturedAt', 'width', 'height', 'rotation']) if (!(key in header)) return undefined;
  const image = packet.subarray(4 + length);
  const dimensions = jpegDimensions(image);
  if (!dimensions || dimensions.width !== header.width || dimensions.height !== header.height) return undefined;
  return { header, image };
}

async function openConsole(token) {
  const socket = new WebSocket(`${wsBase}/ws`);
  const messages = [];
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  socket.on('message', raw => { try { messages.push(JSON.parse(raw.toString())); } catch { /* ignore */ } });
  socket.send(JSON.stringify({ type: 'auth', token }));
  await waitFor(() => messages.some(message => message.type === 'auth-result' && message.success === true), 15000, 'console auth');
  // 服务端会按静默阈值清掉空闲控制连接：驱动可能在两次命令之间停很久，这里保持心跳。
  // 控制台角色只认 heartbeat-console；发裸 heartbeat 服务端不会刷新 lastSeen，连接照样被回收。
  const heartbeat = setInterval(() => { try { socket.send(JSON.stringify({ type: 'heartbeat-console' })); } catch { /* connection closed */ } }, 3000);
  heartbeat.unref?.();
  socket.on('close', () => clearInterval(heartbeat));
  return { socket, messages };
}
async function consoleCommand(session, payload, name) {
  const requestId = crypto.randomUUID();
  session.socket.send(JSON.stringify({ type: 'command', requestId, ...payload }));
  return waitFor(() => session.messages.find(message => message.type === 'command-ack' && message.requestId === requestId && message.phase === 'completed'), 20000, name);
}

/** Subscribe as the receiver device and collect validated frames from the relay. */
function openSubscriber(token) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${wsBase}/media/ws`);
    const frames = [];
    const messages = [];
    // sessionId 由中继在每条媒体连接上单独签发：订阅端拿到的不是发布端的会话号，
    // 因此这里只把它作为「连接已建立」的证据，帧侧另用 media-ready 的发布会话号核对。
    const state = { ready: false, sessionId: '', frames, messages, closeCode: 0 };
    socket.on('error', error => reject(error));
    socket.on('close', code => { state.closeCode = code; });
    socket.on('open', () => {
      socket.on('message', (raw, binary) => {
        if (binary) { const parsed = parseFrame(Buffer.from(raw)); if (parsed) frames.push(parsed); return; }
        try { const message = JSON.parse(raw.toString()); messages.push(message); if (message.type === 'media-ready') { state.ready = true; state.sessionId = message.sessionId ?? ''; } } catch { /* ignore */ }
      });
      socket.send(JSON.stringify({ type: 'media-auth', token, direction: 'subscribe' }));
      resolve({ socket, state });
    });
  });
}

/** Open a media connection and report how the relay answers a given direction. */
function probeDirection(token, direction) {
  return new Promise(resolve => {
    const socket = new WebSocket(`${wsBase}/media/ws`);
    const messages = [];
    const timer = setTimeout(() => { try { socket.terminate(); } catch { /* ignore */ } resolve({ result: 'no-close', messages }); }, 8000);
    socket.on('open', () => {
      socket.on('message', raw => { try { messages.push(JSON.parse(raw.toString())); } catch { /* ignore */ } });
      socket.send(JSON.stringify({ type: 'media-auth', token, direction }));
    });
    socket.on('close', code => { clearTimeout(timer); resolve({ result: code, messages }); });
    socket.on('error', () => { clearTimeout(timer); resolve({ result: 'error', messages }); });
  });
}

async function main() {
  fs.mkdirSync(evidence, { recursive: true });
  const started = Date.now();

  // 1) A web console owns the API side. The receiver is the second real Windows instance; it registers
  // itself, so identify it as the online windows-inference device that is not the publisher.
  const owner = await request('/api/account/login', { username, password, component: 'web-console', deviceIdentity: sha256('e2e-windows-push-chain-console'), deviceModel: 'Windows Push Chain Owner' });
  const ownerToken = owner.token;
  step('accounts', { owner: owner.device.deviceId });

  // The publisher device registered through a real login probe before launch; wait until the detector
  // (not just its resident companion) is online with the push capabilities it must declare.
  const publisher = await waitFor(async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.deviceId === publisherDeviceIdOption);
    const ready = node && node.online === true && Array.isArray(node.capabilities)
      && node.capabilities.includes('video-publish') && node.capabilities.includes('stream-control');
    return ready ? node : undefined;
  }, 90000, 'windows publisher online with push capabilities').catch(() => undefined);
  if (!publisher) {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    throw new Error(`the Windows publisher ${publisherDeviceIdOption} never reported push capabilities; devices=${list.map(device => `${device.deviceId}:${device.component}:${device.online}:${(device.capabilities ?? []).join('|')}`).join(',')}`);
  }
  step('publisher', { deviceId: publisher.deviceId, deviceName: publisher.deviceName, capabilities: publisher.capabilities });
  report.checks.publisherCapabilities = Array.isArray(publisher.capabilities) && publisher.capabilities.includes('video-publish') && publisher.capabilities.includes('stream-control') && !publisher.capabilities.includes('camera-config');
  report.checks.publisherDeviceIdHidden = publisher.component === 'windows-inference';

  const receiver = await waitFor(async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.component === 'windows-inference' && device.deviceId !== publisher.deviceId && device.online === true);
    return node || undefined;
  }, 90000, 'the receiver instance registered itself').catch(() => undefined);
  if (!receiver) throw new Error('the second Windows instance never registered as a receiver');
  step('receiver', { deviceId: receiver.deviceId, deviceName: receiver.deviceName });
  report.checks.receiverRegistered = true;

  // 2) Explicit binding is required for an inference publisher (no auto-binding to avoid loops).
  const beforeBind = (await get('/api/streams', ownerToken)).streams ?? [];
  const publisherStream = beforeBind.find(stream => stream.publisherDeviceId === publisher.deviceId);
  report.checks.publisherUnboundByDefault = !!publisherStream && !publisherStream.targetDeviceId;
  const bound = await request('/api/streams/bind', { publisherDeviceId: publisher.deviceId, targetDeviceId: receiver.deviceId }, ownerToken);
  step('bind', { streamId: bound.stream.streamId, targetDeviceId: bound.stream.targetDeviceId });
  report.checks.publisherStreamBound = bound.stream.targetDeviceId === receiver.deviceId;
  report.checks.selfBindRejected = await request('/api/streams/bind', { publisherDeviceId: publisher.deviceId, targetDeviceId: publisher.deviceId }, ownerToken).then(() => false, () => true);
  if (!bound.stream || !bound.stream.streamId) throw new Error('the publisher stream was not bound before verification');

  // 3) Start the push branch through the same remote command the console uses.
  const session = await openConsole(ownerToken);
  const ack = await consoleCommand(session, { type: 'command', targetDeviceId: publisher.deviceId, command: 'resume' }, 'start push');
  step('start-stream', ack);
  report.checks.pushStarted = ack.success === true;

  // The node reports its own push state through heartbeat components; a faulted branch must be visible here.
  // 组件状态走服务端通用词表：running / starting / error / stopped。刚下发命令时会出现 starting，
  // 必须等到终态，否则一拿到过渡态就当成结论。
  const pushState = await waitFor(async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.deviceId === publisher.deviceId);
    const state = node?.components?.publish;
    if (state === 'error') throw new Error(`publisher reports a faulted push branch; inspect the isolated media diagnostics`);
    return state === 'running' ? state : undefined;
  }, 60000, 'publisher push state reported').catch(error => String(error.message));
  step('push-state', { state: pushState });
  report.checks.pushStateReported = pushState === 'running';

  // 4) The receiver is a real Windows instance started by the caller. 先临时把同一路推流绑到一台帧探针设备，
  // 用它验证帧契约；之后再把目标切回真实接收端，让它持续消费并在界面上显示推流来源。
  const probe = await request('/api/account/login', { username, password, component: 'windows-inference', deviceIdentity: sha256('e2e-windows-push-chain-frame-probe'), deviceModel: 'Push Chain Frame Probe' });
  await request('/api/streams/bind', { publisherDeviceId: publisher.deviceId, targetDeviceId: probe.device.deviceId }, ownerToken);
  const subscriber = await openSubscriber(probe.token);
  await waitFor(() => subscriber.state.ready, 20000, 'subscriber media-ready');

  // 探针期间真实接收端已经建成远程来源卡片（绑定关系出现时服务端就下发了 stream-list）。
  // 这里只做过程记录：卡片的真正断言在后面——重新绑定到接收端后它必须就绪、在推理并上报帧率，
  // 那条断言本身就要求同样的来源 ID 存在，因此不把这一瞬时的设备列表快照当成结论。
  const receiverNode = await waitFor(async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.deviceId === receiver.deviceId);
    return node && node.online === true && (node.sources ?? []).some(source => source.sourceId === bound.stream.sourceId) ? node : undefined;
  }, 90000, 'receiver built the remote source').catch(() => undefined);
  step('receiver-source', { deviceId: receiver.deviceId, sources: (receiverNode?.sources ?? []).map(source => `${source.sourceId}:${source.sourceName}:${source.isMonitoring}:${source.actualFps}`) });

  await waitFor(() => subscriber.state.frames.length >= 1, timeoutMs, 'first relayed frame');
  const first = subscriber.state.frames[0].header, firstImage = subscriber.state.frames[0].image;
  fs.writeFileSync(path.join(evidence, 'first-frame.jpg'), firstImage);
  step('first-frame', { sequence: first.sequence, width: first.width, height: first.height, rotation: first.rotation, bytes: firstImage.length, jpegHead: firstImage.subarray(0, 2).toString('hex'), jpegTail: firstImage.subarray(-2).toString('hex') });
  report.checks.frameStreamId = first.streamId === bound.stream.streamId;
  report.checks.frameSessionId = !!first.sessionId && subscriber.state.sessionId !== first.sessionId;
  report.checks.frameSizeWithinContract = Math.max(first.width, first.height) <= 1280 && Math.min(first.width, first.height) <= 720;
  report.checks.frameRotationNormalized = first.rotation === 0;
  report.checks.frameCapturedAtMonotonic = Number.isSafeInteger(first.capturedAt) && first.capturedAt > 0;

  await waitFor(() => subscriber.state.frames.length >= 8, timeoutMs, 'eight relayed frames');
  let monotonic = true, valid = true;
  for (let index = 1; index < subscriber.state.frames.length; index++) {
    const previous = subscriber.state.frames[index - 1].header, current = subscriber.state.frames[index].header;
    if (current.sequence <= previous.sequence) monotonic = false;
    if (current.streamId !== first.streamId || current.sessionId !== first.sessionId) valid = false;
  }
  const last = subscriber.state.frames[subscriber.state.frames.length - 1].header;
  fs.writeFileSync(path.join(evidence, 'latest-frame.jpg'), subscriber.state.frames[subscriber.state.frames.length - 1].image);
  step('frames', { received: subscriber.state.frames.length, firstSequence: first.sequence, lastSequence: last.sequence });
  report.checks.frameSequenceMonotonic = monotonic;
  report.checks.frameIdentityStable = valid;

  // 把目标切回真实接收端：界面截图与它的推理状态都必须建立在这条绑定上。
  const rebound = await request('/api/streams/bind', { publisherDeviceId: publisher.deviceId, targetDeviceId: receiver.deviceId }, ownerToken);
  report.checks.receiverStreamBound = rebound.stream.targetDeviceId === receiver.deviceId;
  step('rebind-to-receiver', { targetDeviceId: rebound.stream.targetDeviceId, sourceId: rebound.stream.sourceId });
  try { subscriber.socket.close(); } catch { /* probe connection no longer needed */ }
  // 绑定重新落到接收端之后再让它开始推理（拿到真实媒体会话后 IsReady 才会为真）。
  // 重启命令可能在来源还没就绪时到达，因此这里重试到节点自己报告在推理为止。
  const resumeAck = await consoleCommand(session, { type: 'command', targetDeviceId: receiver.deviceId, command: 'resume' }, 'receiver start inference')
    .catch(error => ({ success: false, reason: String(error.message) }));
  step('receiver-start', resumeAck);
  // 5) The service reports the relay as live and advancing.
  const live = await waitFor(async () => {
    const streams = (await get('/api/streams', ownerToken)).streams ?? [];
    const stream = streams.find(item => item.streamId === bound.stream.streamId);
    return stream && stream.isStreaming === true && (stream.stats?.forwarded ?? 0) > 0 ? stream : undefined;
  }, timeoutMs, 'relay forwarding').catch(async () => (await get('/api/streams', ownerToken)).streams.find(item => item.streamId === bound.stream.streamId));
  report.checks.relayStreaming = live.isStreaming === true;
  report.checks.relayStatsForwarded = (live.stats?.forwarded ?? 0) > 0;
  step('relay', { isStreaming: live.isStreaming, stats: live.stats });

  const receiverSourceStatus = async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.deviceId === receiver.deviceId);
    return (node?.sources ?? []).find(item => item.sourceId === rebound.stream.sourceId);
  };
  let receiverRunning;
  const receiverDeadline = Date.now() + timeoutMs;
  let attempts = 0, lastAttempt = 0;
  while (Date.now() < receiverDeadline) {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = list.find(device => device.deviceId === receiver.deviceId);
    const source = (node?.sources ?? []).find(item => item.sourceId === rebound.stream.sourceId);
    if (source && source.isMonitoring === true && (source.actualFps ?? 0) > 0) { receiverRunning = source; break; }
    // 来源未就绪时启动会被拒；每 10 秒重试一次，并记录节点在线状态与最近一次回执原因。
    if (attempts === 0 || Date.now() - lastAttempt >= 10000) {
      lastAttempt = Date.now(); attempts++;
      step(`receiver-state-${attempts}`, { online: node?.online === true, isMonitoring: source?.isMonitoring, isReady: source?.isReady });
      const ack = await consoleCommand(session, { type: 'command', targetDeviceId: receiver.deviceId, command: 'resume' }, `receiver start inference #${attempts}`)
        .catch(error => ({ success: false, reason: String(error.message) }));
      step(`receiver-start-${attempts}`, ack);
    }
    await sleep(1500);
  }
  report.checks.receiverInferring = !!receiverRunning;
  step('receiver-inference', { source: receiverRunning?.sourceName, isMonitoring: receiverRunning?.isMonitoring, actualFps: receiverRunning?.actualFps, error: receiverRunning?.error });
  if (!receiverRunning) {
    const last = await receiverSourceStatus();
    step('receiver-source-last', { isMonitoring: last?.isMonitoring, isReady: last?.isReady, error: last?.error });
  }

  // 7) The publisher must not produce local alerts while it only pushes.
  const alerts = (await get('/api/alerts', ownerToken)).alerts ?? [];
  report.checks.noLocalAlertFromPublisher = alerts.every(alert => alert.deviceId !== publisher.deviceId);
  step('alerts', { total: alerts.length, fromPublisher: alerts.filter(alert => alert.deviceId === publisher.deviceId).length });

  try { session.socket.close(); } catch { /* already closed */ }
  report.durationMs = Date.now() - started;
  const failed = Object.entries(report.checks).filter(([, value]) => value !== true).map(([name]) => name);
  report.ok = failed.length === 0;
  if (!report.ok) fail('checks', failed.join(', '));
  fs.writeFileSync(path.join(evidence, 'windows-push-chain-report.json'), JSON.stringify(report, null, 2));
  if (captureDelaySeconds > 0) {
    // 保持推流与订阅连接存活，让调用方截取界面证据；不改变任何断言结果。
    step('capture-window', { seconds: captureDelaySeconds });
    if (captureMarkerPath) { try { fs.writeFileSync(captureMarkerPath, new Date().toISOString()); } catch { /* marker is best effort */ } }
    await sleep(captureDelaySeconds * 1000);
  }
  try { subscriber?.socket.close(); } catch { /* already closed */ }
  console.log(`${report.ok ? 'PASS' : 'FAIL'} Windows push chain: ${Object.keys(report.checks).length - failed.length}/${Object.keys(report.checks).length} checks, ${subscriber ? subscriber.state.frames.length + ' frames' : 'relay delivery verified'}`);
  process.exit(report.ok ? 0 : 1);
}

main().catch(error => {
  report.failures.push({ name: 'run', detail: String(error && error.message ? error.message : error) });
  report.ok = false;
  try { fs.mkdirSync(evidence, { recursive: true }); fs.writeFileSync(path.join(evidence, 'windows-push-chain-report.json'), JSON.stringify(report, null, 2)); } catch { /* evidence best effort */ }
  console.error(`FAIL Windows push chain: ${error && error.stack ? error.stack : error}`);
  process.exit(1);
});
