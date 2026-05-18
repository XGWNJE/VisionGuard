# Server

`server/` 是 VisionGuard 的中继服务，负责 HTTP + WebSocket 入口、告警入库、截图下载和更新文件分发。

## 当前职责

- 处理 `/health`
- 提供 `/api/*` 路由
- 提供 `/releases/*` 静态下载
- 维护 WebSocket 连接与角色认证
- 聚合告警并清理过期数据
- 清理过期截图

## 关键文件

- `server/src/index.ts` - 服务入口，挂载路由、WS、TTL 清理
- `server/src/config.ts` - 环境变量与运行参数
- `server/src/services/ConnectionManager.ts` - WS 认证、心跳、广播、角色路由
- `server/src/services/AlertStore.ts` - 告警缓存与持久化
- `server/src/services/ScreenshotCleanup.ts` - 截图 TTL 清理
- `server/src/routes/update.ts` - 更新查询
- `server/src/routes/screenshot.ts` - 截图下载

## 运行参数

- `PORT`
- `API_KEY`
- `SCREENSHOT_TTL_HOURS`
- `ALERT_TTL_HOURS`
- `MAX_UPLOAD_BYTES`
- `ENABLE_HTTP_SCREENSHOT_UPLOAD`
- `MAX_WS_CONNECTIONS`

## 已验证事实

- 连接上限当前由 `MAX_WS_CONNECTIONS` 控制，默认 100
- 接收端幽灵阈值当前为 45s
- 检测端幽灵阈值当前也按 45s 统一处理
- 截图目录当前为 `data/screenshots/<alertId>.png`
- WS 认证存在超时控制，当前实现为 5000ms

## 写文档时要避免的点

- 不要把 `README.md` 的旧表述当成唯一事实来源
- 不要把未确认的发布流程写成强约束
- 不要在文档里默认未来接口不变

