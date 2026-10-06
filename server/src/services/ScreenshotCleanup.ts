import fs from 'fs';
import path from 'path';
import { config } from '../config';
import { maintainAccountCache, maintainLegacyCache, safeDirectory } from './CacheMaintenance';

let cleanupTimer: ReturnType<typeof setInterval> | null = null;
export function cleanupScreenshots(): void {
  let removed = 0;
  try {
    const root = path.join(config.dataDir, 'accounts');
    if (safeDirectory(root)) for (const account of fs.readdirSync(root, {withFileTypes:true})) {
      if (account.isDirectory() && /^[A-Za-z0-9_-]{1,128}$/.test(account.name)) removed += maintainAccountCache(account.name, true).removedFiles;
    }
    removed += maintainLegacyCache(true).removedFiles;
    if (removed) console.log(`[cleanup] removed ${removed} expired unreferenced screenshots`);
  } catch { console.error('[cleanup] Screenshot maintenance failed'); }
}
export function startCleanupTimer(): void {
  if (cleanupTimer) return;
  cleanupTimer = setInterval(cleanupScreenshots, config.cleanupIntervalMs);
}
