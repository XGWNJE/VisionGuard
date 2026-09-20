#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const DEFAULT_BASE_URL = 'https://visionguard.xgwnje.cn';

function parseBaseUrl(argv) {
  const index = argv.indexOf('--base-url');
  if (index === -1) return process.env.VISIONGUARD_BASE_URL || DEFAULT_BASE_URL;
  if (!argv[index + 1]) throw new Error('--base-url requires a value');
  return argv[index + 1];
}

function normalizeBaseUrl(baseUrl) {
  const parsed = new URL(baseUrl);
  if (!/^https?:$/.test(parsed.protocol)) throw new Error(`unsupported protocol: ${parsed.protocol}`);
  return parsed.toString().replace(/\/$/, '');
}

async function expectResponse(fetchImpl, url, options, expectedStatus) {
  const response = await fetchImpl(url, options);
  if (response.status !== expectedStatus) {
    throw new Error(`${options?.method || 'GET'} ${url} returned ${response.status}; expected ${expectedStatus}`);
  }
  return response;
}

async function checkOnlineReleaseContract({ baseUrl, releases, fetchImpl = fetch }) {
  const base = normalizeBaseUrl(baseUrl);
  const health = await expectResponse(fetchImpl, `${base}/health`, undefined, 200);
  const healthBody = await health.json();
  if (healthBody.ok !== true) throw new Error(`/health returned unexpected body: ${JSON.stringify(healthBody)}`);

  const results = [];
  for (const [platform, release] of Object.entries(releases)) {
    const updateUrl = new URL(`${base}/api/update`);
    updateUrl.searchParams.set('platform', platform);
    updateUrl.searchParams.set('version', '0.0.0');
    const update = await expectResponse(fetchImpl, updateUrl, undefined, 200);
    const payload = await update.json();

    if (payload.latestVersion !== release.version || payload.downloadUrl !== release.url || Number(payload.size) !== Number(release.size)) {
      throw new Error(`${platform} update metadata drifted: ${JSON.stringify(payload)}`);
    }

    const assetUrl = `${base}${release.url}`;
    const head = await expectResponse(fetchImpl, assetUrl, { method: 'HEAD' }, 200);
    if (Number(head.headers.get('content-length')) !== Number(release.size)) {
      throw new Error(`${platform} asset size drifted: ${head.headers.get('content-length')} != ${release.size}`);
    }

    await expectResponse(fetchImpl, assetUrl, { headers: { Range: 'bytes=0-0' } }, 206);
    results.push(`${platform}=v${release.version}`);
  }
  return results;
}

async function main() {
  const releases = JSON.parse(fs.readFileSync(path.join(ROOT, 'server', 'data', 'releases.json'), 'utf8'));
  const results = await checkOnlineReleaseContract({ baseUrl: parseBaseUrl(process.argv.slice(2)), releases });
  console.log(`Online release contract passed: ${results.join(', ')}`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`Online release contract failed: ${error.message}`);
    process.exitCode = 1;
  });
}

module.exports = { checkOnlineReleaseContract, normalizeBaseUrl, parseBaseUrl };
