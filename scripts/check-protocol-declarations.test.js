const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { checkProtocolDeclarations, strayVersionLiterals, CHECKS } = require('./check-protocol-declarations');

const REPOSITORY = path.resolve(__dirname, '..');

test('仓库当前满足全部协议声明与闸门检查', () => {
  assert.deepEqual(checkProtocolDeclarations(REPOSITORY), []);
});

/** 造一份最小仓库：每条要求都能命中，再按用例定点破坏。 */
function fixture(t, version = '9.9.9') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-protocol-guard-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'VERSION'), `${version}\n`);
  fs.mkdirSync(path.join(root, 'scripts'), { recursive: true });
  // 收管清单里先写上被扫描文件的类名，具体用例再决定要不要收管
  fs.writeFileSync(path.join(root, 'scripts', 'sync-version.js'), 'module.exports = {};\n');
  const copies = new Map();
  for (const check of CHECKS) {
    for (const requirement of check.requirements) {
      if (copies.has(requirement.file)) continue;
      const content = fs.readFileSync(path.join(REPOSITORY, requirement.file), 'utf-8');
      copies.set(requirement.file, content);
      const target = path.join(root, requirement.file);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(target, content);
    }
  }
  return { root, copies };
}

test('把某条声明从文件里去掉就会被指出来', (t) => {
  const { root } = fixture(t);
  assert.deepEqual(checkProtocolDeclarations(root), []);
  const file = 'detector/windows-wpf/Services/MediaPublishService.cs';
  const content = fs.readFileSync(path.join(root, file), 'utf-8');
  const broken = content.replace(/type = "media-auth", token = _token, direction = "publish", version = AppConfig\.Version, sourceId = _sourceId, sourceName = _sourceName/, 'type = "media-auth", token = _token, direction = "publish"');
  assert.notEqual(broken, content);
  fs.writeFileSync(path.join(root, file), broken);
  const violations = checkProtocolDeclarations(root);
  assert.equal(violations.length, 1);
  assert.equal(violations[0].id, 'client-windows');
  assert.equal(violations[0].file, file);
});

test('缺失的文件按文件缺失报告', (t) => {
  const { root } = fixture(t);
  fs.unlinkSync(path.join(root, 'server/src/services/protocol.ts'));
  const violations = checkProtocolDeclarations(root);
  assert.ok(violations.some(item => item.file === 'server/src/services/protocol.ts' && item.reason === '文件缺失'));
});

test('代码里的版本字面量必须被 sync-version 收管，否则判为孤立版本', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-protocol-literal-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, 'VERSION'), '9.9.9\n');
  fs.mkdirSync(path.join(root, 'scripts'), { recursive: true });
  const stray = path.join(root, 'detector', 'android', 'app', 'AppConstants.kt');
  fs.mkdirSync(path.dirname(stray), { recursive: true });
  fs.writeFileSync(stray, 'object AppConstants {\n    const val VERSION = "9.9.9"\n}\n');
  const sync = path.join(root, 'scripts', 'sync-version.js');

  fs.writeFileSync(sync, 'module.exports = {};\n');
  assert.deepEqual(strayVersionLiterals(root).map(item => item.file), ['detector/android/app/AppConstants.kt']);

  fs.writeFileSync(sync, "// 4. 相机推流节点（Android） AppConstants.kt\nmodule.exports = {};\n");
  assert.deepEqual(strayVersionLiterals(root), []);

  // 旧版本的字面量不是问题：闸门按当前服务版本判定
  fs.writeFileSync(stray, 'object AppConstants {\n    const val VERSION = "1.0.0"\n}\n');
  assert.deepEqual(strayVersionLiterals(root), []);
});
