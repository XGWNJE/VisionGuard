import { Router } from 'express';
import path from 'path';
import fs from 'fs';

const router = Router();

// 平台类型映射
const PLATFORM_MAP: Record<string, string> = {
  'winforms': 'winforms',
  'wpf': 'wpf',
  'windows': 'winforms',
  'android-detector': 'android-detector',
  'android': 'android-receiver',
};

/**
 * GET /api/update?platform=winforms&version=4.0.0
 * 查询指定平台的最新版本信息
 */
router.get('/api/update', (req, res) => {
  const platform = String(req.query.platform || '').toLowerCase();
  const currentVersion = String(req.query.version || '');

  // 映射平台名称
  const mappedPlatform = PLATFORM_MAP[platform] || platform;

  // 读取 releases.json
  const releasesPath = path.resolve(__dirname, '..', '..', 'data', 'releases.json');
  let releases: Record<string, { version: string; url: string; size: number }> = {};
  try {
    releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
  } catch {
    return res.status(500).json({ ok: false, error: 'releases config not found' });
  }

  const info = releases[mappedPlatform];
  if (!info) {
    return res.status(404).json({ ok: false, error: `platform not found: ${platform}` });
  }

  const hasUpdate = compareVersion(currentVersion, info.version) < 0;

  res.json({
    ok: true,
    hasUpdate,
    latestVersion: info.version,
    downloadUrl: info.url,
    size: info.size,
    forceUpdate: false,
  });
});

/**
 * 语义化版本比较
 * @returns -1: a < b, 0: a === b, 1: a > b
 */
function compareVersion(a: string, b: string): number {
  const parse = (v: string) => v.split('.').map(Number);
  const av = parse(a);
  const bv = parse(b);
  const len = Math.max(av.length, bv.length);
  for (let i = 0; i < len; i++) {
    const an = av[i] ?? 0;
    const bn = bv[i] ?? 0;
    if (an < bn) return -1;
    if (an > bn) return 1;
  }
  return 0;
}

export default router;
