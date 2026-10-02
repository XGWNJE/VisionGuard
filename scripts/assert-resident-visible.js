"use strict";
// node scripts/assert-resident-visible.js <service-url> <server-assigned-deviceId>
const WebSocket = require('../server/node_modules/ws');
const { login, logout } = require('./windows-account-test-lib');
const [url, deviceId] = process.argv.slice(2);
if (!url || !deviceId) { console.error('usage: node scripts/assert-resident-visible.js <service-url> <deviceId>'); process.exit(2); }
(async () => {
  const session = await login(url, 'web-console', 'Resident visibility');
  const receiver = new WebSocket(url.replace(/^http/, 'ws').replace(/\/$/, '') + '/ws');
  let settled = false;
  const timeout = setTimeout(() => finish(false, 'timeout waiting for resident component'), 25000);
  async function finish(visible, detail) {
    if (settled) return; settled = true; clearTimeout(timeout);
    try { receiver.close(); } catch {}
    await logout(url, session.token);
    console.log(JSON.stringify({ visible, deviceId, detail: detail || null })); process.exitCode = visible ? 0 : 1;
  }
  receiver.on('open', () => receiver.send(JSON.stringify({ type: 'auth', token: session.token })));
  receiver.on('message', data => {
    let message; try { message = JSON.parse(data.toString()); } catch { return; }
    if (message.type === 'auth-result' && !message.success) return finish(false, 'account authentication rejected');
    if (message.type !== 'device-list') return;
    const device = (message.devices || []).find(item => item.deviceId === deviceId);
    if (device?.components?.resident === 'running') finish(true, device.components);
  });
  receiver.on('error', error => finish(false, 'receiver error: ' + error.message));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
