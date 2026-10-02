# 视觉中继

`server/` 承担统一服务职责，沿用内部技术名称 `VisionGuard.Relay` 和 npm 包名 `visionguard-relay`。它提供账号登录、节点身份、实时媒体转发、状态、事件、控制、截图及公共模型和更新文件分发；组件分工见[项目概览](10-project-overview.md)。

正式地址为 `https://visionguard.xgwnje.cn`。既有生产入口由 Nginx SNI 经 `127.0.0.1:9443` 转发至 `127.0.0.1:3000`，服务目录为 `/opt/visionguard-server`；根域 `https://xgwnje.cn` 是个人主页。源码更新不代表生产服务已升级，当前测试和生产证据见[验证报告](90-verification-report.md)。

## 统一接入契约

### 账号与身份

一套账号对应一套系统链路。所有组件使用账号密码登录，设备 ID、所属账号、角色、平台及随机会话凭据由服务端确定。客户端无需填写节点凭据或手动匹配通道；认证只使用本实例签发的会话。

| 登录组件 | 角色 / 类型 / 平台 | 职责 |
|---|---|---|
| `web-console` | `console / console / web` | Web 控制台 |
| `android-console` | `console / console / android` | Android 控制台 |
| `android-notifier` | `notifier / notification / android` | 独立通知节点 |
| `android-camera` | `detector / visual / android` | VisionGuard 镜头推流，仅采集和推流 |
| `windows-inference` | `detector / visual / windows` | 视觉推理及现有窗口采集 |

Windows 登录自动获得同设备 ID 的 `windows-resident` 子会话，角色为 `lifecycle / resident / windows`。主会话退出、轮换、到期或设备解绑同时使子会话失效；独立刷新子会话仍保留主会话约束。平台是身份信息，权限由服务端组件映射决定。

账号通过根目录 `scripts/provision-account.js <username>` 创建，无公开注册入口。必须指定私有 `VISIONGUARD_DATA_DIR`；密码只通过 `VISIONGUARD_ACCOUNT_PASSWORD` 临时环境变量或标准输入传入。CLI 与服务共用单进程 JSON 存储，应停止对应服务后创建账号再启动，操作入口见[运维](60-operations.md)。密码使用随机盐与 scrypt，磁盘会话只保存 token 哈希；有效期为 30 天。

HTTP 使用 `Authorization: Bearer <token>`；`/ws` 的首条消息为 `{type:'auth',token}`。认证结果包含账号、设备身份、组件、`maxSources` 和账号的 `timeStandard`。客户端自称的身份字段不参与权限判断，同角色同设备的新连接替换旧连接。

设备名称以服务端登记值为准。`PATCH /api/devices/:deviceId` 更新主节点及驻留名，并向对应现存连接推送 `device-updated`、向控制台刷新设备列表；旧客户端心跳不能覆盖登记名。退出、改密、解绑立即撤销相关控制和媒体连接及待处理项。

## 账号隔离与数据

设备列表、媒体绑定、画面、事件、截图、控制请求、通知范围及显示时区均限定在当前账号。跨账号目标按不存在处理；截图必须关联当前账号中已保存的事件。HTTP 历史和截图仅控制台可读取，HTTP 事件上传仅推理组件可调用，镜头推流不能上报推理事件。

```text
VISIONGUARD_DATA_DIR/
  accounts.json
  accounts/<accountId>/
    alerts.json
    screenshots/<alertId>.(png|jpg)
    notification-scopes.json
    time-standard.json
    streams.json
```

存储使用临时文件、`fsync` 和原子替换，操作落盘后才确认成功。截图默认保留 72 小时，事件默认 7 天，每设备最多 200 条。独立测试实例使用单独端口、进程和数据目录；`VISIONGUARD_CHANNEL` 只描述实例，不是用户需要填写的匹配凭据。

## 实时媒体

`/media/ws` 与控制 socket 分开。首条消息为 `{type:'media-auth',token,direction:'publish'|'subscribe'}`，只有镜头推流可发布，只有 Windows 推理节点可订阅。每个摄像设备有独立 `streamId`；同账号只有一个兼容推理设备时自动绑定，多个时由控制台选择。`sourceId` 与绑定保存，重新连接使用新的媒体 `sessionId`，缓冲随连接或绑定变化清空。

二进制帧为 4 字节大端 JSON 头长度、UTF-8 头和 JPEG。头包含 `streamId/sessionId/sequence/capturedAt/width/height/rotation`；转发时增加服务端 `receivedAt` 毫秒时间用于观测。JSON 头最多 4096 字节、JPEG 最多 2 MiB，分辨率最长边不超过 1280、短边不超过 720，服务检查 JPEG 声明尺寸与帧元数据一致。接收端仍须实际解码，错误帧不能算推理进展。

发布端等待 `media-ready`，每次最多一帧未确认，服务用 `frame-ack` 授予下一帧额度。订阅端用 `frame-received` 确认接收/解码；服务每路保留一帧在途和一帧最新候选，候选年龄超过 2.5 秒丢弃，消费者 5 秒不确认即关闭。服务缓存年龄用单调时钟判断，相机时间只作相对变化检查，不要求各设备墙钟同步。WS 入口同时限制整条消息、分片数量及缓冲块数量。

首帧接收、持续推流、接收确认和推理完成是不同事实。发布 2.5 秒无新帧标记 `frame-stalled`；异常连接断开标记 `connection-lost`。显式 `stream-stop` 的 `user/background/locked` 原因是正常停止。推理端不能反复处理同一缓存帧来刷新 `lastProgressAt`。

