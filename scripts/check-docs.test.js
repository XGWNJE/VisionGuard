const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  auditRepository,
  checkIndexCoverage,
  checkDocumentLayout,
  checkEvidencePaths,
  checkLicenseTexts,
  checkPlanDocuments,
  checkProductContract,
  checkCancelledPlans,
  checkComponentContract,
  checkCurrentComponentNames,
  checkReadmeVersion,
  checkTaskListContract,
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

test('new topic needs only the canonical index and releases use their own entry', () => {
  const errors = [];
  checkIndexCoverage(
    ['docs/00-文档索引.md', 'docs/80-新模块.md', 'docs/releases/README.md', 'docs/releases/v0.7.0.md'],
    '# Index',
    '# Releases',
    errors
  );
  assert.equal(errors.length, 3);
  const registered = [];
  checkIndexCoverage(['docs/80-新模块.md', 'docs/releases/README.md', 'docs/releases/v0.7.0.md'],
    '[专题](80-新模块.md) [发行](releases/README.md)', '[版本](v0.7.0.md)', registered);
  assert.deepEqual(registered, []);
  const misplaced = [];
  checkIndexCoverage(['docs/releases/v0.7.0.md'], '[版本](releases/v0.7.0.md)', '', misplaced);
  assert.equal(misplaced.length, 1);
});

test('release and plan layout accept their own files and reject other subfolders', () => {
  const docs = ['docs/80-新模块.md', 'docs/releases/README.md', 'docs/releases/v0.7.0.md', 'docs/114-发行说明v0.7.0.md', 'docs/other/说明.md', 'docs/plans/15-方案.md', 'docs/plans/方案.md'];
  const contents = new Map(docs.map(p => [p, '# 标题\n\n负责：本主题。不负责：其他主题。更新时机：变化时。']));
  const errors = [];
  checkDocumentLayout(docs, contents, errors);
  assert.equal(errors.length, 3);
  assert.ok(errors.every(e => e.includes('[layout]')));
});

test('plan documents are registered by their todo entry instead of the index', () => {
  const errors = [];
  checkIndexCoverage(['docs/00-文档索引.md', 'docs/plans/15-方案.md'], '# Index', '# Releases', errors);
  assert.deepEqual(errors, []);
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
  assert.ok(errors.some(message => message.includes('current component table has 8 data rows; expected 7')));
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
    .replace('`com.xgwnje.visionguard.detector`', '`com.xgwnje.visionguard.notifier`')
    .replace('| `VisionGuard.Notifier.Android` | `com.xgwnje.visionguard.notifier` |', '| `VisionGuard.Notifier.Android` | `com.xgwnje.visionguard.detector` |');
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors, naming);
  assert.ok(errors.some(message => message.includes('Android package mapped to 相机推流节点')));
  assert.ok(errors.some(message => message.includes('Android package mapped to 通知节点')));
});

test('camera and inference identities cannot be exchanged despite a shared node type', () => {
  const read = p => fs.readFileSync(path.join(root, p), 'utf8');
  const errors = [];
  checkComponentContract(root, read('README.md'), read('docs/10-当前架构.md'), read('docs/60-构建验证与发布.md'), errors,
    read('docs/15-命名规范.md')
      .replace('| 相机推流节点（Android） | `android-camera` |', '| 相机推流节点（Android） | `windows-inference` |')
      .replace('| 视觉推理节点（Windows） | `windows-inference` |', '| 视觉推理节点（Windows） | `android-camera` |'));
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
    ['docs/releases/v4.5.1.md', '视觉中继、视觉检测（Android）和视觉告警是该版本实际显示名']
  ]);
  const errors = [];
  checkCurrentComponentNames([...contents.keys()], contents, errors);
  assert.equal(errors.length, 2);
  assert.ok(errors.some(message => message.includes('20-统一服务.md')));
  assert.ok(errors.some(message => message.includes('40-Android相机节点.md')));
});

