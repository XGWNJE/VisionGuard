// Web camera chain: real device Chrome camera -> isolated service -> real Windows visual inference node -> person alert.
// Maintained by the visionguard-e2e skill (mode WebCameraChain). Only uses an isolated service instance, a private
// test account and loopback HTTP; it never touches the production service or a real account.
//
// Usage (normally invoked by e2e-smoke.ps1 -Mode WebCameraChain):
//   node scripts/web-camera-chain.js --service http://127.0.0.1:3124 --username vg-test --password *** \
//     --evidence <artifact-directory> [--serial <adb-serial>] [--camera front|back|default] \
//     [--wpf-device-id <id>] [--cdp-port 9333] [--permission-timeout 180] [--alert-timeout 180] [--keep-streaming]
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

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
const evidence = path.resolve(options.get('evidence') ?? path.join(repoRoot, 'artifacts/e2e/web-camera-chain'));
const serial = options.get('serial') ?? '';
const adbPath = options.get('adb') ?? 'adb';
const cameraPreference = (options.get('camera') ?? 'front').toLowerCase();
const wpfDeviceIdOption = options.get('wpf-device-id') ?? '';
const cdpPort = Number(options.get('cdp-port') ?? 9333);
const permissionTimeoutMs = Number(options.get('permission-timeout') ?? 180) * 1000;
const alertTimeoutMs = Number(options.get('alert-timeout') ?? 180) * 1000;
const keepStreaming = options.get('keep-streaming') === 'true';
if (!service || !username || !password) {
  console.error('usage: node web-camera-chain.js --service <url> --username <user> (--password <secret> | --password-env <variable>) --evidence <dir>');
  process.exit(2);
}

const report = { startedAt: new Date().toISOString(), service, checks: {}, steps: [], failures: [], limitations: [] };
let activeCdp = null;
let activeBrowser = null;
let activeTargetId = '';
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
function adb(...args) {
  const command = serial ? ['-s', serial, ...args] : [...args];
  // Keep adb stderr off the parent's stream: a caller that treats native stderr as fatal would abort on
  // harmless warnings such as "Activity not started, its current task has been brought to the front".
  return execFileSync(adbPath, command, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
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

async function waitFor(check, timeoutMs, name, intervalMs = 400) {
  const deadline = Date.now() + timeoutMs;
  let last;
  for (;;) {
    try { last = await check(); } catch (error) { last = { error: String(error.message) }; }
    if (last) return last;
    if (Date.now() > deadline) throw new Error(`timeout waiting for ${name} (last=${JSON.stringify(last)})`);
    await sleep(intervalMs);
  }
}

// ── device Chrome over the DevTools protocol ────────────────────────────────
class Cdp {
  constructor(url) { this.url = url; this.id = 0; this.pending = new Map(); this.handlers = new Map(); }
  open() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.on('open', resolve);
      this.ws.on('error', reject);
      this.ws.on('message', raw => {
        const message = JSON.parse(raw.toString());
        if (message.id && this.pending.has(message.id)) {
          const { resolve: done, reject: failed } = this.pending.get(message.id);
          this.pending.delete(message.id);
          message.error ? failed(new Error(message.error.message)) : done(message.result);
        } else if (message.method) for (const handler of this.handlers.get(message.method) ?? []) handler(message.params);
      });
    });
  }
  on(method, handler) { if (!this.handlers.has(method)) this.handlers.set(method, []); this.handlers.get(method).push(handler); }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => { this.pending.set(id, { resolve, reject }); this.ws.send(JSON.stringify({ id, method, params })); });
  }
  close() { try { this.ws.close(); } catch { /* already closed */ } }
}
async function evaluate(cdp, expression, awaitPromise = false) {
  const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise, returnByValue: true, userGesture: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}

