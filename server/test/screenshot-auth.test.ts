import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import express from 'express';
import test from 'node:test';
import { registration } from './helpers/accounts';

test('registered consoles can read screenshots; detectors, notifiers and anonymous clients cannot', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-screenshot-auth-'));
  process.env.VISIONGUARD_DATA_DIR = directory;
  const { accountStore, accountDirectory } = require('../src/services/AccountStore') as typeof import('../src/services/AccountStore');
  const { addAlert } = require('../src/services/AlertStore') as typeof import('../src/services/AlertStore');
  const account = accountStore.createAccount('picture-owner', 'private-picture-password');
  const sessions = await Promise.all(['web-console', 'windows-inference', 'android-notifier'].map(component => accountStore.login({ ...registration(), username: account.username, password: 'private-picture-password', component })));
  const router = require('../src/routes/screenshot').default;
  const alertId = 'picture-event-1234';
  const file = path.join(accountDirectory(account.accountId), 'screenshots', `${alertId}.png`);
  fs.mkdirSync(path.dirname(file), {recursive:true});
  fs.writeFileSync(file, Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64'));
  addAlert(account.accountId, { alertId, deviceId: sessions[1].device.deviceId, deviceName: 'Visual', timestamp: new Date().toISOString(), detections: [], createdAt: Date.now(), screenshotPath: file });
  const app = express(); app.use(router);
  const server = http.createServer(app);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise<void>(resolve => server.close(() => resolve()));
    fs.rmSync(directory, {recursive:true,force:true});
  });
  const url = `http://127.0.0.1:${(server.address() as any).port}/screenshots/picture-event-1234.png`;
  for (const key of [sessions[0].token]) {
    const response = await fetch(url, {headers:{Authorization:`Bearer ${key}`}});
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/png');
    assert.equal(response.headers.get('cache-control'), 'private, no-store');
    assert.ok((await response.arrayBuffer()).byteLength > 0);
  }
  for (const key of [undefined,sessions[1].token,sessions[2].token,'invalid']) {
    const response = await fetch(url, {headers:key ? {Authorization:`Bearer ${key}`} : {}});
    assert.equal(response.status, key === sessions[1].token || key === sessions[2].token ? 403 : 401);
    await response.text();
  }
});