test('the todo list keeps its chapters, one-line items, priorities and plan links', () => {
  const valid = [
    '# 待办', '> **当前状态**：测试夹具。', '', '## 待办',
    '- [ ] **15 · 来源抽屉与设置抽屉分区清点**（**高**）——整理布局。计划：[详情](plans/15-来源抽屉与设置抽屉分区清点.md)',
    '- [ ] **6 · 校准集换成相机实拍**（**中**）——等待样本。',
    '- [ ] **1 · 部署模式**（**低**）——尚未开始。', '', '## 分支', '', '## 口径'
  ].join('\n');
  const audit = (content) => {
    const errors = [];
    checkTaskListContract(content, errors);
    return errors;
  };
  assert.deepEqual(audit(valid), []);

  // 章节名与顺序是固定契约：改名一次即报错，且报错时必须点名期望的章节。
  const renamed = audit(valid.replace('## 口径', '## 边界'));
  assert.equal(renamed.length, 1);
  assert.ok(renamed[0].includes('fixed chapters in order'));
  assert.ok(renamed[0].includes('## 口径'));

  // 最新状态用开头的引用块给出，读的人第一眼就能拿到。
  const noCallout = audit(valid.replace(/^> \*\*当前状态.*$/m, '当前状态：随便一句话。'));
  assert.ok(noCallout.some((error) => error.includes('当前状态 callout')));

  // 极简列表：不用宽表格。
  const withTable = audit(`${valid}\n| 分支 | 状态 |\n|---|---|\n| main | 已推送 |\n`);
  assert.ok(withTable.some((error) => error.includes('instead of tables')));

  const item = valid.split(/\r?\n/).find((line) => line.startsWith('- [ ] **15 · '));
  assert.ok(item);

  // 勾选框是列表样式的一部分：勾上也合法（完成即删，不该长期留着 [x]）。
  assert.deepEqual(audit(valid.replace('- [ ] **6 · ', '- [x] **6 · ')), []);

  // 条目必须是一条带勾选框与优先级的完整行。
  const untagged = audit(valid.replace(item, '- [ ] **15 · 来源抽屉与设置抽屉分区清点**——没有优先级标签。'));
  assert.ok(untagged.some((error) => error.includes('not one item line')));

  const noCheckbox = audit(valid.replace('- [ ] **6 · 校准集换成相机实拍**', '- **6 · 校准集换成相机实拍**'));
  assert.ok(noCheckbox.some((error) => error.includes('not one item line')));

  // 编号长期沿用、全局唯一。
  const repeated = audit(valid.replace('- [ ] **6 · 校准集换成相机实拍**', '- [ ] **15 · 校准集换成相机实拍**'));
  assert.ok(repeated.some((error) => error.includes('repeats item number 15')));

  // 顺序即优先级：低排在高/中之后才合法。
  const unordered = audit(valid.replace(item, item.replace('（**高**', '（**低**')));
  assert.ok(unordered.some((error) => error.includes('keep the list sorted')));

  // 超过一行就写计划文档，不允许在待办里铺开。
  const tooLong = audit(valid.replace(item, `- [ ] **15 · 来源抽屉与设置抽屉分区清点**（**高**）——${'锁'.repeat(210)}`));
  assert.ok(tooLong.some((error) => error.includes('longer than 200 characters')));

  // 计划文档编号必须与条目编号一致（否则就是挂错了条目）。
  const mismatched = audit(valid.replace(item, item.replace(/plans\/15-/, 'plans/6-')));
  assert.ok(mismatched.some((error) => error.includes('links a plan numbered 6')));
});

test('plan documents stay bound to a live todo entry and are deleted with it', () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'visionguard-plans-'));
  try {
    const plansDir = path.join(tempRoot, 'docs/plans');
    fs.mkdirSync(path.join(plansDir, 'nested'), { recursive: true });
    fs.writeFileSync(path.join(plansDir, '15-方案.md'), '# 方案\n');
    fs.writeFileSync(path.join(plansDir, '7-孤儿.md'), '# 孤儿\n');
    fs.writeFileSync(path.join(plansDir, '18-重复.md'), '# 重复\n');
    fs.writeFileSync(path.join(plansDir, '18-再重复.md'), '# 再重复\n');
    fs.writeFileSync(path.join(plansDir, '未编号.md'), '# 无编号\n');
    fs.writeFileSync(path.join(plansDir, 'nested/16-子目录.md'), '# 子目录\n');

    const taskList = [
      '## 待办',
      '',
      '- **15 · 来源抽屉分区**（高）——做完了就删。计划：[方案](plans/15-方案.md)',
      '- **18 · CI 变体**（中）——这条没有链接计划文档。',
      '',
      '## 分支'
    ].join('\n');
    const errors = [];
    checkPlanDocuments(tempRoot, taskList, '# 文档索引\n\n- [注意](docs/plans/15-方案.md)', errors);
    const joined = errors.join('\n');
    // 待办删了、计划没删：孤儿文档必须红。
    assert.match(joined, /docs\/plans\/7-孤儿\.md is not bound to a live 待办 7/);
    assert.match(joined, /docs\/plans\/7-孤儿\.md must be linked from its entry/);
    assert.match(joined, /duplicates the plan number 18/);
    assert.match(joined, /docs\/plans\/未编号\.md must be named/);
    assert.match(joined, /docs\/plans\/nested must not contain subdirectories/);
    // 计划文档只在待办条目上登记，不许再铺进文档索引。
    assert.match(joined, /00-文档索引\.md must not list plan documents/);
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
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
