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

/** Windows 检测端的两个推理档位；档位决定更新包，不能混用。 */
const WINDOWS_PROFILES = new Set(['modern', 'legacy']);

/**
 * 解析请求要用的发布条目键。
 *
 * Windows 检测端是单一源码的两个推理档位（modern = Win10+，legacy = Win7 SP1），
 * 两档的原生 ONNX Runtime 不兼容，更新包也必须分开：
 * 档位键为 `wpf-<profile>`。该键尚未随某次发行登记时回落到 `wpf`，
 * 以免给 legacy 端返回 404；回落与否由 releases.json 的实际内容决定，不猜测。
 */
export function resolveReleaseKey(
  releases: Record<string, unknown>,
  platform: string,
  profile: string
): string | null {
  const mapped = PLATFORM_MAP[platform] || platform;
  if (mapped !== 'wpf' || !WINDOWS_PROFILES.has(profile)) {
    return mapped;
  }

  const profileKey = `wpf-${profile}`;
  return profileKey in releases ? profileKey : mapped;
}

/**
 * GET /api/update?platform=wpf&version=<current-version>&profile=<modern|legacy>
 * 查询指定平台（Windows 检测端可再指定推理档位）的最新版本信息
 */
router.get('/api/update', (req, res) => {
  const platform = String(req.query.platform || '').toLowerCase();
  const profile = String(req.query.profile || '').toLowerCase();
  const currentVersion = String(req.query.version || '');

  // 读取 releases.json
  const releasesPath = path.resolve(__dirname, '..', '..', 'data', 'releases.json');
  let releases: Record<string, { version: string; url: string; size: number }> = {};
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
