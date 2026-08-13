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
  assert.match(skill, /preflight/i);
  assert.match(skill, /-PreflightOnly/);
  assert.match(skill, /-SkipServerDeploy/);
  assert.match(skill, /默认不.*GitHub|no GitHub/i);
  assert.match(skill, /D:\\ObjectCode\\Server-infra/);
  assert.match(skill, /\/opt\/visionguard-server/);
  assert.match(skill, /app-release-unsigned\.apk/);
  assert.match(skill, /apksigner verify/);
  assert.match(skill, /HEAD 200/);
  assert.match(skill, /Range.*206/);
});

test('agent entrypoint references visionguard-release instead of push-update', () => {
  const readme = read('README.md');
  const agents = read('AGENTS.md');
  const combined = `${readme}\n${agents}`;

  assert.match(agents, /visionguard-release/);
  assert.doesNotMatch(combined, /push-update/);
});

test('release-facing documentation stays aligned with VERSION and the canonical publish script', () => {
  const version = read('VERSION').trim();
  const readme = read('README.md');
  const modelAssets = read('docs/codex/35-model-assets.md');

  assert.ok(readme.includes(`badge/version-${version}-`));
  assert.match(modelAssets, /scripts[\\/]publish-release\.ps1/);
  assert.doesNotMatch(modelAssets, /scripts[\\/]release\.js/);
});

test('old Claude workflow entrypoints are not kept as canonical project files', () => {
  assert.ok(
    !fs.existsSync(path.join(root, '.claude/skills/push-update/SKILL.md')),
    'old Claude push-update skill should not be tracked'
  );
  assert.ok(
    !fs.existsSync(path.join(root, '.claude/skills/version-alignment/SKILL.md')),
    'old Claude version-alignment skill should not be tracked'
  );
  assert.ok(
    !fs.existsSync(path.join(root, 'scripts/hooks/check-claude-md.ps1')),
    'old CLAUDE.md sync hook should not be tracked'
  );
});

