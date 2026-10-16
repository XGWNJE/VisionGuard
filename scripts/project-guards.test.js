const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const { requireVersionAuthorization } = require('./project-guards');

const root = path.resolve(__dirname, '..');
const cleanEnv = { ...process.env, VISIONGUARD_OWNER_VERSION: '', VISIONGUARD_OWNER_RELEASE: '', VISIONGUARD_OWNER_PUSH: '' };

test('authorization is scoped to the exact requested version', () => {
  assert.throws(() => requireVersionAuthorization('0.7.0', {}), /requires owner/);
  assert.throws(() => requireVersionAuthorization('0.7.0', { VISIONGUARD_OWNER_VERSION: '0.6.9' }), /requires owner/);
  assert.doesNotThrow(() => requireVersionAuthorization('0.7.0', { VISIONGUARD_OWNER_VERSION: '0.7.0' }));
  assert.doesNotThrow(() => requireVersionAuthorization('0.7.0', { VISIONGUARD_OWNER_RELEASE: '0.7.0' }));
});

function withRepository(run) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-hooks-'));
  const cwd = path.join(temp, 'work');
  fs.mkdirSync(cwd);
  const command = (exe, args, env = cleanEnv) => spawnSync(exe, args, { cwd, env, encoding: 'utf8' });
  const git = (args, env = cleanEnv) => command('git', args, env);
  const ok = result => assert.equal(result.status, 0, result.stderr || result.stdout);
  try {
    ok(git(['init', '-b', 'main']));
    ok(git(['config', 'user.name', 'Hook Test']));
    ok(git(['config', 'user.email', 'hook-test@example.invalid']));
    for (const file of ['.githooks/pre-commit', '.githooks/pre-push', 'scripts/project-guards.js', 'scripts/install-git-hooks.js']) {
      const target = path.join(cwd, file);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(path.join(root, file), target);
      if (file.startsWith('.githooks/')) fs.chmodSync(target, 0o755);
    }
    fs.writeFileSync(path.join(cwd, 'VERSION'), '0.6.9\n');
    ok(git(['add', '.']));
    // Initial fixture commit predates hook installation.
    ok(git(['commit', '-m', 'fixture']));
    run({ temp, cwd, git, command, ok });
  } finally {
    fs.rmSync(temp, { recursive: true, force: true });
  }
}

test('real hooks reject unauthorized commit and local push, and accept scoped authorization', () => {
  withRepository(({ temp, cwd, git, command, ok }) => {
    ok(command(process.execPath, ['scripts/install-git-hooks.js']));
    fs.writeFileSync(path.join(cwd, 'ordinary.txt'), 'ordinary change');
    ok(git(['add', 'ordinary.txt']));
    ok(git(['commit', '-m', 'ordinary']));
    fs.writeFileSync(path.join(cwd, 'VERSION'), '0.7.0\n');
    ok(git(['add', 'VERSION']));
    let result = git(['commit', '-m', 'blocked']);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /requires owner authorization/);
    // Worktree approval cannot override a different staged version.
    fs.writeFileSync(path.join(cwd, 'VERSION'), '0.7.1\n');
    result = git(['commit', '-m', 'mismatch'], { ...cleanEnv, VISIONGUARD_OWNER_VERSION: '0.7.1' });
    assert.notEqual(result.status, 0);
    ok(git(['commit', '-m', 'authorized'], { ...cleanEnv, VISIONGUARD_OWNER_VERSION: '0.7.0' }));
    ok(git(['rm', '--cached', 'VERSION']));
    assert.notEqual(git(['commit', '-m', 'delete-version']).status, 0);
    ok(git(['reset', 'HEAD', '--', 'VERSION']));
    const remote = path.join(temp, 'remote.git');
    ok(git(['init', '--bare', remote]));
    ok(git(['remote', 'add', 'fixture', remote]));
    result = git(['push', 'fixture', 'main']);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Push requires owner authorization/);
    ok(git(['push', 'fixture', 'main'], { ...cleanEnv, VISIONGUARD_OWNER_PUSH: '1' }));
  });
});

