// ┌─────────────────────────────────────────────────────────┐
// │ localReleases.ts                                        │
// │ 角色：本地服务实例的发行源覆盖层                          │
// │ 职责：把本地构建的夹具组件包按线上同样的形状暴露给客户端  │
// │       在线实例只读跟踪的 releases.json；本文件只在设了    │
// │       `VISIONGUARD_LOCAL_RELEASES_FILE` 的本地实例生效    │
// └─────────────────────────────────────────────────────────┘

import { Router } from 'express';
import fs from 'fs';
import path from 'path';

/** 覆盖层条目：字段与 `releases.json` 对齐，另可给 `fileName` / `publishedAt`。 */
export interface LocalReleaseEntry {
  version: string;
  fileName: string;
  size: number;
  sha256: string;
  publishedAt?: string;
}

export interface LocalReleaseInput {
  version?: unknown;
  fileName?: unknown;
  url?: unknown;
  size?: unknown;
  sha256?: unknown;
  publishedAt?: unknown;
}

/** 覆盖层文件路径每次现读环境变量：测试与本地实例都不需要重建模块。 */
export function localReleasesPath(): string {
  return (process.env.VISIONGUARD_LOCAL_RELEASES_FILE || '').trim();
}

/**
 * 解析覆盖层；任何字段不合规的条目直接丢弃（宁可少发一个包，也不发一个地址不可信或校验不了的包）。
 * 文件不存在或整体不是对象时返回 null，由调用方按「没有本地发行源」处理。
 */
export function parseLocalReleases(text: string): Record<string, LocalReleaseEntry> | null {
  let raw: unknown;
  try { raw = JSON.parse(text); } catch { return null; }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const entries: Record<string, LocalReleaseEntry> = {};
  for (const [platform, value] of Object.entries(raw as Record<string, LocalReleaseInput>)) {
    if (!value || typeof value !== 'object') continue;
    const version = typeof value.version === 'string' ? value.version.trim() : '';
    const size = typeof value.size === 'number' ? value.size : Number.NaN;
    const sha256 = typeof value.sha256 === 'string' ? value.sha256.trim().toLowerCase() : '';
    const named = typeof value.fileName === 'string' && value.fileName.trim()
      ? value.fileName.trim()
      : (typeof value.url === 'string' ? path.basename(value.url.trim()) : '');
    if (!version || !named || !Number.isInteger(size) || size <= 0 || !/^[0-9a-f]{64}$/.test(sha256)) continue;
    entries[platform] = {
      version,
      fileName: named,
      size,
      sha256,
      publishedAt: typeof value.publishedAt === 'string' ? value.publishedAt : undefined,
    };
  }
  return entries;
}

/** 读本地覆盖层；路径为空或文件不可用时返回空对象。 */
export function readLocalReleases(): Record<string, LocalReleaseEntry> {
  const file = localReleasesPath();
  if (!file) return {};
  try { return parseLocalReleases(fs.readFileSync(file, 'utf-8')) ?? {}; } catch { return {}; }
}

/** 覆盖层条目转成 `/api/update` 用的相对地址形状。 */
export function localUpdateEntries(entries: Record<string, LocalReleaseEntry>): Record<string, { version: string; url: string; size: number; sha256: string }> {
  const merged: Record<string, { version: string; url: string; size: number; sha256: string }> = {};
  for (const [platform, entry] of Object.entries(entries)) {
    merged[platform] = { version: entry.version, url: `/releases/${entry.fileName}`, size: entry.size, sha256: entry.sha256 };
  }
  return merged;
}

/** 覆盖层覆盖同平台条目，其余原样保留。 */
export function mergeLocalReleases<T>(base: Record<string, T>, overlay: Record<string, T>): Record<string, T> {
  return { ...base, ...overlay };
}

/**
 * 组装 GitHub 发行形状的响应：Android 的 ClientUpdater 直接把它当 `api.github.com/.../releases` 用，
 * 因此 `browser_download_url` 必须是绝对地址（指向本实例），`digest` 必须是 `sha256:<hex>` 形状。
 */
export function buildLocalReleases(entries: Record<string, LocalReleaseEntry>, origin: string, now: string): unknown[] {
  return Object.values(entries).map(entry => ({
    tag_name: `v${entry.version}`,
    published_at: entry.publishedAt || now,
    draft: false,
    prerelease: false,
    assets: [{
      name: entry.fileName,
      browser_download_url: `${origin}/releases/${encodeURIComponent(entry.fileName)}`,
      size: entry.size,
      digest: `sha256:${entry.sha256}`,
      state: 'uploaded',
    }],
  }));
}

const router = Router();

router.get('/api/local/releases', (req, res) => {
  const file = localReleasesPath();
  if (!file) return res.status(404).json({ ok: false, error: 'local releases not configured' });
  let text: string;
  try { text = fs.readFileSync(file, 'utf-8'); }
  catch { return res.status(404).json({ ok: false, error: 'local releases file missing' }); }
  const entries = parseLocalReleases(text);
  if (!entries) return res.status(404).json({ ok: false, error: 'local releases file missing' });
  const origin = `${req.protocol}://${req.get('host')}`;
  res.json(buildLocalReleases(entries, origin, new Date().toISOString()));
});

export default router;
