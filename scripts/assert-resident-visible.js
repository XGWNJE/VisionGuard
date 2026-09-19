// 接收端侧断言：服务端是否真的看到了这台设备的驻留组件（components.resident === 'running'）。
// 用法：node scripts/assert-resident-visible.js <ws-url> <channel> <apiKey> <deviceId>
// 退出码 0 = 可见；1 = 超时或接收端错误；2 = 参数缺失。
// 用于「检测端 -> 同目录驻留 -> Server」这一段是否真的贯通，服务端视角而不是本地进程视角。
const WebSocket = require('../server/node_modules/ws');

const [url, channel, apiKey, deviceId] = process.argv.slice(2);
if (!url || !channel || !apiKey || !deviceId) {
  console.error('usage: node scripts/assert-resident-visible.js <ws-url> <channel> <apiKey> <deviceId>');
  process.exit(2);
}

const receiver = new WebSocket(url.replace(/^http/, 'ws') + '/ws');
const deadline = Date.now() + 25000;
let settled = false;

function finish(visible, detail) {
  if (settled) return;
  settled = true;
  console.log(JSON.stringify({ visible, deviceId, detail: detail || null }));
  try { receiver.close(); } catch {}
  process.exit(visible ? 0 : 1);
}

receiver.on('open', () => {
  receiver.send(JSON.stringify({
    type: 'auth', channel, apiKey, role: 'android', deviceId: `resident-visibility-${process.pid}`,
  }));
});

receiver.on('message', (data) => {
  let message;
  try { message = JSON.parse(data.toString()); } catch { return; }
  if (message.type !== 'device-list') return;

  const device = (message.devices || []).find((item) => item.deviceId === deviceId);
  const components = device && device.components ? device.components : null;
  if (components && components.resident === 'running') {
    finish(true, components);
  }
});

receiver.on('error', (error) => {
  console.error('receiver error: ' + error.message);
  finish(false, 'receiver error: ' + error.message);
});

const timer = setInterval(() => {
  if (Date.now() > deadline) { clearInterval(timer); finish(false, 'timeout waiting for resident component'); }
}, 500);
timer.unref?.();
