const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

test('release skill replaces the old push-update entry and documents release gates', () => {
  assert.ok(
    fs.existsSync(path.join(root, '.agents/skills/visionguard-release/SKILL.md')),
    'visionguard-release skill should exist'
  );
  assert.ok(
    !fs.existsSync(path.join(root, '.agents/skills/push-update/SKILL.md')),
    'old push-update skill should be removed'
  );

  const skill = read('.agents/skills/visionguard-release/SKILL.md');
  assert.match(skill, /^name: visionguard-release/m);
  assert.match(skill, /Use when VisionGuard needs/i);
  assert.match(skill, /scripts[\\/]publish-release\.ps1/);
  assert.match(skill, /默认不.*GitHub|no GitHub/i);
  assert.match(skill, /D:\\ObjectCode\\Server-infra/);
  assert.match(skill, /\/opt\/visionguard-server/);
  assert.match(skill, /app-release-unsigned\.apk/);
  assert.match(skill, /apksigner verify/);
  assert.match(skill, /HEAD 200/);
  assert.match(skill, /Range.*206/);
});

test('repository entrypoints reference visionguard-release instead of push-update', () => {
  const readme = read('README.md');
  const agents = read('AGENTS.md');
  const combined = `${readme}\n${agents}`;

  assert.match(readme, /visionguard-release/);
  assert.match(agents, /visionguard-release/);
  assert.doesNotMatch(combined, /push-update/);
});

test('publish-release.ps1 keeps GitHub optional and release deployment reproducible', () => {
  const script = read('scripts/publish-release.ps1');

  assert.match(script, /param\s*\(/);
  assert.match(script, /\$Version/);
  assert.match(script, /ValidateSet\('All','Windows','Android','Server','WinForms','WPF','AndroidDetector','AndroidReceiver'\)/);
  assert.match(script, /\$PushGitHub/);
  assert.match(script, /\$CreateTag/);
  assert.match(script, /\$CreateGitHubRelease/);
  assert.match(script, /if\s*\(\$PushGitHub\)/);
  assert.match(script, /if\s*\(\$CreateTag\)/);
  assert.match(script, /if\s*\(\$CreateGitHubRelease\)/);
  assert.match(script, /D:\\ObjectCode\\Server-infra\\server\.local\.env/);
  assert.match(script, /\/opt\/visionguard-server/);
  assert.match(script, /app-release-unsigned\.apk/);
  assert.match(script, /apksigner/);
  assert.match(script, /VISIONGUARD_ANDROID_STORE_PASSWORD/);
  assert.match(script, /VISIONGUARD_ANDROID_KEY_PASSWORD/);
  assert.match(script, /\.tmp/);
  assert.match(script, /mv -f/);
  assert.match(script, /api\/update/);
  assert.match(script, /Range/);
});

test('server deployment script targets the current dedicated runtime layout', () => {
  const script = read('server/deploy.sh');

  assert.match(script, /SERVER_INFRA_ENV/);
  assert.match(script, /server\.local\.env/);
  assert.match(script, /\/opt\/visionguard-server/);
  assert.doesNotMatch(script, /\/opt\/visionguard\/VisionGuard_Server/);
  assert.doesNotMatch(script, /VPS_ALIAS="xgwnje"/);
});
