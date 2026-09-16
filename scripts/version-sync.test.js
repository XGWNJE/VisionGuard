const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const { releaseFileName } = require('./sync-version.js');

test('releaseFileName 为 Windows 两个推理档位产出互不相同的包名', () => {
  const modern = releaseFileName('wpf', '4.4.4');
  const legacy = releaseFileName('wpf-legacy', '4.4.4');
  assert.equal(modern, 'VisionGuard-WPF-v4.4.4.zip');
  assert.equal(legacy, 'VisionGuard-WPF-Legacy-v4.4.4.zip');
  // 两档包名不得相同：modern 是 Win10/11 包，legacy 是 Win7 SP1 包，混用会导致更新后端加载失败。
  assert.notEqual(modern, legacy);
});

test('releaseFileName 覆盖全部现存平台键', () => {
  assert.equal(releaseFileName('android-detector', '4.4.4'), 'VisionGuard-Detector-v4.4.4.apk');
  assert.equal(releaseFileName('android-receiver', '4.4.4'), 'VisionGuard-Receiver-v4.4.4.apk');
});

test('releaseFileName 的产物名必须逐字出现在发布脚本里', () => {
  const publishScript = fs.readFileSync(path.join(ROOT, 'scripts', 'publish-release.ps1'), 'utf-8');
  // sync-version.js 只改 releases.json 的 url，真正生成包的是 publish-release.ps1。
  // 两边文件名写法漂移会让更新接口指向不存在的文件，所以在这里对死。
  for (const key of ['wpf', 'wpf-legacy', 'android-detector', 'android-receiver']) {
    const fileName = releaseFileName(key, '$Version');
    assert.ok(
      publishScript.includes(fileName),
      `publish-release.ps1 里找不到 ${key} 的产物名 ${fileName}`
    );
  }
});

test('releases.json 的 url 与 releaseFileName 一致', () => {
  const releasesPath = path.join(ROOT, 'server', 'data', 'releases.json');
  const releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
  const keys = Object.keys(releases);
  assert.ok(keys.length > 0, 'releases.json 不应为空');
  assert.ok(!keys.includes('winforms'), 'WinForms 检测端已退役，条目不得再留在发布元数据里');
  for (const [key, entry] of Object.entries(releases)) {
    assert.equal(
      entry.url,
      `/releases/${releaseFileName(key, entry.version)}`,
      `${key} 的 url 与 releaseFileName 推导出的文件名不一致`
    );
  }
});
