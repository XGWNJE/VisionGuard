const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const {
  auditRepository,
  checkIndexCoverage,
  checkLicenseTexts,
  checkProductContract,
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
    ['docs/codex/00-index.md', 'docs/codex/15-product-roadmap.md'],
    '# Index',
    '# CODEX',
    errors
  );
  assert.equal(errors.length, 2);
  assert.ok(errors.every((message) => message.includes('15-product-roadmap.md')));
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

test('network and offline-alarm product decisions cannot silently drift', () => {
  const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
  const errors = [];
  const roadmap = read('docs/codex/15-product-roadmap.md')
    .replaceAll('不再规划 P2P、ICE、STUN 或 TURN', '重新规划 P2P')
    .replaceAll('DeviceOfflineAlert', 'DeviceStatus')
    .replaceAll('允许在可管理范围内误报', '误报与漏报同等处理')
    .replaceAll('免费版以目前已经实现的纯软件视觉方案为边界', '视觉功能按功能点收费')
    .replaceAll('系统一旦接入检测硬件探测器，即进入付费版', '高级软件功能进入付费版');

  checkProductContract(
    read('README.md'),
    read('docs/codex/10-project-overview.md'),
    roadmap,
    read('AGENTS.md'),
    errors
  );

  assert.ok(errors.some((message) => message.includes('Server-only network boundary')));
  assert.ok(errors.some((message) => message.includes('device-offline alert contract')));
  assert.ok(errors.some((message) => message.includes('missed-detection priority')));
  assert.ok(errors.some((message) => message.includes('free software-visual edition boundary')));
  assert.ok(errors.some((message) => message.includes('paid hardware-detector edition boundary')));
});

test('license transition and commercial boundary cannot silently drift', () => {
  const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
  const errors = [];
  checkLicenseTexts({
    license: read('LICENSE').replaceAll('Hardware Detector Use', 'Optional Hardware Use'),
    legacyMit: read('LICENSE-MIT'),
    licenseHistory: read('LICENSE-HISTORY.md').replace('c43c0ff122043d477b442b7507d193b62ea321bb', 'unknown'),
    commercialLicense: read('COMMERCIAL-LICENSE.md'),
    contributing: read('CONTRIBUTING.md').replace('暂不接受外部代码、模型、素材或文档 Pull Request', '欢迎直接提交任何 Pull Request'),
    readme: read('README.md').replace('badge/license-VGSAL--1.0-', 'badge/license-MIT-'),
    roadmap: read('docs/codex/15-product-roadmap.md'),
    agents: read('AGENTS.md')
  }, errors);

  assert.ok(errors.some((message) => message.includes('paid hardware-detector definition')));
  assert.ok(errors.some((message) => message.includes('exact MIT cutoff commit')));
  assert.ok(errors.some((message) => message.includes('controlled contribution boundary')));
  assert.ok(errors.some((message) => message.includes('VGSAL-1.0 badge')));
});
