#!/usr/bin/env node
/**
 * 护栏扫描（guardrail scan）
 *
 * 双向体检，结果供 owner / agent 复核后登记进 docs/06-任务清单.md：
 *   1. 重复犯错：同一个文件或同一类主题被反复修复；
 *   2. 护栏缺口：AGENTS.md 里的硬规则还没有对应的自动化检查；
 *   3. 护栏体检：现有检查里失效、重复、恒真或长期不动的部分——只加不减，护栏迟早变成负担。
 *
 * 用法：
 *   node scripts/scan-guardrails.js                 # 报告打印到 stdout
 *   node scripts/scan-guardrails.js --out <file>    # 同时写入文件（周报 artifact）
 *   node scripts/scan-guardrails.js --check         # 只跑能稳定判定的硬检查，有命中即退出码 1
 *
 * 报告是启发式聚类：只列候选，不自动改动任何文档，谁看谁判断，不依赖任何会话或人来跑。
 * `--check` 那一半不依赖复核，随每次 CI 失败即修：工作流引用了不存在的脚本 / 目录、
 * `continue-on-error: true`、检查脚本里的恒真断言，以及非测试检查脚本引用了仓库里不存在的目标。
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');

function read(relative, base = root) {
  try { return fs.readFileSync(path.join(base, relative), 'utf8'); } catch { return ''; }
}

function git(args, cwd = root) {
  try {
    return execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
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
// 路径匹配要求「前面是非路径字符、扩展名后不再跟字母数字」，否则会把 package.json 截成 package.js。
const PATH_PATTERN = /(?:^|[\s`'"(])([\w][\w./\\-]*\.(?:kt|kts|java|js|mjs|cjs|ts|tsx|jsx|cs|ps1|sh|py|json|yml|yaml|xml|md|gradle|toml|ini))(?![A-Za-z0-9])/g;

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
  const found = [...text.matchAll(PATH_PATTERN)].map(match => match[1].replace(/\\/g, '/'));
  return found.filter(item => !item.startsWith('http'));
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

// ── 信号三：现有护栏体检（失效 / 重复 / 恒真 / 僵化） ────────────────

const GUARD_FILE = /^(?:check-.*|.*\.test)\.js$/;

function guardFiles(base = root) {
  const dir = path.join(base, 'scripts');
  return fs.existsSync(dir) ? fs.readdirSync(dir).filter(name => GUARD_FILE.test(name)).sort() : [];
}

/**
 * 该路径在脚本里是否处于「断言不存在」的上下文：废弃清单、`!fs.existsSync(...)`、
 * deprecated 注释等。这类命中是护栏在防旧入口复活，不是失效引用。
 */
function assertedAbsent(text, item) {
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index++) {
    if (!lines[index].includes(item)) continue;
    const window = lines.slice(Math.max(0, index - 8), index + 2).join('\n');
    if (/deprecated|removed|forbidden|retired|legacy|废弃|退役|废止|已取消|不应存在|不该存在|should not/i.test(window)) return true;
    if (/!\s*(?:fs\.)?existsSync/.test(window)) return true;
  }
  return false;
}

