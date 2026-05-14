#!/usr/bin/env node
// ┌─────────────────────────────────────────────────────────┐
// │ release.js                                              │
// │ 角色：开发者一键发布脚本                                 │
// │ 用法：node scripts/release.js [version]                 │
// │ 示例：node scripts/release.js 4.1.0                    │
// └─────────────────────────────────────────────────────────┘

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const RELEASES_DIR = path.join(ROOT, 'server', 'data', 'releases');

function main() {
  const version = process.argv[2];
  if (!version) {
    console.error('❌ 请指定版本号，例如: node scripts/release.js 4.1.0');
    process.exit(1);
  }

  console.log(`🚀 开始发布 VisionGuard v${version}\n`);

  // 1. 同步版本号
  console.log('📌 步骤 1/4: 同步版本号到全端…');
  execSync(`node "${path.join(ROOT, 'scripts', 'sync-version.js')}" ${version}`, { stdio: 'inherit' });

  // 2. 编译各端
  console.log('\n📌 步骤 2/4: 编译各端…');

  // Server
  console.log('  🔨 Server…');
  execSync('npm run build', { cwd: path.join(ROOT, 'server'), stdio: 'inherit' });

  // WPF
  console.log('  🔨 WPF…');
  execSync('dotnet build "detector/windows/VisionGuard.csproj" -c Release -v minimal', { cwd: ROOT, stdio: 'inherit' });

  // WinForms
  console.log('  🔨 WinForms…');
  const msbuildPath = findMsBuild();
  execSync(`"${msbuildPath}" "detector/windows-winforms/VisionGuard.csproj" /p:Configuration=Release /v:minimal /nologo`, { cwd: ROOT, stdio: 'inherit' });

  // Android 检测端
  console.log('  🔨 Android 检测端…');
  const javaHome = process.env.JAVA_HOME || 'C:\\Android\\Android Studio\\jbr';
  execSync('.\\gradlew.bat assembleRelease --console=plain', {
    cwd: path.join(ROOT, 'detector', 'android'),
    env: { ...process.env, JAVA_HOME: javaHome },
    stdio: 'inherit',
  });

  // Android 接收端
  console.log('  🔨 Android 接收端…');
  execSync('.\\gradlew.bat assembleRelease --console=plain', {
    cwd: path.join(ROOT, 'receiver', 'android'),
    env: { ...process.env, JAVA_HOME: javaHome },
    stdio: 'inherit',
  });

  // 3. 复制构建产物到 releases 目录
  console.log('\n📌 步骤 3/4: 复制构建产物…');
  fs.mkdirSync(RELEASES_DIR, { recursive: true });

  const artifacts = [
    {
      src: path.join(ROOT, 'detector', 'windows-winforms', 'bin', 'Release', 'VisionGuard.exe'),
      dest: path.join(RELEASES_DIR, `VisionGuard-v${version}.zip`),
      isZip: true,
      sourceDir: path.join(ROOT, 'detector', 'windows-winforms', 'bin', 'Release'),
    },
    {
      src: path.join(ROOT, 'detector', 'windows', 'bin', 'Release', 'net9.0-windows', 'VisionGuard.exe'),
      dest: path.join(RELEASES_DIR, `VisionGuard-WPF-v${version}.zip`),
      isZip: true,
      sourceDir: path.join(ROOT, 'detector', 'windows', 'bin', 'Release', 'net9.0-windows'),
    },
    {
      src: path.join(ROOT, 'detector', 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release-unsigned.apk'),
      dest: path.join(RELEASES_DIR, `VisionGuard-Detector-v${version}.apk`),
      isZip: false,
    },
    {
      src: path.join(ROOT, 'receiver', 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release-unsigned.apk'),
      dest: path.join(RELEASES_DIR, `VisionGuard-Receiver-v${version}.apk`),
      isZip: false,
    },
  ];

  for (const art of artifacts) {
    if (art.isZip) {
      // 使用 PowerShell 压缩
      execSync(`powershell -Command "Compress-Archive -Path '${art.sourceDir}\\*' -DestinationPath '${art.dest}' -Force"`, { stdio: 'inherit' });
    } else {
      fs.copyFileSync(art.src, art.dest);
    }
    console.log(`  ✓ ${path.basename(art.dest)}`);
  }

  // 4. 更新 releases.json
  console.log('\n📌 步骤 4/4: 更新 releases.json…');
  const releasesPath = path.join(ROOT, 'server', 'data', 'releases.json');
  const releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));

  for (const key of Object.keys(releases)) {
    const ext = key === 'android-detector' || key === 'android-receiver' ? 'apk' : 'zip';
    const prefix = key === 'android-detector' ? 'VisionGuard-Detector' :
                   key === 'android-receiver' ? 'VisionGuard-Receiver' :
                   key === 'wpf' ? 'VisionGuard-WPF' : 'VisionGuard';
    const filePath = path.join(RELEASES_DIR, `${prefix}-v${version}.${ext}`);
    const stat = fs.statSync(filePath);
    releases[key] = {
      version,
      url: `/releases/${prefix}-v${version}.${ext}`,
      size: stat.size,
    };
  }
  fs.writeFileSync(releasesPath, JSON.stringify(releases, null, 2) + '\n');

  console.log('\n✅ 发布完成！');
  console.log(`\n下一步：\n  1. 提交版本变更: git add -A && git commit -m "release: v${version}"`);
  console.log(`  2. 推送标签: git tag v${version} && git push origin v${version}`);
  console.log(`  3. 部署 Server: cd server && npm run build && npm start`);
}

function findMsBuild() {
  const vswhere = 'C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe';
  if (!fs.existsSync(vswhere)) {
    throw new Error('vswhere.exe 未找到，请安装 Visual Studio 2022');
  }
  const result = execSync(`"${vswhere}" -latest -find "MSBuild\\\\**\\\\Bin\\\\MSBuild.exe"`).toString().trim();
  if (!result) throw new Error('MSBuild 未找到');
  return result.split('\n')[0];
}

main();
