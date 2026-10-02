const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  auditRepository,
  checkIndexCoverage,
  checkEvidencePaths,
  checkLicenseTexts,
  checkProductContract,
  checkCancelledPlans,
  checkComponentContract,
  checkReadmeVersion,
  checkVerificationVersionClaims
} = require('./check-docs');

const root = path.resolve(__dirname, '..');

test('current repository documentation contract passes', () => {
  assert.deepEqual(auditRepository(root), []);
});

test('README version drift is rejected', () => {
  const errors = [];
  checkReadmeVersion(
    '4.4.3',
    '[![Version](badge/version-4.4.2-blue)]',
    errors
  );
  assert.equal(errors.length, 1);
  assert.ok(errors.every((message) => message.includes('README.md')));
});

test('new canonical document must be registered in canonical documentation entrypoints', () => {
  const errors = [];
  checkIndexCoverage(
    ['docs/codex/00-index.md', 'docs/codex/new-current-module.md'],
    '# Index',
    '# CODEX',
    errors
  );
  assert.equal(errors.length, 2);
  assert.ok(errors.every((message) => message.includes('new-current-module.md')));
});

test('stale artifact paths in current evidence documents are rejected', () => {
  // 临时 root 里自带 artifacts/：checkEvidencePaths 只在存在取证目录的环境做存在性校验
  // （干净 checkout 会整类跳过），所以这条覆盖必须自带目录，否则在 CI 上会退化成空断言。
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-evidence-'));
  try {
    fs.mkdirSync(path.join(tempRoot, 'artifacts'), { recursive: true });
    const errors = [];
    checkEvidencePaths(tempRoot, [
      ['docs/codex/90-verification-report.md', '`artifacts/e2e/does-not-exist/summary.json`']
    ], errors);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /missing artifact/);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test('artifact existence checks are skipped without a capture directory', () => {
  // 干净 checkout（CI）没有 artifacts/：明确整类跳过而不是逐条假失败，
  // 这条把该行为固化，避免以后有人把跳过改成静默通过或重新引入 59 条假失败。
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-evidence-skip-'));
  try {
    const errors = [];
    checkEvidencePaths(tempRoot, [
      ['docs/codex/90-verification-report.md', '`artifacts/e2e/does-not-exist/summary.json`']
    ], errors);
    assert.deepEqual(errors, []);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test('stale current-version claims in verification reports are rejected', () => {
  const errors = [];
  checkVerificationVersionClaims(
    '4.4.3',
    '- 根 `VERSION` 当前为 `4.3.0`\n- Server `package.json` 当前版本为 `4.3.0`',
    errors
  );
  assert.equal(errors.length, 2);
});

test('current relay and offline-state boundaries cannot silently drift', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkProductContract(
    read('README.md').replaceAll('不使用 P2P、ICE、STUN 或 TURN', '使用 P2P')
      .replaceAll('通知收件确认不等于声音播放', '通知收件确认等于声音播放')
      .replaceAll('漏报风险是检测效果与故障处置的最高优先级', '误报与漏报同等处理'),
    read('docs/codex/10-project-overview.md'), read('AGENTS.md'), errors
  );
  assert.ok(errors.some(message => message.includes('no-P2P boundary')));
  assert.ok(errors.some(message => message.includes('receipt-playback boundary')));
  assert.ok(errors.some(message => message.includes('missed-detection priority')));
});

test('cancelled plans cannot return as active documents or references', () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-cancelled-'));
  try {
    const docs = ['README.md', 'docs/codex/10-project-overview.md', 'docs/codex/90-verification-report.md'];
    const contents = new Map([
      ['README.md', '[旧入口](docs/codex/15-product-roadmap.md)'],
      ['docs/codex/10-project-overview.md', '5.0 以内更新：Detector Platform'],
      ['docs/codex/90-verification-report.md', '# V10 · 批次历史']
    ]);
    fs.mkdirSync(path.join(tempRoot, 'docs/codex'), { recursive: true });
    fs.writeFileSync(path.join(tempRoot, 'docs/codex/15-product-roadmap.md'), '# 已取消的方案');
    const errors = [];
    checkCancelledPlans(tempRoot, docs, contents, errors);
    assert.equal(errors.length, 4);
    assert.ok(errors.every(message => message.includes('cancelled-plan')));
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

test('canonical component names cannot drift', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root,
    read('README.md').replaceAll('视觉中继', 'Server'),
    read('docs/codex/10-project-overview.md'), read('docs/codex/60-operations.md'), errors);
  assert.ok(errors.some(message => message.includes('canonical component name 视觉中继')));
});

test('component platforms cannot drift', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root,
    read('README.md').replace('| VisionGuard 镜头推流 | Android |', '| VisionGuard 镜头推流 | Windows |'),
    read('docs/codex/10-project-overview.md'), read('docs/codex/60-operations.md'), errors);
  assert.ok(errors.some(message => message.includes('README.md must list the canonical platforms')));
});

test('MIT license and contribution policy cannot silently drift', () => {
  const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
  const errors = [];
  checkLicenseTexts({
    license: read('LICENSE').replace('MIT License', 'Restricted License')
      .replace('Permission is hereby granted, free of charge', 'Permission requires approval'),
    contributing: read('CONTRIBUTING.md').replace('暂不接受外部代码、模型、素材或文档 Pull Request', '欢迎直接提交任何 Pull Request'),
    readme: read('README.md').replace('badge/license-MIT-', 'badge/license-Other-'),
    overview: read('docs/codex/10-project-overview.md'),
    agents: read('AGENTS.md')
  }, errors);

  assert.ok(errors.some((message) => message.includes('MIT license title')));
  assert.ok(errors.some((message) => message.includes('MIT permission grant')));
  assert.ok(errors.some((message) => message.includes('controlled contribution boundary')));
  assert.ok(errors.some((message) => message.includes('MIT badge')));
});
