#!/usr/bin/env node
// ┌─────────────────────────────────────────────────────────┐
// │ sync-version.js                                         │
// │ 角色：版本号统一同步脚本                                 │
// │ 用法：node scripts/sync-version.js [version] [--source-only] │
// └─────────────────────────────────────────────────────────┘

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

/**
 * releases.json 里各平台条目应指向的产物文件名。
 *
 * 必须与 scripts/publish-release.ps1 实际生成的包名逐字一致，否则更新接口会指向不存在的文件。
 * 视觉节点（Windows）只发布一个整包，启动器按操作系统选择内部推理运行时。
 */
function releaseFileName(key, version) {
  if (key === 'android-detector') return `VisionGuard-Detector-v${version}.apk`;
  if (key === 'android-notifier') return `VisionGuard-Notifier-v${version}.apk`;
  if (key === 'android-receiver') return `VisionGuard-Receiver-v${version}.apk`;
  if (key === 'wpf') return `VisionGuard-WPF-v${version}.zip`;
  return `VisionGuard-${key}-v${version}.zip`;
}

function main() {
  const args = process.argv.slice(2);
  const sourceOnly = args.includes('--source-only');
  const versions = args.filter(arg => arg !== '--source-only');
  const newVersion = versions[0] || readRootVersion();
  if (versions.length > 1 || !/^\d+\.\d+\.\d+$/.test(newVersion)) {
    console.error('❌ 版本号格式错误，应为 x.y.z');
    process.exit(1);
  }
  const [major, minor, patch] = newVersion.split('.').map(Number);
  const versionCode = major * 1000 + minor * 100 + patch;

  console.log(`🔄 同步版本号到全端: ${newVersion}`);

  // 1. 根目录 VERSION
  writeFile(path.join(ROOT, 'VERSION'), newVersion + '\n');

  // 2. 视觉节点（Windows） AppConfig.cs
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'Utils', 'AppConfig.cs'),
    /Version\s*=\s*"[\d.]+"/,
    `Version = "${newVersion}"`
  );

  // 3. 相机推流节点（Android） build.gradle.kts
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

  // 4. 相机推流节点（Android） AppConstants.kt
  replaceInFile(
    path.join(ROOT, 'detector', 'android', 'app', 'src', 'main', 'java', 'com', 'xgwnje', 'visionguard', 'detector', 'AppConstants.kt'),
    /VERSION = "[\d.]+"/,
    `VERSION = "${newVersion}"`
  );

  // Server 与 Web 的工程及锁文件版本。
  for (const directory of ['server', 'receiver/web']) {
    for (const fileName of ['package.json', 'package-lock.json']) {
      const filePath = path.join(ROOT, directory, fileName);
      const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
      data.version = newVersion;
      if (fileName === 'package-lock.json' && data.packages?.['']) {
        data.packages[''].version = newVersion;
      }
      writeFile(filePath, JSON.stringify(data, null, 2) + '\n');
    }
  }

  // 9. Server index.ts 硬编码版本
  replaceInFile(
    path.join(ROOT, 'server', 'src', 'index.ts'),
    /v[\d.]+/g,
    `v${newVersion}`
  );

  // 10. 视觉节点（Windows） .csproj (Version/FileVersion/AssemblyVersion)
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.Detector.Windows.csproj'),
    /<Version>[\d.]+<\/Version>/,
    `<Version>${newVersion}</Version>`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.Detector.Windows.csproj'),
    /<FileVersion>[\d.]+<\/FileVersion>/,
    `<FileVersion>${newVersion}</FileVersion>`
  );
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-wpf', 'VisionGuard.Detector.Windows.csproj'),
    /<AssemblyVersion>[\d.]+<\/AssemblyVersion>/,
    `<AssemblyVersion>${newVersion}</AssemblyVersion>`
  );

  // 10.1 Windows 启动器必须与统一包使用同一版本号。
  replaceInFile(
    path.join(ROOT, 'detector', 'windows-launcher', 'Program.cs'),
    /private const string Version = "[\d.]+"/,
    `private const string Version = "${newVersion}"`
  );
  for (const element of ['Version', 'FileVersion', 'AssemblyVersion']) {
    for (const [directory, project] of [
      ['windows-launcher', 'VisionGuard.Detector.Windows.Launcher.csproj'],
      ['windows-resident', 'VisionGuard.Resident.Windows.csproj']
    ]) {
      replaceInFile(
        path.join(ROOT, 'detector', directory, project),
        new RegExp(`<${element}>[\\d.]+<\\/${element}>`),
        `<${element}>${newVersion}</${element}>`
      );
    }
  }

  // 通知节点直接从 VERSION 计算 versionName / versionCode，无硬编码字段。
  replaceInFile(path.join(ROOT, 'README.md'), /badge\/version-[\d.]+-/, `badge/version-${newVersion}-`);

  // 11. Server releases.json
  const releasesPath = path.join(ROOT, 'server', 'data', 'releases.json');
  if (!sourceOnly && fs.existsSync(releasesPath)) {
    const releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
    for (const key of Object.keys(releases)) {
      // 被明确搁置的端必须继续指向最后一个已发布包。若在统一版本同步时改写，
      // 更新接口会错误地给未上线端返回当前版本及不存在的下载地址。
      if (releases[key].heldBack === true) {
        console.log(`  ↷ ${key} is held back; preserving ${releases[key].version}`);
        continue;
      }
      releases[key].version = newVersion;
      releases[key].url = `/releases/${releaseFileName(key, newVersion)}`;
    }
    fs.writeFileSync(releasesPath, JSON.stringify(releases, null, 2) + '\n');
  }

  if (sourceOnly) console.log('  ↷ source-only: preserving existing release metadata');

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