/** Fresh tab so the run never depends on the operator's open tabs. */
async function openCameraPage() {
  adb('forward', `tcp:${cdpPort}`, 'localabstract:chrome_devtools_remote');
  const version = await fetch(`http://127.0.0.1:${cdpPort}/json/version`).then(response => response.json());
  report.browser = version.Browser;
  const browser = new Cdp(version.webSocketDebuggerUrl);
  await browser.open();
  const created = await browser.send('Target.createTarget', { url: 'about:blank' });
  const page = await waitFor(async () => {
    const targets = await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then(response => response.json());
    return targets.find(target => target.id === created.targetId);
  }, 15000, 'created camera target');
  const cdp = new Cdp(page.webSocketDebuggerUrl);
  await cdp.open();
  const consoleErrors = [];
  cdp.on('Runtime.exceptionThrown', params => consoleErrors.push(String(params.exceptionDetails?.exception?.description ?? params.exceptionDetails?.text ?? 'exception').slice(0, 300)));
  cdp.on('Log.entryAdded', params => { if (params.entry.level === 'error') consoleErrors.push('log: ' + String(params.entry.text).slice(0, 300)); });
  await cdp.send('Runtime.enable');
  await cdp.send('Page.enable');
  await cdp.send('Log.enable');
  // Collect the media failures the page only reports as a generic message.
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    window.__vgMediaErrors = [];
    const original = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    if (original) {
      try { navigator.mediaDevices.getUserMedia = async constraints => { try { return await original(constraints); } catch (error) { window.__vgMediaErrors.push({ name: error?.name, message: String(error?.message).slice(0, 200) }); throw error; } }; } catch { /* read-only implementation */ }
    }
  })();` });
  return { cdp, consoleErrors, browser, targetId: created.targetId };
}
async function cameraInventory(cdp) {
  return JSON.parse(await evaluate(cdp, `(async () => JSON.stringify({
    href: location.href, secure: window.isSecureContext,
    devices: (await navigator.mediaDevices.enumerateDevices()).filter(device => device.kind === 'videoinput').map(device => ({ label: device.label, deviceId: device.deviceId })),
    options: [...(document.querySelector('select[aria-label="摄像头"]')?.options ?? [])].map(option => ({ value: option.value, label: option.textContent })),
    mediaErrors: window.__vgMediaErrors ?? [] }))()`, true));
}
async function loginOnPage(cdp) {
  const filled = await evaluate(cdp, `(() => {
    const form = document.querySelector('form.login-panel');
    if (!form) return 'no-login-form';
    const setValue = (element, value) => { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(element, value); element.dispatchEvent(new Event('input', { bubbles: true })); };
    setValue(form.querySelector('input[autocomplete="username"]'), ${JSON.stringify(username)});
    setValue(form.querySelector('input[autocomplete="current-password"]'), ${JSON.stringify(password)});
    form.requestSubmit();
    return 'submitted';
  })()`);
  return filled;
}
async function waitForHome(cdp, timeoutMs, name) {
  return waitFor(async () => (await evaluate(cdp, 'document.querySelector(".topbar") !== null')) ? true : undefined, timeoutMs, name);
}
async function pageState(cdp) {
  return JSON.parse(await evaluate(cdp, `JSON.stringify({
    status: document.querySelector('.status')?.textContent ?? '',
    warning: document.querySelector('.warning')?.textContent ?? '',
    stats: [...document.querySelectorAll('.stats dd')].map(item => item.textContent),
    streaming: [...document.querySelectorAll('button')].some(item => item.textContent.includes('停止推流')),
    target: document.querySelector('select[aria-label="目标视觉推理节点"]')?.value ?? null,
    mediaErrors: window.__vgMediaErrors ?? [] })`));
}
async function clickStart(cdp) {
  return evaluate(cdp, `(() => {
    const button = [...document.querySelectorAll('button')].find(item => item.textContent.includes('开始推流'));
    if (!button) return 'no-start-button';
    if (button.disabled) return 'start-disabled: ' + (document.querySelector('.warning')?.textContent ?? '');
    button.click();
    return 'clicked';
  })()`);
}
async function clickStop(cdp) {
  return evaluate(cdp, `(() => {
    const button = [...document.querySelectorAll('button')].find(item => item.textContent.includes('停止推流'));
    if (!button) return 'not-streaming';
    button.click();
    return 'clicked';
  })()`);
}
async function selectCamera(cdp, preference) {
  const wanted = preference === 'front' ? 'facing front' : preference === 'back' ? 'facing back' : '';
  if (!wanted) return 'default';
  return evaluate(cdp, `(() => {
    const select = document.querySelector('select[aria-label="摄像头"]');
    if (!select) return 'no-camera-select';
    if (select.disabled) return 'select-disabled';
    const match = [...select.options].find(option => option.textContent.includes(${JSON.stringify(wanted)}));
    if (!match) return 'no-match: ' + [...select.options].map(option => option.textContent).join(' | ');
    const setter = Object.getOwnPropertyDescriptor(window.HTMLSelectElement.prototype, 'value').set;
    setter.call(select, match.value);
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return 'selected ' + match.textContent;
  })()`);
}
async function screenshot(cdp, name) {
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  const target = path.join(evidence, name);
  fs.writeFileSync(target, Buffer.from(shot.data, 'base64'));
  return target;
}

// ── console channel ─────────────────────────────────────────────────────────
async function openConsole(token) {
  const socket = new WebSocket(`${wsBase}/ws`);
  const messages = [];
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  socket.on('message', raw => { try { messages.push(JSON.parse(raw.toString())); } catch { /* ignore */ } });
  socket.send(JSON.stringify({ type: 'auth', token }));
  await waitFor(() => messages.some(message => message.type === 'auth-result' && message.success === true), 15000, 'console auth');
  return { socket, messages };
}
async function consoleCommand(session, payload, name) {
  const requestId = crypto.randomUUID();
  session.socket.send(JSON.stringify({ type: 'command', requestId, ...payload }));
  const ack = await waitFor(() => session.messages.find(message => message.type === 'command-ack' && message.requestId === requestId && message.phase === 'completed'), 20000, name);
  return ack;
}

async function main() {
  fs.mkdirSync(evidence, { recursive: true });
  const serviceUrl = new URL(service);
  const port = serviceUrl.port || '80';

  // 1) Owner session for the API/console side of the chain (browser keeps its own web-camera session).
  const owner = await request('/api/account/login', { username, password, component: 'web-console', deviceIdentity: sha256('e2e-web-camera-chain-console'), deviceModel: 'Web Camera Chain Owner' });
  const ownerToken = owner.token;
  const devices = (await get('/api/devices', ownerToken)).devices ?? [];
  // The callers start the Windows node in parallel; give it time to finish authenticating and settle online.
  const wpf = await waitFor(async () => {
    const list = (await get('/api/devices', ownerToken)).devices ?? [];
    const node = wpfDeviceIdOption ? list.find(device => device.deviceId === wpfDeviceIdOption) : list.find(device => device.component === 'windows-inference');
    return node && node.online !== false ? node : undefined;
  }, 90000, 'windows inference node online').catch(() => undefined);
  if (!wpf) { fail('windows-node', 'the Windows visual inference node never reported online: ' + devices.map(device => device.component).join(',')); throw new Error('the Windows visual inference node is required for this mode'); }
  step('windows-node', { deviceId: wpf.deviceId, deviceName: wpf.deviceName, online: wpf.online });
  report.checks.windowsNodeOnline = wpf.online === true;

  // 2) Device side: loopback bridge, Chrome, camera page.
  adb('reverse', `tcp:${port}`, `tcp:${port}`);
  step('adb-reverse', adb('reverse', '--list'));
  // Bringing an existing Chrome task to the front reports a warning and still exits zero; the CDP wait below
  // is the real check, so a non-zero exit here must not abort the run.
  try { adb('shell', 'am', 'start', '-n', 'com.android.chrome/com.google.android.apps.chrome.Main', '-d', 'about:blank'); }
  catch (error) { step('chrome-launch', String(error.stderr ?? error.message).slice(0, 200)); }
  await sleep(3000);
  const { cdp, consoleErrors, browser, targetId } = await openCameraPage();
  activeCdp = cdp;
  activeBrowser = browser;
  activeTargetId = targetId;
  await cdp.send('Page.navigate', { url: `${service}/camera/` });
  await waitFor(async () => (await evaluate(cdp, 'document.querySelector("form.login-panel") !== null')) ? true : undefined, 30000, 'camera login form');
  const secure = JSON.parse(await evaluate(cdp, 'JSON.stringify({ secure: window.isSecureContext, getUserMedia: typeof navigator.mediaDevices?.getUserMedia })'));
  report.checks.secureContext = secure.secure === true && secure.getUserMedia === 'function';
  if (!report.checks.secureContext) fail('secure-context', secure);
  await loginOnPage(cdp);
  await waitForHome(cdp, 30000, 'camera home');

  const cameraDevice = await waitFor(async () => (await get('/api/devices', ownerToken)).devices.find(device => device.component === 'web-camera'), 20000, 'web-camera registration');
  report.checks.cameraRegistered = !!cameraDevice;
  step('camera-registered', { deviceId: cameraDevice.deviceId, deviceName: cameraDevice.deviceName, platform: cameraDevice.platform });

  const binding = await request('/api/streams/bind', { publisherDeviceId: cameraDevice.deviceId, targetDeviceId: wpf?.deviceId }, ownerToken);
  report.stream = binding.stream;
  step('bind', { streamId: binding.stream?.streamId, sourceId: binding.stream?.sourceId, targetDeviceId: binding.stream?.targetDeviceId });
  const targetBound = await waitFor(async () => (await evaluate(cdp, `document.querySelector('select[aria-label="目标视觉推理节点"]')?.value === ${JSON.stringify(wpf?.deviceId)}`)) ? true : undefined, 20000, 'target selection');
  report.checks.targetBound = targetBound === true;

  // 3) Start streaming. Chrome hides camera labels until this origin is granted, so an unlabeled device list
  //    means the operator still has to accept the device prompt; the page is then reloaded so the camera list
  //    carries real device ids and the requested camera can be selected before publishing.
  let streaming = false;
  let permissionGranted = (await cameraInventory(cdp)).devices.some(device => device.label);
  if (!permissionGranted) {
    step('start-click', await clickStart(cdp));
    let instructionsPrinted = false;
    const permissionDeadline = Date.now() + permissionTimeoutMs;
    while (Date.now() < permissionDeadline) {
      if ((await pageState(cdp)).streaming) { streaming = true; break; }
      // A non-empty label is the permission signal.
      if ((await cameraInventory(cdp)).devices.some(device => device.label)) { permissionGranted = true; break; }
      if (!instructionsPrinted) {
        console.log('WAITING for the device: accept the Chrome camera prompt ("访问该网站时允许") on the tablet.');
        instructionsPrinted = true;
      }
      await sleep(2000);
    }
    if (!streaming && permissionGranted) {
      step('permission-granted', await cameraInventory(cdp));
      await cdp.send('Page.navigate', { url: `${service}/camera/` });
      const restored = await waitFor(async () => {
        const state = await evaluate(cdp, 'document.querySelector("form.login-panel") ? "login" : (document.querySelector(".topbar") ? "home" : "")');
        return state || undefined;
      }, 30000, 'camera page after reload');
      if (restored === 'login') { await loginOnPage(cdp); await waitForHome(cdp, 30000, 'camera home after reload'); }
    }
  }
  if (!streaming && permissionGranted) {
    // Select the requested camera before publishing: the page disables the selector while streaming.
    step('camera-selection', await selectCamera(cdp, cameraPreference));
    step('start-click', await clickStart(cdp));
    streaming = await waitFor(async () => (await pageState(cdp)).streaming ? true : undefined, 30000, 'streaming state').catch(() => false);
  }
  const state = await pageState(cdp);
  report.checks.streaming = streaming === true;
  report.pageState = state;
  step('page-state', state);
  if (!streaming) fail('streaming', { state, hint: 'the device Chrome camera prompt was not accepted, or no camera is available' });
  else await screenshot(cdp, 'device-camera-page.png');

  if (streaming) {
    // 4) The relay must carry the stream and the Windows node must create the matching remote source.
    const stream = await waitFor(async () => (await get('/api/streams', ownerToken)).streams.find(item => item.publisherDeviceId === cameraDevice.deviceId && item.isStreaming), 20000, 'relay stream');
    report.checks.relayStreaming = !!stream;
    step('relay', { streamId: stream.streamId, sourceId: stream.sourceId, stats: stream.stats });

    const bound = await waitFor(async () => {
      const list = (await get('/api/devices', ownerToken)).devices;
      const node = list.find(device => device.deviceId === wpf?.deviceId);
      const source = (node?.sources ?? []).find(item => item.sourceId === stream.sourceId);
      return source ? { source, node } : undefined;
    }, 30000, 'windows remote source');
    report.checks.windowsSourceBound = !!bound;
    step('windows-source', { sourceId: bound.source.sourceId, sourceName: bound.source.sourceName, modelKey: bound.source.modelKey });

    // 5) Start inference on the remote source through the real control channel.
    const control = await openConsole(ownerToken);
    const ack = await consoleCommand(control, { command: 'resume', targetDeviceId: wpf.deviceId }, 'resume');
    step('resume', ack);
    if (!ack.success) fail('resume', ack.reason ?? ack);
    const monitoring = await waitFor(async () => {
      const list = (await get('/api/devices', ownerToken)).devices;
      const node = list.find(device => device.deviceId === wpf?.deviceId);
      const source = (node?.sources ?? []).find(item => item.sourceId === stream.sourceId);
      return source?.isMonitoring && Number(source.actualFps) > 0 ? source : undefined;
    }, 60000, 'remote source monitoring');
    report.checks.inferenceRunning = !!monitoring;
    report.source = { sourceId: monitoring.sourceId, actualFps: monitoring.actualFps, modelKey: monitoring.modelKey, backend: monitoring.backend ?? '', error: monitoring.error ?? '' };
    step('inference', report.source);

    // 6) Wait for a real person detection to reach the service.
    const since = Date.now();
    console.log('WAITING for a person in front of the device camera (the Windows node must raise a person alert).');
    let alert;
    const alertDeadline = Date.now() + alertTimeoutMs;
    while (Date.now() < alertDeadline) {
      const alerts = (await get(`/api/alerts?since=${since}&limit=20`, ownerToken)).alerts ?? [];
      alert = alerts.find(item => (item.detections ?? []).some(detection => (detection.label ?? detection.className ?? '').toLowerCase() === 'person') || (item.detections ?? []).length > 0);
      const list = (await get('/api/devices', ownerToken)).devices;
      const node = list.find(device => device.deviceId === wpf?.deviceId);
      const source = (node?.sources ?? []).find(item => item.sourceId === stream.sourceId);
      console.log(`poll fps=${source?.actualFps ?? 0} monitoring=${source?.isMonitoring ?? false} alerts=${alerts.length}`);
      if (alert) break;
      await sleep(5000);
    }
    report.checks.personAlertReceived = !!alert;
    if (!alert) {
      fail('person-alert', 'no person detection alert reached the service within the timeout; make sure a person is in front of the device camera');
      await screenshot(cdp, 'device-camera-no-person.png');
    } else {
      report.alert = alert;
      fs.writeFileSync(path.join(evidence, 'person-alert.json'), JSON.stringify(alert, null, 2), 'utf8');
      step('person-alert', { alertId: alert.alertId, sourceId: alert.sourceId, detections: alert.detections, screenshotUrl: alert.screenshotUrl });
      if (alert.screenshotUrl) {
        const response = await fetch(`${service}${alert.screenshotUrl}`, { headers: { Authorization: `Bearer ${ownerToken}` } });
        const bytes = Buffer.from(await response.arrayBuffer());
        fs.writeFileSync(path.join(evidence, 'server-alert-screenshot.jpg'), bytes);
        report.checks.serverScreenshot = response.ok && bytes.length > 1000;
        step('server-screenshot', { status: response.status, contentType: response.headers.get('content-type'), bytes: bytes.length });
      }
    }
    if (!keepStreaming) step('stop', await clickStop(cdp));
    control.socket.close();
  }
  report.consoleErrors = consoleErrors;
  await closePage();
}

/** Close the tab this run created so repeated runs do not pile up blank tabs on the device. */
async function closePage() {
  if (activeBrowser && activeTargetId) {
    try { await activeBrowser.send('Target.closeTarget', { targetId: activeTargetId }); } catch { /* tab already gone */ }
  }
  if (activeCdp) activeCdp.close();
  if (activeBrowser) activeBrowser.close();
  activeCdp = null; activeBrowser = null; activeTargetId = '';
}

async function cleanup() {
  try { adb('reverse', '--remove', `tcp:${new URL(service).port || '80'}`); } catch { /* not established */ }
  try { adb('forward', '--remove', `tcp:${cdpPort}`); } catch { /* not established */ }
}

main()
  .then(async () => { await cleanup(); })
  .catch(async error => {
    fail('fatal', String(error?.stack ?? error));
    // Never leave the device camera publishing after a failed run.
    if (activeCdp) { try { step('stop-after-failure', await clickStop(activeCdp)); } catch { /* page already gone */ } }
    await closePage();
    await cleanup();
  })
  .finally(() => {
    report.finishedAt = new Date().toISOString();
    report.passed = report.failures.length === 0;
    fs.mkdirSync(evidence, { recursive: true });
    fs.writeFileSync(path.join(evidence, 'web-camera-chain-report.json'), JSON.stringify(report, null, 2), 'utf8');
    console.log(JSON.stringify({ passed: report.passed, checks: report.checks, failures: report.failures }, null, 2));
    console.log(report.passed ? 'WEB CAMERA CHAIN PASSED' : `WEB CAMERA CHAIN FAILED (${report.failures.length})`);
    process.exit(report.passed ? 0 : 1);
  });
