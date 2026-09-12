const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const WebSocket = require('../server/node_modules/ws');

const root = path.resolve(__dirname, '..');
const exe = path.join(root, 'detector/windows-resident/bin/Release/net472/VisionGuard.Resident.exe');
const apiKey = process.env.VISIONGUARD_API_KEY;
const channel = process.env.VISIONGUARD_CHANNEL || 'vnext-e2e';
const serverUrl = process.env.VISIONGUARD_SERVER_URL || 'http://127.0.0.1:3100';
assert(apiKey, 'VISIONGUARD_API_KEY is required');
assert(fs.existsSync(exe), `Resident artifact not found: ${exe}`);

const configPath = path.join(os.tmpdir(), `visionguard-resident-smoke-${process.pid}.json`);
fs.writeFileSync(configPath, JSON.stringify({
  ServerUrl: serverUrl,
  ApiKey: apiKey,
  DeviceId: 'resident-win7-smoke',
  DeviceName: 'Resident Win7 Smoke',
  WpfPath: 'C:/missing/VisionGuard.exe',
  WinFormsPath: 'C:/missing/VisionGuard.exe',
}), 'utf8');

const resident = spawn(exe, ['--config', configPath], {
  cwd: path.dirname(exe),
  env: { ...process.env, VISIONGUARD_CHANNEL: channel },
  windowsHide: true,
});
const receiver = new WebSocket(serverUrl.replace(/^http/, 'ws') + '/ws');
const timeout = setTimeout(() => finish(new Error('Resident smoke timed out')), 15000);
let finished = false;
let commandSent = false;

function finish(error) {
  if (finished) return;
  finished = true;
  clearTimeout(timeout);
  try { receiver.close(); } catch {}
  try { resident.kill(); } catch {}
  try { fs.unlinkSync(configPath); } catch {}
  if (error) { console.error(error.stack || error); process.exitCode = 1; }
  else console.log('PASS resident authenticated, reported heartbeat, and returned a correlated lifecycle command result.');
}

resident.once('error', finish);
resident.once('exit', code => { if (!finished) finish(new Error(`Resident exited early with code ${code}`)); });
receiver.once('error', finish);
receiver.once('open', () => receiver.send(JSON.stringify({
  type: 'auth', channel, apiKey, role: 'android', deviceId: `resident-smoke-receiver-${process.pid}`,
})));
receiver.on('message', data => {
  const message = JSON.parse(data.toString());
  if (!commandSent && message.type === 'device-list') {
    const target = message.devices.find(device => device.deviceId === 'resident-win7-smoke');
    if (target && target.components && target.components.resident === 'running') {
      commandSent = true;
      receiver.send(JSON.stringify({
        type: 'command', requestId: `resident-smoke-${process.pid}`,
        targetDeviceId: 'resident-win7-smoke', command: 'open-wpf',
      }));
    }
  }
  if (message.type === 'command-ack' && message.requestId === `resident-smoke-${process.pid}` && message.phase === 'completed') {
    try {
      assert.equal(message.targetDeviceId, 'resident-win7-smoke');
      assert.equal(message.command, 'open-wpf');
      assert.equal(message.success, false);
      assert.equal(message.reason, 'configured executable not found');
      finish();
    } catch (error) { finish(error); }
  }
});
