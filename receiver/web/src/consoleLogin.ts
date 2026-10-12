// ┌─────────────────────────────────────────────────────────┐
// │ consoleLogin.ts                                         │
// │ 角色：控制台登录编排（登录页与本地预览内置登录共用）      │
// └─────────────────────────────────────────────────────────┘

import { accountRequest, browserDeviceIdentity, browserDeviceModel, parseLogin, type Login } from './account';
import { clearSessionLogin, saveSessionLogin } from './sessionLogin';

export type ConsoleLoginResult = { value: Login; warning: string };

/**
 * 登录并尽力保存会话。保存失败不作为登录失败：返回 warning 让调用方提示“刷新后需重新登录”，
 * 与会话能力不可用时的既有行为一致。
 */
export async function performConsoleLogin(username: string, password: string): Promise<ConsoleLoginResult> {
  const value = parseLogin(await accountRequest('/api/account/login', undefined, {
    username: username.trim(),
    password,
    component: 'web-console',
    deviceIdentity: await browserDeviceIdentity(),
    deviceModel: browserDeviceModel(),
  }));
  let warning = '';
  try { await saveSessionLogin(value); }
  catch { await clearSessionLogin().catch(() => {}); warning = '本次登录未保存，刷新后需重新登录。使用 HTTPS 并允许站点存储可保持登录。'; }
  return { value, warning };
}
