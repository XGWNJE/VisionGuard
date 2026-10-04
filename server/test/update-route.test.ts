import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import path from 'node:path';
import { resolveReleaseKey } from '../src/routes/update';

function readReleases(): Record<string, { version: string; url: string; size: number }> {
  const releasesPath = path.resolve(__dirname, '..', 'data', 'releases.json');
  return JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
}

test('resolveReleaseKey 把 Windows 平台别名统一到 wpf', () => {
  assert.equal(resolveReleaseKey('wpf'), 'wpf');
  assert.equal(resolveReleaseKey('windows'), 'wpf');
  assert.equal(resolveReleaseKey('winforms'), 'winforms');
});

test('resolveReleaseKey 解析 Android 平台', () => {
  assert.equal(resolveReleaseKey('android-detector'), 'android-detector');
  assert.equal(resolveReleaseKey('android'), 'android-receiver');
  assert.equal(resolveReleaseKey('android-receiver'), 'android-receiver');
  assert.equal(resolveReleaseKey('android-notifier'), 'android-notifier');
});

test('releases.json 的平台条目自洽', () => {
  const releases = readReleases();
  const keys = Object.keys(releases);
  assert.ok(keys.includes('wpf'), '缺少 wpf 条目');
  assert.ok(keys.includes('android-detector'), '缺少 android-detector 条目');
  assert.ok(keys.includes('android-receiver'), '缺少 android-receiver 条目');
  assert.ok(keys.includes('android-notifier'), '缺少 android-notifier 条目');
  // 发布维护四个平台，Windows 不按内部运行时拆分条目。
  assert.ok(!keys.includes('winforms'), 'winforms 条目应已退役');
  assert.ok(!keys.includes('wpf-legacy'), 'Windows 发布条目必须统一为 wpf');
  // 每个条目的 URL 必须是它的版本号对应的文件名，避免发布脚本改名后静默错配。
  for (const [platform, entry] of Object.entries(releases)) {
    assert.ok(
      path.basename(entry.url).includes(`v${entry.version}`),
      `${platform} 的 url ${entry.url} 与版本 ${entry.version} 不一致`
    );
  }
});
