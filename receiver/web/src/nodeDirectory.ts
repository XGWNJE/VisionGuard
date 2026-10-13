// ┌─────────────────────────────────────────────────────────┐
// │ nodeDirectory.ts                                        │
// │ 角色：节点目录宽度的边界、持久化与键盘步进（纯函数）      │
// └─────────────────────────────────────────────────────────┘

export const NODE_DIRECTORY_MIN_WIDTH = 208;
export const NODE_DIRECTORY_MAX_WIDTH = 480;
/** 未拖拽过时的设计默认宽度，也是双击复位的目标；各断点可用 CSS 变量覆盖显示值。 */
export const NODE_DIRECTORY_DEFAULT_WIDTH = 240;
/** 拖到再宽也要给详情列留出的最小可用宽度。 */
const NODE_DIRECTORY_RESERVED_WIDTH = 360;
const NODE_DIRECTORY_STORAGE_KEY = 'visionguard.node-directory-width';

/** 当前可用宽度下的上限：不超过设计上限，也不挤掉详情列。 */
export function nodeDirectoryWidthLimit(containerWidth = 0): number {
  if (!Number.isFinite(containerWidth) || containerWidth <= 0) return NODE_DIRECTORY_MAX_WIDTH;
  return Math.max(NODE_DIRECTORY_MIN_WIDTH, Math.min(NODE_DIRECTORY_MAX_WIDTH, Math.round(containerWidth - NODE_DIRECTORY_RESERVED_WIDTH)));
}

export function clampNodeDirectoryWidth(width: number, containerWidth = 0): number {
  if (!Number.isFinite(width)) return NODE_DIRECTORY_DEFAULT_WIDTH;
  return Math.min(Math.max(Math.round(width), NODE_DIRECTORY_MIN_WIDTH), nodeDirectoryWidthLimit(containerWidth));
}

/** 读取本机保存的宽度；未保存、存储禁用或值非法时返回 null，交给样式默认值。 */
export function readNodeDirectoryWidth(storage?: Pick<Storage, 'getItem'> | null): number | null {
  try {
    const raw = storage?.getItem(NODE_DIRECTORY_STORAGE_KEY);
    if (!raw) return null;
    const value = Number(raw);
    return Number.isFinite(value) ? clampNodeDirectoryWidth(value) : null;
  } catch { return null; }
}

export function saveNodeDirectoryWidth(storage: Pick<Storage, 'setItem'> | null | undefined, width: number): void {
  try { storage?.setItem(NODE_DIRECTORY_STORAGE_KEY, String(clampNodeDirectoryWidth(width))); } catch { /* 存储禁用只影响下次打开的宽度 */ }
}

/** 方向键步进；其它按键返回 null，交回默认行为。Shift 加大步长。 */
export function nodeDirectoryKeyStep(key: string, shiftKey = false): number | null {
  const step = shiftKey ? 64 : 16;
  if (key === 'ArrowLeft') return -step;
  if (key === 'ArrowRight') return step;
  return null;
}
