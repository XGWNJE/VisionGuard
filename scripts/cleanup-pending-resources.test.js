const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync, execFileSync } = require('node:child_process');
const test = require('node:test');

const source = fs.readFileSync(path.join(__dirname, 'cleanup-pending-resources.ps1'), 'utf8');
const files = [...source.matchAll(/Relative = '([^']+)'; Hash = '([A-F0-9]{64})'/g)];
const directories = [
  '.local/acceptance-direct-input/instrumentation-classes',
  '.local/acceptance-direct-input/instrumentation-dex'
];

// Replace fingerprints only in an isolated script copy; never read real residue.
function fixture(t, { busy = false, inspectFails = false } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-pending-cleanup-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (relative, content = 'keep') => {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
    return target;
  };
  write('VERSION', '0.6.11'); write('server/package.json', '{}');
  write('.gitignore', '.local/\n');
  execFileSync('git', ['init', '-q', root]);
  execFileSync('git', ['-C', root, '-c', 'core.autocrlf=false', 'add', 'VERSION', 'server/package.json', '.gitignore']);
  let fixtureScript = source;
  files.forEach(([, relative, hash], index) => {
    const content = `disposable fixture ${index}`;
    write(relative, content);
    fixtureScript = fixtureScript.replace(hash, crypto.createHash('sha256').update(content).digest('hex'));
  });
  directories.forEach(relative => fs.mkdirSync(path.join(root, relative), { recursive: true }));
  fs.mkdirSync(path.join(root, directories[0], 'com', 'example'), { recursive: true });
  const protectedFiles = [
    '.local/cache-maintenance/cleanup-local-residue.ps1',
    '.local/cache-maintenance/server-cleanup.py',
    '.local/release-0.6.6/preserved-cache.json',
    '.local/release-0.6.6/preserved-python-cache/other.pyc',
    '.local/acceptance-direct-input/helper-keystore.p12',
    '.local/acceptance-direct-input/build-input-helper.ps1',
    'scripts/__pycache__/server-resource-maintenance.cpython-312.pyc'
  ];
  protectedFiles.forEach(relative => write(relative));
  const processResult = inspectFails ? "throw 'fixture inspection failure'" : busy
    ? "[pscustomobject]@{ ProcessId = 999999; CommandLine = 'node pinned-probe.cjs' }"
    : 'return @()';
  fixtureScript = fixtureScript.replace("$ErrorActionPreference = 'Stop'",
    `$ErrorActionPreference = 'Stop'\nfunction Get-CimInstance { param($ClassName) ${processResult} }`);
  const script = write('scripts/cleanup-pending-resources.ps1', fixtureScript);
  const run = (...args) => spawnSync('powershell.exe', [
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, '-RepositoryRoot', root, ...args
  ], { encoding: 'utf8' });
  const exists = relative => fs.existsSync(path.join(root, relative));
  const allPresent = () => [...files.map(match => match[1]), ...directories].forEach(relative => assert.ok(exists(relative), relative));
  const retained = () => protectedFiles.forEach(relative => assert.equal(fs.readFileSync(path.join(root, relative), 'utf8'), 'keep'));
  return { root, script, write, run, exists, allPresent, retained };
}

test('fixed scope and journal inspection contain no remote write operation', () => {
  assert.equal(files.length, 3);
  const remote = source.match(/\$remoteCommand = @'\r?\n([\s\S]+?)\r?\n'@/)[1];
  assert.match(remote, /journalctl --disk-usage/);
  assert.doesNotMatch(remote, /vacuum|rotate|restart|sudo|rm\s|tee\s/);
  assert.doesNotMatch(source, /Remove-Item[^\n]*-Recurse/);
});

test('default preview and Apply WhatIf preserve every candidate and protected file', t => {
  const f = fixture(t);
  for (const args of [[], ['-Apply', '-WhatIf']]) {
    const result = f.run(...args);
    assert.equal(result.status, 0, result.stderr + result.stdout);
    f.allPresent(); f.retained();
  }
});

test('Apply removes exact fingerprinted files and empty trees; rerun is harmless', t => {
  const f = fixture(t);
  for (let count = 0; count < 2; count++) {
    const result = f.run('-Apply');
    assert.equal(result.status, 0, result.stderr + result.stdout);
    [...files.map(match => match[1]), ...directories].forEach(relative => assert.equal(f.exists(relative), false, relative));
    f.retained();
  }
});

test('changed or tracked files and nonempty directories are preserved', t => {
  const f = fixture(t);
  f.write(files[0][1], 'changed');
  execFileSync('git', ['-C', f.root, 'add', '-f', files[1][1]]);
  f.write(`${directories[0]}/new.class`, 'active output');
  const result = f.run('-Apply');
  assert.notEqual(result.status, 0);
  assert.match(result.stdout, /SHA256 changed/);
  assert.match(result.stdout, /tracked file/);
  assert.match(result.stdout, /contains a file/);
  assert.equal(fs.readFileSync(path.join(f.root, files[0][1]), 'utf8'), 'changed');
  assert.ok(f.exists(files[1][1]));
  assert.ok(f.exists(`${directories[0]}/new.class`));
  assert.equal(f.exists(files[2][1]), false);
  f.retained();
});

test('junctions at a target and inside an empty tree preserve external contents', t => {
  const f = fixture(t);
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-pending-outside-'));
  t.after(() => fs.rmSync(outside, { recursive: true, force: true }));
  fs.writeFileSync(path.join(outside, 'keep.txt'), 'external');
  fs.rmdirSync(path.join(f.root, directories[1]));
  fs.symlinkSync(outside, path.join(f.root, directories[1]), 'junction');
  fs.symlinkSync(outside, path.join(f.root, directories[0], 'link'), 'junction');
  const result = f.run('-Apply');
  assert.notEqual(result.status, 0);
  assert.match(result.stdout, /Linked path or junction/);
  assert.match(result.stdout, /Linked descendant/);
  assert.ok(f.exists(directories[0])); assert.ok(f.exists(directories[1]));
  assert.equal(fs.readFileSync(path.join(outside, 'keep.txt'), 'utf8'), 'external');
  f.retained();
});

test('related processes or unavailable process inspection stop all deletion', t => {
  for (const options of [{ busy: true }, { inspectFails: true }]) {
    const f = fixture(t, options);
    const result = f.run('-Apply');
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, options.busy ? /Related task processes are active/ : /Cannot check active processes/);
    f.allPresent(); f.retained();
  }
});

test('a parent directory containing a checkout is not an accepted deletion root', t => {
  const f = fixture(t);
  const result = spawnSync('powershell.exe', [
    '-NoProfile', '-File', f.script, '-RepositoryRoot', path.dirname(f.root), '-Apply'
  ], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  f.allPresent();
});
