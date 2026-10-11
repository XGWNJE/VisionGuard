// ┌─────────────────────────────────────────────────────────┐
// │ session.ts                                              │
// │ 角色：本次会话的协议版本（来自服务端登录响应）             │
// │ 说明：所有 /api 请求与 WebSocket 认证都要带这个版本；      │
// │       服务端只服务同版本端点，旧版本客户端会被静默丢弃。    │
// └─────────────────────────────────────────────────────────┘

let current = '';

export function setProtocolVersion(value: unknown): void {
  current = typeof value === 'string' ? value.trim() : '';
}

/** 当前会话的协议版本；未登录为空串。 */
export function protocolVersion(): string {
  return current;
}

/** 版本请求头；未登录（尚无会话）时返回空对象。 */
export function versionHeaders(): Record<string, string> {
  return current ? { 'X-VisionGuard-Version': current } : {};
}
