const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const WebSocket = require('../server/node_modules/ws');

const root = path.resolve(__dirname, '..');
const exe = path.join(root, 'detector/windows-winforms/bin/Release/VisionGuard.exe');
const serverUrl = process.env.VISIONGUARD_SERVER_URL || 'http://127.0.0.1:3100';
const apiKey = process.env.VISIONGUARD_API_KEY;
const channel = process.env.VISIONGUARD_CHANNEL || 'vnext-e2e';
const reportPath = process.argv[2] || path.join(root, 'artifacts/e2e/winforms-server-push.json');
// 来源数量按当前配置取证；四路是默认回归基线。配合 Server 的 MAX_SOURCES_PER_DETECTOR
// 可以验证上限变化后的真实心跳链路，例如上限 6 时传 6。
const sourceCount = Number(process.argv[3] || process.env.VISIONGUARD_SOURCE_COUNT || 4);

assert(apiKey, 'VISIONGUARD_API_KEY is required');
assert(fs.existsSync(exe), `WinForms artifact not found: ${exe}`);
assert(Number.isInteger(sourceCount) && sourceCount >= 2 && sourceCount <= 16,
  `source count must be an integer between 2 and 16, got ${process.argv[3]}`);

// 每路检测频率必须留在 1–5 的合法区间内，因此按序循环而不是直接用序号。
const expectedFps = [];
const expectedNames = [];
for (let i = 1; i <= sourceCount; i++) expectedFps.push(1 + ((i - 1) % 5));

const profileRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-winforms-server-'));
const appData = path.join(profileRoot, 'Roaming');
const settingsDir = path.join(appData, 'VisionGuard');
fs.mkdirSync(settingsDir, { recursive: true });
const settingsPath = path.join(settingsDir, 'settings.ini');
const lines = [
  '# isolated WinForms server-push smoke profile',
  'DeviceId=winforms-server-push-smoke',
  'DeviceName=WinForms Server Push Smoke',
  `WinForms.Source.Count=${sourceCount}`,
];
for (let i = 1; i <= sourceCount; i++) {
  expectedNames.push(`winforms-smoke-source-${i}`);
  lines.push(`WinForms.Source.${i}.Id=winforms-smoke-source-${i}`);
  lines.push(`WinForms.Source.${i}.Name=Smoke Source ${i}`);
  lines.push(`WinForms.Source.${i}.Model=yolov5nu_320`);
  lines.push(`WinForms.Source.${i}.ConfidenceThresholdPct=${40 + (i % 40)}`);
  lines.push(`WinForms.Source.${i}.TargetFps=${expectedFps[i - 1]}`);
  lines.push(`WinForms.Source.${i}.AlertCooldownSeconds=${i + 4}`);
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
const timeout = setTimeout(() => finish(new Error('WinForms server-push smoke timed out')), 20000);
let finished = false;

function finish(error, device) {
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
    sourceCount, deviceId: device.deviceId, capabilities: device.capabilities, sources: device.sources };
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, JSON.stringify(report, null, 2), 'utf8');
  console.log(`PASS WinForms reported ${device.sources.length} independent sources with source-control capability.`);
  console.log(`Report: ${reportPath}`);
}

app.once('error', finish);
app.once('exit', code => { if (!finished) finish(new Error(`WinForms exited early with code ${code}`)); });
receiver.once('error', finish);
receiver.once('open', () => receiver.send(JSON.stringify({
  type: 'auth', channel, apiKey, role: 'android',
  deviceId: `winforms-server-push-receiver-${process.pid}`,
})));
receiver.on('message', raw => {
  try {
    const message = JSON.parse(raw.toString());
    if (message.type !== 'device-list') return;
    const device = message.devices.find(item => item.deviceId === 'winforms-server-push-smoke');
    if (!device || !Array.isArray(device.sources) || device.sources.length !== sourceCount) return;
    assert(device.capabilities.includes('source-control'));
    assert.deepEqual(device.sources.map(source => source.sourceId), expectedNames);
    assert.deepEqual(device.sources.map(source => source.targetSamplingRate), expectedFps);
    assert(device.sources.every(source => source.isMonitoring === false));
    finish(null, device);
  } catch (error) { finish(error); }
});