function scanExistingGuards(base = root) {
  const dir = path.join(base, 'scripts');
  const plausiblePath = item => item.includes('/') && !item.includes('//') && !item.includes('/.') && !item.endsWith('/');
  const entries = guardFiles(base).map(name => {
    const filePath = path.join(dir, name);
    const text = fs.readFileSync(filePath, 'utf8');
    const referenced = [...new Set(pathsIn(text))].filter(item => !item.startsWith('http'));
    // 同一路径可能写成相对仓库根、相对 scripts/ 或相对脚本自身目录，任一存在即算存在。
    const exists = item => [base, dir].some(basePath => fs.existsSync(path.join(basePath, item)));
    const lastCommit = git(['log', '-1', '--pretty=format:%cs', '--', `scripts/${name}`], base).trim();
    const staleDays = lastCommit ? Math.round((Date.now() - Date.parse(lastCommit)) / 86400000) : null;
    return {
      name,
      lines: text.split(/\r?\n/).length,
      targets: referenced.filter(exists),
    // 先排除「断言不存在」的上下文与文本片段；只报带目录、形状像仓库路径的命中，
    // 裸文件名（package.json 之类）多是通用名或示例，噪声大于价值。
    missing: referenced.filter(item => (
      plausiblePath(item) && !exists(item) && !item.startsWith('.local/') && !assertedAbsent(text, item)
    )),
      skipped: /skip:\s*(?:true|process\.platform)/.test(text) || /\bt\.skip\(/.test(text),
      weak: /assert\.ok\(\s*true\s*\)/.test(text),
      lastCommit,
      staleDays,
    };
  });

  const overlaps = [];
  for (let i = 0; i < entries.length; i++) {
    for (let j = i + 1; j < entries.length; j++) {
      const left = new Set(entries[i].targets);
      const shared = entries[j].targets.filter(item => left.has(item));
      if (shared.length >= 5) overlaps.push({ a: entries[i].name, b: entries[j].name, shared: shared.slice(0, 4) });
    }
  }
  return { entries, overlaps };
}

/** 工作流里指向不存在脚本或目录的引用：看着在查、其实查不到的护栏最该先清。 */
function scanWorkflowIssues(base = root) {
  const dir = path.join(base, '.github/workflows');
  const issues = [];
  if (!fs.existsSync(dir)) return issues;
  for (const name of fs.readdirSync(dir)) {
    if (!/\.ya?ml$/.test(name)) continue;
    const text = fs.readFileSync(path.join(dir, name), 'utf8');
    for (const match of text.matchAll(/node\s+(?:--test\s+)?(scripts\/[\w./-]+\.js)/g)) {
      if (!fs.existsSync(path.join(base, match[1]))) issues.push(`${name}: 引用不存在的脚本 ${match[1]}`);
    }
    for (const match of text.matchAll(/npm\s+--prefix\s+([\w./-]+)/g)) {
      if (!fs.existsSync(path.join(base, match[1]))) issues.push(`${name}: npm --prefix 指向不存在的目录 ${match[1]}`);
    }
    if (/continue-on-error:\s*true/.test(text)) issues.push(`${name}: 有 continue-on-error: true 的步骤（失败不会让 CI 变红）`);
  }
  return issues;
}

/**
 * 硬检查：命中了就是真问题，不需要谁复核，随每次 CI 失败即修。
 * 需要判断力的部分（重复修复聚类、目标重叠、长期未动、体量大）只留在报告里。
 */
function deterministicFindings(base = root) {
  const findings = scanWorkflowIssues(base).slice();
  const guardHealth = scanExistingGuards(base);
  for (const entry of guardHealth.entries) {
    if (entry.weak) findings.push(`scripts/${entry.name}: 含恒真断言（assert.ok(true)），这条检查永远不会失败`);
  }
  // 测试里的路径多是临时夹具，只有 check-*.js 这类真检查才按「引用了不存在的目标」判定。
  for (const entry of guardHealth.entries) {
    if (/\.test\.js$/.test(entry.name) || entry.missing.length === 0) continue;
    const targets = entry.missing.slice(0, 4).map(item => `\`${item}\``).join('、');
    findings.push(`scripts/${entry.name}: 引用了不存在的目标 ${targets}`);
  }
  return findings;
}

function buildReport() {
  const commits = fixSubjects();
  const records = recordEntries();
  const guardHealth = scanExistingGuards();
  const workflowIssues = scanWorkflowIssues();
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
  lines.push('能稳定判定的部分（工作流引用、`continue-on-error`、恒真断言、检查脚本的失效引用）不在这里等人复核——`node scripts/scan-guardrails.js --check` 在每次 CI 里当硬检查跑，红了就修；下面是需要判断的候选。');
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
  lines.push('## 四、护栏体检：只加不减会让项目膨胀');
  lines.push('');
  lines.push(`现有检查脚本 ${guardHealth.entries.length} 个。下面是需要处置或至少确认的部分。`);
  lines.push('');
  lines.push('### 失效引用（引用了找不到的目标）');
  lines.push('');
  const brokenRefs = guardHealth.entries.filter(entry => entry.missing.length > 0);
  if (brokenRefs.length === 0) {
    lines.push('没有发现检查脚本引用不存在的文件。');
  } else {
    for (const entry of brokenRefs) {
      lines.push(`- \`scripts/${entry.name}\` → 找不到：${entry.missing.slice(0, 4).map(item => `\`${item}\``).join('、')}`);
    }
    lines.push('');
    lines.push('这些命中可能来自正则片段、示例文本或「断言该文件已删除」的用例，逐条确认后再动。');
  }
  lines.push('');
  lines.push('### 工作流里的失效引用');
  lines.push('');
  if (workflowIssues.length === 0) {
    lines.push('工作流引用的脚本与目录都存在。');
  } else {
    for (const issue of workflowIssues) lines.push(`- ${issue}`);
  }
  lines.push('');
  lines.push('### 目标重叠（共同目标 ≥5 个，仅供人工判断）');
  lines.push('');
  if (guardHealth.overlaps.length === 0) {
    lines.push('没有发现目标高度重叠的检查。');
  } else {
    for (const overlap of guardHealth.overlaps) {
      lines.push(`- \`${overlap.a}\` ↔ \`${overlap.b}\`：共同目标 ${overlap.shared.map(item => `\`${item}\``).join('、')}`);
    }
    lines.push('');
    lines.push('注意：提到同一批文件不等于重复——「契约检查 + 它的测试」属于正常配对，不要因此合并。');
  }
  lines.push('');
  lines.push('### 恒真断言、跳过条件与僵化');
  lines.push('');
  const healthRows = [...guardHealth.entries]
    .sort((a, b) => b.lines - a.lines)
    .map(entry => {
      const signals = [];
      if (entry.skipped) signals.push('含 skip 条件');
      if (entry.weak) signals.push('含恒真断言');
      if (entry.lines > 400) signals.push('体量大');
      if (entry.staleDays === null) signals.push('无提交记录');
      else if (entry.staleDays > 90) signals.push(`已 ${entry.staleDays} 天未动`);
      return { entry, signals };
    })
    .filter(row => row.signals.length > 0);
  if (healthRows.length === 0) {
    lines.push('没有命中这些信号。');
  } else {
    lines.push('| 检查 | 行数 | 最近提交 | 信号 |');
    lines.push('| --- | --- | --- | --- |');
    for (const row of healthRows) {
      lines.push(`| \`${row.entry.name}\` | ${row.entry.lines} | ${row.entry.lastCommit || '—'} | ${row.signals.join('；')} |`);
    }
  }
  lines.push('');
  lines.push('处置建议：失效引用优先修或删（先排除示例文本与「断言已删除」的用例）；目标重叠多是正常配对，别急着合并；恒真断言与长期未动的检查，评估保留 / 降级为手动 / 删除。护栏数量不设目标，但每条都要说得清它在防什么、失败时谁会看到。');
  lines.push('');
  lines.push('## 五、下一步');
  lines.push('');
  lines.push('复核时两侧一起看：缺口该补就补，体检出的失效、重复、恒真项该删就删。确认要做的写进 [docs/06-任务清单.md](docs/06-任务清单.md) 的「现在要做的」；本报告不自动追加条目，也不自动改动任何检查。');
  lines.push('');

  return lines.join('\n');
}

function main() {
  if (process.argv.includes('--check')) {
    const findings = deterministicFindings();
    if (findings.length === 0) {
      console.log('Guardrail health check passed: 工作流引用、continue-on-error、恒真断言与检查脚本的失效引用都没有命中。');
      return 0;
    }
    console.error(`Guardrail health check failed: ${findings.length} 项要修。`);
    for (const finding of findings) console.error(`- ${finding}`);
    return 1;
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
  return 0;
}

if (require.main === module) process.exit(main());

module.exports = {
  buildReport,
  deterministicFindings,
  assertedAbsent,
  scanExistingGuards,
  scanWorkflowIssues,
  guardFiles,
  main,
};
