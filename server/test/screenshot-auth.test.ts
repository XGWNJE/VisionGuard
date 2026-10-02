import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';

test('registered consoles can read screenshots; detectors, notifiers and anonymous clients cannot', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-screenshot-auth-'));
  process.env.VISIONGUARD_DATA_DIR = directory;
  process.env.API_KEY = 'test-http-administrator';
  process.env.VISIONGUARD_IDENTITIES_FILE = path.join(directory, 'identities.json');
  fs.writeFileSync(process.env.VISIONGUARD_IDENTITIES_FILE, JSON.stringify([
    {deviceId:'web',role:'console',nodeType:'console',platform:'web',apiKey:'test-web-console-key'},
    {deviceId:'visual',role:'detector',nodeType:'visual',platform:'windows',apiKey:'test-visual-detector-key'},
    {deviceId:'vigil',role:'notifier',nodeType:'notification',platform:'android',apiKey:'test-vigil-notifier-key'},
  ]));
  const { config } = require('../src/config') as typeof import('../src/config');
  const router = require('../src/routes/screenshot').default;
  fs.mkdirSync(config.screenshotDir, {recursive:true});
  fs.writeFileSync(path.join(config.screenshotDir, 'test.png'), Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64'));
  const app = express(); app.use(router);
  const server = http.createServer(app);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()));
    fs.rmSync(directory, {recursive:true,force:true});
  });
  const url = `http://127.0.0.1:${(server.address() as any).port}/screenshots/test.png`;
  for (const key of ['test-web-console-key','test-http-administrator']) {
    const response = await fetch(url, {headers:{'X-API-Key':key}});
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/png');
    assert.equal(response.headers.get('cache-control'), 'private, no-store');
    assert.ok((await response.arrayBuffer()).byteLength > 0);
  }
  for (const key of [undefined,'test-visual-detector-key','test-vigil-notifier-key','invalid']) {
    const response = await fetch(url, {headers:key ? {'X-API-Key':key} : {}});
    assert.equal(response.status, 401);
    await response.text();
  }
});
