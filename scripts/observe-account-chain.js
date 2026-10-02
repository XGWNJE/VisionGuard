'use strict';
// Observe real camera -> inference -> relay -> notifier traffic. Never publish an event or frame.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { performance } = require('node:perf_hooks');
const WebSocket = require('../server/node_modules/ws');
const root = path.resolve(__dirname, '..');
const usage = 'node scripts/observe-account-chain.js --url <http(s)://service> --credentials <private-account-file> --evidence <.local/report.json> [--username vg-test] [--isolation-username vg-isolation] [--seconds 40] [--device-id ID] [--source-id ID]';
class ObservationError extends Error {}
const fail = message => { throw new ObservationError(message); };
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function options() {
  const result = {};
  const allowed = new Set(['url', 'credentials', 'evidence', 'username', 'isolation-username', 'seconds', 'device-id', 'source-id']);
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i].slice(2), value = process.argv[i + 1];
    if (!process.argv[i].startsWith('--') || !allowed.has(key) || !value || Object.hasOwn(result, key)) fail(usage);
    result[key] = value;
  }
  if (!result.url || !result.evidence) fail(usage);
  let url; try { url = new URL(result.url); } catch { fail('Invalid service URL'); }
  const octets = url.hostname.split('.').map(Number);
  const lan = /^\d+\.\d+\.\d+\.\d+$/.test(url.hostname) && octets.every(n => n >= 0 && n <= 255)
    && (octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) || (octets[0] === 192 && octets[1] === 168));
  if (url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)
    || !(url.protocol === 'https:' || (url.protocol === 'http:' && (lan || ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))))) fail('Use a HTTPS service origin or a private LAN/loopback HTTP origin');
  const evidence = path.resolve(result.evidence), relative = path.relative(path.join(root, '.local'), evidence);
  if (relative.startsWith('..') || path.isAbsolute(relative)) fail('Evidence must be stored inside the ignored .local directory');
  const seconds = Number(result.seconds || 40);
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 40) fail('--seconds must be an integer from 1 to 40');
  return { ...result, origin: url.origin, evidence, seconds, username: result.username || process.env.VISIONGUARD_TEST_USERNAME || 'vg-test', isolationUsername: result['isolation-username'] || process.env.VISIONGUARD_ISOLATION_USERNAME || 'vg-isolation' };
}
function credentials(settings, username, isolation) {
  const password = process.env[isolation ? 'VISIONGUARD_ISOLATION_PASSWORD' : 'VISIONGUARD_TEST_PASSWORD'];
  if (password) return { username, password };
  const file = settings.credentials || process.env.VISIONGUARD_TEST_ACCOUNTS_PATH;
  if (!file) fail('Supply a private account file or both test-account password environment variables');
  let entries; try { entries = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { fail('Private account file could not be read'); }
  const entry = Array.isArray(entries) ? entries.find(item => item.username === username) : undefined;
  if (!entry || typeof entry.password !== 'string') fail('Requested account is absent from the private account file');
  return { username, password: entry.password };
}

async function main() {
  const settings = options(), started = performance.now(), deadline = started + settings.seconds * 1000;
  const report = { startedAt: new Date().toISOString(), origin: settings.origin, passed: false, mode: 'real-chain-observation', credentialsRecorded: false, syntheticEventsOrFramesSent: false, accounts: {}, cleanup: [] };
  const sessions = [];
  let socket, heartbeat, cancelled = false, finished = false, socketFailure;
  const interrupt = () => { cancelled = true; };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function request(endpoint, token, body, cleanup = false) {
    const remaining = cleanup ? 5000 : Math.min(5000, deadline - performance.now());
    if (remaining <= 0) fail('Observation timed out');
    try {
      const response = await fetch(settings.origin + endpoint, { method: body === undefined ? 'GET' : 'POST', headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), cache: 'no-store', signal: AbortSignal.timeout(Math.ceil(remaining)) });
      return response;
    } catch { fail('HTTP network or TLS request failed'); }
  }
  async function json(endpoint, token, body) {
    const response = await request(endpoint, token, body);
    if (response.status !== 200) fail(`HTTP ${response.status} at ${endpoint.split('?')[0]}`);
    try { return await response.json(); } catch { fail('Invalid service JSON response'); }
  }
  try {
    for (const [label, username, isolation] of [['observed', settings.username, false], ['isolation', settings.isolationUsername, true]]) {
      const session = await json('/api/account/login', undefined, { ...credentials(settings, username, isolation), component: 'web-console', deviceName: 'Chain observer' });
      if (!session.token || session.device?.component !== 'web-console' || session.account?.username !== username.toLowerCase()) fail('Unexpected observer account identity');
      sessions.push(session); report.accounts[label] = { username: session.account.username, loginStatus: 200 };
    }
    const [own, other] = sessions;
    if (own.account.accountId === other.account.accountId) fail('Observation and isolation accounts must be distinct');
    const baseline = await json('/api/alerts?limit=200', own.token), initialStreams = await json('/api/streams', own.token);
    const oldIds = new Set(baseline.alerts.map(event => event.alertId)), candidates = [], receipts = new Map();
    let streams = initialStreams.streams, authenticated = false;
    const wsURL = new URL('/ws', settings.origin); wsURL.protocol = wsURL.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(wsURL, { maxPayload: 3 * 1024 * 1024 });
    socket.on('error', () => { socketFailure = 'Observer WebSocket failed'; });
    socket.on('close', code => { if (!finished) socketFailure = `Observer WebSocket closed (${code})`; });
    socket.on('open', () => socket.send(JSON.stringify({ type: 'auth', token: own.token })));
    socket.on('message', (raw, binary) => {
      if (binary) return;
      let message; try { message = JSON.parse(raw.toString()); } catch { socketFailure = 'Invalid observer WebSocket message'; return; }
      if (message.type === 'auth-result') {
        authenticated = message.success === true && message.account?.accountId === own.account.accountId && message.deviceId === own.device.deviceId && message.role === 'console';
        if (!authenticated) socketFailure = 'Observer WebSocket authentication failed';
        else console.log('[observe] Authenticated; waiting for a new real remote-source detection and notifier receipt.');
      }
      if (!authenticated) return;
      if (message.type === 'stream-list' && Array.isArray(message.streams)) streams = message.streams;
      if (message.type === 'notification-receipt' && typeof message.alertId === 'string') {
        receipts.set(message.alertId, { notifierId: message.notifierId, receivedAt: message.receivedAt, observedAfterMs: performance.now() - started });
        if (receipts.size > 128) receipts.delete(receipts.keys().next().value);
      }
      if (message.type === 'alert' && message.eventKind === 'visual-detection' && !oldIds.has(message.alertId) && !candidates.some(item => item.event.alertId === message.alertId)) {
        const stream = streams.find(item => item.targetDeviceId === message.deviceId && item.sourceId === message.sourceId && item.isStreaming);
        if (!stream || (settings['device-id'] && message.deviceId !== settings['device-id']) || (settings['source-id'] && message.sourceId !== settings['source-id'])) return;
        candidates.push({ event: message, stream: { ...stream }, observedAfterMs: performance.now() - started });
        if (candidates.length > 8) candidates.shift();
        console.log(`[observe] New real visual-detection ${message.alertId}.`);
      }
    });
    heartbeat = setInterval(() => { if (authenticated && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'heartbeat-console' })); }, 3000);
    while (performance.now() < deadline && !cancelled) {
      if (socketFailure) fail(socketFailure);
      const chosen = candidates.find(item => receipts.has(item.event.alertId));
      if (!chosen) { await sleep(100); continue; }
      const { event, stream } = chosen, receipt = receipts.get(event.alertId);
      const history = await json(`/api/alerts?deviceId=${encodeURIComponent(event.deviceId)}&limit=100`, own.token);
      const stored = history.alerts.find(item => item.alertId === event.alertId);
      if (!stored?.hasScreenshot || !stored.screenshotUrl) { await sleep(500); continue; }
      const screenshotURL = new URL(stored.screenshotUrl, settings.origin);
      if (screenshotURL.origin !== settings.origin || !/^\/screenshots\/[A-Za-z0-9_-]{8,128}\.(jpg|jpeg)$/.test(screenshotURL.pathname) || screenshotURL.search) fail('Expected a same-service JPEG screenshot');
      const image = await request(screenshotURL.pathname, own.token), imageBytes = Buffer.from(await image.arrayBuffer());
      const imageType = image.headers.get('content-type') || '', jpeg = imageBytes.length > 12 && imageBytes[0] === 0xff && imageBytes[1] === 0xd8 && imageBytes.at(-2) === 0xff && imageBytes.at(-1) === 0xd9;
      const blocked = await request(screenshotURL.pathname, other.token); await blocked.body?.cancel();
      const ownDevices = await json('/api/devices', own.token), foreignDevices = await json('/api/devices', other.token), foreignStreams = await json('/api/streams', other.token), foreignEvents = await json('/api/alerts?limit=200', other.token);
      const targetIds = new Set([event.deviceId, stream.publisherDeviceId, receipt.notifierId]);
      const notifierOwned = ownDevices.devices.some(item => item.deviceId === receipt.notifierId && item.component === 'android-notifier' && item.accountId === own.account.accountId);
      const isolation = { devicesStatus: 200, streamsStatus: 200, eventsStatus: 200, targetNodesAbsent: foreignDevices.devices.every(item => !targetIds.has(item.deviceId)), targetStreamsAbsent: foreignStreams.streams.every(item => item.streamId !== stream.streamId && item.sourceId !== event.sourceId && !targetIds.has(item.publisherDeviceId) && !targetIds.has(item.targetDeviceId)), targetEventsAbsent: foreignEvents.alerts.every(item => item.alertId !== event.alertId && !targetIds.has(item.deviceId)), screenshotStatus: blocked.status };
      const timings = Object.fromEntries(Object.entries(event.timings || {}).filter(([key, value]) => /^[A-Za-z0-9_-]{1,64}$/.test(key) && typeof value === 'number' && Number.isFinite(value)));
      report.event = { alertId: event.alertId, eventKind: event.eventKind, deviceId: event.deviceId, deviceName: event.deviceName, sourceId: event.sourceId, sourceName: event.sourceName, timestamp: event.timestamp, serverReceivedAt: event.serverReceivedAt, capturedAt: event.capturedAt, timings, detections: event.detections?.map(item => ({ label: item.label, confidence: item.confidence })), observedAfterMs: chosen.observedAfterMs };
      report.stream = stream; report.receipt = { ...receipt, notifierOwned };
      report.screenshot = { sameAccountStatus: image.status, contentType: imageType, bytes: imageBytes.length, jpegMagic: jpeg, sha256: crypto.createHash('sha256').update(imageBytes).digest('hex'), otherAccountStatus: blocked.status };
      report.isolation = isolation;
      report.diagnostics = { remoteCachedAgeMs: timings.remoteCachedAgeMs, publisherCapturedAt: timings.publisherCapturedAt, relayReceivedAt: timings.relayReceivedAt, observerDetectionToReceiptMs: receipt.observedAfterMs - chosen.observedAfterMs, available: typeof timings.remoteCachedAgeMs === 'number' && typeof timings.publisherCapturedAt === 'number' && typeof timings.relayReceivedAt === 'number', clockNote: 'remoteCachedAgeMs and observer durations use local monotonic clocks. Cross-device epoch differences include unknown clock offset and are not absolute network latency. capturedAt is the WPF event send time; publisherCapturedAt is the camera frame time.' };
      if (typeof timings.publisherCapturedAt === 'number' && typeof timings.relayReceivedAt === 'number') report.diagnostics.relayMinusPublisherEpochMs = timings.relayReceivedAt - timings.publisherCapturedAt;
      report.passed = image.status === 200 && imageType.startsWith('image/jpeg') && jpeg && [403, 404].includes(blocked.status) && notifierOwned && isolation.targetNodesAbsent && isolation.targetStreamsAbsent && isolation.targetEventsAbsent;
      if (!report.passed) fail('Observed chain failed screenshot or account-isolation checks');
      console.log('[observe] Real detection, stored JPEG, notifier receipt and cross-account isolation passed.');
      break;
    }
    if (!report.passed) fail(cancelled ? 'Observation interrupted' : 'No complete new real chain observed before timeout');
  } catch (error) {
    report.error = error instanceof ObservationError ? error.message : 'Unexpected observation failure';
  } finally {
    finished = true; clearInterval(heartbeat); socket?.terminate();
    for (const session of sessions) {
      let status = null;
      try { const response = await request('/api/account/logout', session.token, {}, true); status = response.status; await response.body?.cancel(); } catch { /* record cleanup failure without credentials */ }
      report.cleanup.push({ username: session.account.username, logoutStatus: status, sessionRevoked: status === 200 || status === 401 });
    }
    report.passed = report.passed && report.cleanup.every(item => item.sessionRevoked);
    report.completedAt = new Date().toISOString(); report.elapsedMs = performance.now() - started;
    fs.mkdirSync(path.dirname(settings.evidence), { recursive: true }); fs.writeFileSync(settings.evidence, JSON.stringify(report, null, 2));
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
    console.log(JSON.stringify({ passed: report.passed, error: report.error, evidence: settings.evidence, elapsedMs: Math.round(report.elapsedMs), diagnostics: report.diagnostics }));
    if (!report.passed) process.exitCode = 1;
  }
}
main().catch(error => { console.error(error instanceof ObservationError ? error.message : 'Observation setup failed'); process.exitCode = 1; });
