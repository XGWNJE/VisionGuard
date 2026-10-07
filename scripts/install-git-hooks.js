#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');

function installGitHooks(cwd = path.resolve(__dirname, '..')) {
  const git = args => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
  const root = git(['rev-parse', '--show-toplevel']);
  const hooks = path.join(root, '.githooks');
  for (const name of ['pre-commit', 'pre-push']) {
    if (!fs.statSync(path.join(hooks, name)).isFile()) throw new Error(`Missing hook: ${name}`);
  }
  const current = spawnSync('git', ['config', '--get', 'core.hooksPath'], { cwd, encoding: 'utf8' });
  if (current.status !== 0 && current.status !== 1) throw new Error(current.stderr || 'Cannot read hooks configuration');
  const configured = current.stdout.trim();
  if (configured && path.resolve(root, configured) !== hooks) throw new Error('Existing core.hooksPath must be integrated manually');
  if (!configured) {
    const oldHooks = git(['rev-parse', '--git-path', 'hooks']);
    const dir = path.resolve(root, oldHooks);
    const active = fs.existsSync(dir) ? fs.readdirSync(dir).filter(name => !name.endsWith('.sample')) : [];
    if (active.length) throw new Error(`Existing hooks must be integrated manually: ${active.join(', ')}`);
  }
  git(['config', '--local', 'extensions.worktreeConfig', 'true']);
  git(['config', '--worktree', 'core.hooksPath', '.githooks']);
  console.log('Installed repository hooks: staged VERSION guard and default push denial.');
}

if (require.main === module) {
  try { installGitHooks(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { installGitHooks };
