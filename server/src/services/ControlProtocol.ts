const VALID_SET_CONFIG_KEYS = new Set(['cooldown', 'confidence', 'targets', 'targetSamplingRate', 'modelKey']);
export const MAX_TARGETS_LENGTH = 4096;

export type SetConfigValidationResult =
  | { ok: true; value: string }
  | { ok: false; reason: string };

export function validateSetConfigValue(
  key: string,
  rawValue: string,
  modelOptions: readonly string[] = [],
  windows = false,
  labels?: readonly string[],
): SetConfigValidationResult {
  if (!VALID_SET_CONFIG_KEYS.has(key)) {
    return { ok: false, reason: `无效的配置项: ${key}` };
  }

  if (key === 'cooldown') {
    const v = parseInteger(rawValue);
    if (v === undefined || v < 1 || v > 300) {
      return { ok: false, reason: 'cooldown 必须是 1-300 的整数' };
    }
    return { ok: true, value: String(v) };
  }

  if (key === 'confidence') {
    const v = Number(rawValue);
    if (!isFinite(v) || v < (windows ? 0.1 : 0.01) || v > (windows ? 0.95 : 1.0) || windows && Math.abs(v * 100 - Math.round(v * 100)) > 0.000001) {
      return { ok: false, reason: windows ? 'confidence 必须为 0.10-0.95，步长 0.01' : 'confidence 必须是 0.01-1.0 的数字' };
    }
    return { ok: true, value: String(v) };
  }

  if (key === 'targets') {
    const s = String(rawValue ?? '');
    const targets = [...new Set(s.split(',').map(item => item.trim()).filter(Boolean))];
    if (s.length > MAX_TARGETS_LENGTH || targets.length < 1 || targets.length > 256 || targets.some(item => item.length > 64 || /[\r\n\x00]/.test(item))) return { ok: false, reason: '至少选择一个有效目标，完整列表最多 4096 字符' };
    if (labels && targets.some(item => !labels.includes(item))) return { ok: false, reason: '目标不在当前模型标签中' };
    return { ok: true, value: targets.join(',') };
  }

  if (key === 'targetSamplingRate') {
    const v = parseInteger(rawValue);
    if (v === undefined || v < 1 || v > 5) {
      return { ok: false, reason: 'targetSamplingRate 必须是 1-5 的整数' };
    }
    return { ok: true, value: String(v) };
  }

  const value = String(rawValue ?? '').trim();
  if (!value || !modelOptions.includes(value)) {
    return { ok: false, reason: 'modelKey 不在设备支持列表中' };
  }
  return { ok: true, value };
}

export function isValidSetConfigKey(key: string): boolean {
  return VALID_SET_CONFIG_KEYS.has(key);
}

function parseInteger(rawValue: string): number | undefined {
  if (!/^-?\d+$/.test(String(rawValue ?? '').trim())) return undefined;
  const n = Number(rawValue);
  if (!Number.isSafeInteger(n)) return undefined;
  return n;
}
