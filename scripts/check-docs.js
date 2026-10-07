#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');
const { TextDecoder } = require('node:util');
const { releaseFileName } = require('./sync-version');

const DEFAULT_ROOT = path.resolve(__dirname, '..');
const VERSION_PATTERN = /^\d+\.\d+\.\d+$/;

const COMPONENTS = [
  { label: '视觉节点', platform: 'Windows', relativePath: 'detector/windows-wpf', source: 'detector/windows-wpf/App.xaml.cs' },
  { label: '相机推流节点', platform: 'Android', relativePath: 'detector/android', source: 'detector/android/app/build.gradle.kts' },
  { label: '控制台', platform: 'Web', relativePath: 'receiver/web', source: 'receiver/web/src/main.tsx' },
  { label: '通知节点', platform: 'Android', relativePath: 'notifier/android', source: 'notifier/android/app/build.gradle.kts' },
  { label: '统一服务', platform: '服务端', relativePath: 'server', source: 'server/src/index.ts' }
];

const WS_ROLES = ['detector', 'console', 'notifier', 'lifecycle'];

const RETAINED_SKILLS = [
  { name: 'visionguard-build', script: '.agents/skills/visionguard-build/scripts/build-all.ps1', modes: ['All', 'Server', 'Windows', 'WPF', 'WindowsResident', 'Android', 'AndroidDetector', 'AndroidNotifier'] },
  { name: 'visionguard-e2e', script: '.agents/skills/visionguard-e2e/scripts/e2e-smoke.ps1', modes: ['Discover', 'ServerBuild', 'ServerSmoke', 'AndroidDetectorSmoke', 'WpfPersonDetection', 'WpfParserContract', 'ResidentLaunch', 'ModelDownload', 'SourceAutoSave', 'CardLayoutPlan', 'PerformanceWatchdog'] },
  { name: 'visionguard-release', script: 'scripts/publish-release.ps1', modes: ['-PreflightOnly', '-SkipServerDeploy', '-UploadVps'] }
];

const DEPRECATED_ENTRYPOINTS = [
  'CODEX.md',
  '.claude',
  'CLAUDE.md',
  '.Codex/agents',
  'scripts/release.js',
  'scripts/release-helpers.js',
  'scripts/release-helpers.test.js',
  'scripts/bump-version.sh'
];

function toPosix(value) {
  return value.replace(/\\/g, '/');
}

function listMarkdownFiles(root, relativeDirectory) {
  const directory = path.join(root, relativeDirectory);
  if (!fs.existsSync(directory)) {
    return [];
  }

  const result = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const relativePath = toPosix(path.join(relativeDirectory, entry.name));
    if (entry.isDirectory()) {
      result.push(...listMarkdownFiles(root, relativePath));
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      result.push(relativePath);
    }
  }
  return result.sort();
}

function readUtf8(root, relativePath, errors, options = {}) {
  const absolutePath = path.join(root, relativePath);
  if (!fs.existsSync(absolutePath)) {
    errors.push(`[missing-file] ${relativePath}`);
    return '';
  }

  const bytes = fs.readFileSync(absolutePath);
  if (options.checkBom !== false && bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    errors.push(`[encoding] ${relativePath} contains a UTF-8 BOM`);
  }

  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (error) {
    errors.push(`[encoding] ${relativePath} is not valid UTF-8: ${error.message}`);
    return '';
  }
}

function requireText(content, expected, relativePath, label, errors) {
  if (!content.includes(expected)) {
    errors.push(`[contract] ${relativePath} is missing ${label}: ${expected}`);
  }
}

function requirePattern(content, pattern, relativePath, label, errors) {
  if (!pattern.test(content)) {
    errors.push(`[contract] ${relativePath} is missing ${label}`);
  }
}

function checkReadmeVersion(version, readme, errors) {
  requireText(readme, `badge/version-${version}-`, 'README.md', 'the current version badge', errors);
}

