# Server

`server/` 是 VisionGuard 的中继服务，负责 HTTP + WebSocket 入口、告警入库、截图下载和更新文件分发。

正式服务域名为 `https://visionguard.xgwnje.cn`，由新 VPS 上的 Nginx SNI 架构转发到 VisionGuard Node 服务。根域 `https://xgwnje.cn` 留给个人主页，不再作为新客户端的 VisionGuard 服务地址。

当前线上路径：

```text
visionguard.xgwnje.cn:443
  -> Nginx stream SNI
  -> 127.0.0.1:9443 HTTPS virtual host
  -> proxy_pass http://127.0.0.1:3000
  -> /opt/visionguard-server
```

## 当前职责

- 处理 `/health`
- 提供 `/api/*` 路由
- 提供 `/releases/*` 静态下载（客户端更新包）
- 提供 `/models/*` 静态下载（模型文件，无需鉴权）
- 维护 WebSocket 连接与角色认证
- 聚合告警并清理过期数据
- 清理过期截图

## 对外入口

- `GET /health`：健康检查
- `GET /api/update`：客户端更新查询
- `GET /releases/*`：Release 文件下载
- `/ws`：WebSocket 中继入口
- 当前 VPS 不使用仓库内旧式独立 `listen 443 ssl` 站点直接接管公网 443。
- 公共 DNS、端口、Nginx SNI 结构维护在 `D:\ObjectCode\Server-infra`。

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
- 截图目录当前为 `data/screenshots/<alertId>.(png|jpg)`；服务端按图片魔数决定扩展名
- WS 认证存在超时控制，当前实现为 5000ms
- `visionguard.xgwnje.cn` 当前用于 VisionGuard 服务，公网 443 由 Nginx stream 共享，HTTPS 虚拟主机监听 `127.0.0.1:9443`
- 当前 VPS 使用共享证书目录 `/etc/letsencrypt/live/xgwnje.cn/`
- 2026-06-29 上线 smoke：公网 `/health`、`/api/update`、`/releases/*`、`/models/*`、`/ws` 已通过；Android 接收端实机 UI 已显示可连接，等待后续真实告警链路观察
- 根域 `/releases/*` 仅作为旧客户端更新兼容入口，新配置不应继续写入根域

## 写文档时要避免的点

- 不要把 `README.md` 的旧表述当成唯一事实来源
- 不要把未确认的发布流程写成强约束
- 不要在文档里默认未来接口不变
- 不要直接运行旧 `server/deploy.sh --nginx` 覆盖当前 VPS 的 SNI/9443 架构
