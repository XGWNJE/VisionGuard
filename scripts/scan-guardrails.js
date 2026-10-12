#!/usr/bin/env node
/**
 * 护栏扫描（guardrail scan）
 *
 * 定期找出两类工程改进点，供 owner / agent 复核后登记进 docs/06-任务清单.md：
 *   1. 重复犯错：同一个文件或同一类主题被反复修复；
 *   2. 护栏候选：AGENTS.md 里的硬规则还没有对应的自动化检查。
 *
 * 用法：
 *   node scripts/scan-guardrails.js                 # 报告打印到 stdout
 *   node scripts/scan-guardrails.js --out <file>    # 同时写入文件（CI 用）
 *
 * 这是启发式报告：只做聚类与罗列，不自动改动任何文档，结论由人工判断。
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');

function read(relative) {
  try { return fs.readFileSync(path.join(root, relative), 'utf8'); } catch { return ''; }
}

function git(args) {
  try {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  } catch {
    return '';
  }
}

// 修复类措辞：命中即认为这条记录在讲“曾经出过问题”。
const FIX_WORDS = ['修复', '修掉', '修正', '回归', '缺陷', '报错', '失败', '崩溃', '漏', '错', '坑'];
// 主题聚类时忽略的常见词：通用技术名词与文档口头词不构成“重复犯错”信号。
const STOP_WORDS = new Set([
  'the', 'and', 'for', 'with', 'from', 'that', 'this', 'file', 'files', 'true', 'false', 'null',
  'local', 'log', 'logs', 'json', 'api', 'fix', 'fixes', 'web', 'android', 'windows', 'cpu', 'gpu',
  'apk', 'zip', 'md', 'ts', 'js', 'mjs', 'cjs', 'cs', 'server', 'client', 'test', 'tests', 'build',
  'release', 'preview', 'service', 'version', 'config', 'data', 'docs', 'script', 'scripts', 'app',
  '修复', '问题', '以及', '并且', '然后', '一个', '我们', '已经', '现在', '因为', '所以', '同时',
  '记录', '文档', '项目', '内容', '状态', '结果', '场景', '情况', '验证', '提交', '新增', '改为',
  '这条', '那条', '本轮', '本次', '不再', '没有', '可以', '需要', '如果', '就是', '这样',
  '通过', '失败', '成功', '一致', '错误', '修正', '发布', '版本', '证据', '更新', '支持', '导致',
  '出现', '保持', '确认', '检查', '完成', '恢复', '避免', '正确', '实际', '当前', '相关',
]);
const PATH_PATTERN = /[\w./\\-]+\.(?:kt|kts|java|js|mjs|cjs|ts|tsx|jsx|cs|ps1|sh|py|json|yml|yaml|xml|md|gradle|toml|ini)/g;

/** 提交标题里的修复类条目。 */
function fixSubjects(limit = 500) {
  const out = git(['log', `-n${limit}`, '--pretty=format:%s%x09%H']);
  return out
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(Boolean)
    .map(line => {
      const [subject, hash] = line.split('\t');
      return { subject: subject || '', hash: (hash || '').slice(0, 8) };
    })
    .filter(entry => FIX_WORDS.some(word => entry.subject.includes(word)));
}

/** 验证记录里的条目：每条都带出处，作为证据。 */
function recordEntries() {
  const text = read('docs/90-验证记录.md');
  return text
    .split(/\r?\n/)
    .map((line, index) => ({ line: line.trim(), number: index + 1 }))
    .filter(entry => entry.line.startsWith('- ') && FIX_WORDS.some(word => entry.line.includes(word)));
}

function pathsIn(text) {
  const found = text.match(PATH_PATTERN) || [];
  return found.map(item => item.replace(/\\/g, '/')).filter(item => !item.startsWith('http'));
}

function tokensIn(text) {
  // 只收有指向性的词：2–4 字中文词、kebab-case 与 camelCase 标识符。
  const chinese = text.match(/[\u4e00-\u9fa5]{2,4}/g) || [];
  const kebab = text.match(/\b[a-z][a-z0-9]*(?:-[a-z0-9]+)+\b/g) || [];
  const camel = text.match(/\b[a-z]+(?:[A-Z][a-z0-9]+)+\b/g) || [];
  return [...chinese, ...kebab, ...camel].filter(token => !STOP_WORDS.has(token));
}

function tally(map, key, evidence) {
  if (!map.has(key)) map.set(key, { count: 0, evidence: [] });
  const entry = map.get(key);
  entry.count += 1;
  if (evidence && entry.evidence.length < 3) entry.evidence.push(evidence);
  return entry;
}

