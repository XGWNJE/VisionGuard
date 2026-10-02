"use strict";
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const WebSocket = require('../server/node_modules/ws');
const { credentials, login, logout } = require('./windows-account-test-lib');
const root = path.resolve(__dirname, '..');
const exe = path.join(root, 'detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe');
const probe = path.join(root, 'tests/AccountMedia.Probe/bin/Release/net472/AccountMedia.Probe.exe');
const serverUrl = process.env.VISIONGUARD_SERVER_URL || 'http://127.0.0.1:3100';
assert(fs.existsSync(exe) && fs.existsSync(probe), 'Build Windows and AccountMedia.Probe first.');
(async () => {
  const account = credentials();
  fs.mkdirSync(path.join(root, '.local'), { recursive: true });
  const accountDir = fs.mkdtempSync(path.join(root, '.local/resident-account-'));
  const configPath = path.join(accountDir, 'resident-config.json');
  const env = { ...process.env, VISIONGUARD_ACCOUNT_DIR: accountDir, VISIONGUARD_SERVER_URL: serverUrl, VISIONGUARD_LOGIN_PASSWORD: account.password };
  const result = spawnSync(probe, ['--login', serverUrl, account.username, 'Resident smoke'], { env, encoding: 'utf8', timeout: 30000, windowsHide: true });
  assert.equal(result.status, 0, result.stderr || 'Windows account login failed.');
  const identity = JSON.parse(result.stdout.trim());
  fs.writeFileSync(configPath, JSON.stringify({ ServerUrl: serverUrl, AccountDir: accountDir, DeviceId: identity.deviceId, DeviceName: 'Resident smoke', DetectorPath: 'C:/missing/VisionGuard.Detector.Windows.exe', AppId: identity.appId }), 'utf8');
  delete env.VISIONGUARD_LOGIN_PASSWORD;
  const consoleSession = await login(serverUrl, 'web-console', 'Resident smoke observer');
  const resident = spawn(exe, ['--config', configPath], { cwd: path.dirname(exe), env, windowsHide: true });
  const receiver = new WebSocket(serverUrl.replace(/^http/, 'ws') + '/ws');
  let finished = false, commandSent = false;
  const requestId = `resident-smoke-${process.pid}`;
  const timeout = setTimeout(() => finish(new Error('Resident smoke timed out')), 20000);
  async function finish(error) {
    if (finished) return; finished = true; clearTimeout(timeout);
    try { receiver.close(); } catch {}
    try { resident.kill(); } catch {}
    await logout(serverUrl, consoleSession.token);
    if (error) { console.error(error.message); process.exitCode = 1; }
    else console.log('PASS resident authenticated through the Windows account child, reported heartbeat, and returned a correlated lifecycle result.');
    // Keep the encrypted login in this ignored directory for failed-test diagnostics.
  }
  resident.once('error', finish);
  resident.once('exit', code => { if (!finished) finish(new Error(`Resident exited early with code ${code}`)); });
  receiver.once('error', finish);
  receiver.once('open', () => receiver.send(JSON.stringify({ type: 'auth', token: consoleSession.token })));
  receiver.on('message', data => {
    let message; try { message = JSON.parse(data.toString()); } catch { return; }
    if (message.type === 'auth-result' && !message.success) return finish(new Error('Console account authentication rejected'));
    if (!commandSent && message.type === 'device-list') {
      const target = (message.devices || []).find(device => device.deviceId === identity.deviceId);
      if (target?.components?.resident === 'running') {
        commandSent = true; receiver.send(JSON.stringify({ type: 'command', requestId, targetDeviceId: identity.deviceId, command: 'open-detector' }));
      }
    }
    if (message.type === 'command-ack' && message.requestId === requestId && message.phase === 'completed') {
      try {
        assert.equal(message.targetDeviceId, identity.deviceId); assert.equal(message.command, 'open-detector');
        assert.equal(message.success, false); assert.equal(message.reason, 'configured executable not found'); finish();
      } catch (error) { finish(error); }
    }
  });
})().catch(error => { console.error(error.message); process.exitCode = 1; });
