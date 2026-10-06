#!/usr/bin/env node
const http = require('node:http');
const https = require('node:https');
const { randomBytes, createHash } = require('node:crypto');

// Upgrade only: never authenticate, publish frames, or subscribe to an account.
function checkUpgrade(baseUrl, pathname, timeoutMs = 10000) {
  const url = new URL(pathname, baseUrl);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Expected an HTTP(S) service URL');
  const key = randomBytes(16).toString('base64');
  const expectedAccept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  return new Promise((resolve, reject) => {
    const request = (url.protocol === 'https:' ? https : http).request(url, {
      headers: { Upgrade: 'websocket', Connection: 'Upgrade', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': key },
    });
    const timer = setTimeout(() => request.destroy(new Error('Handshake timed out')), timeoutMs);
    request.once('error', (error) => { clearTimeout(timer); reject(new Error(`${pathname}: ${error.message}`)); });
    request.once('response', (response) => {
      clearTimeout(timer);
      response.destroy();
      reject(new Error(`${pathname}: HTTP ${response.statusCode}; expected WebSocket 101`));
    });
    request.once('upgrade', (response, socket) => {
      clearTimeout(timer);
      socket.on('error', () => {});
      socket.destroy();
      if (response.statusCode !== 101 || response.headers.upgrade?.toLowerCase() !== 'websocket' || response.headers['sec-websocket-accept'] !== expectedAccept) {
        reject(new Error(`${pathname}: Invalid WebSocket upgrade`));
      } else resolve(`${pathname}=101`);
    });
    request.end();
  });
}

async function checkWebSocketEntrypoints(baseUrl) {
  // Attempt both even when one fails, so diagnostics identify each ingress path.
  const results = await Promise.allSettled(['/ws', '/media/ws'].map((pathname) => checkUpgrade(baseUrl, pathname)));
  const failures = results.filter((result) => result.status === 'rejected');
  if (failures.length) throw new Error(failures.map((result) => result.reason.message).join('; '));
  return results.map((result) => result.value);
}

if (require.main === module) {
  const index = process.argv.indexOf('--base-url');
  const baseUrl = index < 0 ? 'https://visionguard.xgwnje.cn' : process.argv[index + 1];
  if (!baseUrl) { console.error('--base-url requires a value'); process.exitCode = 1; }
  else checkWebSocketEntrypoints(baseUrl).then(
    (results) => console.log(`WebSocket entrypoints passed: ${results.join(', ')}`),
    (error) => { console.error(`WebSocket entrypoints failed: ${error.message}`); process.exitCode = 1; },
  );
}
module.exports = { checkUpgrade, checkWebSocketEntrypoints };
