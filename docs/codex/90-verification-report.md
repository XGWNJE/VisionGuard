# 当前验证报告

本文是当前源码的证据台账。不把源码存在、构建或界面启动作为完整报警链和实机交付证明。连接状态不等于收件确认，收件确认不等于声音播放；构建、模拟器与真机分别判断。

## 当前状态

核验日期：2026-10-03。开发分支为 `codex/roadmap-next`，保持独立，不合并。根 `VERSION` 为 `4.5.1`，未改版本、push、tag、GitHub Release 或正式发布。仅部署 owner 明确授权的独立公网测试服务，正式业务服务、目录、Nginx 和发布元数据未修改。

owner 已手动走查提交 `9d587eb` 的基础操作链路；细项尚未逐项验收。原有待人工/真机验收项继续保留，本轮新增项继续累积，待 owner 有时间集中验收；基础走查与模拟器结果不作为这些细项的人工完成证据。

当前全组件使用账号登录及服务签发的设备身份。原 Android 检测端改为 **VisionGuard 镜头推流**，仍是视觉节点；真实 CameraX 画面经统一服务转发到 Windows 来源推理。独立通知节点位于 `notifier/android/`，原 Vigil 仓库未修改。

当前对外为 5 类组件、6 个平台实现，后台驻留归入视觉节点内部；服务正式名为 **VisionGuard 统一服务 / VisionGuard Server**，名称与技术标识以[命名规范](15-component-naming.md)为准。下方运行证据对应账号推流提交 `10bc0ae` 的测试组件；本轮文案调整不视为新增 UI 或 E2E 验收，独立公网服务与私有 APK 验收包未替换，Windows 验收脚本仍指向当前本机 Release 构建。

## 自动化与构建证据

| 检查 | 当前结果 | 证据边界 |
|---|---|---|
| 统一服务 | 49 项测试与 clean TypeScript 构建通过 | 账号、角色、设备及子会话；跨账号媒体/事件/截图/控制/范围/时间隔离；撤销/刷新、实时事件与收件；媒体限额/确认/过期；正常镜头停止与真实推理故障区别；不是生产验证 |
| Web | 14 项测试、TypeScript 与 Vite 构建通过 | 登录、刷新并发、旧账号迟到请求、隔离缓存、鉴权截图、设备关联及时间格式；UI 另行运行检查 |
| Windows Release | 启动器、驻留、modern/legacy 均 0 警告 / 0 错误，统一目录 32 个文件 | 实际运行使用 modern；Win7/legacy 仅构建证据 |
| Windows 账号与媒体探针 | `AccountMedia.Probe` 通过 | 大消息/分片/ping/取消/边界、最新帧/重复/停止/单调过期、DPAPI/账号切换、驻留隔离、登出重登与解绑重新登记；实际子进程延迟刷新与退出/重登录竞争、实际 AlertService 保留远程帧诊断字段；不能替代真实 WPF 推理 |
| Android 三端 | 镜头 8 项、控制台 71 项、通知 6 项 JVM 单测通过；三端 lintDebug 与 lintVitalRelease 无错误；签名 Release 与 apksigner verify 通过 | 共享真实 HTTP 迟到 401 条件清理；控制台离线身份、登记名单、迟到 GET/DELETE 隔离与解绑后刷新失败；名称、包名、版本 4.5.1 / code 4501 与共享签名核验；lintDebug 警告分别 27 / 27 / 114，含既有资源与 API 提示；不是物理设备证明 |
| 文档与脚本 | 文档审核及 41 项命名/文档/发行/版本契约与账号 CLI 守卫测试通过 | 中英文名与简称、内部驻留不单列产品、安装/工程身份、登录组件、构建/更新标识；导航、版本、授权、PowerShell 编码与不回显密码；已接现有 CI，尚未 push 或云端执行 |
| 命名固化产物 | 服务/Web、Windows 双档与入口/驻留、镜头和 Android 控制台 Release 构建通过；控制台 71 项与 Web 14 项测试再次通过 | Windows 标题/产品名、两端 APK 显示名/包名/版本及签名已核验；驻留文件说明为“VisionGuard 视觉节点驻留程序”，产品名归属“VisionGuard 视觉节点”；Windows 0 警告/错误，统一目录仍为 32 文件；不包含本轮 UI、设备或公网运行验证 |
| CI | 新增 `.github/workflows/development.yml` | 服务/Web 测试构建、三个 Android Debug 单测/lint/构建、Windows 双档及媒体探针；对应检查在本机执行，未 push，尚无 GitHub 云端运行结果 |

