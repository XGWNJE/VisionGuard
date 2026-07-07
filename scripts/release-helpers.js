const fs = require('fs');
const path = require('path');

function findAndroidReleaseApk(projectDir) {
  const releaseDir = path.join(projectDir, 'app', 'build', 'outputs', 'apk', 'release');
  const signedApk = path.join(releaseDir, 'app-release.apk');
  if (fs.existsSync(signedApk)) {
    return signedApk;
  }

  const unsignedApk = path.join(releaseDir, 'app-release-unsigned.apk');
  if (fs.existsSync(unsignedApk)) {
    throw new Error(
      `Android Release APK must be signed for publishing. Found unsigned artifact only: ${unsignedApk}`
    );
  }

  throw new Error(`Android Release APK not found: ${releaseDir}`);
}

module.exports = {
  findAndroidReleaseApk,
};
