import type React from "react";
import { normalizeDisplayName } from "./account";

export function checkNamePaste(
  e: React.ClipboardEvent<HTMLInputElement>,
  onError: (message: string) => void,
) {
  const input = e.currentTarget;
  const next =
    input.value.slice(0, input.selectionStart ?? 0) +
    e.clipboardData.getData("text") +
    input.value.slice(input.selectionEnd ?? input.value.length);
  try {
    normalizeDisplayName(next);
    if (next.length > 64) throw new Error("名称最多 64 个字符");
  } catch (error) {
    e.preventDefault();
    onError((error as Error).message);
  }
}
