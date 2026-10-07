#!/usr/bin/env node
const { execFileSync } = require('node:child_process');

function requireVersionAuthorization(version, env = process.env) {
  if (env.VISIONGUARD_OWNER_VERSION !== version && env.VISIONGUARD_OWNER_RELEASE !== version) {
    throw new Error(`Version ${version} requires owner authorization and VISIONGUARD_OWNER_VERSION=${version}`);
  }
}

function checkGitOperation(operation, cwd = process.cwd(), env = process.env) {
  if (operation === 'pre-push') {
    if (env.VISIONGUARD_OWNER_PUSH !== '1') throw new Error('Push requires owner authorization and VISIONGUARD_OWNER_PUSH=1');
    return;
  }
  if (operation !== 'pre-commit') throw new Error(`Unknown hook: ${operation}`);
  const git = args => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
  // Inspect the index, not the working tree; a deleted VERSION also fails closed.
  if (!git(['diff', '--cached', '--name-only', '--', 'VERSION'])) return;
  const version = git(['show', ':VERSION']);
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Staged VERSION must be x.y.z');
  requireVersionAuthorization(version, env);
}

if (require.main === module) {
  try { checkGitOperation(process.argv[2]); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
module.exports = { checkGitOperation, requireVersionAuthorization };
