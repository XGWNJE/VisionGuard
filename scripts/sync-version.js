#!/usr/bin/env node
// ┌─────────────────────────────────────────────────────────┐
// │ sync-version.js                                         │
// │ 角色：版本号统一同步脚本                                 │
// │ 用法：node scripts/sync-version.js [new-version]        │
// │ 示例：node scripts/sync-version.js 4.1.0               │
// └─────────────────────────────────────────────────────────┘

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

/**
 * releases.json 里各平台条目应指向的产物文件名。
 *
 * 必须与 scripts/publish-release.ps1 实际生成的包名逐字一致，否则更新接口会指向不存在的文件。
 * Windows 检测端是同一份源码的两个推理档位，包必须区分：
 * `wpf` = modern（Windows 10/11，DirectML），`wpf-legacy` = legacy（Windows 7 SP1，纯 CPU）。
 */
function releaseFileName(key, version) {
  if (key === 'android-detector') return `VisionGuard-Detector-v${version}.apk`;
  if (key === 'android-receiver') return `VisionGuard-Receiver-v${version}.apk`;
  if (key === 'wpf-legacy') return `VisionGuard-WPF-Legacy-v${version}.zip`;
  if (key === 'wpf') return `VisionGuard-WPF-v${version}.zip`;
  return `VisionGuard-${key}-v${version}.zip`;
}

function main() {
  const newVersion = process.argv[2] || readRootVersion();
  if (!/^\d+\.\d+\.\d+$/.test(newVersion)) {
    console.error('❌ 版本号格式错误，应为 x.y.z');
    process.exit(1);
  }
  const [major, minor, patch] = newVersion.split('.').map(Number);
  const versionCode = major * 1000 + minor * 100 + patch;

  console.log(`🔄 同步版本号到全端: ${newVersion}`);

  // 1. 根目录 VERSION
  writeFile(path.join(ROOT, 'VERSION'), newVersion + '\n');

  // 2. Windows 检测端 AppConfig.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Utils', 'AppConfig.cs'),
    /Version\s*=\s*"[\d.]+"/,
    `Version = "${newVersion}"`
  );

  // 3. Android 检测端 build.gradle.kts
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'build.gradle.kts'),
    /versionName = "[\d.]+"/,
    `versionName = "${newVersion}"`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'build.gradle.kts'),
    /versionCode = \d+/,
    `versionCode = ${versionCode}`
  );

  // 4. Android 检测端 AppConstants.kt
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard', 'AppConstants.kt'),
    /VERSION = "[\d.]+"/,
    `VERSION = "${newVersion}"`
  );

  // 5. Android 检测端 AutoUpdater.kt
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard', 'util', 'AutoUpdater.kt'),
    /CURRENT_VERSION = "[\d.]+"/,
    `CURRENT_VERSION = "${newVersion}"`
  );

  // 6. Android 接收端 build.gradle.kts
  replaceInFile(
    path.join(ROOT, 'receiver', 'android', 'app', 'build.gradle.kts'),
    /versionName = "[\d.]+"/,
    `versionName = "${newVersion}"`
  );
  replaceInFile(
    path.join(ROOT, 'receiver', 'android', 'app', 'build.gradle.kts'),
    /versionCode = \d+/,
    `versionCode = ${versionCode}`
  );

  // 7. Android 接收端 AppConstants.kt (VERSION)
  replaceInFile(
    path.join(ROOT, 'receiver', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard_android', 'AppConstants.kt'),
    /VERSION = "[\d.]+"/,
    `VERSION = "${newVersion}"`
  );

  // 8. Server package.json
  const pkgPath = path.join(ROOT, 'server', 'package.json');
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
  pkg.version = newVersion;
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');

  // 8.1 Server package-lock.json
  const lockPath = path.join(ROOT, 'server', 'package-lock.json');
  if (fs.existsSync(lockPath)) {
    const lock = JSON.parse(fs.readFileSync(lockPath, 'utf-8'));
    lock.version = newVersion;
    if (lock.packages && lock.packages['']) {
      lock.packages[''].version = newVersion;
    }
    fs.writeFileSync(lockPath, JSON.stringify(lock, null, 2) + '\n');
    console.log(`  ✓ ${path.relative(ROOT, lockPath)}`);
  }

  // 9. Server index.ts 硬编码版本
  replaceInFile(
    path.join(ROOT, 'server', 'src', 'index.ts'),
    /v[\d.]+/g,
    `v${newVersion}`
  );

  // 10. Windows 检测端 .csproj (Version/FileVersion/AssemblyVersion)
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.csproj'),
    /<Version>[\d.]+<\/Version>/,
    `<Version>${newVersion}</Version>`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.csproj'),
    /<FileVersion>[\d.]+<\/FileVersion>/,
    `<FileVersion>${newVersion}</FileVersion>`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.csproj'),
    /<AssemblyVersion>[\d.]+<\/AssemblyVersion>/,
    `<AssemblyVersion>${newVersion}</AssemblyVersion>`
  );

  // 11. Server releases.json
  const releasesPath = path.join(ROOT, 'server', 'data', 'releases.json');
  if (fs.existsSync(releasesPath)) {
    const releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
    for (const key of Object.keys(releases)) {
      releases[key].version = newVersion;
      releases[key].url = `/releases/${releaseFileName(key, newVersion)}`;
    }
    fs.writeFileSync(releasesPath, JSON.stringify(releases, null, 2) + '\n');
  }

  // 12. Windows 检测端 ServerPushService.cs 硬编码版本
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Services', 'ServerPushService.cs'),
    /\["version"\] = "[\d.]+"/,
    `["version"] = "${newVersion}"`
  );

  console.log('✅ 版本号同步完成');
}

function readRootVersion() {
  return fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf-8').trim();
}

function writeFile(filePath, content) {
  fs.writeFileSync(filePath, content);
  console.log(`  ✓ ${path.relative(ROOT, filePath)}`);
}

function replaceInFile(filePath, pattern, replacement) {
  let content = fs.readFileSync(filePath, 'utf-8');
  const newContent = content.replace(pattern, replacement);
  if (newContent !== content) {
    fs.writeFileSync(filePath, newContent);
    console.log(`  ✓ ${path.relative(ROOT, filePath)}`);
  }
}

// 只在直接执行时同步；被 require 引入（例如契约测试）时不得改写任何文件。
if (require.main === module) {
  main();
}

module.exports = { releaseFileName };
