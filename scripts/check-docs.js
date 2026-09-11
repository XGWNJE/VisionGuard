#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');
const { TextDecoder } = require('node:util');

const DEFAULT_ROOT = path.resolve(__dirname, '..');
const VERSION_PATTERN = /^\d+\.\d+\.\d+$/;

const COMPONENTS = [
  { label: 'Windows WinForms 检测端', relativePath: 'detector/windows-winforms', source: 'detector/windows-winforms/Form1.cs' },
  { label: 'Windows WPF 检测端', relativePath: 'detector/windows-wpf', source: 'detector/windows-wpf/App.xaml.cs' },
  { label: 'Windows 驻留程序', relativePath: 'detector/windows-resident', source: 'detector/windows-resident/Program.cs' },
  { label: 'Android 检测端', relativePath: 'detector/android', source: 'detector/android/app/build.gradle.kts' },
  { label: 'Android 接收端', relativePath: 'receiver/android', source: 'receiver/android/app/build.gradle.kts' },
  { label: 'Server', relativePath: 'server', source: 'server/src/index.ts' }
];

const WS_ROLES = ['windows', 'android', 'android-detector', 'windows-resident'];

const RETAINED_SKILLS = [
  { name: 'visionguard-build', script: '.agents/skills/visionguard-build/scripts/build-all.ps1', modes: ['All', 'Server', 'Windows', 'WinForms', 'WPF', 'WindowsResident', 'Android', 'AndroidDetector', 'AndroidReceiver'] },
  { name: 'visionguard-e2e', script: '.agents/skills/visionguard-e2e/scripts/e2e-smoke.ps1', modes: ['Discover', 'ServerBuild', 'ServerSmoke', 'AndroidDetectorSmoke', 'AndroidReceiverSmoke', 'WpfPersonDetection'] },
  { name: 'visionguard-release', script: 'scripts/publish-release.ps1', modes: ['-PreflightOnly', '-SkipServerDeploy', '-UploadVps'] }
];

