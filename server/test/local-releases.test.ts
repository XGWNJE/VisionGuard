import assert from 'node:assert/strict';
import express from 'express';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import type { AddressInfo } from 'node:net';
import localReleasesRouter, {
  buildLocalReleases,
  localUpdateEntries,
  mergeLocalReleases,
  parseLocalReleases,
} from '../src/routes/localReleases';

const sha256 = 'a'.repeat(64);
const overlay = {
  'android-inference': { version: '0.7.1', fileName: 'VisionGuard-Inference-v0.7.1.apk', size: 1024, sha256 },
};

/** 把路由挂到一个临时端口上，返回基址与关闭函数。 */
async function serve(): Promise<{ base: string; close: () => Promise<void> }> {
  const app = express();
  app.use(localReleasesRouter);
  const server = app.listen(0);
  await new Promise<void>(resolve => server.once('listening', () => resolve()));
  const port = (server.address() as AddressInfo).port;
  return { base: `http://127.0.0.1:${port}`, close: () => new Promise(resolve => server.close(() => resolve())) };
}

function withOverlay<T>(text: string | null, run: () => Promise<T>): Promise<T> {
  const previous = process.env.VISIONGUARD_LOCAL_RELEASES_FILE;
  if (text === null) delete process.env.VISIONGUARD_LOCAL_RELEASES_FILE;
  else {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'vg-local-releases-')), 'releases.local.json');
    fs.writeFileSync(file, text);
    process.env.VISIONGUARD_LOCAL_RELEASES_FILE = file;
  }
  return run().finally(() => {
    if (previous === undefined) delete process.env.VISIONGUARD_LOCAL_RELEASES_FILE;
    else process.env.VISIONGUARD_LOCAL_RELEASES_FILE = previous;
  });
}

test('覆盖层条目按 GitHub 发行形状组装，下载地址指向本实例', () => {
  const entries = parseLocalReleases(JSON.stringify(overlay));
  assert.ok(entries);
  const body = buildLocalReleases(entries, 'http://192.168.31.58:4321', '2026-10-12T00:00:00.000Z') as Array<Record<string, any>>;
  assert.equal(body.length, 1);
  assert.equal(body[0].tag_name, 'v0.7.1');
  assert.equal(body[0].published_at, '2026-10-12T00:00:00.000Z');
  assert.equal(body[0].draft, false);
  assert.equal(body[0].assets[0].name, 'VisionGuard-Inference-v0.7.1.apk');
  assert.equal(body[0].assets[0].browser_download_url, 'http://192.168.31.58:4321/releases/VisionGuard-Inference-v0.7.1.apk');
  assert.equal(body[0].assets[0].size, 1024);
  assert.equal(body[0].assets[0].digest, `sha256:${sha256}`);
  assert.equal(body[0].assets[0].state, 'uploaded');
});

test('覆盖层丢弃字段不合规的条目，而不是发出一个校验不了的包', () => {
  const entries = parseLocalReleases(JSON.stringify({
    'android-inference': overlay['android-inference'],
    broken: { version: '0.7.1', fileName: 'x.apk', size: 0, sha256 },
    'no-version': { fileName: 'x.apk', size: 10, sha256 },
    'no-name': { version: '0.7.1', size: 10, sha256 },
    'bad-hash': { version: '0.7.1', fileName: 'x.apk', size: 10, sha256: 'zz' },
  }));
  assert.deepEqual(Object.keys(entries ?? {}), ['android-inference']);
});

test('覆盖层覆盖同平台条目，其余保留', () => {
  const base: Record<string, { version: string; url?: string }> = { wpf: { version: '0.7.0' }, 'android-inference': { version: '0.7.0' } };
  const merged = mergeLocalReleases(base, localUpdateEntries(parseLocalReleases(JSON.stringify(overlay))!));
  assert.equal(merged.wpf.version, '0.7.0');
  assert.equal(merged['android-inference'].version, '0.7.1');
  assert.equal(merged['android-inference'].url, '/releases/VisionGuard-Inference-v0.7.1.apk');
});

test('未配置本地发行源时端点返回 404，配置后返回本实例的发行列表', async () => {
  const server = await serve();
  try {
    await withOverlay(null, async () => {
      const response = await fetch(`${server.base}/api/local/releases`);
      assert.equal(response.status, 404);
    });
    await withOverlay(JSON.stringify(overlay), async () => {
      const response = await fetch(`${server.base}/api/local/releases`);
      assert.equal(response.status, 200);
      const body = await response.json() as Array<Record<string, any>>;
      assert.equal(body.length, 1);
      assert.equal(body[0].assets[0].browser_download_url, `${server.base}/releases/VisionGuard-Inference-v0.7.1.apk`);
    });
    await withOverlay('{ not json', async () => {
      const response = await fetch(`${server.base}/api/local/releases`);
      assert.equal(response.status, 404);
    });
  } finally {
    await server.close();
  }
});