构建与测试报告保留在各工程 `build/reports/`、`build/test-results/` 及被忽略 `.local/`。三端签名 APK 为各自 `app/build/outputs/apk/release/app-release.apk`；私有签名配置、账号密码、会话与 SDK 配置不提交。

## 真实运行证据

| 环境与检查 | 已观察结果 | 当前限制 |
|---|---|---|
| 本机动态摄像链 | 可见模拟器用 videofile 摄像头，真实 CameraX → 独立 3100 服务 → 可见 Release WPF；出现连续 person、逐帧更新、实际 FPS 与报警截图 | 使用新 Debug Android 包；短时约 2.2–5 FPS，构建和双模拟器争用时出现性能提示，不是老手机或持续性能证明 |
| 本机独立通知节点 | 同一真实 WPF alertId 出现在持久队列、历史及收件记录，Web 显示通知节点已收件 | 观察到播放器 Service 与自动结束记录；不等于人耳确认声音，也不证明真机后台限制 |
| 正常停止与恢复 | 镜头切后台后 WPF 停止，保留明确标注的最后画面，不再产生人员事件；回前台不自动推流，手动启动后有新帧与新事件；公网实际后台停止、手动恢复及按钮停止后镜头与来源均无误报 | 空闲镜头控制断连误报已修复并补服务回归；后续主动关闭 WPF 的节点连接中断独立保留，不混入镜头正常停止结论 |
| 异常断流与恢复 | 强制结束镜头进程后，WPF 等待新画面、FPS 为 0、旧画面时间不推进；收到该来源 detection-interrupted；重开镜头并手动开始后恢复新人员帧 | 证明本轮进程断流场景；不推广到全部网络、驱动、GPU 或厂商电源管理故障 |
| 公网实际链路 | 最终签名镜头/通知包 → 独立 HTTPS/WSS 10443 服务 → 可见 modern Release WPF；Web 来源启动取得 completed，产生真实人员事件与通知收件；最终镜头包实际采集和发送 1280×720，并在 WPF 连续检出 person | 镜头目标 5 FPS，WPF 目标 3 FPS；最终 720P 短时约 1–2.9 FPS、实测 DirectML；不算老手机性能、持续运行、真机或正式生产验收 |
| 通知历史与更新安装 | 签名 Release force-stop 后重开保留账号及 32 条历史，含实际 Public-Inference · Public-Camera 手动确认；最终 APK 覆盖安装后历史与登录仍在，接收恢复连接 | 证明本轮模拟器持久化与同签名覆盖安装；不代替人耳声音或厂商后台限制验收 |
| Android 控制台 | 真实登录、人员事件与带框截图加载；来源 resume/pause 获 completed；最终签名覆盖安装保留登录/历史，显示当前账号 3 个节点及离线镜头；镜头解绑确认后取消，节点仍保留 | 解绑实际撤销另用独立 API 验证；取消确认未删除已有公网节点。旧包 `com.xgwnje.visionguard_android` 的既有通知未操作 |
| 设备解绑契约 | 一次性本机账号的真实 HTTP/WS 验证：解绑 Windows 后主/驻留会话与控制、订阅连接撤销，相机关联解除；解绑 Camera 后会话、控制/发布连接、媒体流与登记清除 | 临时随机端口与独立账号库；不是实际手机界面解绑验收，也未解绑公网已有测试节点 |
| 公网截图与账号隔离 | 最终真实事件 `0752f472-2b60-4c11-ab5f-364555e2d597` 与同事件通知回执；同账号截图 200/JPEG/56321 字节，另一账号同图 404，设备、媒体流、事件均无目标节点；远程帧诊断完整、推理端单调缓存年龄 215ms | 不发送模拟帧或报警；两个观察会话均已注销。215ms 只表示 Windows 本地缓存年龄；跨设备墙钟偏差存在，不能直接把时间戳相减称为端到端网络时延 |
| 正式服务隔离 | 公网测试独立目录、数据、账号库、进程、Node 24 及 10443 监听；测试 TLS health 200；正式 443 health 200、正式服务与 Nginx active，生产 PID 未变 | 正式客户端及数据未升级；测试结果不是正式业务部署结果 |

