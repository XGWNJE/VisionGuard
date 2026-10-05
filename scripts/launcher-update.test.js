const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const launcher = path.join(root, 'detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe');

test('background launcher checks reject incomplete metadata without blocking startup', {
  skip: process.platform !== 'win32', timeout: 40000,
}, async t => {
  assert.ok(fs.existsSync(launcher), 'Build the Windows Release targets before this test');
  const parent = path.join(root, '.local/launcher-update-tests');
  fs.mkdirSync(parent, { recursive: true });
  const directory = fs.mkdtempSync(path.join(parent, 'run-'));
  let reply;
  const requests = [];
  const server = http.createServer((request, response) => {
    requests.push(request.url);
    response.writeHead(reply.status || 200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(reply.body));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const asset={name:'VisionGuard-WPF-v0.7.0.zip',browser_download_url:'https://github.com/XGWNJE/VisionGuard/releases/download/v0.7.0/VisionGuard-WPF-v0.7.0.zip',size:123,digest:'sha256:'+'a'.repeat(64),state:'uploaded'};
  const release=assets=>[{tag_name:'v0.7.0',published_at:'2026-10-05T00:00:00Z',draft:false,prerelease:false,assets}];
  const cases = [
    { name: 'no newer version', body: [], code: 0 },
    { name: 'valid newer version stays noninteractive', body: release([asset]), code: 0 },
    { name: 'missing SHA256', body: release([{...asset,digest:''}]), code: 1, reason: 'InvalidDataException' },
    { name: 'missing download URL', body: release([{...asset,browser_download_url:''}]), code: 1, reason: 'InvalidDataException' },
    { name: 'server failure', status: 500, body: { error: 'fixture failure' }, code: 1, reason: '500' },
  ];
  for (const entry of cases) {
    await t.test(entry.name, async () => {
      reply = entry;
      const caseDirectory = path.join(directory, entry.name.replaceAll(' ', '-'));
      fs.mkdirSync(caseDirectory);
      const configuration = path.join(caseDirectory, 'environment.json');
      const logDirectory = path.join(caseDirectory, 'logs');
      fs.writeFileSync(configuration, JSON.stringify({
        serviceUrl: `http://127.0.0.1:${server.address().port}`,
        accountDir: path.join(caseDirectory, 'accounts'),
        settingsPath: path.join(caseDirectory, 'settings.ini'),
        modelsDirectory: path.join(caseDirectory, 'models'),
        logDirectory,
      }));
      const child = spawn(launcher, ['--isolated-environment', configuration, '--check-update'], {
        windowsHide: true, stdio: 'ignore', env:{...process.env,VISIONGUARD_TEST_RELEASES_URL:`http://127.0.0.1:${server.address().port}/releases`},
      });
      const code = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          child.kill();
          reject(new Error('Background update check failed to exit; a modal dialog may be blocking it'));
        }, 7000);
        child.once('error', error => { clearTimeout(timer); reject(error); });
        child.once('exit', code => { clearTimeout(timer); resolve(code); });
      });
      assert.equal(code, entry.code);
      assert.ok(requests.at(-1).startsWith('/releases?per_page=100&page='));
      assert.ok(requests.every(url => url.startsWith('/releases?')));
      if (entry.reason) {
        const log = fs.readFileSync(path.join(logDirectory, 'launcher.log'), 'utf8');
        assert.match(log, /update-check-failed/);
        assert.ok(log.includes(entry.reason));
        assert.doesNotMatch(log, /\bfatal\b/);
      }
    });
  }
});
