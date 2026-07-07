const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { findAndroidReleaseApk } = require('./release-helpers');

function createProject(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vg-release-'));
  const releaseDir = path.join(root, 'app', 'build', 'outputs', 'apk', 'release');
  fs.mkdirSync(releaseDir, { recursive: true });
  for (const file of files) {
    fs.writeFileSync(path.join(releaseDir, file), 'apk');
  }
  return root;
}

test('findAndroidReleaseApk returns the signed release artifact', () => {
  const project = createProject(['app-release.apk', 'app-release-unsigned.apk']);

  assert.equal(
    findAndroidReleaseApk(project),
    path.join(project, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')
  );
});

test('findAndroidReleaseApk rejects unsigned release artifacts for publishing', () => {
  const project = createProject(['app-release-unsigned.apk']);

  assert.throws(
    () => findAndroidReleaseApk(project),
    /Android Release APK must be signed/
  );
});
