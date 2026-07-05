import path from 'path';
import type { AlertMeta, Detection } from '../models/types';

export type ImageContentType = 'image/jpeg' | 'image/png';

export interface ValidationResult<T> {
  ok: boolean;
  value?: T;
  error?: string;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isSafeAlertId(alertId: unknown): alertId is string {
  return typeof alertId === 'string' && UUID_RE.test(alertId);
}

export function validateImageMagic(buffer: Buffer): ImageContentType | null {
  if (buffer.length < 4) return null;
  if (buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
  if (buffer[0] === 0x89 && buffer[1] === 0x50 && buffer[2] === 0x4e && buffer[3] === 0x47) return 'image/png';
  return null;
}

export function extensionForContentType(contentType: ImageContentType): '.jpg' | '.png' {
  return contentType === 'image/jpeg' ? '.jpg' : '.png';
}

export function getSafeScreenshotPath(
  screenshotDir: string,
  alertId: unknown,
  contentType: ImageContentType,
): { filePath: string; filename: string } | null {
  if (!isSafeAlertId(alertId)) return null;

  const filename = `${alertId}${extensionForContentType(contentType)}`;
  const resolvedDir = path.resolve(screenshotDir);
  const resolvedPath = path.resolve(resolvedDir, filename);
  const relative = path.relative(resolvedDir, resolvedPath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) return null;

  return { filePath: resolvedPath, filename };
}

function validateDetection(d: unknown): d is Detection {
  if (!d || typeof d !== 'object') return false;
  const item = d as Partial<Detection>;
  if (typeof item.label !== 'string' || item.label.length === 0 || item.label.length > 64) return false;
  if (typeof item.confidence !== 'number' || !Number.isFinite(item.confidence) || item.confidence < 0 || item.confidence > 1) return false;
  const bbox = item.bbox;
  if (!bbox || typeof bbox !== 'object') return false;
  const b = bbox as Partial<Detection['bbox']>;
  return (
    typeof b.x === 'number' && Number.isFinite(b.x) &&
    typeof b.y === 'number' && Number.isFinite(b.y) &&
    typeof b.w === 'number' && Number.isFinite(b.w) && b.w >= 0 &&
    typeof b.h === 'number' && Number.isFinite(b.h) && b.h >= 0
  );
}

export function validateAlertMeta(input: unknown): ValidationResult<AlertMeta> {
  if (!input || typeof input !== 'object') return { ok: false, error: 'meta must be an object' };
  const meta = input as Partial<AlertMeta>;
  if (typeof meta.deviceId !== 'string' || meta.deviceId.length === 0 || meta.deviceId.length > 128) {
    return { ok: false, error: 'invalid deviceId' };
  }
  if (typeof meta.deviceName !== 'string' || meta.deviceName.length === 0 || meta.deviceName.length > 64) {
    return { ok: false, error: 'invalid deviceName' };
  }
  if (typeof meta.timestamp !== 'string' || meta.timestamp.length === 0 || meta.timestamp.length > 64) {
    return { ok: false, error: 'invalid timestamp' };
  }
  if (!Array.isArray(meta.detections) || meta.detections.length === 0 || meta.detections.length > 100) {
    return { ok: false, error: 'invalid detections' };
  }
  if (!meta.detections.every(validateDetection)) {
    return { ok: false, error: 'invalid detection item' };
  }

  return {
    ok: true,
    value: {
      deviceId: meta.deviceId,
      deviceName: meta.deviceName,
      timestamp: meta.timestamp,
      detections: meta.detections,
    },
  };
}

export function parsePositiveIntEnv(name: string, fallback: number, min: number, max: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}