test('hook installation is idempotent, checkout-specific and preserves other hooks', () => {
  withRepository(({ temp, cwd, git, command, ok }) => {
    const other = path.join(temp, 'other-worktree');
    ok(git(['worktree', 'add', '-b', 'other', other]));
    ok(command(process.execPath, ['scripts/install-git-hooks.js']));
    ok(command(process.execPath, ['scripts/install-git-hooks.js']));
    const setting = spawnSync('git', ['config', '--get', 'core.hooksPath'], { cwd: other, encoding: 'utf8' });
    assert.equal(setting.status, 1);
    fs.mkdirSync(path.join(cwd, 'other-hooks'));
    ok(git(['config', '--worktree', 'core.hooksPath', 'other-hooks']));
    assert.notEqual(command(process.execPath, ['scripts/install-git-hooks.js']).status, 0);
    assert.equal(git(['config', '--get', 'core.hooksPath']).stdout.trim(), 'other-hooks');
    ok(git(['config', '--worktree', '--unset', 'core.hooksPath']));
    const dir = git(['rev-parse', '--git-path', 'hooks']).stdout.trim();
    fs.writeFileSync(path.resolve(cwd, dir, 'pre-commit'), '# existing hook');
    assert.notEqual(command(process.execPath, ['scripts/install-git-hooks.js']).status, 0);
  });
});

test('publish script checks authorization before mutation while retaining read-only preflight', { skip: process.platform !== 'win32' && 'PowerShell 5.1 check runs on Windows' }, () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-publish-guard-'));
  try {
    const source = fs.readFileSync(path.join(root, 'scripts/publish-release.ps1'), 'utf8');
    const script = path.join(temp, 'guard.ps1');
    // Execute the production parameter and guard block, with no build or remote action.
    fs.writeFileSync(script, source.slice(0, source.indexOf('$repoRoot =')) + "Write-Output 'guard-passed'\n");
    const invoke = (flags, env = cleanEnv) => spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, '-Version', '0.7.0', ...flags], { env, encoding: 'utf8' });
    assert.notEqual(invoke([]).status, 0);
    assert.notEqual(invoke([], { ...cleanEnv, VISIONGUARD_OWNER_RELEASE: '0.6.9' }).status, 0);
    for (const result of [invoke(['-PreflightOnly']), invoke(['-DryRun']), invoke([], { ...cleanEnv, VISIONGUARD_OWNER_RELEASE: '0.7.0' })]) {
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /guard-passed/);
    }
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});

test('console keeps a browser-camera entry reachable from the node overview', () => {
  const consoleEntry = fs.readFileSync(path.join(root, 'receiver/web/src/main.tsx'), 'utf8');
  const overview = fs.readFileSync(path.join(root, 'receiver/web/src/FleetPage.tsx'), 'utf8');
  // 浏览器相机没有安装包，用户只能从控制台进去；入口丢了就等于这个节点类型“消失”。
  assert.match(consoleEntry, /<NodeDirectory\b/, '节点总览未接入控制台');
  assert.match(overview, /href="\/camera\/"/, '控制台缺少浏览器相机入口');
});

test('development CI checks Android release variants with a compile-only signing override', () => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/development.yml'), 'utf8');
  assert.match(workflow, /project: \[detector\/android, notifier\/android, detector\/android-inference\]/);
  assert.match(workflow, /bash gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease lintRelease -PVISIONGUARD_ALLOW_UNSIGNED_RELEASE=true --no-daemon --console=plain/);
  for (const relative of [
    'detector/android/app/build.gradle.kts',
    'notifier/android/app/build.gradle.kts',
    'detector/android-inference/app/build.gradle.kts',
  ]) {
    const gradle = fs.readFileSync(path.join(root, relative), 'utf8');
    assert.match(gradle, /providers\.gradleProperty\("VISIONGUARD_ALLOW_UNSIGNED_RELEASE"\)/, `${relative} 未将 unsigned 选项限制为 Gradle 属性`);
    assert.doesNotMatch(gradle, /buildConfigField\([^\n]*VISIONGUARD_ALLOW_UNSIGNED_RELEASE/, `${relative} 不得把 unsigned 开关写进 APK 的 BuildConfig`);
  }
});

