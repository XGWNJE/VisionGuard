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

function main() {
  const newVersion = process.argv[2] || readRootVersion();
  if (!/^\d+\.\d+\.\d+$/.test(newVersion)) {
    console.error('❌ 版本号格式错误，应为 x.y.z');
    process.exit(1);
  }

  console.log(`🔄 同步版本号到全端: ${newVersion}`);

  // 1. 根目录 VERSION
  writeFile(path.join(ROOT, 'VERSION'), newVersion + '\n');

  // 2. WinForms AssemblyInfo.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-winforms', 'Properties', 'AssemblyInfo.cs'),
    /AssemblyVersion\("[\d.]+"\)/,
    `AssemblyVersion("${newVersion}.0")`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-winforms', 'Properties', 'AssemblyInfo.cs'),
    /AssemblyFileVersion\("[\d.]+"\)/,
    `AssemblyFileVersion("${newVersion}.0")`
  );

  // 3. WPF AppConfig.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Utils', 'AppConfig.cs'),
    /Version = "[\d.]+"/,
    `Version = "${newVersion}"`
  );

  // 4. Android 检测端 build.gradle.kts
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'build.gradle.kts'),
    /versionName = "[\d.]+"/,
    `versionName = "${newVersion}"`
  );

  // 5. Android 检测端 AppConstants.kt
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard', 'AppConstants.kt'),
    /VERSION = "[\d.]+"/,
    `VERSION = "${newVersion}"`
  );

  // 6. Android 检测端 AutoUpdater.kt
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard', 'util', 'AutoUpdater.kt'),
    /CURRENT_VERSION = "[\d.]+"/,
    `CURRENT_VERSION = "${newVersion}"`
  );

  // 7. Android 接收端 build.gradle.kts
  replaceInFile(
    path.join(ROOT, 'receiver', 'android', 'app', 'build.gradle.kts'),
    /versionName = "[\d.]+"/,
    `versionName = "${newVersion}"`
  );

  // 8. Android 接收端 AppConstants.kt (VERSION)
  replaceInFile(
    path.join(ROOT, 'receiver', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard_android', 'AppConstants.kt'),
    /VERSION = "[\d.]+"/,
    `VERSION = "${newVersion}"`
  );

  // 9. Server package.json
  const pkgPath = path.join(ROOT, 'server', 'package.json');
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
  pkg.version = newVersion;
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');

  // 10. Server config.ts / index.ts 硬编码版本
  replaceInFile(
    path.join(ROOT, 'server', 'src', 'config.ts'),
    /minClientVersion: '[\d.]+'/,
    `minClientVersion: '${newVersion}'`
  );
  replaceInFile(
    path.join(ROOT, 'server', 'src', 'index.ts'),
    /v[\d.]+/g,
    `v${newVersion}`
  );

  // 11. WPF .csproj (Version/FileVersion/AssemblyVersion) — 同旧标识 10
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
      const ext = key === 'android-detector' || key === 'android-receiver' ? 'apk' : 'zip';
      const prefix = key === 'android-detector' ? 'VisionGuard-Detector' :
                     key === 'android-receiver' ? 'VisionGuard-Receiver' :
                     key === 'wpf' ? 'VisionGuard-WPF' : 'VisionGuard';
      releases[key].url = `/releases/${prefix}-v${newVersion}.${ext}`;
    }
    fs.writeFileSync(releasesPath, JSON.stringify(releases, null, 2) + '\n');
  }

  // 11. WinForms ServerPushService.cs 硬编码版本
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-winforms', 'Services', 'ServerPushService.cs'),
    /\["version"\] = "[\d.]+"/,
    `["version"] = "${newVersion}"`
  );

  // 12. WPF ServerPushService.cs 硬编码版本
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Services', 'ServerPushService.cs'),
    /\["version"\] = "[\d.]+"/,
    `["version"] = "${newVersion}"`
  );

  // 13. WinForms AutoUpdater.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-winforms', 'Utils', 'AutoUpdater.cs'),
    /private const string CurrentVersion = "[\d.]+"/,
    `private const string CurrentVersion = "${newVersion}"`
  );

  // 14. WPF AutoUpdater.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Utils', 'AutoUpdater.cs'),
    /private const string CurrentVersion = "[\d.]+"/,
    `private const string CurrentVersion = "${newVersion}"`
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

main();
