#!/usr/bin/env node
/**
 * 打包产物协议版本检查（check-apk-protocol-version）
 *
 * 角色：确认 Android 发行包真的声明了它自己的版本号。
 * 背景：Kotlin 会把 Java 的 `static final` 常量（`BuildConfig.VERSION_NAME`）在编译期内联成字面量；
 *       增量构建若复用旧 class，APK 就会一直声明旧协议版本（2026-10-14 实测：0.7.3 的通知节点包声明 0.7.1，
 *       被线上协议版本闸门静默切断），而且 manifest / 设置页显示的版本仍是对的，肉眼与常规检查都看不出。
 * 判定：解析 APK 内 `classes*.dex` 的字符串表，要求出现文件名里的版本号（例如 `...-v0.7.3.apk` → `0.7.3`）。
 *
 * 用法：
 *   node scripts/check-apk-protocol-version.js                 # 检查默认目录 server/data/releases 里当前 VERSION 的包
 *   node scripts/check-apk-protocol-version.js --all           # 检查目录里全部包（各自对照文件名版本，用于一次性审计）
 *   node scripts/check-apk-protocol-version.js --dir <目录>
 *   node scripts/check-apk-protocol-version.js --apk <文件> --version <版本>
 *   node scripts/check-apk-protocol-version.js --json          # 输出机器可读结果
 */
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const ROOT = path.resolve(__dirname, '..');
const DEFAULT_DIR = path.join(ROOT, 'server', 'data', 'releases');
const APK_NAME_PATTERN = /^VisionGuard-(?<platform>[A-Za-z]+)-v(?<version>\d+\.\d+\.\d+)\.apk$/;
const DEX_ENTRY_PATTERN = /^classes\d*\.dex$/;

/** 读取 zip 中央目录，返回可解压的条目表（不支持 zip64：发行包远小于 4G）。 */
function readZipEntries(buffer) {
  const eocdSignature = 0x06054b50;
  let eocd = -1;
  for (let offset = buffer.length - 22; offset >= Math.max(0, buffer.length - 66_000); offset--) {
    if (buffer.readUInt32LE(offset) === eocdSignature) { eocd = offset; break; }
  }
  if (eocd < 0) throw new Error('zip end-of-central-directory not found');

  const entryCount = buffer.readUInt16LE(eocd + 10);
  const directoryOffset = buffer.readUInt32LE(eocd + 16);
  const entries = [];
  let cursor = directoryOffset;
  for (let index = 0; index < entryCount; index++) {
    if (buffer.readUInt32LE(cursor) !== 0x02014b50) throw new Error('zip central directory is corrupt');
    const method = buffer.readUInt16LE(cursor + 10);
    const compressedSize = buffer.readUInt32LE(cursor + 20);
    const uncompressedSize = buffer.readUInt32LE(cursor + 24);
    const nameLength = buffer.readUInt16LE(cursor + 28);
    const extraLength = buffer.readUInt16LE(cursor + 30);
    const commentLength = buffer.readUInt16LE(cursor + 32);
    const localOffset = buffer.readUInt32LE(cursor + 42);
    const name = buffer.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
      throw new Error(`zip64 entries are not supported: ${name}`);
    }
    entries.push({ name, method, compressedSize, localOffset });
    cursor += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

function readZipEntry(buffer, entry) {
  if (buffer.readUInt32LE(entry.localOffset) !== 0x04034b50) throw new Error(`zip local header is corrupt: ${entry.name}`);
  const nameLength = buffer.readUInt16LE(entry.localOffset + 26);
  const extraLength = buffer.readUInt16LE(entry.localOffset + 28);
  const start = entry.localOffset + 30 + nameLength + extraLength;
  const raw = buffer.subarray(start, start + entry.compressedSize);
  if (entry.method === 0) return raw;
  if (entry.method === 8) return zlib.inflateRawSync(raw);
  throw new Error(`unsupported zip compression method ${entry.method} for ${entry.name}`);
}

/** DEX 字符串表：header 的 string_ids_size / string_ids_off 指向字符串偏移数组。 */
function readDexStrings(buffer) {
  if (buffer.subarray(0, 4).toString('latin1') !== 'dex\n') throw new Error('not a dex file');
  const stringIdsSize = buffer.readUInt32LE(56);
  const stringIdsOffset = buffer.readUInt32LE(60);
  const strings = new Set();
  for (let index = 0; index < stringIdsSize; index++) {
    let cursor = buffer.readUInt32LE(stringIdsOffset + index * 4);
    // 跳过 uleb128 的 utf16 长度前缀
    for (;;) {
      const byte = buffer[cursor++];
      if ((byte & 0x80) === 0) break;
    }
    let end = cursor;
    while (buffer[end] !== 0) end++;
    strings.add(buffer.subarray(cursor, end).toString('utf8'));
  }
  return strings;
}

/** 返回 APK 内所有 dex 字符串；读不到 dex 时抛错。 */
function apkDexStrings(apkPath) {
  const buffer = fs.readFileSync(apkPath);
  const entries = readZipEntries(buffer).filter(entry => DEX_ENTRY_PATTERN.test(entry.name));
  if (entries.length === 0) throw new Error('no classes.dex inside the apk');
  const strings = new Set();
  for (const entry of entries) {
    for (const value of readDexStrings(readZipEntry(buffer, entry))) strings.add(value);
  }
  return strings;
}

/** 逐个 APK 判定：包内必须出现它自己的版本号。 */
function checkApk(apkPath, version) {
  const declared = apkDexStrings(apkPath);
  return { apkPath, version, declared: declared.has(version), sample: [...declared].filter(value => /^\d+\.\d+\.\d+$/.test(value)).sort() };
}

function targets(directory, filterVersion) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory)
    .map(name => ({ name, match: name.match(APK_NAME_PATTERN) }))
    .filter(entry => entry.match)
    .map(entry => ({ apkPath: path.join(directory, entry.name), version: entry.match.groups.version }))
    .filter(item => !filterVersion || item.version === filterVersion);
}

