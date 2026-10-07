const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { resolveReleaseNotes } = require('./release-notes');

test('release notes resolve the exact version from the dedicated directory', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-release-notes-'));
  try {
    fs.mkdirSync(path.join(root, 'docs/releases'), { recursive: true });
    fs.writeFileSync(path.join(root, 'docs/releases/v0.6.0.md'), '本版发行说明');
    assert.equal(resolveReleaseNotes('0.6.0', root), 'docs/releases/v0.6.0.md');
    for (const version of ['0.6', '../0.6.0', '0.6.1']) assert.throws(() => resolveReleaseNotes(version, root));
    fs.writeFileSync(path.join(root, 'docs/123-发行说明v0.6.1.md'), '旧入口');
    assert.throws(() => resolveReleaseNotes('0.6.1', root), /Missing release notes/);
    fs.writeFileSync(path.join(root, 'docs/releases/v0.6.1.md'), '');
    assert.throws(() => resolveReleaseNotes('0.6.1', root), /nonempty Chinese/);
    fs.writeFileSync(path.join(root, 'docs/releases/v0.6.1.md'), 'English only');
    assert.throws(() => resolveReleaseNotes('0.6.1', root), /nonempty Chinese/);
    fs.mkdirSync(path.join(root, 'docs/releases/v0.6.2.md'));
    assert.throws(() => resolveReleaseNotes('0.6.2', root), /Missing release notes/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