function checkVersionSources(root, version, errors) {
  const [major, minor, patch] = version.split('.').map(Number);
  const versionCode = major * 1000 + minor * 100 + patch;
  const exactChecks = [
    ['detector/windows-wpf/Utils/AppConfig.cs', `Version = "${version}"`],
    ['detector/windows-wpf/VisionGuard.Detector.Windows.csproj', `<Version>${version}</Version>`],
    ['detector/windows-wpf/VisionGuard.Detector.Windows.csproj', `<FileVersion>${version}</FileVersion>`],
    ['detector/windows-wpf/VisionGuard.Detector.Windows.csproj', `<AssemblyVersion>${version}</AssemblyVersion>`],
    ['detector/windows-launcher/Program.cs', `private const string Version = "${version}"`],
    ['detector/android/app/build.gradle.kts', `versionName = "${version}"`],
    ['detector/android/app/build.gradle.kts', `versionCode = ${versionCode}`],
    ['detector/android/app/src/main/java/com/xgwnje/visionguard/detector/AppConstants.kt', `VERSION = "${version}"`],
    ['server/src/index.ts', `统一服务 v${version} 已启动`]
  ];
  for (const project of [
    'detector/windows-launcher/VisionGuard.Detector.Windows.Launcher.csproj',
    'detector/windows-resident/VisionGuard.Resident.Windows.csproj'
  ]) {
    for (const element of ['Version', 'FileVersion', 'AssemblyVersion']) {
      exactChecks.push([project, `<${element}>${version}</${element}>`]);
    }
  }
  for (const expected of [
    'repositoryRoot.resolve("VERSION").readText().trim()',
    'versionCode = notificationVersionCode', 'versionName = notificationVersion'
  ]) {
    exactChecks.push(['notifier/android/app/build.gradle.kts', expected]);
  }

  const cache = new Map();
  for (const [relativePath, expected] of exactChecks) {
    if (!cache.has(relativePath)) {
      cache.set(relativePath, readUtf8(root, relativePath, errors, { checkBom: false }));
    }
    requireText(cache.get(relativePath), expected, relativePath, 'the VERSION-aligned value', errors);
  }

  for (const relativePath of ['server/package.json', 'server/package-lock.json', 'receiver/web/package.json', 'receiver/web/package-lock.json', 'server/data/releases.json']) {
    const content = readUtf8(root, relativePath, errors, { checkBom: false });
    if (!content) {
      continue;
    }
    try {
      const data = JSON.parse(content);
      if (relativePath.endsWith('package.json') && data.version !== version) {
        errors.push(`[version] ${relativePath} has ${data.version}; expected ${version}`);
      }
      if (relativePath.endsWith('package-lock.json')) {
        if (data.version !== version || data.packages?.['']?.version !== version) {
          errors.push(`[version] ${relativePath} root package versions must both be ${version}`);
        }
      }
      if (relativePath.endsWith('releases.json')) {
        for (const [platform, release] of Object.entries(data)) {
          if (!VERSION_PATTERN.test(release.version) || release.url !== `/releases/${releaseFileName(platform, release.version)}`) {
            errors.push(`[version] ${relativePath} entry ${platform} has invalid package metadata`);
          }
          // 0.x 源码处于内测，已有下载包的版本独立于当前开发版本。
          // 正式发布仍由 publish-release.ps1 校验目标版本、产物名与大小。
          if (major === 0) continue;
          // 分端上线：显式标记 heldBack 的平台本次不发布，允许它停留在上一个已发布版本
          // （必须继续指向真实存在的文件，否则客户端会拿到 404 的更新提示）。
          // 标记只能写在该平台条目**内部**：顶层加键会被当成一个新平台，从而破坏下面的对齐检查与 server 的解析。
          if (release.heldBack === true) {
            if (!release.version || !release.url) {
              errors.push(`[version] ${relativePath} entry ${platform} is held back but incomplete`);
            }
            continue;
          }
          if (release.version !== version || !String(release.url).includes(`-v${version}.`)) {
            errors.push(`[version] ${relativePath} entry ${platform} is not aligned with ${version}`);
          }
        }
      }
    } catch (error) {
      errors.push(`[json] ${relativePath} cannot be parsed: ${error.message}`);
    }
  }
}

function normalizeMarkdownTarget(rawTarget) {
  let target = rawTarget.trim();
  if (target.startsWith('<') && target.endsWith('>')) {
    target = target.slice(1, -1);
  }
  target = target.replace(/\s+["'][^"']*["']$/, '');
  return target;
}

function checkLocalLinks(root, markdownFiles, contents, errors) {
  const linkPattern = /!?(?:\[[^\]]*\])\(([^)]+)\)/g;

  for (const relativePath of markdownFiles) {
    const content = contents.get(relativePath) || '';
    for (const match of content.matchAll(linkPattern)) {
      const target = normalizeMarkdownTarget(match[1]);
      if (!target || target.startsWith('#') || /^(?:https?:|mailto:|tel:|data:)/i.test(target)) {
        continue;
      }

      const pathPart = target.split('#', 1)[0];
      if (!pathPart) {
        continue;
      }

      let decodedPath;
      try {
        decodedPath = decodeURIComponent(pathPart);
      } catch {
        errors.push(`[link] ${relativePath} contains an invalid encoded path: ${target}`);
        continue;
      }

      const resolved = path.resolve(root, path.dirname(relativePath), decodedPath);
      if (!resolved.startsWith(path.resolve(root) + path.sep) && resolved !== path.resolve(root)) {
        errors.push(`[link] ${relativePath} points outside the repository: ${target}`);
      } else if (!fs.existsSync(resolved)) {
        errors.push(`[link] ${relativePath} points to a missing target: ${target}`);
      }
    }
  }
}