function currentVersion() {
  try { return fs.readFileSync(path.join(ROOT, 'VERSION'), 'utf8').trim(); } catch { return undefined; }
}

function main() {
  const args = process.argv.slice(2);
  const read = flag => { const index = args.indexOf(flag); return index < 0 ? undefined : args[index + 1]; };
  const json = args.includes('--json');
  const explicitApk = read('--apk');
  const explicitVersion = read('--version');
  const directory = read('--dir') ?? DEFAULT_DIR;

  let list;
  if (explicitApk) {
    if (!explicitVersion) { console.error('--apk requires --version'); process.exitCode = 1; return; }
    list = [{ apkPath: path.resolve(explicitApk), version: explicitVersion }];
  } else {
    // 默认只查当前 VERSION 的包（发布 preflight 要检的就是这批）；--all 才逐包对照文件名版本。
    const version = explicitVersion ?? (args.includes('--all') ? undefined : currentVersion());
    list = targets(directory, version);
    if (list.length === 0) { console.error(`没有找到可检查的 APK：${directory}${version ? `（版本 ${version}）` : ''}`); process.exitCode = 1; return; }
  }

  const results = [];
  const failures = [];
  for (const item of list) {
    try {
      const result = checkApk(item.apkPath, item.version);
      results.push(result);
      if (!result.declared) failures.push(`${path.basename(item.apkPath)} 声明的是 ${result.sample.join(' / ') || '(没有版本字符串)'}，而不是自己的版本 ${item.version}`);
    } catch (error) {
      failures.push(`${path.basename(item.apkPath)} 无法检查：${error.message}`);
    }
  }

  if (json) {
    console.log(JSON.stringify({ results, failures }, null, 2));
  } else {
    for (const result of results) {
      console.log(`${result.declared ? 'OK  ' : 'FAIL'} ${path.basename(result.apkPath)}  期望 ${result.version}  包内 ${result.sample.join(' / ') || '(无)'}`);
    }
  }

  if (failures.length > 0) {
    if (!json) for (const failure of failures) console.error(`- ${failure}`);
    console.error(`APK 协议版本检查失败：${failures.length} 个包声明的版本与自身发行版本不一致。`);
    process.exitCode = 1;
    return;
  }
  if (!json) console.log(`APK 协议版本检查通过：${results.length} 个包都声明了自己的版本。`);
}

if (require.main === module) main();

module.exports = { apkDexStrings, checkApk, readDexStrings, readZipEntries, main };
