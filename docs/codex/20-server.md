# 视觉中继

`server/` 在架构中承担统一服务职责，沿用内部技术名称“视觉中继”（`VisionGuard.Relay`、npm 包 `visionguard-relay`）。当前实现连接、认证、状态、告警流转和控制转发，以及截图、模型和更新文件分发；职责划分见[项目概览](10-project-overview.md)。

正式服务域名为 `https://visionguard.xgwnje.cn`，由 VPS 上的 Nginx SNI 架构转发到 VisionGuard Node 服务。根域 `https://xgwnje.cn` 用于个人主页，不能作为当前客户端的服务地址。

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
- 转发控制台的设备/来源控制与参数配置请求，并回传执行结果
- 清理过期截图

视觉中继生成节点连接中断和持续检测中断事件。连接状态不等于通知收件确认，通知收件确认不等于声音播放；本机与真机证据分别见[验证报告](90-verification-report.md)。

## 统一接入契约

### 身份与权限

认证消息携带 `channel`、`apiKey`、`deviceId`、`deviceName`、`role`、`nodeType`、`platform`。`VISIONGUARD_IDENTITIES_FILE` 指向私有 JSON 数组，每条登记 `deviceId/role/nodeType/platform/apiKey`；凭据、身份及通道必须全部匹配，不能自称其他节点或角色。服务启动时加载登记，调整后重启；登记方法见[运维](60-operations.md#节点登记)。

| 角色 | 节点类型 | 权限 |
|---|---|---|
| `detector` | `visual` / `sensor` | 上报自己的事件、状态与能力，回传自己的控制执行结果 |
| `console` | `console` | 查看节点与事件，按目标能力下发控制和配置 |
| `notifier` | `notification` | 接收实时事件，确认自己已收件，上报连接心跳 |
| `lifecycle` | `resident` | 上报驻留状态，执行打开/关闭视觉程序 |

平台只描述运行环境，不决定权限。视觉主程序与驻留可共用设备 ID，但使用独立凭据；同一角色与 ID 的新连接替换旧连接，旧连接不能继续更改状态或回传结果。`clientType` 是现有控制台的显示投影，不参与认证或权限判定。`API_KEY` 仅保留 HTTP 管理读取权限；HTTP 告警上传必须使用登记的检测节点凭据并绑定其设备 ID。

### 事件与送达

- `alert` 使用稳定 UUID `alertId` 去重，公共字段为 `eventKind`、`timestamp`、`expiresAt`、`summary`、设备身份；实时有效期最多 30 秒，过期事件拒绝入库与重试。
- `visual-detection` 保留已上报来源的 `sourceId/sourceName` 和检测框；截图经同一 `alertId` 的独立消息关联。`sensor-detection` 不要求图片、检测框或虚构视觉来源，`detections=[]`。
- `connection-lost` 与 `detection-interrupted` 由服务生成，同样经事件入库和实时通知链流转。设备连接中断事件不带来源；逐视觉来源检测中断事件保留来源身份。
- 服务生成的故障事件遇到临时落盘失败时，在原有效期内每 3 秒重试，不延长事件期限；服务进程重启后的故障重新依据当前连接和状态判断。
- `alert-ack/stored` 证明事件已原子写入并 `fsync`；相同事件重试返回 `duplicate`，不重复广播，同 ID 不同内容返回 `alert-id-conflict`。入库与通知收件是两个阶段。
- 服务对当前在线通知节点每 3 秒重试未确认事件，截止 `expiresAt`；`notification-receipt` 只确认该通知节点的待收件事件，并向控制台广播收件事实。断开或替换会话后丢弃其待投递项，重连不补发旧会话告警。事件历史仍可查看，但不会作为重新响铃的依据。
- `NotificationNodeClient` 提供通知节点的最小 WS 客户端和接收回调；`NotificationSession` 在本地使用单调时钟监测服务响应，启动后或最近有效响应后 45 秒无响应便触发一次中断回调，重连尝试与发送心跳不会延长该期限。恢复响应后允许监测下一次中断。按事件 ID 去重，接收回调成功后才回收件确认；回调失败可在有效期内重试。通知节点的后台入口与声音策略见[控制台与通知节点](50-android-receiver.md#visionguard-通知节点)。

### 通知范围

`get-notification-scopes` 返回已登记检测和通知身份，包含离线节点与当前范围，不包含凭据。控制台发送 `set-notification-scope`，字段为 `requestId`、`targetNotifierId`、`scope`；成功保存后返回 `notification-scope-result`，广播新的 `notification-scopes`，并向在线通知节点推送 `notification-scope`。通知认证结果携带 `notificationScope`。

范围格式为 `{mode: "all" | "selected", targets: [{deviceId, sourceId?}]}`，最多 100 项；默认全部，指定空集合不收件。目标必须是已登记检测身份，sourceId 仅适用于视觉节点；在线来源必须是当前上报来源，离线来源可按稳定 ID 配置。指定设备包括其所有来源；指定来源仍接收父设备连接中断事件。修改范围时丢弃已不匹配的待投递项，扩大范围不补发历史。通知节点自身监测的服务中断不受服务器范围过滤。

`NotificationScopeStore` 在数据目录 `notification-scopes.json` 使用临时文件加原子替换保存；写入失败保留原有效范围，不回成功。服务重启读取持久化配置。

### 状态、配置与控制

- 检测节点每 3 秒上报 `heartbeat`，保留的 Android 控制台每 30 秒、Web 每 3 秒上报 `heartbeat-console`，通知节点每 3 秒上报 `heartbeat-notifier`；驻留使用 `resident-heartbeat`。服务响应 `heartbeat-ack`，45 秒无心跳清理连接，维护周期 3 秒。
- 已关闭的检测连接保留 10 秒重连宽限；仍未重连则生成一次连接中断事件。服务进程停机由通知节点本地监测，不能依赖停机的服务发送故障通知。
- 检测状态分开实际运行 `isMonitoring/isReady`、运行意图 `monitoringExpected` 和最后成功处理时间 `lastProgressAt`。持续预期运行却 15 秒无成功处理，或持续 15 秒未实际运行，生成一次中断事件；恢复后重新监测。主动暂停不产生检测中断。视觉状态按来源判断，传感器按节点判断。
- 能力上报受到节点类型限制：传感器不能宣称视觉来源控制或截图能力。`command`、`set-config` 继续复用 `requestId` 去重及 `forwarded/completed` 两阶段回执；执行完成必须由实际目标连接确认，不能把已转发称为执行成功。
- 首期保留已有配置键与校验。视觉支持冷却、置信度、目标、采样率和已有模型切换；传感器基础只接受冷却、置信度，且必须声明 `config-control`。具体传感器设置与配置模板待实际硬件需求细化。

### 统一告警时间

服务通道持有一个告警显示标准，默认 `Asia/Shanghai`（北京时间 UTC+8），也可设为 `UTC`。控制台发送 `set-time-standard`，携带 `requestId` 和 `timeZone`；只有已认证控制台可修改。服务先将标准原子写入并 `fsync` 到数据目录 `time-standard.json`，成功后回 `time-standard-result`，向在线控制台和通知节点广播 `time-standard`；失败保留原标准。认证结果携带 `timeStandard`，重连时重新同步，控制台可用 `get-time-standard` 读取。

`timeStandard` / `time-standard` 包含 `timeZone` 和 UTC ISO 8601 `serverTime`。Web 节点响应时间、事件列表和详情采用该时区；通知节点持久化所收标准，告警列表与弹窗立即重绘。事件中的 ISO 时间与有效期保留原发生瞬间，切换显示标准不改变事件有效期、补发行为或设备系统时钟；此设置不提供节点时钟漂移校准。

## 对外入口

- `GET /console/`：同源 Web 控制台静态入口，构建产物位于 `server/dist/console/`；凭据在内存，HTTP 事件和截图仍需认证
- `POST /console/test-session`：默认关闭；显式开启隔离测试自动登录时，仅为回环或私有 IPv4 客户端生成临时 `console/console/web` 身份，不返回管理密钥或既有节点凭据。同源浏览器每次打开获得独立身份，重启服务或 24 小时后失效
- `GET /health`：健康检查
- `GET /api/update`：客户端更新查询
- `GET /releases/*`：Release 文件下载
- `/ws`：WebSocket 中继入口
- WS 角色：`detector`、`console`、`notifier`、`lifecycle`
- 公共 DNS、端口、Nginx SNI 结构维护在同级 `Server-infra` 仓库。

## 关键文件

- `server/src/index.ts` - 服务入口，挂载路由、WS、TTL 清理
- `server/src/config.ts` - 环境变量与运行参数
- `server/src/services/ConnectionManager.ts` - WS 认证、心跳、广播、角色路由
- `server/src/services/NodeProtocol.ts` - 登记身份、角色权限和实时事件校验
- `server/src/services/NotificationNodeClient.ts` / `NotificationSession.ts` - 通知接入与本地中断监测
- `server/src/services/NotificationScopeStore.ts` - 通知范围验证、匹配与持久化
- `server/src/services/TimeStandardStore.ts` - 通道告警时区与原子持久化
- `server/src/services/AlertStore.ts` - 告警缓存与持久化
- `server/src/services/ScreenshotCleanup.ts` - 截图 TTL 清理
- `server/src/routes/update.ts` - 更新查询
- `server/src/routes/screenshot.ts` - 截图下载

## 运行参数

- `BIND_HOST`（默认 `127.0.0.1`；仅在明确需要直接对外监听时覆盖）
- `PORT`
- `API_KEY`
- `VISIONGUARD_IDENTITIES_FILE`（必填，私有节点凭据登记文件）
- `VISIONGUARD_TEST_CONSOLE_AUTOLOGIN`（默认 `false`；开启必须设置与默认数据目录不同的 `VISIONGUARD_DATA_DIR`，仅用于隔离测试）
- `SCREENSHOT_TTL_HOURS`
- `ALERT_TTL_HOURS`
- `MAX_UPLOAD_BYTES`
- `ENABLE_HTTP_SCREENSHOT_UPLOAD`
- `MAX_WS_CONNECTIONS`
- `MAX_SOURCES_PER_DETECTOR`（默认 16，允许 1–16；随 `auth-result`/`heartbeat-ack` 下发）

## 实现事实

- 连接上限当前由 `MAX_WS_CONNECTIONS` 控制，默认 100
- 所有 WS 角色的连接幽灵清理阈值为 45 秒
- 截图目录当前为 `data/screenshots/<alertId>.(png|jpg)`；服务端按图片魔数决定扩展名
- WS 认证存在超时控制，当前实现为 5000ms
- 检测端来源上限由 `MAX_SOURCES_PER_DETECTOR` 持有（默认 16、范围 1–16），并在 `auth-result` 与 `heartbeat-ack` 中下发 `maxSources`。默认值必须与检测端 `MultiSourceMonitorCoordinator.MaximumSourceLimit`（16）一致。心跳的 `sources` 数组超过上限时整组拒绝并回明确原因，不再静默截断——静默截断会让超出部分既不报警也不可见。逐来源命令与参数调整只接受最近一次心跳中存在的 `targetSourceId`。
- `device-list` 的每个设备条目携带 `maxSources` 与 `sourceLimitExceeded`：后者表示最近一次心跳的来源数组因超限被整组拒绝（此时条目里的 `sources` 是上一次成功上报的快照），正常心跳会清除该标记。接收端据此解释“来源为什么只有这些”，不需要猜。
- 业务控制命令只允许 `pause`、`resume`、`stop-alarm`；驻留生命周期只允许 `open-detector` / `close-detector`。无效命令或来源不会占用 `requestId`。无 `targetSourceId` 的命令按设备级中继，由检测端解释为“全部来源”；服务端不把设备级命令改写成某个具体来源。
- `request-screenshot` 没有成功回执：检测端把截图作为 `screenshot-data` 异步广播，请求者按 `alertId` 关联；失败路径回带 `requestId` 与 `phase=completed` 的结构化 `command-ack`。
- 驻留连接与检测端、接收端一样参与幽灵清理（同为 45s 阈值）。只有驻留在线的设备仍算在线，因此对它的业务命令回“该设备当前没有检测端在线”，而不是“设备离线”。
- 同一 `deviceId` 的 Windows 主检测端与驻留程序共用一个设备名称；主检测端认证或心跳中的自定义名称会同步到驻留连接记录，主检测端关闭并经过重连宽限后，驻留设备卡仍延续该名称。
- `session-info` 只在 `console` 角色被接受，并且以认证身份为准，不使用消息自称的 `deviceId`。
- `VISIONGUARD_CHANNEL` 标识实例隔离域，认证消息的 `channel` 必须完全一致；错误或缺失通道直接拒绝。测试实例使用独立端口、进程和 `VISIONGUARD_DATA_DIR`，连接表、报警、截图和广播与生产隔离。
- WS 报警以 `alertId` 幂等入库：首次报警原子落盘并 `fsync` 后返回 `alert-ack/stored`，相同内容重试返回 `duplicate` 且不重复广播，同 ID 不同内容返回永久 `alert-id-conflict`；临时落盘失败返回 `storage-failed`，检测端保留队列继续重试。
- 当前 VPS 使用共享证书目录 `/etc/letsencrypt/live/xgwnje.cn/`
- 当前生产状态与完整报警链需要单独核验；本轮结果见[验证报告](90-verification-report.md)。
- 根域 `/releases/*` 仅作为既有线上版本入口，新协议测试通道不使用该入口

## 操作与验证

构建、隔离实例和发布授权见[运维](60-operations.md)；本轮检查与未覆盖项见[验证报告](90-verification-report.md)。生产网络与 TLS 配置由 `Server-infra` 管理。
