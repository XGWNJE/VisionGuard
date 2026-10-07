#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
function resolveReleaseNotes(version, root = path.resolve(__dirname, '..')) {
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Invalid release version');
  const relative = `docs/releases/v${version}.md`;
  const absolute = path.join(root, relative);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) throw new Error(`Missing release notes: ${relative}`);
  if (!/[\u4e00-\u9fff]/.test(fs.readFileSync(absolute, 'utf8'))) throw new Error('Release notes must be a nonempty Chinese document');
  return relative;
}
if (require.main === module) { try { console.log(resolveReleaseNotes(process.argv[2])); } catch (error) { console.error(error.message); process.exitCode = 1; } }
module.exports = { resolveReleaseNotes };
