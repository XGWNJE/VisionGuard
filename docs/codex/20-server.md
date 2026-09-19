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
- 公共 DNS、端口、Nginx SNI 结构维护在本仓库同级的 `C:\Users\xgwnj\Documents\XGWNJE\Server-infra`。

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
- `MAX_SOURCES_PER_DETECTOR`（默认 16，允许 1–16；随 `auth-result`/`heartbeat-ack` 下发）

## 已验证事实

- 连接上限当前由 `MAX_WS_CONNECTIONS` 控制，默认 100
- 接收端幽灵阈值当前为 45s
- 检测端幽灵阈值当前也按 45s 统一处理
- 截图目录当前为 `data/screenshots/<alertId>.(png|jpg)`；服务端按图片魔数决定扩展名
- WS 认证存在超时控制，当前实现为 5000ms
- 检测端来源上限由 `MAX_SOURCES_PER_DETECTOR` 持有（默认 16、范围 1–16），并在 `auth-result` 与 `heartbeat-ack` 中下发 `maxSources`。默认值必须与检测端 `MultiSourceMonitorCoordinator.MaximumSourceLimit`（16）一致：默认 4 时检测端新增来源按钮在 4 路即变灰，且 4 路恰好一页装下、分页页脚从不出现（2026-09 实报“来源卡片不能显示多页、加到 4 个就加不了”）。心跳的 `sources` 数组超过上限时整组拒绝并回明确原因，不再静默截断——静默截断会让超出部分既不报警也不可见。逐来源命令与参数调整只接受最近一次心跳中存在的 `targetSourceId`。
- `device-list` 的每个设备条目携带 `maxSources` 与 `sourceLimitExceeded`：后者表示最近一次心跳的来源数组因超限被整组拒绝（此时条目里的 `sources` 是上一次成功上报的快照），正常心跳会清除该标记。接收端据此解释“来源为什么只有这些”，不需要猜。
- 业务控制命令只允许 `pause`、`resume`、`stop-alarm`；驻留生命周期只允许四个既定打开/关闭命令。无效命令或来源不会占用 `requestId`。无 `targetSourceId` 的命令按设备级中继，由检测端解释为“全部来源”；服务端不把设备级命令改写成某个具体来源。
- `request-screenshot` 没有成功回执：检测端把截图作为 `screenshot-data` 异步广播，请求者按 `alertId` 关联；失败路径回带 `requestId` 与 `phase=completed` 的结构化 `command-ack`。
- 驻留连接与检测端、接收端一样参与幽灵清理（同为 45s 阈值）。只有驻留在线的设备仍算在线，因此对它的业务命令回“该设备当前没有检测端在线”，而不是“设备离线”。
- `session-info` 只在 `android` 角色被接受，并且以认证身份为准，不使用消息自称的 `deviceId`。
- 新协议 Server 实例由 `VISIONGUARD_CHANNEL` 标识隔离域，认证消息必须携带完全一致的 `channel`；错误或缺失通道直接拒绝。测试实例使用独立端口、进程和 `VISIONGUARD_DATA_DIR`，因此连接表、报警、截图和广播不会与仍在线的旧版本混合。
- WS 报警以 `alertId` 幂等入库：首次报警原子落盘并 `fsync` 后返回 `alert-ack/stored`，相同内容重试返回 `duplicate` 且不重复广播，同 ID 不同内容返回永久 `alert-id-conflict`；临时落盘失败返回 `storage-failed`，检测端保留队列继续重试。
- `visionguard.xgwnje.cn` 当前用于 VisionGuard 服务，公网 443 由 Nginx stream 共享，HTTPS 虚拟主机监听 `127.0.0.1:9443`
- 当前 VPS 使用共享证书目录 `/etc/letsencrypt/live/xgwnje.cn/`
- 历史公网 smoke 与 Android 接收端实机启动记录见[验证报告](90-verification-report.md)；这些记录不等同于当前生产状态或完整真实告警链路
- 根域 `/releases/*` 仅作为既有线上版本入口，新协议测试通道不使用该入口

## 写文档时要避免的点

- 不要把 `README.md` 的旧表述当成唯一事实来源
- 不要把未确认的发布流程写成强约束
- 不要在文档里默认未来接口不变
- 不要直接运行旧 `server/deploy.sh --nginx` 覆盖当前 VPS 的 SNI/9443 架构
