const VALID_SET_CONFIG_KEYS = new Set(['cooldown', 'confidence', 'targets', 'targetSamplingRate', 'modelKey']);
const MAX_TARGETS_LENGTH = 500;

export type SetConfigValidationResult =
  | { ok: true; value: string }
  | { ok: false; reason: string };

export function validateSetConfigValue(
  key: string,
  rawValue: string,
  modelOptions: readonly string[] = [],
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
    if (!isFinite(v) || v < 0.01 || v > 1.0) {
      return { ok: false, reason: 'confidence 必须是 0.01-1.0 的数字' };
    }
    return { ok: true, value: String(v) };
  }

  if (key === 'targets') {
    const s = String(rawValue ?? '');
    return { ok: true, value: s.length > MAX_TARGETS_LENGTH ? s.slice(0, MAX_TARGETS_LENGTH) : s };
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
