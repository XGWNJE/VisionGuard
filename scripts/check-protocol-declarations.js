#!/usr/bin/env node
/**
 * 协议版本声明护栏。
 *
 * 把几条硬规则变成每次 CI 都跑的检查，而不是靠人记：
 * 1) 统一服务在控制 WS、媒体 WS 与 `/api` 三条通道都跑版本闸门（闸门只服务上线部署实例）；
 * 2) 每个端在每条通道都声明本端版本，推流端另在媒体通道声明来源身份；
 * 3) 节点目录与推流视图只显示声明了本版协议的端点，解除绑定只放控制台 / 发布端 / 被动方；
 * 4) 版本号只有一个来源：根 `VERSION` → `server/package.json` → `config.version` → `PROTOCOL_VERSION`，
 *    各端要么取构建版本，要么是被 `scripts/sync-version.js` 收管的文件（代码里不许出现孤立版本字面量）；
 * 5) 闸门、目录可见性与多来源并行都有测试覆盖。
 *
 * 用法：`node scripts/check-protocol-declarations.js`；退出码 0 全部满足，1 有不符合项。
 */

const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_ROOT = path.resolve(__dirname, '..');

/** 每条检查 = 说明 + 一组「文件里必须命中的正则」；文件缺失或正则不命中即失败。 */
const CHECKS = [
  {
    id: 'server-gate-http',
    why: 'HTTP 闸门挂在上线部署实例的 /api 上，非本版请求 426 出局并先记下声明版本',
    requirements: [
      { file: 'server/src/index.ts', pattern: /createHttpVersionGate\(config\.protocolGate,\s*recordDroppedVersion\)/ },
      { file: 'server/src/services/protocol.ts', pattern: /if \(!isCurrentVersion\(declared\)\)/ },
      { file: 'server/src/services/protocol.ts', pattern: /status\(426\)/ }
    ]
  },
  {
    id: 'server-gate-control',
    why: '控制通道过闸门，出局与成功两条路径都记录声明版本',
    requirements: [
      { file: 'server/src/services/ConnectionManager.ts', pattern: /wsVersionGate\(message\)/ },
      { file: 'server/src/services/ConnectionManager.ts', pattern: /recordDeclaredVersion\(message\.token, message\.version\)/ },
      { file: 'server/src/services/ConnectionManager.ts', pattern: /accountStore\.recordVersion\(session\.account\.accountId/ }
    ]
  },
  {
    id: 'server-gate-media',
    why: '媒体通道过同一道闸门，并要求推流态推理节点声明来源身份',
    requirements: [
      { file: 'server/src/services/MediaRelay.ts', pattern: /wsVersionGate\(message\)/ },
      { file: 'server/src/services/MediaRelay.ts', pattern: /media source required/ },
      { file: 'server/src/services/MediaRelay.ts', pattern: /declaredSourceId\(message\)/ }
    ]
  },
  {
    id: 'server-version-single-source',
    why: '版本只有一个来源：根 VERSION → server/package.json → config.version → PROTOCOL_VERSION',
    requirements: [
      { file: 'server/src/services/protocol.ts', pattern: /export const PROTOCOL_VERSION = config\.version;/ },
      { file: 'server/src/config.ts', pattern: /require\('\.\.\/package\.json'\)\.version/ }
    ]
  },
  {
    id: 'server-visibility-and-unbind',
    why: '节点目录与推流视图只认声明了本版协议的端点；解除绑定放控制台 / 发布端 / 被动方',
    requirements: [
      { file: 'server/src/services/AccountStore.ts', pattern: /function visibleUnderGate\(device: StoredDevice\): boolean \{ return !config\.protocolGate \|\| device\.version === PROTOCOL_VERSION; \}/ },
      // 控制台节点目录走的是通知范围名册（identities → registeredNodes），这条通道漏了过滤就会残留旧端点。
      { file: 'server/src/services/AccountStore.ts', pattern: /directoryDevices\(accountId: string\)[\s\S]{0,200}?visibleUnderGate\(item\)/ },
      { file: 'server/src/services/AccountStore.ts', pattern: /identities\(accountId: string\)[\s\S]{0,200}?visibleUnderGate\(item\)/ },
      { file: 'server/src/services/MediaRelay.ts', pattern: /accountStore\.directoryDevices\(accountId\)/ },
      { file: 'server/src/services/ConnectionManager.ts', pattern: /accountStore\.directoryDevices\(session\.account\.accountId\)/ },
      { file: 'server/src/services/MediaRelay.ts', pattern: /session\.device\.deviceId === targetDeviceId/ },
      { file: 'server/src/routes/streams.ts', pattern: /post\('\/api\/streams\/unbind'/ }
    ]
  },
  {
    id: 'client-windows',
    why: 'Windows 端在控制认证、发布 / 订阅媒体认证与 /api 请求里都声明版本，推流另声明来源',
    requirements: [
      { file: 'detector/windows-wpf/Services/ServerPushService.cs', pattern: /\["version"\] = AppConfig\.Version/ },
      { file: 'detector/windows-wpf/Services/MediaPublishService.cs', pattern: /type = "media-auth", token = _token, direction = "publish", version = AppConfig\.Version, sourceId = _sourceId, sourceName = _sourceName/ },
      { file: 'detector/windows-wpf/Services/RemoteMediaService.cs', pattern: /type = "media-auth", token = _token, direction = "subscribe", version = Utils\.AppConfig\.Version/ },
      { file: 'detector/windows-wpf/Services/StreamTargetService.cs', pattern: /"X-VisionGuard-Version", AppConfig\.Version/ }
    ]
  },
  {
    id: 'client-android',
    why: 'Android 三个端都声明版本；版本只能是 `scripts/sync-version.js` 收管的源码字面量（`BuildConfig` 等生成常量会被 Kotlin 内联且增量构建不跟踪，声明会冻结在旧值）',
    requirements: [
      { file: 'detector/android/app/src/main/java/com/xgwnje/visionguard/detector/AppConstants.kt', pattern: /const val VERSION = "\d+\.\d+\.\d+"/ },
      { file: 'detector/android/app/src/main/java/com/xgwnje/visionguard/detector/stream/CameraPublisher.kt', pattern: /put\("version", com\.xgwnje\.visionguard\.detector\.AppConstants\.VERSION\)/ },
      { file: 'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/AppConstants.kt', pattern: /const val VERSION = "\d+\.\d+\.\d+"/ },
      { file: 'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/net/ControlClient.kt', pattern: /addProperty\("version", com\.xgwnje\.visionguard\.inference\.AppConstants\.VERSION\)/ },
      { file: 'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/net/MediaClient.kt', pattern: /addProperty\("version", com\.xgwnje\.visionguard\.inference\.AppConstants\.VERSION\)/ },
      { file: 'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/AppConstants.kt', pattern: /const val VERSION = "\d+\.\d+\.\d+"/ },
      { file: 'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/node/NotificationNodeService.kt', pattern: /put\("version", AppConstants\.VERSION\)/ }
    ]
  },
  {
    id: 'client-web',
    why: '控制台与 Web 相机先读 /health 的版本，再在 /api 与媒体认证里声明',
    requirements: [
      { file: 'receiver/web/src/session.ts', pattern: /export function versionHeaders\(\)/ },
      { file: 'receiver/web/src/previewSignIn.ts', pattern: /bootstrapProtocolVersion/ },
      { file: 'receiver/web/src/account.ts', pattern: /versionHeaders\(\)/ },
      { file: 'detector/web/src/session.ts', pattern: /export function versionHeaders\(\)/ },
      { file: 'detector/web/src/media.ts', pattern: /type: 'media-auth', token, direction: 'publish'/ },
      { file: 'detector/web/src/control.ts', pattern: /type: 'auth', token: this\.session\.token, version: this\.session\.version/ }
    ]
  },
  {
    id: 'gate-tests',
    why: '闸门、目录可见性与多来源并行必须有测试覆盖',
    requirements: [
      { file: 'server/test/protocol-gate.test.ts', pattern: /wsVersionGate\(\{ type: 'auth', version: '0\.0\.1' \}, true\)/ },
      { file: 'server/test/version-directory.test.ts', pattern: /the node directory only lists endpoints that declared the served version/ },
      { file: 'server/test/version-directory.test.ts', pattern: /the console node list hides endpoints that did not declare the served version/ },
      { file: 'server/test/media-relay.test.ts', pattern: /one publisher pushes several sources to the same inference node/ }
    ]
  }
];

const SOURCE_ROOTS = ['server/src', 'receiver/web/src', 'detector/web/src', 'detector/windows-wpf', 'detector/android', 'detector/android-inference', 'notifier/android', 'android-shared'];
const CODE_EXTENSIONS = new Set(['.cs', '.kt', '.ts', '.tsx']);
const SKIP_DIRECTORIES = new Set(['node_modules', 'bin', 'obj', 'build', 'dist', '.gradle', 'test', 'tests', 'androidTest', 'testDebugUnitTest', 'uipreview', 'release']);
/** 版本字面量的判定：`VERSION = "1.2.3"` / `"version": "1.2.3"` / `put("version", "1.2.3")`。 */
const VERSION_LITERAL = /(VERSION|version)["']?\s*[:=,)]\s*["']\d+\.\d+\.\d+["']/;

function readVersion(root) {
  return fs.readFileSync(path.join(root, 'VERSION'), 'utf-8').trim();
}

/** 逐条比对：返回不符合项数组（空数组表示全部满足）。 */
function checkProtocolDeclarations(root = DEFAULT_ROOT) {
  const violations = [];
  for (const check of CHECKS) {
    for (const requirement of check.requirements) {
      const file = path.join(root, requirement.file);
      let content = '';
      try { content = fs.readFileSync(file, 'utf-8'); }
      catch { violations.push({ id: check.id, file: requirement.file, reason: '文件缺失', why: check.why }); continue; }
      if (!requirement.pattern.test(content)) violations.push({ id: check.id, file: requirement.file, reason: `未命中 ${requirement.pattern}`, why: check.why });
    }
  }
  return violations.concat(strayVersionLiterals(root), protocolVersionFromBuildConfig(root));
}

/**
 * 协议版本声明不得取自编译期常量。
 *
 * Kotlin 会把 Java 的 `static final`（`BuildConfig.VERSION_NAME`）在编译期内联成字面量，
 * 而且增量构建不跟踪这个生成文件的变化；复用旧 class 时声明就冻结在旧值，
 * 而 manifest 与设置页仍是新版本（2026-10-14 通知节点两次实测）。协议版本只认源码里由
 * `scripts/sync-version.js` 收管的字面量。
 */
const PROTOCOL_VERSION_FILES = [
  'detector/android/app/src/main/java/com/xgwnje/visionguard/detector/stream/CameraPublisher.kt',
  'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/net/ControlClient.kt',
  'detector/android-inference/app/src/main/java/com/xgwnje/visionguard/inference/net/MediaClient.kt',
  'notifier/android/app/src/main/java/com/xgwnje/visionguard/notifier/node/NotificationNodeService.kt',
  'android-shared/src/main/java/com/xgwnje/visionguard/account/AccountStore.kt'
];

function protocolVersionFromBuildConfig(root = DEFAULT_ROOT) {
  const violations = [];
  for (const relative of PROTOCOL_VERSION_FILES) {
    const file = path.join(root, relative);
    if (!fs.existsSync(file)) continue;
    if (/BuildConfig\.VERSION_NAME/.test(fs.readFileSync(file, 'utf-8'))) {
      violations.push({ id: 'version-runtime-source', file: relative, reason: '协议版本取自 BuildConfig 常量', why: '协议版本必须写成 sync-version 收管的源码字面量；BuildConfig 等生成常量会被 Kotlin 内联且增量构建不跟踪，声明会冻结在旧值' });
    }
  }
  return violations;
}

/**
 * 代码里出现版本字面量的文件必须被 `scripts/sync-version.js` 收管，
 * 否则版本一线变化时这个端仍声明旧版本，闸门会把它判成旧端踢掉。
 */
function strayVersionLiterals(root = DEFAULT_ROOT) {
  const version = readVersion(root);
  const sync = fs.readFileSync(path.join(root, 'scripts', 'sync-version.js'), 'utf-8');
  const violations = [];
  for (const relative of SOURCE_ROOTS) {
    const base = path.join(root, relative);
    if (!fs.existsSync(base)) continue;
    const stack = [base];
    while (stack.length) {
      const current = stack.pop();
      for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
        if (entry.isDirectory()) {
          if (SKIP_DIRECTORIES.has(entry.name) || entry.name.startsWith('.')) continue;
          stack.push(path.join(current, entry.name));
          continue;
        }
        if (!CODE_EXTENSIONS.has(path.extname(entry.name))) continue;
        const file = path.join(current, entry.name);
        const content = fs.readFileSync(file, 'utf-8');
        const line = content.split(/\r?\n/).find(candidate => VERSION_LITERAL.test(candidate) && candidate.includes(version));
        if (!line) continue;
        if (sync.includes(entry.name)) continue;
        violations.push({ id: 'version-single-source', file: path.relative(root, file).replace(/\\/g, '/'), reason: `版本字面量 ${version} 不在 sync-version 收管范围`, why: '代码里的版本字面量必须由 scripts/sync-version.js 收管' });
      }
    }
  }
  return violations;
}

function main() {
  const rootIndex = process.argv.indexOf('--root');
  const root = rootIndex >= 0 ? path.resolve(process.argv[rootIndex + 1]) : DEFAULT_ROOT;
  const violations = checkProtocolDeclarations(root);
  const version = readVersion(root);
  if (violations.length === 0) {
    console.log(`Protocol declaration audit passed: 服务版本 ${version}，${CHECKS.length} 组声明与闸门检查全部满足。`);
    return 0;
  }
  console.error(`Protocol declaration audit failed: ${violations.length} 项不符（服务版本 ${version}）`);
  for (const violation of violations) console.error(`- [${violation.id}] ${violation.file}: ${violation.reason}（${violation.why}）`);
  return 1;
}

if (require.main === module) process.exit(main());

module.exports = { checkProtocolDeclarations, protocolVersionFromBuildConfig, strayVersionLiterals, readVersion, CHECKS };
