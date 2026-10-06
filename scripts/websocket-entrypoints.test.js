const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createHash } = require('node:crypto');
const { checkUpgrade, checkWebSocketEntrypoints } = require('./check-websocket-entrypoints');

async function fixture(t, upgrade) {
  const server = http.createServer((request, response) => response.writeHead(404).end());
  const sockets = new Set();
  server.on('connection', (socket) => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  server.on('upgrade', upgrade);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise((resolve) => server.close(resolve)); });
  return `http://127.0.0.1:${server.address().port}`;
}
function accept(request, socket) {
  const digest = createHash('sha1').update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${digest}\r\n\r\n`);
}

test('both paths must upgrade; handshake sends no credentials or business data', async (t) => {
  const paths = [];
  const base = await fixture(t, (request, socket) => {
    paths.push(request.url);
    assert.equal(request.headers.authorization, undefined);
    assert.equal(request.headers.cookie, undefined);
    socket.on('data', (data) => assert.fail(`Unexpected business data: ${data.length} bytes`));
    accept(request, socket);
  });
  assert.deepEqual(await checkWebSocketEntrypoints(base), ['/ws=101', '/media/ws=101']);
  assert.deepEqual(paths.sort(), ['/media/ws', '/ws']);
});
test('control success cannot hide media ingress HTTP 404', async (t) => {
  const base = await fixture(t, (request, socket) => {
    if (request.url === '/ws') accept(request, socket);
    else socket.end('HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
  });
  await assert.rejects(checkWebSocketEntrypoints(base), /\/media\/ws: HTTP 404/);
});
test('rejects a forged 101 with an invalid accept header', async (t) => {
  const base = await fixture(t, (request, socket) => socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: invalid\r\n\r\n'));
  await assert.rejects(checkUpgrade(base, '/media/ws'), /Invalid WebSocket upgrade/);
});
test('stalled handshakes terminate within the specified timeout', async (t) => {
  const base = await fixture(t, () => {});
  await assert.rejects(checkUpgrade(base, '/media/ws', 50), /Handshake timed out/);
});