const DEPRECATED_ENTRYPOINTS = [
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
    ['detector/windows-winforms/Properties/AssemblyInfo.cs', `AssemblyVersion("${version}.0")`],
    ['detector/windows-winforms/Properties/AssemblyInfo.cs', `AssemblyFileVersion("${version}.0")`],
    ['detector/windows-winforms/VisionGuard.csproj', `<ApplicationVersion>${version}.%2a</ApplicationVersion>`],
    ['detector/windows-winforms/Services/ServerPushService.cs', `["version"] = "${version}"`],
    ['detector/windows-wpf/Utils/AppConfig.cs', `Version = "${version}"`],
    ['detector/windows-wpf/VisionGuard.csproj', `<Version>${version}</Version>`],
    ['detector/windows-wpf/VisionGuard.csproj', `<FileVersion>${version}</FileVersion>`],
    ['detector/windows-wpf/VisionGuard.csproj', `<AssemblyVersion>${version}</AssemblyVersion>`],
    ['detector/android/app/build.gradle.kts', `versionName = "${version}"`],
    ['detector/android/app/build.gradle.kts', `versionCode = ${versionCode}`],
    ['detector/android/app/src/main/java/com/xgwnje/visionguard/AppConstants.kt', `VERSION = "${version}"`],
    ['receiver/android/app/build.gradle.kts', `versionName = "${version}"`],
    ['receiver/android/app/build.gradle.kts', `versionCode = ${versionCode}`],
    ['receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt', `VERSION = "${version}"`],
    ['server/src/index.ts', `VisionGuard Server v${version} 已启动`]
  ];

  const cache = new Map();
  for (const [relativePath, expected] of exactChecks) {
    if (!cache.has(relativePath)) {
      cache.set(relativePath, readUtf8(root, relativePath, errors, { checkBom: false }));
    }
    requireText(cache.get(relativePath), expected, relativePath, 'the VERSION-aligned value', errors);
  }

  for (const relativePath of ['server/package.json', 'server/package-lock.json', 'server/data/releases.json']) {
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

function checkComponentContract(root, readme, overview, operations, errors) {
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
  const overviewRows = tableRows(overview, '## 当前实际组件与验证状态');
  if (readmeRows.length !== COMPONENTS.length) {
    errors.push(`[component] README.md current component table has ${readmeRows.length} data rows; expected ${COMPONENTS.length}`);
  }
  if (overviewRows.length !== COMPONENTS.length) {
    errors.push(`[component] docs/codex/10-project-overview.md current component table has ${overviewRows.length} data rows; expected ${COMPONENTS.length}`);
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
    requireText(overview, component.relativePath, 'docs/codex/10-project-overview.md', `the component path for ${component.label}`, errors);
    requireText(readme, component.label, 'README.md', `the canonical component name ${component.label}`, errors);
    requireText(overview, component.label, 'docs/codex/10-project-overview.md', `the canonical component name ${component.label}`, errors);
  }

  const connectionManager = readUtf8(root, 'server/src/services/ConnectionManager.ts', errors, { checkBom: false });
  for (const role of WS_ROLES) {
    requireText(connectionManager, `'${role}'`, 'server/src/services/ConnectionManager.ts', `the active WebSocket role ${role}`, errors);
    requireText(overview, `\`${role}\``, 'docs/codex/10-project-overview.md', `the documented WebSocket role ${role}`, errors);
  }

  const residentProgram = readUtf8(root, 'detector/windows-resident/Program.cs', errors, { checkBom: false });
  for (const command of ['open-wpf', 'open-winforms', 'close-wpf', 'close-winforms']) {
    requireText(residentProgram, `"${command}"`, 'detector/windows-resident/Program.cs', `the resident command ${command}`, errors);
    requireText(operations, `\`${command}\``, 'docs/codex/60-operations.md', `the resident command ${command}`, errors);
  }

  const detectorGradle = readUtf8(root, 'detector/android/app/build.gradle.kts', errors, { checkBom: false });
  const receiverGradle = readUtf8(root, 'receiver/android/app/build.gradle.kts', errors, { checkBom: false });
  requireText(detectorGradle, 'com.xgwnje.visionguard', 'detector/android/app/build.gradle.kts', 'the Android detector package', errors);
  requireText(receiverGradle, 'com.xgwnje.visionguard_android', 'receiver/android/app/build.gradle.kts', 'the Android receiver package', errors);
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
  requirePattern(buildScript, /ValidateSet\("All".*"AndroidReceiver"\)/s, RETAINED_SKILLS[0].script, 'the complete build target contract', errors);
  requireText(buildScript, 'Windows Resident', RETAINED_SKILLS[0].script, 'the Windows Resident build target', errors);

  const e2eScript = readUtf8(root, RETAINED_SKILLS[1].script, errors, { checkBom: false });
  requirePattern(e2eScript, /ValidateSet\('Discover'.*'WpfPersonDetection'\)/s, RETAINED_SKILLS[1].script, 'the complete e2e mode contract', errors);
  requirePattern(e2eScript, /ServerSmoke compatibility alias[\s\S]*compile\/artifact smoke only/, RETAINED_SKILLS[1].script, 'the non-E2E ServerSmoke alias boundary', errors);
  requireText(e2eScript, 'WpfPersonDetection', RETAINED_SKILLS[1].script, 'the WPF person semantic mode', errors);

  const releaseScript = readUtf8(root, RETAINED_SKILLS[2].script, errors, { checkBom: false });
  requireText(releaseScript, 'Invoke-ReleasePreflight', RETAINED_SKILLS[2].script, 'the release preflight gate', errors);
  requireText(releaseScript, 'Preflight only complete', RETAINED_SKILLS[2].script, 'the preflight-only boundary', errors);
  requireText(releaseScript, '$SkipServerDeploy', RETAINED_SKILLS[2].script, 'the explicit server-deploy opt-out', errors);
}

function checkValidationContract(root, readme, operations, verificationReport, errors) {
  const wpfScript = readUtf8(root, 'scripts/test-wpf-person-detection.ps1', errors, { checkBom: false });
  const wpfSmokeProgram = readUtf8(root, 'detector/windows-wpf-smoke/Program.cs', errors, { checkBom: false });
  requireText(wpfScript, '$images.Count -ne 4', 'scripts/test-wpf-person-detection.ps1', 'the exactly-four-window fixture contract', errors);
  requireText(wpfScript, 'personHitFrames', 'scripts/test-wpf-person-detection.ps1', 'the person detection assertion', errors);
  requireText(wpfScript, '$report.passed', 'scripts/test-wpf-person-detection.ps1', 'the explicit passing report', errors);
  requireText(wpfSmokeProgram, 'WatchedClasses', 'detector/windows-wpf-smoke/Program.cs', 'the watched person class configuration', errors);
  requireText(wpfSmokeProgram, 'string.Equals(d.Label, "person"', 'detector/windows-wpf-smoke/Program.cs', 'the exact person-label assertion', errors);
  requireText(wpfSmokeProgram, 'expectedLabel = "person"', 'detector/windows-wpf-smoke/Program.cs', 'the person evidence label', errors);
  requireText(readme, 'person', 'README.md', 'the WPF person semantic assertion', errors);
  requireText(operations, '真实窗口采集', 'docs/codex/60-operations.md', 'the real-window boundary', errors);
  requirePattern(operations, /完整(?:报警|告警)链/, 'docs/codex/60-operations.md', 'the full-alert-chain boundary', errors);
  requireText(verificationReport, '不把源码存在', 'docs/codex/90-verification-report.md', 'the evidence anti-overclaim rule', errors);
  requirePattern(verificationReport, /待人工[、/].*真机/, 'docs/codex/90-verification-report.md', 'the pending manual/device status vocabulary', errors);
}

function checkDocumentResponsibilities(readme, index, codexGuide, agents, roadmap, operations, verificationReport, errors) {
  requireText(readme, './docs/codex/00-index.md', 'README.md', 'the canonical documentation index link', errors);
  requireText(readme, './docs/codex/60-operations.md', 'README.md', 'the operational verification pointer', errors);
  requireText(index, 'README 面向用户和开发者', 'docs/codex/00-index.md', 'the README responsibility statement', errors);
  requireText(index, 'AGENTS.md 维护项目操作规则', 'docs/codex/00-index.md', 'the AGENTS responsibility statement', errors);
  requireText(index, '路线图维护规划、阶段进度和验收状态', 'docs/codex/00-index.md', 'the roadmap responsibility statement', errors);
  requireText(index, '验证报告维护自动化、人工和真机证据', 'docs/codex/00-index.md', 'the verification responsibility statement', errors);
  requireText(codexGuide, 'docs/codex/00-index.md', 'CODEX.md', 'the canonical documentation index pointer', errors);
  requireText(agents, 'docs/codex/90-verification-report.md', 'AGENTS.md', 'the verification evidence pointer', errors);
  requireText(agents, 'docs/codex/60-operations.md', 'AGENTS.md', 'the operations pointer', errors);
  requireText(roadmap, '验收', 'docs/codex/15-product-roadmap.md', 'the roadmap acceptance ownership', errors);
  requireText(operations, 'ServerBuild', 'docs/codex/60-operations.md', 'the operational ServerBuild entry', errors);
  requireText(verificationReport, '证据台账', 'docs/codex/90-verification-report.md', 'the verification-ledger ownership', errors);
}

function checkEvidencePaths(root, documents, errors) {
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

function checkIndexCoverage(codexFiles, index, codexGuide, errors) {
  for (const relativePath of codexFiles) {
    const fileName = path.basename(relativePath);
    if (fileName === '00-index.md') {
      continue;
    }
    requireText(index, `](${fileName})`, 'docs/codex/00-index.md', `navigation for ${fileName}`, errors);
    requireText(codexGuide, `](docs/codex/${fileName})`, 'CODEX.md', `navigation for ${fileName}`, errors);
  }
}

function checkVerificationVersionClaims(version, verificationReport, errors) {
  for (const line of verificationReport.split(/\r?\n/)) {
    if (!/当前(?:版本)?为/.test(line) || !/(?:VERSION|package\.json)/.test(line)) {
      continue;
    }
    const match = line.match(/\b(\d+\.\d+\.\d+)\b/);
    if (match && match[1] !== version) {
      errors.push(`[version] docs/codex/90-verification-report.md claims ${match[1]} as current; expected ${version}`);
    }
  }
}

function checkProductContract(readme, overview, roadmap, agents, errors) {
  requireText(roadmap, '本文是产品方向、阶段顺序与验收闸门的唯一事实来源', 'docs/codex/15-product-roadmap.md', 'the roadmap ownership statement', errors);
  requireText(roadmap, '免费版以目前已经实现的纯软件视觉方案为边界', 'docs/codex/15-product-roadmap.md', 'the free software-visual edition boundary', errors);
  requireText(roadmap, '系统一旦接入检测硬件探测器，即进入付费版', 'docs/codex/15-product-roadmap.md', 'the paid hardware-detector edition boundary', errors);
  requireText(roadmap, '首个销售市场暂定中国大陆', 'docs/codex/15-product-roadmap.md', 'the initial sales market', errors);
  requireText(roadmap, '以控制台为最高权限管理入口', 'docs/codex/15-product-roadmap.md', 'the Web console authority boundary', errors);
  requirePattern(roadmap, /Win7[^\n]*(?:WinForms|Visual Detector)/, 'docs/codex/15-product-roadmap.md', 'the implemented Win7 compatibility boundary', errors);
  requirePattern(roadmap, /驻留程序[^\n]*Win7 SP1 x64 兼容[^\n]*硬门槛/, 'docs/codex/15-product-roadmap.md', 'the resident Win7 delivery gate', errors);
  requireText(roadmap, '不再规划 P2P、ICE、STUN 或 TURN', 'docs/codex/15-product-roadmap.md', 'the Server-only network boundary', errors);
  requireText(roadmap, '允许在可管理范围内误报', 'docs/codex/15-product-roadmap.md', 'the missed-detection priority', errors);
  requireText(roadmap, 'DeviceOfflineAlert', 'docs/codex/15-product-roadmap.md', 'the device-offline alert contract', errors);
  requireText(roadmap, '独立的 Server 外部健康监测', 'docs/codex/15-product-roadmap.md', 'the external Server monitoring boundary', errors);

  for (const [relativePath, content] of [
    ['README.md', readme],
    ['docs/codex/10-project-overview.md', overview]
  ]) {
    requirePattern(content, /Visual Detector/, relativePath, 'the Visual Detector product term', errors);
    requirePattern(content, /(?:目前|当前)已(?:经)?实现的纯软件视觉方案[^\n]*免费版/, relativePath, 'the free software-visual edition summary', errors);
    requirePattern(content, /接入检测硬件探测器[^\n]*付费版/, relativePath, 'the paid hardware-detector edition summary', errors);
    requirePattern(content, /Win7[^\n]*(?:WinForms|Visual Detector)|(?:WinForms|Visual Detector)[^\n]*Win7/, relativePath, 'the Win7-only compatibility summary', errors);
    requireText(content, '所有公网业务数据统一通过 Server', relativePath, 'the Server-only transport summary', errors);
    requirePattern(content, /不再规划 P2P/, relativePath, 'the no-P2P boundary', errors);
    requirePattern(content, /漏报风险[^\n]*最高优先级/, relativePath, 'the missed-detection priority summary', errors);
    requirePattern(content, /离线报警/, relativePath, 'the device-offline alert summary', errors);
    requirePattern(content, /(?:未来|尚未|不等于)[^\n]*DeviceOfflineAlert|DeviceOfflineAlert[^\n]*(?:未来|尚未|不等于|未)/, relativePath, 'the not-yet-delivered offline-alert boundary', errors);
  }

  requireText(readme, '](./docs/codex/15-product-roadmap.md)', 'README.md', 'the canonical roadmap link', errors);
  requireText(overview, '](15-product-roadmap.md)', 'docs/codex/10-project-overview.md', 'the canonical roadmap link', errors);
  requireText(agents, '](docs/codex/15-product-roadmap.md)', 'AGENTS.md', 'the canonical roadmap link', errors);
}

function checkLicenseTexts(texts, errors) {
  const {
    license,
    legacyMit,
    licenseHistory,
    commercialLicense,
    contributing,
    readme,
    roadmap,
    agents
  } = texts;
  const cutoff = 'c43c0ff122043d477b442b7507d193b62ea321bb';

  requireText(license, 'VisionGuard Source Available License 1.0', 'LICENSE', 'the VGSAL-1.0 title', errors);
  requireText(license, 'This license is a source-available license. It is not an open-source license.', 'LICENSE', 'the non-open-source declaration', errors);
  requireText(license, 'Pure Software Visual Use', 'LICENSE', 'the free software-visual definition', errors);
  requireText(license, 'Hardware Detector Use', 'LICENSE', 'the paid hardware-detector definition', errors);
  requireText(license, 'Commercial License Required', 'LICENSE', 'the commercial authorization boundary', errors);
  requireText(license, 'LICENSE-HISTORY.md', 'LICENSE', 'the license-history pointer', errors);
  requireText(license, 'LICENSE-MIT', 'LICENSE', 'the preserved MIT pointer', errors);

  requirePattern(legacyMit, /^MIT License\r?\n/, 'LICENSE-MIT', 'the preserved MIT license text', errors);
  requireText(legacyMit, 'Copyright (c) 2026 xgwnje', 'LICENSE-MIT', 'the historical copyright notice', errors);

  requireText(licenseHistory, cutoff, 'LICENSE-HISTORY.md', 'the exact MIT cutoff commit', errors);
  requireText(licenseHistory, '`v4.4.3`', 'LICENSE-HISTORY.md', 'the final MIT release tag', errors);
  requireText(licenseHistory, '许可证切换不试图撤回或缩减上述历史版本已经授予的权限', 'LICENSE-HISTORY.md', 'the non-retroactive license statement', errors);

  requireText(commercialLicense, '必须商业授权的范围', 'COMMERCIAL-LICENSE.md', 'the commercial authorization scope', errors);
  requireText(commercialLicense, 'Edge Detector', 'COMMERCIAL-LICENSE.md', 'the hardware detector example', errors);
  requireText(commercialLicense, cutoff, 'COMMERCIAL-LICENSE.md', 'the MIT cutoff pointer', errors);
  requireText(contributing, '暂不接受外部代码、模型、素材或文档 Pull Request', 'CONTRIBUTING.md', 'the controlled contribution boundary', errors);

  requireText(readme, 'badge/license-VGSAL--1.0-', 'README.md', 'the VGSAL-1.0 badge', errors);
  requireText(readme, '这是源码可见许可证，不是开源许可证', 'README.md', 'the source-available license summary', errors);
  requireText(readme, 'COMMERCIAL-LICENSE.md', 'README.md', 'the commercial license link', errors);
  requireText(readme, 'LICENSE-HISTORY.md', 'README.md', 'the license history link', errors);
  requireText(readme, 'LICENSE-MIT', 'README.md', 'the historical MIT link', errors);
  requireText(roadmap, '`VGSAL-1.0`', 'docs/codex/15-product-roadmap.md', 'the product license strategy', errors);
  requireText(agents, 'LICENSE-HISTORY.md', 'AGENTS.md', 'the immutable MIT cutoff pointer', errors);
}

function checkLicenseContract(root, readme, roadmap, agents, errors) {
  checkLicenseTexts({
    license: readUtf8(root, 'LICENSE', errors),
    legacyMit: readUtf8(root, 'LICENSE-MIT', errors),
    licenseHistory: readUtf8(root, 'LICENSE-HISTORY.md', errors),
    commercialLicense: readUtf8(root, 'COMMERCIAL-LICENSE.md', errors),
    contributing: readUtf8(root, 'CONTRIBUTING.md', errors),
    readme,
    roadmap,
    agents
  }, errors);
}

function checkDomainAlignment(root, operations, readme, overview, errors) {
  const match = operations.match(/VisionGuard 正式域名：`(https:\/\/[^`]+)`/);
  if (!match) {
    errors.push('[domain] docs/codex/60-operations.md does not declare the canonical service domain');
    return;
  }

  const domain = match[1];
  for (const [relativePath, content] of [
    ['README.md', readme],
    ['docs/codex/10-project-overview.md', overview],
    ['detector/windows-winforms/Form1.cs', readUtf8(root, 'detector/windows-winforms/Form1.cs', errors, { checkBom: false })],
    ['detector/windows-wpf/Utils/AppConfig.cs', readUtf8(root, 'detector/windows-wpf/Utils/AppConfig.cs', errors, { checkBom: false })],
    ['detector/android/app/src/main/java/com/xgwnje/visionguard/AppConstants.kt', readUtf8(root, 'detector/android/app/src/main/java/com/xgwnje/visionguard/AppConstants.kt', errors, { checkBom: false })],
    ['receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt', readUtf8(root, 'receiver/android/app/src/main/java/com/xgwnje/visionguard_android/AppConstants.kt', errors, { checkBom: false })]
  ]) {
    requireText(content, domain, relativePath, 'the canonical service domain', errors);
  }
}

function auditRepository(root = DEFAULT_ROOT) {
  const errors = [];
  const codexFiles = listMarkdownFiles(root, 'docs/codex');
  const docsFiles = listMarkdownFiles(root, 'docs');
  const designFiles = listMarkdownFiles(root, 'docs/design');
  const historicalSpec = 'docs/superpowers/specs/2026-07-12-v5-multi-user-p2p-architecture.md';
  const markdownFiles = [...new Set([
    'README.md',
    'AGENTS.md',
    'CODEX.md',
    'COMMERCIAL-LICENSE.md',
    'CONTRIBUTING.md',
    'LICENSE-HISTORY.md',
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
  const codexGuide = contents.get('CODEX.md') || '';
  const index = contents.get('docs/codex/00-index.md') || '';
  const overview = contents.get('docs/codex/10-project-overview.md') || '';
  const roadmap = contents.get('docs/codex/15-product-roadmap.md') || '';
  const operations = contents.get('docs/codex/60-operations.md') || '';
  const verificationReport = contents.get('docs/codex/90-verification-report.md') || '';

  if (VERSION_PATTERN.test(version)) {
    checkReadmeVersion(version, readme, errors);
    checkVersionSources(root, version, errors);
    checkVerificationVersionClaims(version, verificationReport, errors);
  }
  checkIndexCoverage(codexFiles, index, codexGuide, errors);
  checkProductContract(readme, overview, roadmap, agents, errors);
  checkLicenseContract(root, readme, roadmap, agents, errors);
  checkDomainAlignment(root, operations, readme, overview, errors);
  checkComponentContract(root, readme, overview, operations, errors);
  checkSkillContract(root, errors);
  checkValidationContract(root, readme, operations, verificationReport, errors);
  checkDocumentResponsibilities(readme, index, codexGuide, agents, roadmap, operations, verificationReport, errors);
  checkEvidencePaths(root, [
    ['docs/codex/15-product-roadmap.md', roadmap],
    ['docs/codex/90-verification-report.md', verificationReport]
  ], errors);
  checkLocalLinks(root, markdownFiles, contents, errors);
  checkDocumentAnchors(markdownFiles, contents, errors);
  checkDeprecatedEntrypoints(root, markdownFiles, contents, errors);

  const designIndex = contents.get('docs/design/README.md') || '';
  for (const relativePath of designFiles) {
    const fileName = path.basename(relativePath);
    if (fileName !== 'README.md') {
      requireText(designIndex, `](./${fileName})`, 'docs/design/README.md', `navigation for ${fileName}`, errors);
    }
  }

  const oldSpec = contents.get(historicalSpec) || '';
  requirePattern(oldSpec.slice(0, 1000), /已取代/, historicalSpec, 'the superseded status', errors);
  requireText(oldSpec.slice(0, 1000), '../../codex/15-product-roadmap.md', historicalSpec, 'the replacement roadmap link', errors);

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

  console.log('Documentation audit passed: navigation, links, encoding, versions, six components, four WS roles, retained Skills, evidence paths, domain, license and product boundaries are aligned.');
}

if (require.main === module) {
  main();
}

module.exports = {
  auditRepository,
  checkComponentContract,
  checkDocumentAnchors,
  checkDocumentResponsibilities,
  checkDeprecatedEntrypoints,
  checkEvidencePaths,
  checkIndexCoverage,
  checkLicenseTexts,
  checkProductContract,
  checkReadmeVersion,
  checkSkillContract,
  checkValidationContract,
  checkVerificationVersionClaims
};
