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
  checkCurrentComponentNames,
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
    ['docs/00-文档索引.md', 'docs/codex/new-current-module.md'],
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
      ['docs/90-验证记录.md', '`artifacts/e2e/does-not-exist/summary.json`']
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
      ['docs/90-验证记录.md', '`artifacts/e2e/does-not-exist/summary.json`']
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
    read('docs/10-当前架构.md'), read('AGENTS.md'), errors
  );
  assert.ok(errors.some(message => message.includes('no-P2P boundary')));
  assert.ok(errors.some(message => message.includes('receipt-playback boundary')));
  assert.ok(errors.some(message => message.includes('missed-detection priority')));
});

test('cancelled plans cannot return as active documents or references', () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-cancelled-'));
  try {
    const docs = ['README.md', 'docs/10-当前架构.md', 'docs/90-验证记录.md'];
    const contents = new Map([
      ['README.md', '[旧入口](docs/codex/15-product-roadmap.md)'],
      ['docs/10-当前架构.md', '5.0 以内更新：Detector Platform'],
      ['docs/90-验证记录.md', '# V10 · 批次历史']
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
    read('README.md').replaceAll('统一服务', 'Server'),
    read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors);
  assert.ok(errors.some(message => message.includes('canonical component name 统一服务')));
});

test('the resident process cannot reappear as a standalone product', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  const readme = read('README.md').replace('## 当前组件', '## 当前组件\n\n| 视觉驻留 | Windows | 驻留 | `detector/windows-resident/` |');
  const naming = read('docs/15-命名规范.md').replace('## 规范名称', '## 规范名称\n\n| 视觉驻留 | VisionGuard Resident | 视觉驻留 / Resident | Windows |');
  checkComponentContract(root, readme, read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors, naming);
  assert.ok(errors.some(message => message.includes('current component table has 7 data rows; expected 6')));
  assert.ok(errors.some(message => message.includes('canonical Chinese names')));
});

test('English names and abbreviations cannot diverge between console platforms', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors,
    read('docs/15-命名规范.md').replace('| 控制台 | Web |', '| 接收端 | Web |'));
  assert.ok(errors.some(message => message.includes('canonical Chinese names')));
});

test('a package assigned to the wrong Android application is rejected', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  const naming = read('docs/15-命名规范.md')
    .replace('`com.xgwnje.visionguard.detector`', '`com.xgwnje.visionguard.receiver`')
    .replace('| `VisionGuard.Receiver.Android` | `com.xgwnje.visionguard.receiver` |', '| `VisionGuard.Receiver.Android` | `com.xgwnje.visionguard.detector` |');
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors, naming);
  assert.ok(errors.some(message => message.includes('Android package mapped to 相机推流节点')));
  assert.ok(errors.some(message => message.includes('Android package mapped to 控制台')));
});

test('camera and inference identities cannot be exchanged despite a shared node type', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors,
    read('docs/15-命名规范.md')
      .replace('| 相机推流节点（Android） | `android-camera` |', '| 相机推流节点（Android） | `windows-inference` |')
      .replace('| 视觉节点（Windows） | `windows-inference` |', '| 视觉节点（Windows） | `android-camera` |'));
  assert.ok(errors.some(message => message.includes('component/role/nodeType/platform mappings')));
});

test('Web and server npm identities cannot be assigned to each other', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  const naming = read('docs/15-命名规范.md')
    .replace('| `visionguard-web-console` | 同源 `/console/` |', '| `visionguard-relay` | 同源 `/console/` |')
    .replace('npm `visionguard-relay`', 'npm `visionguard-web-console`');
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors, naming);
  assert.ok(errors.some(message => message.includes('engineering identifier mapped to 控制台（Web）')));
  assert.ok(errors.some(message => message.includes('engineering identifier mapped to 统一服务（服务端）')));
});

test('build targets, update keys and notifier delivery claims cannot drift', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  const naming = read('docs/15-命名规范.md')
    .replace('| `AndroidDetector` | `android-detector` |', '| `AndroidReceiver` | `android-receiver` |')
    .replace('应用内自动更新未实现', '应用内自动更新已实现');
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors, naming);
  assert.ok(errors.some(message => message.includes('build targets, update keys and output paths')));
  assert.ok(errors.some(message => message.includes('notifier manual update boundary')));
});

test('component platforms cannot drift', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root,
    read('README.md').replace('| 相机推流节点 | Android |', '| 相机推流节点 | Windows |'),
    read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors);
  assert.ok(errors.some(message => message.includes('README.md must list the canonical platforms')));
});

test('retired display names are rejected in current modules while version facts remain valid', () => {
  const contents = new Map([
    ['docs/20-统一服务.md', '# 视觉中继'],
    ['docs/40-Android相机节点.md', '# 视觉检测（Android）'],
    ['docs/15-命名规范.md', '视觉中继仅为旧版本名称；visionguard-relay 是技术标识'],
    ['docs/104-发行说明v4.5.1.md', '视觉中继、视觉检测（Android）和视觉告警是该版本实际显示名']
  ]);
  const errors = [];
  checkCurrentComponentNames([...contents.keys()], contents, errors);
  assert.equal(errors.length, 2);
  assert.ok(errors.some(message => message.includes('20-统一服务.md')));
  assert.ok(errors.some(message => message.includes('40-Android相机节点.md')));
});

test('MIT license and contribution policy cannot silently drift', () => {
  const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
  const errors = [];
  checkLicenseTexts({
    license: read('LICENSE').replace('MIT License', 'Restricted License')
      .replace('Permission is hereby granted, free of charge', 'Permission requires approval'),
    contributing: read('CONTRIBUTING.md').replace('暂不接受外部代码、模型、素材或文档 Pull Request', '欢迎直接提交任何 Pull Request'),
    readme: read('README.md').replace('badge/license-MIT-', 'badge/license-Other-'),
    overview: read('docs/10-当前架构.md'),
    agents: read('AGENTS.md')
  }, errors);

  assert.ok(errors.some((message) => message.includes('MIT license title')));
  assert.ok(errors.some((message) => message.includes('MIT permission grant')));
  assert.ok(errors.some((message) => message.includes('controlled contribution boundary')));
  assert.ok(errors.some((message) => message.includes('MIT badge')));
});
