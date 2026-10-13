const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

const { apkDexStrings, checkApk, readDexStrings } = require('./check-apk-protocol-version');

/** 造一个最小 DEX：头部 + string_ids + string_data，够解析字符串表即可。 */
function makeDex(strings) {
  const headerSize = 112;
  const idsSize = strings.length * 4;
  const dataStart = headerSize + idsSize;
  const dataParts = [];
  const offsets = [];
  let cursor = dataStart;
  for (const value of strings) {
    const bytes = Buffer.from(value, 'utf8');
    const item = Buffer.concat([Buffer.from([bytes.length]), bytes, Buffer.from([0])]);
    offsets.push(cursor);
    dataParts.push(item);
    cursor += item.length;
  }
  const header = Buffer.alloc(headerSize);
  Buffer.from('dex\n035\0', 'latin1').copy(header, 0);
  header.writeUInt32LE(strings.length, 56);
  header.writeUInt32LE(headerSize, 60);
  const ids = Buffer.alloc(idsSize);
  offsets.forEach((offset, index) => ids.writeUInt32LE(offset, index * 4));
  return Buffer.concat([header, ids, ...dataParts]);
}

/** 造一个最小 zip（stored 方式，不压缩）；读取端不校验 CRC。 */
function makeZip(entries) {
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(0, 8); // stored
    local.writeUInt32LE(entry.data.length, 18);
    local.writeUInt32LE(entry.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    const localBytes = Buffer.concat([local, name, entry.data]);
    localParts.push(localBytes);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(0, 10); // stored
    central.writeUInt32LE(entry.data.length, 20);
    central.writeUInt32LE(entry.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    centralParts.push(Buffer.concat([central, name]));
    offset += localBytes.length;
  }
  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, centralDirectory, end]);
}

function writeApk(directory, name, strings) {
  const target = path.join(directory, name);
  fs.writeFileSync(target, makeZip([{ name: 'classes.dex', data: makeDex(strings) }]));
  return target;
}

test('dex string table and apk reader find the packaged version literal', () => {
  const dex = makeDex(['hello', '0.7.3', 'okhttp/4.12.0']);
  const strings = readDexStrings(dex);
  assert.ok(strings.has('0.7.3'));
  assert.equal(strings.size, 3);

  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-apk-dex-'));
  try {
    const apk = writeApk(temp, 'VisionGuard-Notifier-v0.7.3.apk', ['0.7.3']);
    assert.ok(apkDexStrings(apk).has('0.7.3'));
    assert.deepEqual(checkApk(apk, '0.7.3').declared, true);
    assert.deepEqual(checkApk(apk, '0.7.2').declared, false);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});

test('a package frozen on an older protocol version fails the check', () => {
  const script = path.resolve(__dirname, 'check-apk-protocol-version.js');
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-apk-check-'));
  try {
    // 复现 2026-10-14 的线上实例：0.7.3 的包，DEX 里只有 0.7.1。
    writeApk(temp, 'VisionGuard-Notifier-v0.7.3.apk', ['0.7.1']);
    writeApk(temp, 'VisionGuard-Inference-v0.7.3.apk', ['0.7.3']);

    const failed = spawnSync(process.execPath, [script, '--dir', temp, '--version', '0.7.3'], { encoding: 'utf8' });
    assert.equal(failed.status, 1);
    assert.match(failed.stdout, /FAIL VisionGuard-Notifier-v0\.7\.3\.apk/);
    assert.match(failed.stderr, /声明的是 0\.7\.1/);

    fs.rmSync(path.join(temp, 'VisionGuard-Notifier-v0.7.3.apk'));
    writeApk(temp, 'VisionGuard-Notifier-v0.7.3.apk', ['0.7.3']);
    const passed = spawnSync(process.execPath, [script, '--dir', temp, '--version', '0.7.3'], { encoding: 'utf8' });
    assert.equal(passed.status, 0, passed.stderr);
    assert.match(passed.stdout, /检查通过/);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});

test('an apk without dex files is reported instead of silently passing', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-apk-empty-'));
  try {
    const apk = writeApk(temp, 'VisionGuard-Notifier-v0.7.3.apk', ['0.7.3']);
    const withoutDex = makeZip([{ name: 'AndroidManifest.xml', data: Buffer.from('x') }]);
    fs.writeFileSync(apk, withoutDex);
    assert.throws(() => apkDexStrings(apk), /no classes\.dex/);
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