function checkDocumentAnchors(markdownFiles, contents, errors) {
  const headingsByFile = new Map();
  const slugCounts = new Map();
  const slugify = (heading) => heading
    .trim()
    .toLowerCase()
    .replace(/[`*_~]/g, '')
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');

  for (const relativePath of markdownFiles) {
    const slugs = new Set();
    for (const line of (contents.get(relativePath) || '').split(/\r?\n/)) {
      const match = line.match(/^#{1,6}\s+(.+?)\s*#*$/);
      if (!match) {
        continue;
      }
      const base = slugify(match[1]);
      const count = slugCounts.get(`${relativePath}:${base}`) || 0;
      slugCounts.set(`${relativePath}:${base}`, count + 1);
      slugs.add(count === 0 ? base : `${base}-${count}`);
    }
    headingsByFile.set(relativePath, slugs);
  }

  const linkPattern = /!?(?:\[[^\]]*\])\(([^)]+)\)/g;
  for (const relativePath of markdownFiles) {
    const content = contents.get(relativePath) || '';
    for (const match of content.matchAll(linkPattern)) {
      const target = normalizeMarkdownTarget(match[1]);
      if (!target || /^(?:https?:|mailto:|tel:|data:)/i.test(target)) {
        continue;
      }
      const hashIndex = target.indexOf('#');
      if (hashIndex < 0 || hashIndex === target.length - 1) {
        continue;
      }
      const pathPart = target.slice(0, hashIndex);
      const anchor = decodeURIComponent(target.slice(hashIndex + 1)).toLowerCase();
      const targetFile = pathPart
        ? toPosix(path.normalize(path.join(path.dirname(relativePath), decodeURIComponent(pathPart))))
        : relativePath;
      if (!headingsByFile.has(targetFile)) {
        continue;
      }
      if (!headingsByFile.get(targetFile).has(anchor)) {
        errors.push(`[anchor] ${relativePath} points to a missing heading anchor: ${target}`);
      }
    }
  }
}

function checkDeprecatedEntrypoints(root, markdownFiles, contents, errors) {
  for (const relativePath of DEPRECATED_ENTRYPOINTS) {
    if (fs.existsSync(path.join(root, relativePath))) {
      errors.push(`[deprecated-entrypoint] ${relativePath} still exists; use the current build, e2e, or publish entrypoint`);
    }
  }

  const deprecatedPattern = /(?:^|[\\/])(?:release\.js|release-helpers(?:\.test)?\.js|bump-version\.sh)$|(?:^|[\\/])(?:CLAUDE\.md|\.claude|\.Codex[\\/]agents)(?:$|[\\/])/i;
  for (const relativePath of markdownFiles) {
    if (deprecatedPattern.test(relativePath)) {
      continue;
    }
    const content = contents.get(relativePath) || '';
    if (/scripts[\\/]release\.js|scripts[\\/]release-helpers|scripts[\\/]bump-version\.sh|\.claude[\\/]|CLAUDE\.md|\.Codex[\\/]agents/i.test(content)) {
      errors.push(`[deprecated-reference] ${relativePath} references a removed legacy entrypoint`);
    }
  }

  const activeScriptFiles = [
    '.gitignore',
    'scripts/publish-release.ps1',
    '.agents/skills/visionguard-build/scripts/build-all.ps1',
    '.agents/skills/visionguard-e2e/scripts/e2e-smoke.ps1'
  ];
  const stalePathPattern = /detector[\\/]android[\\/]app[\\/]src[\\/]main[\\/]assets[\\/]models/i;
  for (const relativePath of activeScriptFiles) {
    const absolutePath = path.join(root, relativePath);
    if (!fs.existsSync(absolutePath)) {
      continue;
    }
    const content = fs.readFileSync(absolutePath, 'utf8');
    if (stalePathPattern.test(content)) {
      errors.push(`[deprecated-reference] ${relativePath} references the removed Android assets/models directory`);
    }
  }
}

function checkComponentContract(root, readme, overview, operations, errors, namingOverride) {
  const namingPath = 'docs/15-命名规范.md';
  const naming = namingOverride ?? readUtf8(root, namingPath, errors);
  const tableRows = (content, heading) => {
    const start = content.indexOf(heading);
    if (start < 0) {
      return [];
    }
    const section = content.slice(start + heading.length);
    const end = section.search(/\n##\s/);
    return (end >= 0 ? section.slice(0, end) : section)
      .split(/\r?\n/)
      .filter((line) => /^\|\s*[^|-].*\|\s*$/.test(line) && !/^\|\s*(?:组件|规范名称)\s*\|/.test(line));
  };
  const readmeRows = tableRows(readme, '## 当前组件');
  const overviewRows = tableRows(overview, '## 当前实际组件');
  const rawCells = row => row.split('|').slice(1, -1).map(cell => cell.trim());
  const cells = row => rawCells(row).map(cell => cell.replaceAll('`', ''));
  const namingRows = tableRows(naming, '## 规范名称').map(cells);
  const expectedNaming = COMPONENTS.map(({label, platform}) => [label, platform]);
  if (JSON.stringify(namingRows) !== JSON.stringify(expectedNaming)) {
    errors.push(`[naming] ${namingPath} must use the canonical Chinese names and platforms in order`);
  }
  requireText(naming, '品牌简称 **VG**', namingPath, 'the canonical brand abbreviation', errors);
  const protocolRows = tableRows(naming, '## 登录组件与协议身份').slice(1).map(cells);
  const expectedProtocol = [
    ['视觉节点（Windows）', 'windows-inference', 'detector', 'visual', 'windows'],
    ['视觉节点（驻留子进程）', 'windows-resident', 'lifecycle', 'resident', 'windows'],
    ['相机推流节点（Android）', 'android-camera', 'detector', 'visual', 'android'],
    ['控制台（Web）', 'web-console', 'console', 'console', 'web'],
    ['通知节点（Android）', 'android-notifier', 'notifier', 'notification', 'android']
  ];
  if (JSON.stringify(protocolRows) !== JSON.stringify(expectedProtocol)) {
    errors.push(`[naming] ${namingPath} must keep the component/role/nodeType/platform mappings aligned`);
  }
  const engineeringRows = tableRows(naming, '## 工程与安装身份').slice(1).map(cells);
  const buildRows = tableRows(naming, '## 构建与更新标识').slice(1).map(rawCells);
  const expectedBuild = [
    ['视觉节点（Windows）', ['Windows', 'WPF'], ['wpf'], 'detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe'],
    ['视觉节点（驻留子进程）', ['WindowsResident'], ['wpf'], 'detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe'],
    ['相机推流节点（Android）', ['AndroidDetector'], ['android-detector'], 'detector/android/app/build/outputs/apk/release/app-release.apk'],
    ['控制台（Web）', ['Server'], [], 'server/dist/console/index.html'],
    ['通知节点（Android）', ['AndroidNotifier'], ['android-notifier'], 'notifier/android/app/build/outputs/apk/release/app-release.apk'],
    ['统一服务（服务端）', ['Server'], [], 'server/dist/index.js']
  ];
  const codes = cell => [...cell.matchAll(/`([^`]+)`/g)].map(match => match[1]);
  const actualBuild = buildRows.map(row => [row[0], codes(row[1] || ''), codes(row[2] || ''), (row[3] || '').replaceAll('`', '')]);
  if (JSON.stringify(actualBuild) !== JSON.stringify(expectedBuild)) {
    errors.push(`[naming] ${namingPath} must keep build targets, update keys and output paths mapped to their components`);
  }
  const notifierBuild = buildRows.find(row => row[0] === '通知节点（Android）') || [];
  requireText(notifierBuild[2] || '', '应用内自动更新未实现', namingPath, 'the notifier manual update boundary', errors);
  if (readmeRows.length !== COMPONENTS.length) {
    errors.push(`[component] README.md current component table has ${readmeRows.length} data rows; expected ${COMPONENTS.length}`);
  }
  if (overviewRows.length !== COMPONENTS.length) {
    errors.push(`[component] docs/10-当前架构.md current component table has ${overviewRows.length} data rows; expected ${COMPONENTS.length}`);
  }

  const expectedNames = COMPONENTS.map(component => component.label);
  for (const [relativePath, rows] of [['README.md', readmeRows], ['docs/10-当前架构.md', overviewRows]]) {
    const names = rows.map(row => row.split('|')[1].trim());
    const platforms = rows.map(row => row.split('|')[2].trim());
    if (JSON.stringify(platforms) !== JSON.stringify(COMPONENTS.map(component => component.platform))) {
      errors.push(`[component] ${relativePath} must list the canonical platforms separately from application names`);
    }
    if (JSON.stringify(names) !== JSON.stringify(expectedNames)) {
      errors.push(`[component] ${relativePath} must use the canonical component names in order: ${expectedNames.join(', ')}`);
    }
  }

  for (const component of COMPONENTS) {
    if (!fs.existsSync(path.join(root, component.relativePath))) {
      errors.push(`[component] missing current component directory: ${component.relativePath}`);
    }
    const source = readUtf8(root, component.source, errors, { checkBom: false });
    if (!source) {
      continue;
    }
    requireText(readme, component.relativePath, 'README.md', `the component path for ${component.label}`, errors);
    requireText(overview, component.relativePath, 'docs/10-当前架构.md', `the component path for ${component.label}`, errors);
    requireText(readme, component.label, 'README.md', `the canonical component name ${component.label}`, errors);
    requireText(overview, component.label, 'docs/10-当前架构.md', `the canonical component name ${component.label}`, errors);
  }

  const connectionManager = readUtf8(root, 'server/src/services/ConnectionManager.ts', errors, { checkBom: false });
  for (const role of WS_ROLES) {
    requireText(connectionManager, `'${role}'`, 'server/src/services/ConnectionManager.ts', `the active WebSocket role ${role}`, errors);
    requireText(overview, `\`${role}\``, 'docs/10-当前架构.md', `the documented WebSocket role ${role}`, errors);
  }

  const residentProgram = readUtf8(root, 'detector/windows-resident/Program.cs', errors, { checkBom: false });
  for (const [relativePath, content] of [['README.md', readme], ['docs/10-当前架构.md', overview]]) {
    requireText(content, 'detector/windows-resident/', relativePath, 'the visual node internal resident source entry', errors);
  }
  // Windows 只剩一个检测端，生命周期命令统一为 detector。
  for (const command of ['open-detector', 'close-detector']) {
    requireText(residentProgram, `"${command}"`, 'detector/windows-resident/Program.cs', `the resident command ${command}`, errors);
    requireText(operations, `\`${command}\``, 'docs/60-构建验证与发布.md', `the resident command ${command}`, errors);
  }

  for (const [directory, role, name, engineering, rowName] of [
    ['detector', 'detector', '相机推流节点', 'VisionGuard.Detector.Android', '相机推流节点（Android）'],
    ['notifier', 'notifier', '通知节点', 'VisionGuard.Notifier.Android', '通知节点（Android）']
  ]) {
    const base = directory + '/android';
    const packageName = 'com.xgwnje.visionguard.' + role;
    const gradlePath = base + '/app/build.gradle.kts';
    const gradle = readUtf8(root, gradlePath, errors, { checkBom: false });
    for (const key of ['namespace', 'applicationId']) {
      requirePattern(gradle, new RegExp(key + '\\s*=\\s*"' + packageName.replaceAll('.', '\\.') + '"'), gradlePath, 'the canonical Android ' + key, errors);
    }
    const settingsPath = base + '/settings.gradle.kts';
    requireText(readUtf8(root, settingsPath, errors, { checkBom: false }), 'rootProject.name = "' + engineering + '"', settingsPath, 'the canonical engineering name', errors);
    const stringsPath = base + '/app/src/main/res/values/strings.xml';
    requireText(readUtf8(root, stringsPath, errors, { checkBom: false }), '<string name="app_name">' + name + '</string>', stringsPath, 'the short application display name', errors);
    const row = engineeringRows.find(cells => cells[0] === rowName) || [];
    requireText(row.join('|'), packageName, namingPath, 'the Android package mapped to ' + name, errors);
    requireText(row.join('|'), engineering, namingPath, 'the engineering name mapped to ' + name, errors);
  }
  for (const [project, assembly, title, product] of [
    ['detector/windows-wpf/VisionGuard.Detector.Windows.csproj', 'VisionGuard.Detector.Windows', '视觉节点', '视觉节点'],
    ['detector/windows-launcher/VisionGuard.Detector.Windows.Launcher.csproj', 'VisionGuard.Detector.Windows', '视觉节点', '视觉节点'],
    ['detector/windows-resident/VisionGuard.Resident.Windows.csproj', 'VisionGuard.Resident.Windows', '视觉节点驻留程序', '视觉节点']
  ]) {
    const content = readUtf8(root, project, errors, { checkBom: false });
    requireText(content, '<AssemblyName>' + assembly + '</AssemblyName>', project, 'the canonical executable identity', errors);
    const rootNamespace = project.includes('windows-launcher') ? 'VisionGuard.Detector.Windows.Launcher' : assembly;
    requireText(content, '<RootNamespace>' + rootNamespace + '</RootNamespace>', project, 'the canonical Windows namespace', errors);
    requireText(content, '<Title>' + title + '</Title>', project, 'the application title', errors);
    requireText(content, '<AssemblyTitle>' + title + '</AssemblyTitle>', project, 'the Windows file description', errors);
    requireText(content, '<Product>' + product + '</Product>', project, 'the Windows product name', errors);
    const row = engineeringRows.find(cells => cells[0] === '视觉节点（Windows）') || [];
    for (const identifier of [rootNamespace, assembly + '.exe']) {
      requireText(row.join('|'), identifier, namingPath, 'the Windows engineering identity mapped to ' + title, errors);
    }
  }
  const packageJson = JSON.parse(readUtf8(root, 'server/package.json', errors, { checkBom: false }));
  if (packageJson.name !== 'visionguard-relay') errors.push('[component] server/package.json must use the canonical relay package name');
  for (const name of ['统一服务']) {
    requireText(packageJson.description, name, 'server/package.json', 'the canonical server display name', errors);
  }
  const webPackage = JSON.parse(readUtf8(root, 'receiver/web/package.json', errors, { checkBom: false }));
  if (webPackage.name !== 'visionguard-web-console') errors.push('[component] receiver/web/package.json must use the canonical console package name');
  for (const [rowName, identifiers] of [
    ['控制台（Web）', ['receiver/web/', webPackage.name, '/console/']],
    ['统一服务（服务端）', ['server/', 'VisionGuard.Relay', packageJson.name, 'server/dist/index.js']]
  ]) {
    const row = engineeringRows.find(cells => cells[0] === rowName) || [];
    for (const identifier of identifiers) {
      requireText(row.join('|'), identifier, namingPath, 'the engineering identifier mapped to ' + rowName, errors);
    }
  }

}

function checkSkillContract(root, errors) {
  const skillsRoot = path.join(root, '.agents', 'skills');
  const actualSkills = fs.existsSync(skillsRoot)
    ? fs.readdirSync(skillsRoot, { withFileTypes: true }).filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort()
    : [];
  const expectedSkills = RETAINED_SKILLS.map((skill) => skill.name).sort();
  if (JSON.stringify(actualSkills) !== JSON.stringify(expectedSkills)) {
    errors.push(`[skill] retained Skill directories differ from the governed set; expected ${expectedSkills.join(', ')}, found ${actualSkills.join(', ')}`);
  }

  for (const skill of RETAINED_SKILLS) {
    const skillPath = `.agents/skills/${skill.name}/SKILL.md`;
    const metadataPath = `.agents/skills/${skill.name}/agents/openai.yaml`;
    const content = readUtf8(root, skillPath, errors);
    const metadata = readUtf8(root, metadataPath, errors);
    requirePattern(content, new RegExp(`^name:\\s*${skill.name.replace('-', '\\-')}\\s*$`, 'm'), skillPath, 'the folder-aligned name', errors);
    requirePattern(content, /^description:\s*\S/m, skillPath, 'a non-empty description', errors);
    if (!fs.existsSync(path.join(root, skill.script))) {
      errors.push(`[skill] ${skill.name} references missing script ${skill.script}`);
    }
    requirePattern(metadata, /^interface:\s*$/m, metadataPath, 'the interface metadata block', errors);
    requirePattern(metadata, /^\s+display_name:\s*"[^"]+"/m, metadataPath, 'the display name', errors);
    requirePattern(metadata, /^\s+short_description:\s*"[^"]+"/m, metadataPath, 'the short description', errors);
    requireText(metadata, `Use $${skill.name}`, metadataPath, 'the default prompt Skill trigger', errors);
    if (/[A-Za-z]:[\\/]|(?:^|\s)\/(?:opt|home|Users)\//.test(content)) {
      errors.push(`[skill] ${skillPath} contains a hardcoded developer or runtime filesystem path`);
    }
    for (const mode of skill.modes) {
      requireText(content, mode, skillPath, `the supported mode ${mode}`, errors);
    }
  }

  const buildScript = readUtf8(root, RETAINED_SKILLS[0].script, errors, { checkBom: false });
  requirePattern(buildScript, /ValidateSet\("All".*"AndroidNotifier"\)/s, RETAINED_SKILLS[0].script, 'the complete build target contract', errors);
  requireText(buildScript, 'Windows Resident', RETAINED_SKILLS[0].script, 'the Windows Resident build target', errors);

  const e2eScript = readUtf8(root, RETAINED_SKILLS[1].script, errors, { checkBom: false });
  const modes = RETAINED_SKILLS[1].modes;
  // 逐项校验实际 ValidateSet 内容，避免只靠「首尾能匹配」而漏掉中间被删/被加的模式。
  requirePattern(
    e2eScript,
    new RegExp(`ValidateSet\\(${modes.map((mode) => `'${mode}'`).join(',\\s*')}\\)`),
    RETAINED_SKILLS[1].script,
    'the complete e2e mode contract',
    errors
  );
  // 已随 WinForms 退役移除的模式不得再出现。
  for (const retired of ['WinFormsPersonDetection', 'WindowsTests']) {
    if (e2eScript.includes(retired)) {
      errors.push(`[contract] ${RETAINED_SKILLS[1].script} still declares the retired mode ${retired}`);
    }
  }
  requirePattern(e2eScript, /ServerSmoke compatibility alias[\s\S]*compile\/artifact smoke only/, RETAINED_SKILLS[1].script, 'the non-E2E ServerSmoke alias boundary', errors);
  requireText(e2eScript, 'WpfPersonDetection', RETAINED_SKILLS[1].script, 'the WPF person semantic mode', errors);
  requireText(e2eScript, 'WpfParserContract', RETAINED_SKILLS[1].script, 'the inference-profile output contract mode', errors);
  requireText(e2eScript, 'ResidentLaunch', RETAINED_SKILLS[1].script, 'the resident launch and server-visibility mode', errors);
  requireText(e2eScript, 'ModelDownload', RETAINED_SKILLS[1].script, 'the model download contract mode', errors);
  requireText(e2eScript, 'SourceAutoSave', RETAINED_SKILLS[1].script, 'the per-source auto-save contract mode', errors);
  requireText(e2eScript, 'assert-resident-visible.js', RETAINED_SKILLS[1].script, 'the server-side resident visibility assertion', errors);

  const releaseScript = readUtf8(root, RETAINED_SKILLS[2].script, errors, { checkBom: false });
  requireText(releaseScript, 'Invoke-ReleasePreflight', RETAINED_SKILLS[2].script, 'the release preflight gate', errors);
  requireText(releaseScript, 'Preflight only complete', RETAINED_SKILLS[2].script, 'the preflight-only boundary', errors);
  requireText(releaseScript, '$SkipServerDeploy', RETAINED_SKILLS[2].script, 'the explicit server-deploy opt-out', errors);
}

function checkValidationContract(root, readme, operations, verificationReport, errors) {
  const wpfSmokeProgram = readUtf8(root, 'detector/windows-wpf-smoke/Program.cs', errors, { checkBom: false });
  requireText(wpfSmokeProgram, 'WatchedClasses', 'detector/windows-wpf-smoke/Program.cs', 'the watched person class configuration', errors);
  requireText(wpfSmokeProgram, 'string.Equals(d.Label, "person"', 'detector/windows-wpf-smoke/Program.cs', 'the exact person-label assertion', errors);
  requireText(wpfSmokeProgram, 'expectedLabel = "person"', 'detector/windows-wpf-smoke/Program.cs', 'the person evidence label', errors);
  requireText(readme, 'person', 'README.md', 'the WPF person semantic assertion', errors);
  requireText(operations, '真实窗口采集', 'docs/60-构建验证与发布.md', 'the real-window boundary', errors);
  requirePattern(operations, /完整(?:报警|告警)链/, 'docs/60-构建验证与发布.md', 'the full-alert-chain boundary', errors);
  requireText(verificationReport, '不把源码存在', 'docs/90-验证记录.md', 'the evidence anti-overclaim rule', errors);
  requirePattern(verificationReport, /待人工[、/].*真机/, 'docs/90-验证记录.md', 'the pending manual/device status vocabulary', errors);
}

function checkDocumentResponsibilities(readme, index, agents, operations, verificationReport, errors) {
  requireText(readme, './docs/00-文档索引.md', 'README.md', 'the canonical documentation index link', errors);
  requireText(readme, './docs/60-构建验证与发布.md', 'README.md', 'the operational verification pointer', errors);
  requireText(index, 'README 面向用户和开发者', 'docs/00-文档索引.md', 'the README responsibility statement', errors);
  requireText(index, 'AGENTS.md 维护项目操作规则', 'docs/00-文档索引.md', 'the AGENTS responsibility statement', errors);

  requireText(index, '验证报告维护自动化、人工和真机证据', 'docs/00-文档索引.md', 'the verification responsibility statement', errors);
  requireText(agents, 'docs/00-文档索引.md', 'AGENTS.md', 'the canonical documentation index pointer', errors);
  requireText(agents, 'docs/90-验证记录.md', 'AGENTS.md', 'the verification evidence pointer', errors);
  requireText(agents, 'docs/60-构建验证与发布.md', 'AGENTS.md', 'the operations pointer', errors);

  requireText(operations, 'ServerBuild', 'docs/60-构建验证与发布.md', 'the operational ServerBuild entry', errors);
  requireText(verificationReport, '证据台账', 'docs/90-验证记录.md', 'the verification-ledger ownership', errors);
}

function checkEvidencePaths(root, documents, errors) {
  // artifacts/ 是本地取证产物，被 .gitignore 忽略，因此干净 checkout（CI）里根本不存在。
  // 在没有它的环境里逐条校验「证据文件存在」只会得到一屏假失败（2026-09-20 实测 59 条，
  // 从 09-16 起每个 push 的 Documentation audit 都是红的），而且无法通过补文件来修——
  // 把取证产物入库既不合适也没有意义。
  //
  // 所以：取证机器（存在 artifacts/）仍然全量校验；干净 checkout 明确整类跳过并打印原因，
  // 不静默放行，也不伪造占位文件。真正的证据审查仍在取证机器与发布 preflight 上执行。
  const artifactsRoot = path.join(root, 'artifacts');
  if (!fs.existsSync(artifactsRoot)) {
    console.log('[evidence] artifacts/ 不在本次 checkout 中，跳过证据文件存在性校验；请在取证机器上运行以校验这些路径。');
    return;
  }

  for (const [relativePath, content] of documents) {
    for (const match of content.matchAll(/`(artifacts\/[^`]+)`/g)) {
      const evidencePath = match[1];
      if (evidencePath.includes('<')) {
        continue;
      }
      if (!fs.existsSync(path.join(root, evidencePath))) {
        errors.push(`[evidence] ${relativePath} points to a missing artifact: ${evidencePath}`);
      }
    }
  }
}

function checkIndexCoverage(docsFiles, index, releaseIndex, errors) {
  for (const relativePath of docsFiles) {
    if (relativePath === 'docs/00-文档索引.md') continue;
    const release = relativePath.startsWith('docs/releases/') && relativePath !== 'docs/releases/README.md';
    const target = path.posix.relative(release ? 'docs/releases' : 'docs', relativePath);
    requireText(release ? releaseIndex : index, `](${target})`, release ? 'docs/releases/README.md' : 'docs/00-文档索引.md', `navigation for ${target}`, errors);
  }
}

function checkDocumentLayout(docsFiles, contents, errors) {
  for (const relativePath of docsFiles) {
    if (!/^docs\/\d+-[^/]*[\u4e00-\u9fff][^/]*\.md$/.test(relativePath) &&
        !/^docs\/releases\/(?:README|v\d+\.\d+\.\d+)\.md$/.test(relativePath)) {
      errors.push(`[layout] ${relativePath} must be a numbered Chinese topic or a versioned release note`);
    }
    if (/^docs\/\d+-发行说明/.test(relativePath)) errors.push(`[layout] ${relativePath} belongs in docs/releases/`);
    const beginning = (contents.get(relativePath) || '').split(/\r?\n/).slice(0, 8).join('\n');
    for (const responsibility of ['负责：', '不负责：', '更新时机：']) requireText(beginning, responsibility, relativePath, 'document scope', errors);
  }
}

function checkVerificationVersionClaims(version, verificationReport, errors) {
  for (const line of verificationReport.split(/\r?\n/)) {
    if (!/当前(?:版本)?为/.test(line) || !/(?:VERSION|package\.json)/.test(line)) {
      continue;
    }
    const match = line.match(/\b(\d+\.\d+\.\d+)\b/);
    if (match && match[1] !== version) {
      errors.push(`[version] docs/90-验证记录.md claims ${match[1]} as current; expected ${version}`);
    }
  }
}

function checkProductContract(readme, overview, agents, errors) {
  for (const [relativePath, content] of [
    ['README.md', readme],
    ['docs/10-当前架构.md', overview]
  ]) {
    requireText(content, '当前', relativePath, 'the current implementation summary', errors);
    requirePattern(content, /Win7[^\n]*legacy|legacy[^\n]*Win7/, relativePath, 'the Win7 legacy compatibility summary', errors);
    requireText(content, '所有公网业务数据统一通过统一服务', relativePath, 'the relay-only transport summary', errors);
    requireText(content, '不使用 P2P、ICE、STUN 或 TURN', relativePath, 'the no-P2P boundary', errors);
    requirePattern(content, /漏报风险[^\n]*最高优先级/, relativePath, 'the missed-detection priority summary', errors);
    requirePattern(content, /通知收件确认不等于声音播放/, relativePath, 'the receipt-playback boundary', errors);
  }
  requireText(agents, '通知收件确认不等于声音播放', 'AGENTS.md', 'the evidence anti-overclaim boundary', errors);
}

function checkCurrentComponentNames(markdownFiles, contents, errors) {
  const staleNames = /视觉中继|视觉检测[（(](?:Windows|Android)[）)]|视觉告警|\bVision Guard\b/;
  for (const relativePath of markdownFiles) {
    if (relativePath === 'docs/15-命名规范.md') continue;
    if (!['README.md', 'AGENTS.md'].includes(relativePath) && (!relativePath.startsWith('docs/') || relativePath.startsWith('docs/releases/'))) continue;
    if (staleNames.test(contents.get(relativePath) || '')) {
      errors.push(`[naming] ${relativePath} uses a retired component display name; use the canonical naming document`);
    }
  }
}

function checkCancelledPlans(root, markdownFiles, contents, errors) {
  const retiredPath = 'docs/codex/15-product-roadmap.md';
  if (fs.existsSync(path.join(root, retiredPath))) {
    errors.push('[cancelled-plan] cancelled roadmap must not be restored');
  }
  const cancelledTerms = /15-product-roadmap\.md|(?<![\d.])5\.0(?![\d.])|\bV\d+\s*[·：]|决策\s*\d+|Detector Platform|Reliable Event Network|Device & Fleet Cloud|Linux (?:ARM64 )?Edge Detector|Web Management Console|DeviceOfflineAlert/;
  for (const relativePath of markdownFiles) {
    // 授权条款与正式发布历史不作为未来工作清单；它们仍受独立的许可与版本检查约束。
    if (!['README.md', 'AGENTS.md'].includes(relativePath) && (!relativePath.startsWith('docs/') || relativePath.startsWith('docs/releases/'))) continue;
    if (cancelledTerms.test(contents.get(relativePath) || '')) {
      errors.push(`[cancelled-plan] ${relativePath} references cancelled version/platform plans`);
    }
  }
}

function checkLicenseTexts(texts, errors) {
  const {
    license,
    contributing,
    readme,
    overview,
    agents
  } = texts;

  requirePattern(license, /^MIT License\r?\n/, 'LICENSE', 'the MIT license title', errors);
  requireText(license, 'Copyright (c) 2026 xgwnje', 'LICENSE', 'the copyright notice', errors);
  requireText(license, 'Permission is hereby granted, free of charge', 'LICENSE', 'the MIT permission grant', errors);
  requireText(license, 'to use, copy, modify, merge, publish, distribute, sublicense, and/or sell', 'LICENSE', 'the MIT permitted uses', errors);
  requireText(license, 'The above copyright notice and this permission notice shall be included', 'LICENSE', 'the MIT notice condition', errors);
  requireText(license, 'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND', 'LICENSE', 'the MIT warranty disclaimer', errors);
  requireText(contributing, '暂不接受外部代码、模型、素材或文档 Pull Request', 'CONTRIBUTING.md', 'the controlled contribution boundary', errors);

  requireText(readme, 'badge/license-MIT-', 'README.md', 'the MIT badge', errors);
  requireText(readme, '[MIT License](./LICENSE)', 'README.md', 'the project license link', errors);
  requireText(contributing, '[MIT License](LICENSE)', 'CONTRIBUTING.md', 'the project license link', errors);
  requireText(overview, 'MIT License', 'docs/10-当前架构.md', 'the MIT license summary', errors);
  requireText(agents, '根目录 `LICENSE` 是唯一项目许可证入口', 'AGENTS.md', 'the canonical project license pointer', errors);
  for (const [relativePath, content] of [
    ['README.md', readme], ['CONTRIBUTING.md', contributing],
    ['docs/10-当前架构.md', overview], ['AGENTS.md', agents]
  ]) {
    requireText(content, '第三方', relativePath, 'the separate third-party license boundary', errors);
  }
}

function checkLicenseContract(root, readme, overview, agents, errors) {
  checkLicenseTexts({
    license: readUtf8(root, 'LICENSE', errors),
    contributing: readUtf8(root, 'CONTRIBUTING.md', errors),
    readme,
    overview,
    agents
  }, errors);
  for (const relativePath of ['server/package.json', 'server/package-lock.json']) {
    const metadata = JSON.parse(readUtf8(root, relativePath, errors));
    const projectPackage = relativePath.endsWith('package-lock.json') ? metadata.packages[''] : metadata;
    if (projectPackage.license !== 'MIT') errors.push(`[license] ${relativePath} must declare MIT for the project package`);
  }
}

function checkDomainAlignment(root, operations, readme, overview, errors) {
  const match = operations.match(/VisionGuard 正式域名：`(https:\/\/[^`]+)`/);
  if (!match) {
    errors.push('[domain] docs/60-构建验证与发布.md does not declare the canonical service domain');
    return;
  }

  const domain = match[1];
  for (const [relativePath, content] of [
    ['README.md', readme],
    ['docs/10-当前架构.md', overview],
    ['detector/windows-shared/Utils/AccountSession.cs', readUtf8(root, 'detector/windows-shared/Utils/AccountSession.cs', errors, { checkBom: false })],
    ['detector/android/app/build.gradle.kts', readUtf8(root, 'detector/android/app/build.gradle.kts', errors, { checkBom: false })],
    ['android-shared/src/main/java/com/xgwnje/visionguard/account/AccountStore.kt', readUtf8(root, 'android-shared/src/main/java/com/xgwnje/visionguard/account/AccountStore.kt', errors, { checkBom: false })]
  ]) {
    requireText(content, domain, relativePath, 'the canonical service domain', errors);
  }
}

function auditRepository(root = DEFAULT_ROOT) {
  const errors = [];
  const docsFiles = listMarkdownFiles(root, 'docs');
  const markdownFiles = [...new Set([
    'README.md',
    'AGENTS.md',
    'CONTRIBUTING.md',
    ...docsFiles
  ])].sort();
  const contents = new Map();

  for (const relativePath of markdownFiles) {
    contents.set(relativePath, readUtf8(root, relativePath, errors));
  }

  const version = readUtf8(root, 'VERSION', errors).trim();
  if (!VERSION_PATTERN.test(version)) {
    errors.push(`[version] VERSION must use x.y.z format; found: ${version || '(empty)'}`);
  }

  const readme = contents.get('README.md') || '';
  const agents = contents.get('AGENTS.md') || '';
  const releaseIndex = contents.get('docs/releases/README.md') || '';
  const index = contents.get('docs/00-文档索引.md') || '';
  const overview = contents.get('docs/10-当前架构.md') || '';

  const operations = contents.get('docs/60-构建验证与发布.md') || '';
  const verificationReport = contents.get('docs/90-验证记录.md') || '';

  if (VERSION_PATTERN.test(version)) {
    checkReadmeVersion(version, readme, errors);
    checkVersionSources(root, version, errors);
    checkVerificationVersionClaims(version, verificationReport, errors);
  }
  checkIndexCoverage(docsFiles, index, releaseIndex, errors);
  checkProductContract(readme, overview, agents, errors);
  checkLicenseContract(root, readme, overview, agents, errors);
  checkDomainAlignment(root, operations, readme, overview, errors);
  checkComponentContract(root, readme, overview, operations, errors);
  checkCurrentComponentNames(markdownFiles, contents, errors);
  checkSkillContract(root, errors);
  checkValidationContract(root, readme, operations, verificationReport, errors);
  checkDocumentResponsibilities(readme, index, agents, operations, verificationReport, errors);
  checkEvidencePaths(root, [

    ['docs/90-验证记录.md', verificationReport]
  ], errors);
  checkLocalLinks(root, markdownFiles, contents, errors);
  checkDocumentAnchors(markdownFiles, contents, errors);
  checkDeprecatedEntrypoints(root, markdownFiles, contents, errors);
  checkCancelledPlans(root, markdownFiles, contents, errors);

  checkDocumentLayout(docsFiles, contents, errors);

  return errors;
}

function main() {
  const errors = auditRepository(DEFAULT_ROOT);
  if (errors.length > 0) {
    console.error(`Documentation audit failed with ${errors.length} issue(s):`);
    for (const error of errors) {
      console.error(`- ${error}`);
    }
    process.exitCode = 1;
    return;
  }

  console.log('Documentation audit passed: navigation, links, encoding, versions, components, four WS roles, retained Skills, evidence paths, domain, license and product boundaries are aligned.');
}

if (require.main === module) {
  main();
}

module.exports = {
  auditRepository,
  checkComponentContract,
  checkCurrentComponentNames,
  checkDocumentAnchors,
  checkDocumentLayout,
  checkDocumentResponsibilities,
  checkDeprecatedEntrypoints,
  checkEvidencePaths,
  checkIndexCoverage,
  checkLicenseTexts,
  checkProductContract,
  checkCancelledPlans,
  checkReadmeVersion,
  checkVersionSources,
  checkSkillContract,
  checkValidationContract,
  checkVerificationVersionClaims
};
