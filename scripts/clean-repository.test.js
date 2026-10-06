const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const test = require('node:test');
const repository = path.resolve(__dirname, '..');
const script = path.join(__dirname, 'clean-repository.ps1');

// Tiny isolated files; never run Apply against the developer's actual checkout.
function fixture() {
  const directory = path.join(repository, '.local', 'cleanup-script-tests');
  fs.mkdirSync(directory, { recursive: true });
  const root = fs.mkdtempSync(path.join(directory, 'case-'));
  const write = (relative, content = 'fixture') => {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, content); return file;
  };
  write('VERSION', '0.6.1'); write('server/package.json', '{"private":true}');
  write('.gitignore', '.local/\n**/obj/\ndetector/android/app/build/\n');
  write('tests/Sample/Sample.csproj', '<Project Sdk="Microsoft.NET.Sdk"/>');
  execFileSync('git', ['init', '-q', root]);
  execFileSync('git', ['-C', root, 'add', 'VERSION', 'server/package.json', '.gitignore', 'tests/Sample/Sample.csproj']);
  const helper = '.local/direct-input/';
  write(helper + 'helper-unsigned.apk'); write(helper + 'helper-keystore.p12');
  write(helper + 'instrumentation-classes/A.class'); write(helper + 'instrumentation-dex/classes.dex');
  const protectedFiles = ['DirectInstrumentation.java','AndroidManifest.xml','build-input-helper.ps1'].map(n => helper + n)
    .concat(['.local/visionguard-release.env','.local/test-accounts.json','.local/perf-camera.log',
      '.local/acceptance-gradle/keep.bin','.local/camera-perf-final-test.apk','models/keep.onnx',
      'detector/android/app/build/outputs/apk/release/app-release.apk','detector/android/app/build/test-results/result.xml']);
  protectedFiles.forEach(p => write(p));
  write('tests/Sample/obj/intermediate.tmp'); write('detector/android/app/build/intermediates/temp.bin');
  const run = (...args) => execFileSync('powershell.exe', ['-NoProfile','-ExecutionPolicy','Bypass','-File',script,'-RepositoryRoot',root,...args], { encoding:'utf8' });
  const report = () => JSON.parse(fs.readFileSync(path.join(root, '.local/repository-cleanup-plan.json'), 'utf8'));
  return { root, write, run, report, protectedFiles, helper };
}

test('preview/WhatIf protect sources, signing, packages, evidence, models and shared cache', () => {
  const f = fixture();
  const before = f.protectedFiles.map(p => fs.readFileSync(path.join(f.root,p)));
  f.run();
  assert.ok(f.report().Candidates.every(c => !c.RelativePath.includes('/build/') && !c.RelativePath.endsWith('/obj')));
  f.run('-Apply','-IncludeBuildIntermediates','-WhatIf');
  assert.ok(fs.existsSync(path.join(f.root, f.helper + 'helper-unsigned.apk')));
  assert.ok(fs.existsSync(path.join(f.root,'tests/Sample/obj/intermediate.tmp')));
  f.protectedFiles.forEach((p,i) => assert.deepEqual(fs.readFileSync(path.join(f.root,p)), before[i]));
  assert.ok(f.report().Candidates.filter(c=>c.Eligible).every(c=>c.Status==='WhatIfOrDeclined'));
});

test('Apply removes only disposable helper outputs and retains reusable source', () => {
  const f = fixture();
  f.run('-Apply');
  for (const p of ['helper-unsigned.apk','helper-keystore.p12','instrumentation-classes','instrumentation-dex'])
    assert.equal(fs.existsSync(path.join(f.root,f.helper,p)), false);
  f.protectedFiles.forEach(p => assert.ok(fs.existsSync(path.join(f.root,p))));
  assert.ok(fs.existsSync(path.join(f.root,'tests/Sample/obj/intermediate.tmp')));
});

test('tracked helper output and unexpected class-folder source are preserved', () => {
  const f = fixture();
  execFileSync('git', ['-C',f.root,'add','-f',f.helper+'helper-unsigned.apk']);
  f.write(f.helper+'instrumentation-classes/keep.java');
  f.run('-Apply');
  assert.ok(fs.existsSync(path.join(f.root,f.helper+'helper-unsigned.apk')));
  assert.ok(fs.existsSync(path.join(f.root,f.helper+'instrumentation-classes/keep.java')));
  assert.equal(f.report().Candidates.find(c=>c.RelativePath.endsWith('helper-unsigned.apk')).Eligible, false);
});

test('linked helper directory and protected package inside an intermediate are refused', () => {
  const f = fixture();
  const outside = path.join(path.dirname(f.root), path.basename(f.root)+'-outside');
  fs.mkdirSync(outside); fs.writeFileSync(path.join(outside,'helper-unsigned.apk'),'must remain');
  fs.symlinkSync(outside, path.join(f.root,'.local/acceptance-direct-input'),'junction');
  f.write('detector/android/app/build/intermediates/signed.apk');
  f.run('-IncludeBuildIntermediates');
  const plan=f.report();
  assert.ok(plan.Candidates.filter(c=>c.RelativePath.startsWith('.local/acceptance-direct-input/')).every(c=>!c.Eligible));
  assert.equal(plan.Candidates.find(c=>c.RelativePath.endsWith('/intermediates')).Eligible,false);
  assert.equal(fs.readFileSync(path.join(outside,'helper-unsigned.apk'),'utf8'),'must remain');
});
