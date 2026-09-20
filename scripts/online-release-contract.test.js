const assert = require('node:assert/strict');
const test = require('node:test');

const { checkOnlineReleaseContract, normalizeBaseUrl } = require('./check-online-release-contract');

function response(status, body = null, headers = {}) {
  return new Response(body === null ? null : JSON.stringify(body), { status, headers });
}

test('线上发布契约同时校验健康、更新元数据、文件大小和 Range 下载', async () => {
  const releases = {
    wpf: { version: '4.5.1', url: '/releases/VisionGuard-WPF-v4.5.1.zip', size: 123 }
  };
  const calls = [];
  const fetchImpl = async (url, options = {}) => {
    const text = String(url);
    calls.push({ text, options });
    if (text.endsWith('/health')) return response(200, { ok: true });
    if (text.includes('/api/update?')) {
      return response(200, { latestVersion: '4.5.1', downloadUrl: releases.wpf.url, size: 123 });
    }
    if (options.method === 'HEAD') return response(200, null, { 'content-length': '123' });
    if (options.headers?.Range) return response(206);
    throw new Error(`unexpected URL: ${text}`);
  };

  await assert.doesNotReject(() => checkOnlineReleaseContract({ baseUrl: 'https://example.test/', releases, fetchImpl }));
  assert.equal(calls.length, 4);
});

test('线上更新元数据漂移会失败', async () => {
  const fetchImpl = async (url) => {
    if (String(url).endsWith('/health')) return response(200, { ok: true });
    return response(200, { latestVersion: '4.5.0', downloadUrl: '/wrong.apk', size: 1 });
  };

  await assert.rejects(
    () => checkOnlineReleaseContract({
      baseUrl: 'https://example.test',
      releases: { 'android-detector': { version: '4.4.4', url: '/releases/d.apk', size: 2 } },
      fetchImpl
    }),
    /android-detector update metadata drifted/
  );
});

test('服务地址必须是 HTTP 或 HTTPS', () => {
  assert.equal(normalizeBaseUrl('https://example.test/'), 'https://example.test');
  assert.throws(() => normalizeBaseUrl('file:///tmp/release'), /unsupported protocol/);
});