test('publish-release.ps1 keeps GitHub optional and release deployment reproducible', () => {
  const script = read('scripts/publish-release.ps1');

  assert.match(script, /param\s*\(/);
  assert.match(script, /\$Version/);
  assert.match(script, /ValidateSet\('All','Windows','Android','Server','WinForms','WPF','AndroidDetector','AndroidReceiver'\)/);
  assert.match(script, /\$PushGitHub/);
  assert.match(script, /\$CreateTag/);
  assert.match(script, /\$CreateGitHubRelease/);
  assert.match(script, /\$SkipServerDeploy/);
  assert.match(script, /\$PreflightOnly/);
  assert.match(script, /if\s*\(\$PushGitHub\)/);
  assert.match(script, /if\s*\(\$CreateTag\)/);
  assert.match(script, /if\s*\(\$CreateGitHubRelease\)/);
  assert.match(script, /Invoke-ReleasePreflight/);
  assert.match(script, /scripts\\check-docs\.js/);
  assert.match(script, /Preflight only complete/);
  assert.match(script, /Restore-WinFormsPackages/);
  assert.match(script, /Test-PythonParamiko/);
  assert.match(script, /Deploy-ServerCode/);
  assert.match(script, /Verify-OnlineServer/);
  assert.match(script, /\$serverDeployPlanned/);
  assert.match(script, /D:\\ObjectCode\\Server-infra\\server\.local\.env/);
  assert.match(script, /\/opt\/visionguard-server/);
  assert.match(script, /app-release-unsigned\.apk/);
  assert.match(script, /apksigner/);
  assert.match(script, /zipalign/);
  assert.match(script, /Set-AndroidJavaHome/);
  assert.match(script, /\$Name\$extension/);
  assert.match(script, /VISIONGUARD_ANDROID_STORE_PASSWORD/);
  assert.match(script, /VISIONGUARD_ANDROID_KEY_PASSWORD/);
  assert.match(script, /\.tmp/);
  assert.match(script, /mv -f/);
  assert.match(script, /remote mkdir failed/);
  assert.match(script, /VPS upload verification failed/);
  assert.match(script, /Server deploy failed/);
  assert.match(script, /Online release verification failed/);
  assert.match(script, /latestVersion/);
  assert.match(script, /downloadUrl/);
  assert.match(script, /fileSize/);
  assert.match(script, /api\/update/);
  assert.match(script, /Range/);
  assert.doesNotMatch(script, /server\\deploy\.sh/);
});

test('GitHub-only release mode reuses validated assets without rebuilding or deploying', () => {
  const script = read('scripts/publish-release.ps1');
  const githubOnlyStart = script.indexOf('if ($GitHubOnly) {', script.indexOf('Set-Location $repoRoot'));
  const syncVersionStart = script.indexOf('Write-Step "Sync version"');

  assert.match(script, /\[switch\]\$GitHubOnly/);
  assert.match(script, /\[string\]\$GitHubReleaseNotesPath/);
  assert.match(script, /\[string\]\$GitHubTagTarget = 'HEAD'/);
  assert.ok(githubOnlyStart >= 0, 'GitHub-only execution branch should exist');
  assert.ok(githubOnlyStart < syncVersionStart, 'GitHub-only execution must return before version sync and build');
  assert.match(script, /Get-GitHubOnlyArtifacts/);
  assert.match(script, /Requested version .* does not match repository VERSION/);
  assert.match(script, /metadata filename mismatch/);
  assert.match(script, /asset size mismatch/);
  assert.match(script, /Assert-ZipIsClean -ZipPath \$path/);
  assert.match(script, /Verify-AndroidApk -ApkPath \$path/);
  assert.match(script, /-GitHubOnly cannot be combined with VPS upload, source push, Server deployment, or build-control switches/);
  assert.match(script, /-GitHubOnly requires both -CreateTag and -CreateGitHubRelease/);
});

test('GitHub release creation is safe, repeatable, and requires Chinese notes', () => {
  const script = read('scripts/publish-release.ps1');

  assert.match(script, /GitHub release notes must contain Chinese text/);
  assert.match(script, /\[\\u4e00-\\u9fff\]/);
  assert.match(script, /'release', 'view', \$tagName/);
  assert.match(script, /'release', 'create', \$tagName/);
  assert.match(script, /'release', 'edit', \$tagName/);
  assert.match(script, /'release', 'upload', \$tagName/);
  assert.match(script, /'--clobber'/);
  assert.match(script, /Local tag .* Refusing to overwrite it/);
  assert.match(script, /Remote tag .* Refusing to force-push it/);
  assert.doesNotMatch(script, /'tag', '-f'/);
  assert.doesNotMatch(script, /'push',[\s\S]{0,120}'--force'/);
});

test('Android signing uses one ignored shared identity across build and release automation', () => {
  const detectorGradle = read('detector/android/app/build.gradle.kts');
  const receiverGradle = read('receiver/android/app/build.gradle.kts');
  const publishScript = read('scripts/publish-release.ps1');
  const initializer = read('scripts/initialize-android-signing.ps1');
  const gitignore = read('.gitignore');

  assert.match(gitignore, /^\.local\/$/m);
  for (const gradle of [detectorGradle, receiverGradle]) {
    assert.match(gradle, /\.local\/visionguard-release\.env/);
    assert.match(gradle, /VISIONGUARD_ANDROID_STORE_FILE/);
    assert.match(gradle, /VISIONGUARD_ANDROID_STORE_PASSWORD/);
    assert.match(gradle, /VISIONGUARD_ANDROID_KEY_PASSWORD/);
    assert.match(gradle, /releaseStoreFile\?\.isFile == true/);
    assert.match(gradle, /enableV2Signing = true/);
    assert.match(gradle, /enableV3Signing = true/);
    assert.match(gradle, /VISIONGUARD_ALLOW_UNSIGNED_RELEASE/);
    assert.match(gradle, /Signed Android Release is required/);
    assert.match(gradle, /releasePackagingRequested && !hasReleaseKeystore/);
  }

  assert.match(publishScript, /Join-Path \$repoRoot \$storeFile/);
  assert.match(publishScript, /initialize-android-signing\.ps1/);
  assert.match(publishScript, /--v3-signing-enabled', 'true'/);
  assert.ok(
    publishScript.indexOf(".local\\visionguard-release.env") < publishScript.indexOf("[Environment]::GetEnvironmentVariable($name)"),
    'environment variables should override ignored local signing files'
  );
  assert.match(initializer, /RandomNumberGenerator/);
  assert.match(initializer, /SetAccessRuleProtection\(\$true, \$false\)/);
  assert.match(initializer, /-storepass:env/);
  assert.match(initializer, /-keypass:env/);
  assert.match(initializer, /visionguard-android-release\.p12/);
  assert.match(initializer, /-storetype', 'PKCS12'/);
});

test('server deployment script targets the current dedicated runtime layout', () => {
  const script = read('server/deploy.sh');

  assert.match(script, /SERVER_INFRA_ENV/);
  assert.match(script, /server\.local\.env/);
  assert.match(script, /\/opt\/visionguard-server/);
  assert.doesNotMatch(script, /\/opt\/visionguard\/VisionGuard_Server/);
  assert.doesNotMatch(script, /VPS_ALIAS="xgwnje"/);
});

test('build script restores WinForms packages before compiling', () => {
  const script = read('.agents/skills/visionguard-build/scripts/build-all.ps1');

  assert.match(script, /Restore-WinFormsPackages/);
  assert.match(script, /RestorePackagesConfig=true/);
  assert.ok(
    script.indexOf('Restore-WinFormsPackages -MSBuild $msbuild') < script.indexOf('-Name "WinForms"'),
    'WinForms NuGet restore should run before the WinForms build step'
  );
});
