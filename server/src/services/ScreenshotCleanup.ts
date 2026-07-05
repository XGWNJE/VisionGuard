import fs from 'fs';
import path from 'path';
import { config } from '../config';

let cleanupTimer: ReturnType<typeof setInterval> | null = null;

/**
 * 清理过期截图文件（基于截图 TTL 配置）
 */
export function cleanupScreenshots(): void {
  const dir = config.screenshotDir;
  if (!fs.existsSync(dir)) return;

  const ttlMs = config.screenshotTtlHours * 3600 * 1000;
  const now = Date.now();
  let removed = 0;

  try {
    const files = fs.readdirSync(dir);
    for (const file of files) {
      const ext = path.extname(file).toLowerCase();
      if (ext !== '.png' && ext !== '.jpg' && ext !== '.jpeg') continue;
      const filePath = path.join(dir, file);
      try {
        const stat = fs.statSync(filePath);
        if (now - stat.mtime.getTime() > ttlMs) {
          fs.unlinkSync(filePath);
          removed++;
        }
      } catch {
        // 单个文件删除失败不阻塞整体流程
      }
    }
    if (removed > 0) {
      console.log(`[cleanup] 已清理 ${removed} 个过期截图 (TTL=${config.screenshotTtlHours}h)`);
    }
  } catch (err) {
    console.error('[cleanup] 截图清理异常:', err);
  }
}

/**
 * 启动定期清理定时器
 */
export function startCleanupTimer(): void {
  if (cleanupTimer) return;
  cleanupTimer = setInterval(cleanupScreenshots, config.cleanupIntervalMs);
  console.log(`[cleanup] 截图清理定时器已启动: 每 ${config.cleanupIntervalMs / 1000}s, TTL=${config.screenshotTtlHours}h`);
}
