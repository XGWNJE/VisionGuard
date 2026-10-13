const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { deterministicFindings, assertedAbsent, scanWorkflowIssues, guardFiles } = require('./scan-guardrails');

const REPOSITORY = path.resolve(__dirname, '..');
// 拼出来而不是写整串：本文件自己也在被扫描的脚本目录里，字面出现会被恒真断言检查命中。
const WEAK_ASSERTION = ['assert.ok(', 'true);'].join('');

/** 最小夹具仓库：只有一个工作流、一个检查脚本和一个 npm 目录。 */
function fixture(t) {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-guardrail-scan-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));
  fs.mkdirSync(path.join(base, 'scripts'), { recursive: true });
  fs.mkdirSync(path.join(base, '.github', 'workflows'), { recursive: true });
  fs.mkdirSync(path.join(base, 'docs'), { recursive: true });
  fs.mkdirSync(path.join(base, 'receiver', 'web'), { recursive: true });
  fs.writeFileSync(path.join(base, 'docs', 'current.md'), '# 现状\n');
  fs.writeFileSync(path.join(base, 'scripts', 'check-clean.js'), "// 引用 docs/current.md\nmodule.exports = {};\n");
  fs.writeFileSync(path.join(base, 'scripts', 'check-clean.test.js'), "// 夹具里可以引用 docs/only-in-fixture.md\nmodule.exports = {};\n");
  fs.writeFileSync(
    path.join(base, '.github', 'workflows', 'clean.yml'),
    "steps:\n  - run: node scripts/check-clean.js\n  - run: npm --prefix receiver/web test\n"
  );
  return base;
}

test('干净的夹具仓库没有硬检查命中', (t) => {
  assert.deepEqual(deterministicFindings(fixture(t)), []);
});

test('工作流引用不存在的脚本或目录、以及 continue-on-error 都会命中', (t) => {
  const base = fixture(t);
  fs.writeFileSync(
    path.join(base, '.github', 'workflows', 'broken.yml'),
    "continue-on-error: true\nsteps:\n  - run: node scripts/check-missing.js\n  - run: npm --prefix nowhere/web test\n"
  );
  const findings = deterministicFindings(base);
  assert.equal(findings.length, 3);
  assert.ok(findings.some(item => item.includes('引用不存在的脚本 scripts/check-missing.js')));
  assert.ok(findings.some(item => item.includes('npm --prefix 指向不存在的目录 nowhere/web')));
  assert.ok(findings.some(item => item.includes('continue-on-error')));
});

test('检查脚本里的恒真断言会命中', (t) => {
  const base = fixture(t);
  fs.writeFileSync(path.join(base, 'scripts', 'check-weak.js'), `${WEAK_ASSERTION}\n`);
  const findings = deterministicFindings(base);
  assert.deepEqual(findings, [`scripts/check-weak.js: 含恒真断言（${WEAK_ASSERTION.replace(';', '')}），这条检查永远不会失败`]);
});

test('真检查引用不存在的目标是硬失败，测试夹具里的路径不算', (t) => {
  const base = fixture(t);
  fs.writeFileSync(path.join(base, 'scripts', 'check-stale.js'), '// 比对 docs/nope/missing.md\nmodule.exports = {};\n');
  fs.writeFileSync(path.join(base, 'scripts', 'check-stale.test.js'), '// 造夹具时会写 docs/nope/missing.md\nmodule.exports = {};\n');
  const findings = deterministicFindings(base);
  assert.deepEqual(findings, ['scripts/check-stale.js: 引用了不存在的目标 `docs/nope/missing.md`']);
});

test('断言「该文件已删除 / 已退役」的引用不算失效引用', () => {
  assert.equal(assertedAbsent("if (!fs.existsSync('docs/old/plan.md')) return;", 'docs/old/plan.md'), true);
  assert.equal(assertedAbsent("const retiredPath = 'docs/codex/15-product-roadmap.md';", 'docs/codex/15-product-roadmap.md'), true);
  assert.equal(assertedAbsent('// deprecated: docs/old/plan.md 不再使用', 'docs/old/plan.md'), true);
  assert.equal(assertedAbsent("const target = 'docs/current/plan.md';", 'docs/current/plan.md'), false);
});

test('工作流扫描能识别工作流文件本身', (t) => {
  const base = fixture(t);
  assert.deepEqual(scanWorkflowIssues(base), []);
  fs.rmSync(path.join(base, '.github', 'workflows', 'clean.yml'));
  assert.deepEqual(scanWorkflowIssues(base), []);
});

test('仓库本身通过硬检查', () => {
  assert.deepEqual(deterministicFindings(REPOSITORY), []);
});

test('仓库里的检查脚本都被硬检查覆盖到', () => {
  const guards = guardFiles(REPOSITORY);
  assert.ok(guards.includes('check-docs.js'));
  assert.ok(guards.includes('check-protocol-declarations.js'));
});
