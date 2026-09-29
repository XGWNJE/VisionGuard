import { Router } from 'express';
import path from 'path';
import fs from 'fs';

const router = Router();

/**
 * 平台类型映射。
 * 只保留迁移后的客户端标识：Windows 检测端统一为单一 WPF 构建（V10 起 WinForms 已退役），
 * 旧标识 open/close-wpf|winforms 一并不再受理，客户端一律查询 `wpf`。
 */
const PLATFORM_MAP: Record<string, string> = {
  'wpf': 'wpf',
  'windows': 'wpf',
  'android-detector': 'android-detector',
  'android': 'android-receiver',
};

/**
 * 解析请求要用的发布条目键。
 *
 * Windows 检测端由统一包的启动器按操作系统选择内部运行时，服务端只发布 `wpf`。
 * profile 参数保留在函数签名中只为旧调用方平滑升级，不再影响发布条目。
 */
export function resolveReleaseKey(
  _releases: Record<string, unknown>,
  platform: string,
  _profile: string
): string | null {
  return PLATFORM_MAP[platform] || platform;
}

/**
 * GET /api/update?platform=wpf&version=<current-version>
 * 查询指定平台的最新版本信息
 */
router.get('/api/update', (req, res) => {
  const platform = String(req.query.platform || '').toLowerCase();
  const profile = String(req.query.profile || '').toLowerCase();
  const currentVersion = String(req.query.version || '');

  // 读取 releases.json
  const releasesPath = path.resolve(__dirname, '..', '..', 'data', 'releases.json');
  let releases: Record<string, { version: string; url: string; size: number; sha256?: string }> = {};
  try {
    releases = JSON.parse(fs.readFileSync(releasesPath, 'utf-8'));
  } catch {
    return res.status(500).json({ ok: false, error: 'releases config not found' });
  }

  const mappedPlatform = resolveReleaseKey(releases, platform, profile);
  const info = mappedPlatform ? releases[mappedPlatform] : undefined;
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
    sha256: info.sha256 || '',
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