test('preview builds carry the isolated sign-in while release packages stay clean', () => {
  const gradle = fs.readFileSync(path.join(root, 'scripts/ui-preview.init.gradle'), 'utf8');
  const previewKeys = ['preview_endpoint', 'preview_username', 'preview_password', 'preview_channel'];
  // 预览构建注入三件套；正式构建没有这一步，应用回落到登录页。
  for (const key of previewKeys) {
    assert.match(gradle, new RegExp(`name="${key}"`), `ui-preview.init.gradle 没有注入 ${key}`);
  }
  assert.match(gradle, /test-accounts\.json/, '预览凭据的来源文件没有被引用');
  // 三个应用的资源占位必须留在 main 且为空：缺了 release 变体引用 R.string.preview_* 会编译失败，
  // 留了非空值就等于正式包内置了凭据。
  for (const relative of [
    'detector/android/app/src/main/res/values/strings.xml',
    'notifier/android/app/src/main/res/values/strings.xml',
    'detector/android-inference/app/src/main/res/values/strings.xml',
  ]) {
    const text = fs.readFileSync(path.join(root, relative), 'utf8');
    for (const key of previewKeys) {
      assert.match(text, new RegExp(`name="${key}"[^>]*/>`), `${relative} 的 ${key} 必须是空值占位`);
    }
  }
  // 三个 Android 端都要登记并触发预览登录。
  for (const relative of [
    'detector/android/app/src/main/java/com/xgwnje/visionguard/detector/MainActivity.kt',
    'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/MainActivity.kt',
    'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/MainActivity.kt',
  ]) {
    const text = fs.readFileSync(path.join(root, relative), 'utf8');
    assert.match(text, /PreviewSignIn\.configure\(/, `${relative} 没有登记预览凭据`);
    assert.match(text, /PreviewSignIn\.signIn\(/, `${relative} 没有发起预览登录`);
  }
  // Windows 侧的预览凭据经环境变量递入（隔离配置的字段契约不含账号密码）。
  const program = fs.readFileSync(path.join(root, 'detector/windows-wpf/Program.cs'), 'utf8');
  assert.match(program, /VISIONGUARD_PREVIEW_SIGNIN/);
  assert.match(program, /--login-ui/, '缺少停在登录界面的调试入口');
  // Web 两侧（控制台与 Web 相机）同样免登录：隔离实例把凭据交给服务端，端点只服务回环请求。
  const isolatedServer = fs.readFileSync(path.join(root, 'scripts/start-isolated-test-server.ps1'), 'utf8');
  assert.match(isolatedServer, /VISIONGUARD_PREVIEW_SIGNIN/, '隔离测试服务没有内置预览凭据');
  const accountRoute = fs.readFileSync(path.join(root, 'server/src/routes/account.ts'), 'utf8');
  assert.match(accountRoute, /preview-signin/, '服务端缺少预览凭据端点');
  assert.match(accountRoute, /isLoopbackRequest/, '预览凭据端点必须限定回环请求');
  assert.match(fs.readFileSync(path.join(root, 'server/src/config.ts'), 'utf8'), /VISIONGUARD_PREVIEW_SIGNIN/, '服务端配置没有读取预览凭据变量');
  for (const relative of ['receiver/web/src/main.tsx', 'detector/web/src/main.tsx']) {
    const text = fs.readFileSync(path.join(root, relative), 'utf8');
    assert.match(text, /bootstrapProtocolVersion\(/, `${relative} 没有在登录前就位协议版本`);
    assert.match(text, /debugLoginScreen\(/, `${relative} 没有保留停在登录界面的调试入口`);
  }
  // 停在登录界面必须连会话一起清：否则应用启动会恢复已保存的会话，照样进主界面。
  const previewSignIn = fs.readFileSync(path.join(root, 'android-shared/src/main/java/com/xgwnje/visionguard/account/PreviewSignIn.kt'), 'utf8');
  assert.match(previewSignIn, /debugLoginScreen\)[\s\S]{0,400}?store\.clear\(\)/, '停在登录界面没有清除本机会话');
  // 本地服务版先按通道做局域网发现，找不到才回落内置地址；端口每次变化靠这一步自愈。
  assert.match(previewSignIn, /LocalServiceDiscovery\.discover\(/, '本地服务版没有做局域网自动发现');
  assert.match(previewSignIn, /localUpdateBase/, '本地服务版没有登记本地更新源');
  const discovery = fs.readFileSync(path.join(root, 'android-shared/src/main/java/com/xgwnje/visionguard/account/LocalServiceDiscovery.kt'), 'utf8');
  assert.match(discovery, /const val DISCOVERY_PORT = 45899/, '发现端口必须与服务端信标端口一致');
  const lanBeacon = fs.readFileSync(path.join(root, 'server/src/services/LanBeacon.ts'), 'utf8');
  assert.match(lanBeacon, /visionguard-local/, '服务端信标没有写约定的 service 标识');
  assert.match(fs.readFileSync(path.join(root, 'server/src/config.ts'), 'utf8'), /VISIONGUARD_LAN_BEACON/, '服务端配置没有读取信标开关');
});
