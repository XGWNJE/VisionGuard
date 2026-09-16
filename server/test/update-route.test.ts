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
  const releases = { wpf: { version: '1.0.0', url: '/releases/a.zip', size: 1 } };
  assert.equal(resolveReleaseKey(releases, 'wpf', ''), 'wpf');
  assert.equal(resolveReleaseKey(releases, 'windows', ''), 'wpf');
  // V10 起 Windows 只剩单一 WPF 检测端，旧标识不得再被受理。
  assert.equal(resolveReleaseKey(releases, 'winforms', ''), 'winforms');
});

test('resolveReleaseKey 在档位键已登记时按档位分发，未登记时回落到 wpf', () => {
  const withLegacy = {
    wpf: { version: '1.0.0', url: '/releases/modern.zip', size: 1 },
    'wpf-legacy': { version: '1.0.0', url: '/releases/legacy.zip', size: 1 },
  };
  // Win7 上的 legacy 构建只能拿到 legacy 包，两档原生 ONNX Runtime 不兼容。
  assert.equal(resolveReleaseKey(withLegacy, 'wpf', 'legacy'), 'wpf-legacy');
  assert.equal(resolveReleaseKey(withLegacy, 'wpf', 'modern'), 'wpf');

  const withoutLegacy = { wpf: { version: '1.0.0', url: '/releases/modern.zip', size: 1 } };
  // 档位包尚未登记时回落，避免给 legacy 端返回 404。
  assert.equal(resolveReleaseKey(withoutLegacy, 'wpf', 'legacy'), 'wpf');
  // 未知档位不参与分发。
  assert.equal(resolveReleaseKey(withoutLegacy, 'wpf', 'arm64'), 'wpf');
});

test('android 平台不受档位影响', () => {
  const releases = { 'android-detector': { version: '1.0.0', url: '/releases/d.apk', size: 1 } };
  assert.equal(resolveReleaseKey(releases, 'android-detector', 'legacy'), 'android-detector');
  assert.equal(resolveReleaseKey(releases, 'android', 'legacy'), 'android-receiver');
});

test('releases.json 的平台条目自洽', () => {
  const releases = readReleases();
  const keys = Object.keys(releases);
  assert.ok(keys.includes('wpf'), '缺少 wpf 条目');
  assert.ok(keys.includes('android-detector'), '缺少 android-detector 条目');
  assert.ok(keys.includes('android-receiver'), '缺少 android-receiver 条目');
  // WinForms 检测端已退役，条目不得再留在发布元数据里。
  assert.ok(!keys.includes('winforms'), 'winforms 条目应已退役');
  // 每个条目的 URL 必须是它的版本号对应的文件名，避免发布脚本改名后静默错配。
  for (const [platform, entry] of Object.entries(releases)) {
    assert.ok(
      path.basename(entry.url).includes(`v${entry.version}`),
      `${platform} 的 url ${entry.url} 与版本 ${entry.version} 不一致`
    );
  }
});
