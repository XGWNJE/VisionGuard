const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const { checkVersionSources } = require('./check-docs');

const ROOT = path.resolve(__dirname, '..');
const { releaseFileName } = require('./sync-version.js');

test('releaseFileName 为 Windows 生成唯一统一包名', () => {
  assert.equal(releaseFileName('wpf', '4.4.4'), 'VisionGuard-WPF-v4.4.4.zip');
});

test('releaseFileName 覆盖全部现存平台键', () => {
  assert.equal(releaseFileName('android-detector', '4.4.4'), 'VisionGuard-Detector-v4.4.4.apk');
  assert.equal(releaseFileName('android-receiver', '4.4.4'), 'VisionGuard-Receiver-v4.4.4.apk');
});

test('releaseFileName 的产物名必须逐字出现在发布脚本里', () => {
  const publishScript = fs.readFileSync(path.join(ROOT, 'scripts', 'publish-release.ps1'), 'utf-8');
  // sync-version.js 只改 releases.json 的 url，真正生成包的是 publish-release.ps1。
  // 两边文件名写法漂移会让更新接口指向不存在的文件，所以在这里对死。
  for (const key of ['wpf', 'android-detector', 'android-receiver']) {
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
  assert.ok(!keys.includes('wpf-legacy'), '视觉节点（Windows）不得再拆分发布条目');
  for (const [key, entry] of Object.entries(releases)) {
    assert.equal(
      entry.url,
      `/releases/${releaseFileName(key, entry.version)}`,
      `${key} 的 url 与 releaseFileName 推导出的文件名不一致`
    );
  }
});

test('被搁置的平台在统一版本同步时保留最后一个已发布包', () => {
  const syncScript = fs.readFileSync(path.join(ROOT, 'scripts', 'sync-version.js'), 'utf-8');
  const releases = JSON.parse(fs.readFileSync(path.join(ROOT, 'server', 'data', 'releases.json'), 'utf-8'));
  const detector = releases['android-detector'];

  assert.equal(detector.heldBack, true);
  assert.equal(detector.version, '4.4.4');
  assert.equal(detector.url, '/releases/VisionGuard-Detector-v4.4.4.apk');
  assert.match(syncScript, /releases\[key\]\.heldBack === true/, 'sync-version must preserve held-back entries');
});

function withVersionFixture(callback) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-version-'));
  const files = [
    'VERSION', 'README.md', 'scripts/sync-version.js',
    'detector/windows-wpf/Utils/AppConfig.cs',
    'detector/windows-wpf/VisionGuard.Detector.Windows.csproj',
    'detector/windows-launcher/Program.cs',
    'detector/windows-launcher/VisionGuard.Detector.Windows.Launcher.csproj',
    'detector/windows-resident/VisionGuard.Resident.Windows.csproj',
    'detector/android/app/build.gradle.kts',
    'detector/android/app/src/main/java/com/xgwnje/visionguard/detector/AppConstants.kt',
    'receiver/android/app/build.gradle.kts',
    'receiver/android/app/src/main/java/com/xgwnje/visionguard/receiver/AppConstants.kt',
    'notifier/android/app/build.gradle.kts',
    'server/package.json', 'server/package-lock.json', 'server/src/index.ts',
    'server/data/releases.json', 'receiver/web/package.json', 'receiver/web/package-lock.json'
  ];
  try {
    for (const file of files) {
      const target = path.join(root, file);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(path.join(ROOT, file), target);
    }
    callback(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function syncFixture(root, ...args) {
  const result = spawnSync(process.execPath, [path.join(root, 'scripts/sync-version.js'), ...args], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr || result.stdout);
}

test('0.x source-only synchronization aligns every component without inventing released packages', () => {
  withVersionFixture(root => {
    const releasesPath = path.join(root, 'server/data/releases.json');
    const existingReleases = fs.readFileSync(releasesPath);
    syncFixture(root, '0.5.1', '--source-only');
    assert.equal(fs.readFileSync(path.join(root, 'VERSION'), 'utf8').trim(), '0.5.1');
    const errors = [];
    checkVersionSources(root, '0.5.1', errors);
    assert.deepEqual(errors, []);
    assert.deepEqual(fs.readFileSync(releasesPath), existingReleases);
    assert.match(fs.readFileSync(path.join(root, 'README.md'), 'utf8'), /badge\/version-0\.5\.1-/);
    // 幂等性与无参数时读取根 VERSION，避免第二次同步改写已有下载包。
    syncFixture(root, '--source-only');
    assert.deepEqual(fs.readFileSync(releasesPath), existingReleases);
  });
});

test('release synchronization still updates released targets and preserves held-back packages', () => {
  withVersionFixture(root => {
    const releasesPath = path.join(root, 'server/data/releases.json');
    const before = JSON.parse(fs.readFileSync(releasesPath, 'utf8'));
    syncFixture(root, '0.5.2');
    const after = JSON.parse(fs.readFileSync(releasesPath, 'utf8'));
    assert.deepEqual(after['android-detector'], before['android-detector']);
    for (const key of ['wpf', 'android-receiver']) {
      assert.equal(after[key].version, '0.5.2');
      assert.equal(after[key].url, `/releases/${releaseFileName(key, '0.5.2')}`);
    }
  });
});

test('0.x audit rejects resident or Web version drift and invalid existing package metadata', () => {
  withVersionFixture(root => {
    syncFixture(root, '0.5.1', '--source-only');
    const resident = path.join(root, 'detector/windows-resident/VisionGuard.Resident.Windows.csproj');
    fs.writeFileSync(resident, fs.readFileSync(resident, 'utf8').replace('<Version>0.5.1</Version>', '<Version>9.0.0</Version>'));
    const web = path.join(root, 'receiver/web/package.json');
    const webPackage = JSON.parse(fs.readFileSync(web, 'utf8'));
    webPackage.version = '9.0.0';
    fs.writeFileSync(web, JSON.stringify(webPackage));
    const releasesPath = path.join(root, 'server/data/releases.json');
    const releases = JSON.parse(fs.readFileSync(releasesPath, 'utf8'));
    releases.wpf.url = '/releases/nonexistent.zip';
    fs.writeFileSync(releasesPath, JSON.stringify(releases));
    const errors = [];
    checkVersionSources(root, '0.5.1', errors);
    assert.ok(errors.some(error => error.includes('windows-resident')));
    assert.ok(errors.some(error => error.includes('receiver/web/package.json')));
    assert.ok(errors.some(error => error.includes('invalid package metadata')));
  });
});
