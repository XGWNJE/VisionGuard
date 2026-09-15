const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const WebSocket = require('../server/node_modules/ws');

// Verifies the unified device-level command semantics end to end:
// a `command` without targetSourceId must reach the detector as a whole-device
// action (the detector answers for "all sources"), while a per-source command
// stays per-source. Requires a running isolated Server and a built WinForms app.

const root = path.resolve(__dirname, '..');
const exe = path.join(root, 'detector/windows-winforms/bin/Release/VisionGuard.exe');
const serverUrl = process.env.VISIONGUARD_SERVER_URL || 'http://127.0.0.1:3100';
const apiKey = process.env.VISIONGUARD_API_KEY;
const channel = process.env.VISIONGUARD_CHANNEL || 'vnext-e2e';
const reportPath = process.argv[2] || path.join(root, 'artifacts/e2e/winforms-device-level-command.json');
const sourceCount = Number(process.argv[3] || 2);

assert(apiKey, 'VISIONGUARD_API_KEY is required');
assert(fs.existsSync(exe), `WinForms artifact not found: ${exe}`);

const profileRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-device-command-'));
const appData = path.join(profileRoot, 'Roaming');
const settingsDir = path.join(appData, 'VisionGuard');
fs.mkdirSync(settingsDir, { recursive: true });
const settingsPath = path.join(settingsDir, 'settings.ini');
const lines = [
  '# isolated WinForms device-level command profile',
  'DeviceId=winforms-device-command',
  'DeviceName=WinForms Device Command',
  `WinForms.Source.Count=${sourceCount}`,
];
for (let i = 1; i <= sourceCount; i++) {
  lines.push(`WinForms.Source.${i}.Id=winforms-command-source-${i}`);
  lines.push(`WinForms.Source.${i}.Name=Command Source ${i}`);
  lines.push(`WinForms.Source.${i}.Model=yolov5nu_320`);
  lines.push(`WinForms.Source.${i}.WatchedClasses=person`);
  lines.push(`WinForms.Source.${i}.CaptureMode=ScreenRegion`);
  lines.push(`WinForms.Source.${i}.ScreenRegion=`);
}
fs.writeFileSync(settingsPath, lines.join('\n'), 'utf8');

const app = spawn(exe, [], {
  cwd: path.dirname(exe),
  env: { ...process.env, APPDATA: appData, VISIONGUARD_SERVER_URL: serverUrl,
    VISIONGUARD_API_KEY: apiKey, VISIONGUARD_CHANNEL: channel,
    VISIONGUARD_SETTINGS_PATH: settingsPath },
  windowsHide: false,
});
const receiver = new WebSocket(serverUrl.replace(/^http/, 'ws') + '/ws');
const timeout = setTimeout(() => finish(new Error('device-level command check timed out')), 30000);
let finished = false;
let deviceReady = false;
let deviceLevelAck = null;
let sourceScopedAck = null;
const acks = [];

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(timeout);
  try { receiver.close(); } catch {}
  try { app.kill(); } catch {}
  try { fs.rmSync(profileRoot, { recursive: true, force: true }); } catch {}
  if (error) {
    console.error(error.stack || error);
    process.exitCode = 1;
    return;
  }
  const report = { passed: true, checkedAt: new Date().toISOString(), serverUrl, channel,
    deviceLevelAck, sourceScopedAck, acks: acks };
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf8');
  console.log(`PASS device-level command reached the detector as a whole-device action and was acknowledged: ${deviceLevelAck.reason}`);
  console.log(`Report: ${reportPath}`);
}

function sendCommand(requestId, command, targetSourceId) {
  const message = { type: 'command', requestId, targetDeviceId: 'winforms-device-command', command };
  if (targetSourceId) message.targetSourceId = targetSourceId;
  receiver.send(JSON.stringify(message));
}

app.once('error', finish);
app.once('exit', code => { if (!finished) finish(new Error(`WinForms exited early with code ${code}`)); });
receiver.once('error', finish);
receiver.once('open', () => receiver.send(JSON.stringify({
  type: 'auth', channel, apiKey, role: 'android',
  deviceId: `winforms-device-command-receiver-${process.pid}`,
})));
receiver.on('message', raw => {
  try {
    const message = JSON.parse(raw.toString());
    if (message.type === 'device-list') {
      const device = message.devices.find(item => item.deviceId === 'winforms-device-command');
      if (device && Array.isArray(device.sources) && device.sources.length === sourceCount &&
          device.capabilities.includes('source-control') && !deviceReady) {
        deviceReady = true;
        sendCommand('device-level-command-01', 'pause');
      }
      return;
    }
    if (message.type !== 'command-ack') return;
    if (message.phase !== 'completed') return;
    if (message.command !== 'pause' && message.command !== 'resume') return;
    acks.push({ command: message.command, success: message.success, reason: message.reason,
      targetSourceId: message.targetSourceId ?? null });

    if (deviceLevelAck === null) {
      deviceLevelAck = { command: message.command, success: message.success, reason: message.reason,
        targetSourceId: message.targetSourceId ?? null };
      // The unified rule: a device-level command must be answered for the whole device,
      // not silently applied to the first source.
      assert.equal(message.success, true, 'a device-level command must be accepted');
      assert.equal(message.targetSourceId ?? null, null, 'a device-level ack must not name a single source');
      assert.match(message.reason, /全部来源/, 'the ack must state that all sources were addressed');
      sendCommand('source-scoped-command-1', 'resume', `winforms-command-source-${sourceCount}`);
      return;
    }
    if (sourceScopedAck === null) {
      sourceScopedAck = { command: message.command, success: message.success, reason: message.reason,
        targetSourceId: message.targetSourceId ?? null };
      // A per-source command must keep naming that source.
      assert.equal(message.targetSourceId, `winforms-command-source-${sourceCount}`,
        'a per-source ack must echo the addressed source');
      finish(null);
    }
  } catch (error) { finish(error); }
});
