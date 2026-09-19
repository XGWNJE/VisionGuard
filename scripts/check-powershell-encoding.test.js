// Windows PowerShell 5.1 的读取规则：带 BOM 的 UTF-8 按 UTF-8 解码，**无 BOM 则按系统 ANSI（本机 936）解码**。
// 含中文的脚本一旦无 BOM，多字节序列里的 0x5C 会被当成续行/转义，整份脚本解析失败，
// 报错信息还与真实原因无关（2026-09-17 实测：发布脚本 20 处语法错误、e2e 脚本 8 处，
// 任何发布调用都会在解析阶段直接中止）。
// 该测试把「含非 ASCII 的 PowerShell 脚本必须带 BOM」变成机器可判定的约束。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const skippedDirectories = new Set(['node_modules', 'bin', 'obj', 'build', '.gradle', '.git', '.local', 'artifacts', 'dist']);

function collectPowerShellScripts(directory) {
  const found = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (skippedDirectories.has(entry.name)) continue;
      found.push(...collectPowerShellScripts(path.join(directory, entry.name)));
      continue;
    }
    if (entry.isFile() && entry.name.toLowerCase().endsWith('.ps1')) found.push(path.join(directory, entry.name));
  }
  return found;
}

test('every non-ASCII PowerShell script carries a UTF-8 BOM so PowerShell 5.1 reads it as UTF-8', () => {
  const scripts = collectPowerShellScripts(root);
  assert.ok(scripts.length >= 4, `expected the maintained PowerShell entry points, found ${scripts.length}`);

  const offenders = [];
  for (const script of scripts) {
    const bytes = fs.readFileSync(script);
    // 文本层面的非 ASCII 判定：解析时去掉 BOM 后按 UTF-8 解码。
    const text = bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]))
      ? bytes.subarray(3).toString('utf8')
      : bytes.toString('utf8');
    const hasNonAscii = /[^\x00-\x7F]/.test(text);
    const hasBom = bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]));
    if (hasNonAscii && !hasBom) offenders.push(path.relative(root, script));
    if (!hasNonAscii && hasBom) offenders.push(`${path.relative(root, script)} (ASCII-only file carries a BOM; keep PowerShell files BOM-only when they contain non-ASCII)`);
  }

  assert.deepEqual(offenders, [], 'PowerShell encoding contract violated');
});