镜头控制连接关闭或空闲不会生成推理故障报警，包括用户停止、后台和锁屏退出。真实媒体异常仍通过绑定的 Windows 来源状态产生检测中断；只有推理节点自身断开才产生其节点连接中断。这个边界由服务签发的组件身份决定，客户端自报断开原因不能绕过推理节点故障判断。

服务推送 `stream-list` 到本账号控制台和视觉节点；控制 WS `get-streams` 或 HTTP `GET /api/streams` 可主动读取。`POST /api/streams/bind` 接受 `publisherDeviceId/targetDeviceId`，仅允许同账号控制台或发布端绑定自己的镜头。

## 事件、通知与控制

- `alert` 以稳定 UUID `alertId` 去重，实时有效期最多 30 秒。首次落盘回 `alert-ack/stored`，同内容重试回 `duplicate`，同 ID 不同内容回 `alert-id-conflict`；临时落盘失败回 `storage-failed`，不延长事件有效期。
- `visual-detection` 保留当前来源身份与检测框，截图通过同一 `alertId` 的独立消息关联。服务生成 `connection-lost` 和逐来源 `detection-interrupted`，落盘临时失败在原有效期内每 3 秒重试。
- 在线通知节点每 3 秒收到未确认事件的重试，截止 `expiresAt`。`notification-receipt` 仅证明对应通知节点已收件；连接状态、入库、收件和声音播放不能互相代替。旧会话待投递项在断开或替换时丢弃，重连不重放旧报警。
- `get-notification-scopes` 和 `set-notification-scope` 仅操作本账号登记设备。范围为 `{mode:'all'|'selected',targets:[{deviceId,sourceId?}]}`，最多 100 项，默认全部；指定空集合不收件。调整范围不会补发历史，指定来源仍接收父设备连接中断事件。
- 节点实际运行 `isMonitoring/isReady`、运行意图 `monitoringExpected` 和 `lastProgressAt` 分开。持续预期运行但 15 秒无成功处理或未实际运行产生一次故障，恢复后重新监测；主动暂停不产生该故障。
- 检测、通知和驻留节点通常每 3 秒心跳，控制台心跳可为 30 秒；控制连接 45 秒无响应清理，检测连接保留 10 秒重连宽限。服务停机由通知节点本地 45 秒响应看门狗监测。
- 来源上限由 `MAX_SOURCES_PER_DETECTOR` 下发，默认 16、范围 1–16。超限心跳整组拒绝，列表携带 `sourceLimitExceeded`；来源控制只接受最近成功心跳中存在的来源。
- 业务命令为 `pause/resume/stop-alarm`，驻留命令为 `open-detector/close-detector`。`command/set-config` 用 `requestId` 关联 `forwarded/completed` 两阶段结果，不能把已转发称为已执行。镜头组件不能声明推理、截图或模型配置权限。
- `request-screenshot` 成功时以 `screenshot-data` 异步返回，请求者按 `alertId` 关联；失败回结构化 `command-ack`。

每账号的告警显示时区默认 `Asia/Shanghai`，可由控制台设置为 `UTC`。`set-time-standard` 先持久化再回结果，并广播给本账号控制台和通知节点；认证和重连同步标准。ISO 时间和事件有效期保留原发生瞬间，显示时区不校准节点时钟、不改变重试或补发策略。

## 对外入口

- `/console/`：同源 Web 控制台，需要真实账号登录。
- `/api/account/login`、`session`、`refresh`、`logout`、`password`：登录、读取会话、轮换、退出、改密。
- `/api/devices` 及 `/:deviceId`：账号设备列表、改名、解绑。
- `/api/streams` 和 `/api/streams/bind`：账号媒体列表与绑定。
- `/api/alerts`、`/api/alert`、`/screenshots/*`：鉴权历史、可选上传和截图。
- `/ws`、`/media/ws`：控制和媒体 WS。
- `/health`、`/api/update`、`/releases/*`、`/models/*`：公共健康检查、更新查询和公共文件分发。

## 关键文件与配置

入口和参数为 `server/src/index.ts`、`config.ts`；账号为 `services/AccountStore.ts` 和 `routes/account.ts`；控制为 `ConnectionManager.ts`、`NodeProtocol.ts`；媒体为 `MediaRelay.ts`、`WebSocketLimits.ts` 和 `routes/streams.ts`；事件、通知范围和时区分别由 `AlertStore.ts`、`NotificationScopeStore.ts`、`TimeStandardStore.ts` 保存，截图清理由 `ScreenshotCleanup.ts` 完成。

主要环境变量见 `server/.env.example`：`BIND_HOST` 默认 `127.0.0.1`、`PORT` 默认 3000、`VISIONGUARD_DATA_DIR`、`VISIONGUARD_CHANNEL`，以及 TTL、上传、连接和来源限制。默认 HTTP 可放在现有代理后；直接 HTTPS 的隔离测试入口同时设置 `VISIONGUARD_TLS_CERT_FILE` 与 `VISIONGUARD_TLS_KEY_FILE`，使用同一服务器的 HTTP 和 WS 路径。

构建、隔离运行和发布授权见[运维](60-operations.md)；当前检查和未覆盖项只维护于[验证报告](90-verification-report.md)。生产网络和证书配置由同级 `Server-infra` 管理。
