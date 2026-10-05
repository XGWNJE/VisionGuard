#!/usr/bin/env node
const fs = require('node:fs');
const path = require('node:path');
function resolveReleaseNotes(version, root = path.resolve(__dirname, '..')) {
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Invalid release version');
  const directory = path.join(root, 'docs');
  const matches = fs.readdirSync(directory).filter(name => /^\d+-发行说明v\d+\.\d+\.\d+\.md$/.test(name) && name.endsWith(`发行说明v${version}.md`));
  if (matches.length !== 1) throw new Error(`Expected exactly one release note for ${version}; found ${matches.length}`);
  const relative = `docs/${matches[0]}`;
  if (!fs.statSync(path.join(root, relative)).isFile() || !/[\u4e00-\u9fff]/.test(fs.readFileSync(path.join(root, relative), 'utf8'))) throw new Error('Release notes must be a nonempty Chinese document');
  return relative;
}
if (require.main === module) { try { console.log(resolveReleaseNotes(process.argv[2])); } catch (error) { console.error(error.message); process.exitCode = 1; } }
module.exports = { resolveReleaseNotes };
