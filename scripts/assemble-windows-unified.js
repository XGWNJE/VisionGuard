#!/usr/bin/env node

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const repoRoot = path.resolve(__dirname, '..');
const defaultDestination = path.join(repoRoot, 'detector', 'windows-package', 'bin', 'Release');
const destination = path.resolve(process.argv[2] || defaultDestination);

function assertInsideRepo(target) {
  const relative = path.relative(repoRoot, target);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error(`拒绝清理仓库外目录: ${target}`);
  }
}

function copyTree(source, target, filter = () => true) {
  if (!fs.existsSync(source)) throw new Error(`缺少构建目录: ${source}`);
  fs.mkdirSync(target, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    if (!filter(entry)) continue;
    const from = path.join(source, entry.name);
    const to = path.join(target, entry.name);
    if (entry.isDirectory()) copyTree(from, to, filter);
    else fs.copyFileSync(from, to);
  }
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase();
}

assertInsideRepo(destination);
fs.rmSync(destination, { recursive: true, force: true });
fs.mkdirSync(destination, { recursive: true });

const launcher = path.join(repoRoot, 'detector', 'windows-launcher', 'bin', 'Release', 'net472');
copyTree(launcher, destination, entry => !entry.name.toLowerCase().endsWith('.pdb'));

const resident = path.join(repoRoot, 'detector', 'windows-resident', 'bin', 'Release', 'net472');
for (const name of ['VisionGuard.Resident.exe', 'VisionGuard.Resident.exe.config']) {
  const source = path.join(resident, name);
  if (!fs.existsSync(source)) throw new Error(`缺少驻留产物: ${source}`);
  fs.copyFileSync(source, path.join(destination, name));
}

for (const profile of ['modern', 'legacy']) {
  const source = path.join(repoRoot, 'detector', 'windows-wpf', 'bin', 'x64', profile);
  const target = path.join(destination, 'runtimes', profile);
  copyTree(source, target, entry => {
    const lower = entry.name.toLowerCase();
    if (lower === 'alerts' || lower === 'assets') return false;
    if (lower === 'visionguard.resident.exe' || lower === 'visionguard.resident.exe.config') return false;
    if (lower.endsWith('.pdb') || lower.endsWith('.lib') || lower.endsWith('.onnx')) return false;
    if (lower.endsWith('.debug.dll')) return false;
    return true;
  });
}

const required = [
  'VisionGuard.exe',
  'VisionGuard.exe.config',
  'VisionGuard.Resident.exe',
  'VisionGuard.Resident.exe.config',
  'runtimes/modern/VisionGuard.exe',
  'runtimes/modern/native/modern/onnxruntime.dll',
  'runtimes/legacy/VisionGuard.exe',
  'runtimes/legacy/native/legacy/onnxruntime.dll',
];
for (const relative of required) {
  if (!fs.existsSync(path.join(destination, relative))) throw new Error(`统一包缺少必要文件: ${relative}`);
}
if (fs.existsSync(path.join(destination, 'onnxruntime.dll'))) {
  throw new Error('统一包根目录不得包含 onnxruntime.dll');
}

const version = fs.readFileSync(path.join(repoRoot, 'VERSION'), 'utf8').trim();
const manifest = {
  schemaVersion: 1,
  version,
  entryPoint: 'VisionGuard.exe',
  profiles: {
    modern: 'runtimes/modern/VisionGuard.exe',
    legacy: 'runtimes/legacy/VisionGuard.exe',
  },
  requiredFiles: Object.fromEntries(required.map(relative => [relative, sha256(path.join(destination, relative))])),
};
fs.writeFileSync(path.join(destination, 'package-manifest.json'), JSON.stringify(manifest, null, 2) + '\n', 'utf8');

console.log(`统一 Windows 包已生成: ${destination}`);
console.log(`版本: ${version}; 文件数: ${fs.readdirSync(destination, { recursive: true }).length}`);