function buildReport() {
  const commits = fixSubjects();
  const records = recordEntries();
  const fileHits = new Map();
  const themeHits = new Map();

  // 主题只从修复类提交标题统计：验证记录是长句，混进来会全是“通过 / 失败”这类过程词。
  for (const commit of commits) {
    const excerpt = commit.subject.slice(0, 64);
    for (const file of pathsIn(commit.subject)) tally(fileHits, file, `提交 ${commit.hash}：${excerpt}`);
    for (const token of tokensIn(commit.subject)) tally(themeHits, token, `提交 ${commit.hash}：${excerpt}`);
  }
  for (const record of records) {
    const excerpt = record.line.replace(/^-\s*/, '').slice(0, 64);
    for (const file of pathsIn(record.line)) tally(fileHits, file, `验证记录:${record.number} 「${excerpt}…」`);
  }

  const repeatedFiles = [...fileHits.entries()]
    .filter(([, value]) => value.count >= 2)
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, 12);
  const repeatedThemes = [...themeHits.entries()]
    .filter(([, value]) => value.count >= 3)
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, 12);

  // 护栏现状：文档硬规则 vs 现有自动化检查，供人工对照。
  const ruleLines = [];
  for (const file of ['AGENTS.md', '.github/workflows/development.yml']) {
    const text = read(file);
    for (const line of text.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('- ')) continue;
      if (/不得|必须|禁止|唯一|只允许|不能/.test(trimmed)) ruleLines.push(`${file}: ${trimmed}`);
    }
  }
  const scriptDir = path.join(root, 'scripts');
  const checks = fs.existsSync(scriptDir)
    ? fs.readdirSync(scriptDir).filter(name => /^(check-.*\.js|.*\.test\.js)$/.test(name)).sort()
    : [];

  const lines = [];
  lines.push('# 护栏扫描报告');
  lines.push('');
  lines.push(`生成时间：${new Date().toISOString()}；修复类提交 ${commits.length} 条、验证记录条目 ${records.length} 条。`);
  lines.push('');
  lines.push('本报告只列候选，供复核后登记进 [docs/06-任务清单.md](docs/06-任务清单.md)；不自动改动文档。');
  lines.push('');
  lines.push('## 一、重复犯错：同一文件被反复修复');
  lines.push('');
  if (repeatedFiles.length === 0) {
    lines.push('最近历史里没有同一文件被反复修复的信号。');
  } else {
    lines.push('| 文件 | 命中次数 | 证据 |');
    lines.push('| --- | --- | --- |');
    for (const [file, value] of repeatedFiles) {
      lines.push(`| \`${file}\` | ${value.count} | ${value.evidence.join('；')} |`);
    }
    lines.push('');
    lines.push('处置建议：命中 ≥2 次的文件，若同类问题还会复发，考虑把它固化成自动化检查（见第三节）。');
  }
  lines.push('');
  lines.push('## 二、反复出现的主题');
  lines.push('');
  if (repeatedThemes.length === 0) {
    lines.push('没有达到阈值（≥4 次）的主题。');
  } else {
    lines.push('| 主题词 | 命中次数 | 证据 |');
    lines.push('| --- | --- | --- |');
    for (const [theme, value] of repeatedThemes) {
      lines.push(`| ${theme} | ${value.count} | ${value.evidence.join('；')} |`);
    }
  }
  lines.push('');
  lines.push('## 三、护栏现状（供人工对照）');
  lines.push('');
  lines.push('### 文档里的硬规则');
  lines.push('');
  if (ruleLines.length === 0) {
    lines.push('未提取到硬规则条目。');
  } else {
    for (const rule of ruleLines.slice(0, 25)) lines.push(`- ${rule}`);
  }
  lines.push('');
  lines.push('### 现有自动化检查脚本');
  lines.push('');
  for (const name of checks) lines.push(`- \`scripts/${name}\``);
  lines.push('');
  lines.push('处置建议：逐条对照——「硬规则」里的每一条，如果能被脚本稳定判定，就应该有对应检查；找不到对应检查的规则就是护栏候选。');
  lines.push('');
  lines.push('## 四、下一步');
  lines.push('');
  lines.push('复核上面的候选后，把确认要做的写进 [docs/06-任务清单.md](docs/06-任务清单.md) 的「现在要做的」；本报告不自动追加条目。');
  lines.push('');

  return lines.join('\n');
}

const report = buildReport();
const outIndex = process.argv.indexOf('--out');
if (outIndex >= 0 && process.argv[outIndex + 1]) {
  const target = path.resolve(root, process.argv[outIndex + 1]);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, report, 'utf8');
  console.log(`Guardrail scan written: ${path.relative(root, target)}`);
} else {
  console.log(report);
}
