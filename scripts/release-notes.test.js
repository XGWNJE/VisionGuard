const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { resolveReleaseNotes } = require('./release-notes');
test('numbered flat notes resolve by exact version and reject ambiguous or missing files',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'vg-release-notes-'));
 try {
  fs.mkdirSync(path.join(root,'docs'));fs.writeFileSync(path.join(root,'docs/123-发行说明v0.6.0.md'),'本版发行说明');
  assert.equal(resolveReleaseNotes('0.6.0',root),'docs/123-发行说明v0.6.0.md');
  assert.throws(()=>resolveReleaseNotes('0.6',root));assert.throws(()=>resolveReleaseNotes('0.6.1',root));
  fs.writeFileSync(path.join(root,'docs/124-发行说明v0.6.0.md'),'重复说明');assert.throws(()=>resolveReleaseNotes('0.6.0',root));
 } finally { fs.rmSync(root,{recursive:true,force:true}); }
});