本轮无凭据取证集中在 `.local/account-media-e2e/`：`local-chain.json`、`local-before-background.json`、`local-after-background.json`、`local-abnormal-stop.json`、`public-api.json`、`public-chain-final.json`、`public-normal-stop.json`、`public-final-health.json`、`public-final-wpf-ui.json`、`device-unbind-contract.json`、`final-android-artifacts.json` 及 `camera-public/`、`console-public/`、`notifier-local/`、`notifier-public/`。旧待验收素材保留在 `.local/unified-access-verification/`、`.local/group2-verification/`、`.local/owner-acceptance/`；不重新把旧协议或拆分前 Vigil 结果记为新组件验证。

## 测试环境

公网测试入口为 `https://visionguard.xgwnje.cn:10443/console/`，服务地址为 `https://visionguard.xgwnje.cn:10443`；本机独立入口与启动方式见[运维文档](60-operations.md#本机隔离测试)。`vg-test` 用于完整链路，`vg-isolation` 用于隔离检查。随机密码仅在被忽略 `.local/e2e-server/account-stream-e2e/test-accounts.json`，不公开展示。

VPS 测试根目录 `/opt/visionguard-account-test`，systemd 单元 `visionguard-account-test.service`；只开放独立 10443，只读复用现有证书，不修改正式代理。测试服务保持可供后续验收使用；不是正式发版。Windows 测试账号/配置、模型及 IPC 使用独立目录，不改原自启；未修改锁屏、休眠或唤醒设置。

本轮 WPF、独立驻留、两台模拟器与本机 3100 服务均已关闭；浏览器测试会话已注销，公网测试服务保持运行且 health 为 200，正式服务 health 为 200、PID 669 未变。客户端登录与历史保留在私有测试目录或模拟器数据中。收尾记录为 `.local/account-media-e2e/cleanup.json`；测试入口与最终三端 APK 集中在 `.local/account-media-e2e/acceptance/README.md`。这些关闭与构建记录不等于人工验收通过。

## 待人工/真机验收与未覆盖项

- 新通知节点与原 Vigil 并存安装、系统浅深色、大字号、连接设置、历史/铃声库和旋转，仍需真实设备目检。
- 新节点后台接警、有限次数、手动确认、队列恢复与服务中断，仍需真实设备闭环；模拟器局部通过不代替 owner 验收，真机测试前由 owner 确认闹钟流静音。
- 北京时间/UTC 切换后的跨端列表、详情、弹窗与重连恢复，仍待 owner 验收；统一设置只改变显示时区，不校准设备系统时钟。
- 真实 Release WPF → 服务 → 新通知节点的 owner 完整报警链验收、连续运行、采集/网络/服务故障恢复与厂商后台限制继续保留；本轮模拟器闭环只作为新增证据。
- 新账号登录/退出/改密、设备命名与解绑、多视觉节点选择、账号切换后缓存隔离，需 owner 按真实使用习惯验收。
- 镜头真机效果、720P 实际采集、低亮度/收预览耗电与发热、后台/锁屏停止及手动恢复，仍待真实手机验收；模拟器不能证明省电效果。
- 首次摄像头权限返回后仍需再点一次开始；已在测试入口说明。控制台正常 UI 退出被既有旧包浮窗遮挡，本轮未计通过，仅停止本轮应用；旧应用与其通知未修改。
- Win7、其他 GPU/DPI、Android 真机及生产验证单独安排；第 3 组硬件未准备，物理硬件驱动未实施。
- 新通知节点正式发布流水线与发行更新仍未验收；本轮安装仅为隔离模拟器测试，没有正式上传、发布，不从 Vigil 获取更新。
- 命名固化后的镜头服务提示、控制台节点空态和视觉节点启停文案，以及 Windows“后台驻留”分区，尚未运行目检；继续留待 owner 集中验收。
