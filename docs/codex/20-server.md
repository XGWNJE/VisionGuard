# Server

`server/` 是 VisionGuard 当前 4.x 的中继服务，负责 HTTP + WebSocket 入口、告警记录、截图下载和更新文件分发。

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
- 聚合告警、维护设备在线状态并清理过期数据
- 清理过期截图

当前 Server 尚未实现路线图中的 `DeviceOfflineAlert`、多租户权威事件库或独立 Web Management Console；连接列表中的 `online=false` 只是在线状态，不是离线报警已送达。

## 对外入口

- `GET /health`：健康检查
- `GET /api/update`：客户端更新查询
- `GET /releases/*`：Release 文件下载
- `/ws`：WebSocket 中继入口
- WS 角色：`windows`、`android`、`android-detector`、`windows-resident`
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

- `BIND_HOST`（默认 `127.0.0.1`；仅在明确需要直接对外监听时覆盖）
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
- 检测端心跳最多保留四个来源；第五个及以后被截断。逐来源命令与参数调整只接受最近一次心跳中存在的 `targetSourceId`。
- 业务控制命令只允许 `pause`、`resume`、`stop-alarm`；驻留生命周期只允许四个既定打开/关闭命令。无效命令或来源不会占用 `requestId`。
- `visionguard.xgwnje.cn` 当前用于 VisionGuard 服务，公网 443 由 Nginx stream 共享，HTTPS 虚拟主机监听 `127.0.0.1:9443`
- 当前 VPS 使用共享证书目录 `/etc/letsencrypt/live/xgwnje.cn/`
- 历史公网 smoke 与 Android 接收端实机启动记录见[验证报告](90-verification-report.md)；这些记录不等同于当前生产状态或完整真实告警链路
- 根域 `/releases/*` 仅作为旧客户端更新兼容入口，新配置不应继续写入根域

## 写文档时要避免的点

- 不要把 `README.md` 的旧表述当成唯一事实来源
- 不要把未确认的发布流程写成强约束
- 不要在文档里默认未来接口不变
- 不要直接运行旧 `server/deploy.sh --nginx` 覆盖当前 VPS 的 SNI/9443 架构
