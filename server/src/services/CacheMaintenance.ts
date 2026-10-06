import fs from 'fs';
import path from 'path';
import { config } from '../config';
import { getAlerts } from './AlertStore';

export type CacheCategory = { id: string; label: string; files: number; bytes: number; cleanableFiles: number; cleanableBytes: number };
export type CacheReport = { categories: CacheCategory[]; removedFiles: number; removedBytes: number; releasedBytes: number | null; failedFiles: number };

/** Cleanup never accepts a client supplied path, and refuses linked ancestors. */
export function safeDirectory(directory: string): boolean {
  let current = path.resolve(directory);
  while (true) {
    try { const stat = fs.lstatSync(current); if (!stat.isDirectory() || stat.isSymbolicLink()) return false; }
    catch { return false; }
    const parent = path.dirname(current); if (parent === current) return true; current = parent;
  }
}
export function screenshotCache(directory: string, references: Set<string> | null, clean: boolean, now = Date.now()): CacheReport {
  const category: CacheCategory = { id: 'screenshots', label: '过期且无引用的截图', files: 0, bytes: 0, cleanableFiles: 0, cleanableBytes: 0 };
  const report: CacheReport = { categories: [category], removedFiles: 0, removedBytes: 0, releasedBytes: 0, failedFiles: 0 };
  if (!safeDirectory(directory)) { if (fs.existsSync(directory)) report.failedFiles++; return report; }
  for (const name of fs.readdirSync(directory)) {
    if (!/^[A-Za-z0-9_-]{8,128}\.(jpg|jpeg|png)$/.test(name)) continue;
    const file = path.join(directory, name);
    try {
      const stat = fs.lstatSync(file);
      if (!stat.isFile() || stat.isSymbolicLink()) continue;
      category.files++; category.bytes += stat.size;
      if (!references || references.has(path.resolve(file)) || now - stat.mtimeMs <= config.screenshotTtlHours * 3600000) continue;
      category.cleanableFiles++; category.cleanableBytes += stat.size;
      if (clean) {
        // Synchronous scan/unlink cannot interleave with uploads on the event loop.
        fs.unlinkSync(file); report.removedFiles++; report.removedBytes += stat.size;
        if (stat.blocks === undefined) report.releasedBytes = null;
        else if (report.releasedBytes !== null) report.releasedBytes += stat.nlink === 1 ? stat.blocks * 512 : 0;
      }
    } catch { report.failedFiles++; }
  }
  return report;
}
export function maintainAccountCache(accountId: string, clean = false): CacheReport {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(accountId)) throw new Error('Invalid account');
  const metadata = path.join(config.dataDir, 'accounts', accountId, 'alerts.json');
  if (fs.existsSync(metadata)) {
    try {
      if (!safeDirectory(path.dirname(metadata)) || fs.lstatSync(metadata).isSymbolicLink()) throw new Error();
      const rows = JSON.parse(fs.readFileSync(metadata, 'utf8'));
      if (!rows || typeof rows !== 'object' || Array.isArray(rows) || Object.values(rows).some(list => !Array.isArray(list) || list.some(row => !row || !Number.isFinite(row.createdAt)))) throw new Error();
    } catch { const report = screenshotCache(path.dirname(metadata) + '/screenshots', null, clean); report.failedFiles++; return report; }
  }
  const references = new Set(getAlerts(accountId, undefined, undefined, Number.MAX_SAFE_INTEGER)
    .flatMap(alert => alert.screenshotPath ? [path.resolve(alert.screenshotPath)] : []));
  return screenshotCache(path.join(config.dataDir, 'accounts', accountId, 'screenshots'), references, clean);
}
export function maintainLegacyCache(clean = false): CacheReport {
  let references: Set<string> | null = new Set();
  const metadata = path.join(config.dataDir, 'alerts.json');
  if (fs.existsSync(metadata)) {
    try {
      if (!safeDirectory(config.dataDir) || !fs.lstatSync(metadata).isFile() || fs.lstatSync(metadata).isSymbolicLink()) throw new Error();
      const rows = JSON.parse(fs.readFileSync(metadata, 'utf8'));
      if (!rows || typeof rows !== 'object' || Array.isArray(rows)) throw new Error();
      for (const list of Object.values(rows)) {
        if (!Array.isArray(list)) throw new Error();
        for (const row of list) {
          if (!row || !Number.isFinite(row.createdAt)) throw new Error();
          if (row.screenshotPath && row.createdAt >= Date.now() - config.alertTtlHours * 3600000) references.add(path.resolve(row.screenshotPath));
        }
      }
    } catch { references = null; }
  }
  const report = screenshotCache(path.join(config.dataDir, 'screenshots'), references, clean);
  if (!references) report.failedFiles++;
  return report;
}
export function parseCacheReport(value: unknown): CacheReport | undefined {
  if (!value || typeof value !== 'object') return;
  const report = value as CacheReport;
  const number = (n: unknown) => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0;
  if (!Array.isArray(report.categories) || report.categories.length > 16 ||
    (report.releasedBytes !== null && !number(report.releasedBytes)) ||
    ![report.removedFiles, report.removedBytes, report.failedFiles].every(number)) return;
  const ids = new Set<string>();
  for (const c of report.categories) {
    if (!c || !/^[a-z][a-z0-9-]{0,31}$/.test(c.id) || ids.has(c.id) || typeof c.label !== 'string' || !c.label.length || c.label.length > 64 || /[\x00-\x1f]/.test(c.label) ||
      ![c.files,c.bytes,c.cleanableFiles,c.cleanableBytes].every(number) || c.cleanableFiles > c.files || c.cleanableBytes > c.bytes) return;
    ids.add(c.id);
  }
  if (report.removedFiles > report.categories.reduce((n,c)=>n+c.cleanableFiles,0) || report.removedBytes > report.categories.reduce((n,c)=>n+c.cleanableBytes,0)) return;
  return {categories:report.categories.map(c=>({id:c.id,label:c.label,files:c.files,bytes:c.bytes,cleanableFiles:c.cleanableFiles,cleanableBytes:c.cleanableBytes})),
    removedFiles:report.removedFiles,removedBytes:report.removedBytes,releasedBytes:report.releasedBytes,failedFiles:report.failedFiles};
}
