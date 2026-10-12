// ┌─────────────────────────────────────────────────────────┐
// │ previewSignIn.ts                                        │
// │ 角色：控制台的协议版本引导与本地预览内置登录              │
// │ 说明：控制台由服务端自己托管，版本取自 /health；          │
// │       预览凭据只在隔离实例存在，生产实例 404。            │
// └─────────────────────────────────────────────────────────┘

import { setProtocolVersion } from './session.ts';

/**
 * 部署实例的 `/api` 前置协议版本闸门要求声明版本，而控制台在登录前拿不到版本
 * （版本原本只来自登录响应）——首次登录因此会被 426 静默挡掉。这里在启动时先问
 * `/health`（不受闸门约束）把版本就位，登录与后续请求才能通过。
 */
export async function bootstrapProtocolVersion(fetchImpl: typeof fetch = fetch): Promise<void> {
  try {
    const response = await fetchImpl('/health', { cache: 'no-store' });
    if (!response.ok) return;
    const body: unknown = await response.json();
    const version = typeof (body as { version?: unknown })?.version === 'string' ? (body as { version: string }).version.trim() : '';
    if (version) setProtocolVersion(version);
  } catch { /* 健康检查不可达时保持空版本，登录会给出明确失败原因 */ }
}

/** 本地预览的内置账号；生产实例该端点返回 404，这里得到 null。 */
export async function previewCredentials(fetchImpl: typeof fetch = fetch): Promise<{ username: string; password: string } | null> {
  try {
    const response = await fetchImpl('/api/account/preview-signin', { cache: 'no-store' });
    if (!response.ok) return null;
    const body = (await response.json()) as { username?: unknown; password?: unknown };
    const username = typeof body?.username === 'string' ? body.username.trim() : '';
    const password = typeof body?.password === 'string' ? body.password : '';
    return username && password ? { username, password } : null;
  } catch { return null; }
}

/** 停在登录界面调试登录流程：地址带 `?login` 时跳过内置登录。 */
export function debugLoginScreen(search: string = location.search): boolean {
  return new URLSearchParams(search).has('login');
}
