# Project Overview

VisionGuard 当前是由视觉探测器、Server 和接收端组成的 AI 实时监控系统；长期定位是可部署、可扩展、可运营的边缘智能探测平台。

## 产品定位

VisionGuard 的核心价值不是绑定某个视觉模型，而是完成一条可验证的闭环：

`现场感知 -> 本地判断 -> 证据生成 -> 可靠送达 -> 用户处置 -> 设备运维`

长期产品由三部分组成：

1. **Detector Platform**：统一接入视觉、毫米波、PIR、门磁、振动和环境传感器，在边缘完成推理、融合与事件生成。
2. **Reliable Event Network**：所有公网业务数据统一通过 Server，并使用 HTTPS/WSS `443` 加密传输；Server 负责可靠投递、权威事件入库、设备在线感知和离线兜底，不再规划 P2P、ICE、STUN 或 TURN。
3. **Device & Fleet Cloud**：Server 与 Web 控制台是完整交付物和系统权威控制面，管理账号、租户、场地、探测器、传感器、事件、配置、模型、OTA、调试、审计与运行状态。

视觉检测仍是首要能力和重要证据来源，但不再等同于产品本身。新增检测能力应接入统一观察值、融合规则和报警事件模型，而不是为每种传感器复制一套 Server 和接收端协议。

产品对象统一定义为：

- **Detector**：所有能够产生标准 Observation / AlertEvent 的探测器总称；
- **Visual Detector**：现有 WinForms、WPF 和 Android 检测端，均是正式视觉探测器，不是临时客户端；
- **Edge Detector**：未来 Linux ARM64 开发板探测器，可同时接入视觉和非视觉传感器，并可启用 Gateway 能力；
- **Receiver**：接收、解密、展示和处置报警的终端；
- **Web Management Console**：管理场地、探测器、传感器、配置、模型、OTA 和审计的 Server Web 控制台。

商业分层只按“是否接入检测硬件探测器”判断：目前已实现的纯软件视觉方案是免费版；系统一旦注册并启用 Edge Detector 或其他检测硬件探测器，即进入付费版。免费与付费共用同一套协议、Server、Receiver 和 Web 控制台底座，不把高级纯软件视觉功能单独定义成第三个产品层。

当前主线采用 `VGSAL-1.0` 源码可见与商业双授权模式，不再是开源 MIT 主线。纯软件视觉方案允许免费内部使用；硬件探测器接入、再分发和对外托管需要书面商业授权。历史 MIT 边界和第三方许可证责任由根目录 `LICENSE-HISTORY.md` 维护。

商业化路线、阶段顺序和验收闸门由 [15-product-roadmap.md](15-product-roadmap.md) 维护。

## 架构

- 检测端：Windows WinForms、Windows WPF、Android Detector
- 中继端：`server/`
- 接收端：`receiver/android/`

当前 4.x 的主数据链路是 Server WebSocket 中继；账号租户、Server 权威事件存储、Server 生成设备离线报警、Linux 开发板探测单元和多传感器融合均属于未来规划，不能写成已实现能力。

## 长期端角色

| 角色 | 定位 | 兼容边界 |
|---|---|---|
| WinForms Visual Detector | Windows 主力兼容视觉探测器 | 保持 .NET Framework 4.7.2 与 Win7 SP1 x64；新网络能力通过窄 C ABI 原生模块隔离 |
| WPF Visual Detector | 现代 Windows 视觉探测器 | 面向 Win10+，可使用较新运行时，但必须遵守统一探测器与事件协议 |
| Android Visual Detector | 移动视觉探测器 | 继续承担 CameraX 场景，也作为移动探测器参与统一设备体系 |
| Edge Detector | 未来核心开发板探测器 | Linux ARM64 开发板或工业 SOM；负责多传感器采集、融合、推理、本地队列与设备运维 |
| Android Receiver | 首要报警接收与处置端 | 通过前台连接与系统推送协同，不能把永久在线连接当作唯一唤醒机制 |
| VisionGuard Cloud | 控制面、权威数据面与可靠兜底 | 账号租户、设备目录、信令、中继、事件与证据存储、模型与更新分发 |
| Web Management Console | 系统最高权限管理中控 | 运行于 Server Web 端；管理整套系统、探测器、传感器和数据，不承担现场实时检测 |

## 仓库定位

- `AGENTS.md` 是顶层约束，不属于待清理的旧说明
- `docs/codex/` 是当前唯一维护的解释性文档集合
- `.agents/skills/` 是当前项目级 Agent 技能入口
- 历史 `CLAUDE.md`、`.claude/`、`.Codex/agents/`、模块内旧说明文档已迁移后清理，不再作为事实来源

## 目录职责

| 目录 | 职责 |
|---|---|
| `detector/windows-winforms/` | WinForms 主力检测端，面向 Win7+ |
| `detector/windows-wpf/` | WPF 桌面视觉升级线，面向 Win10+ |
| `detector/android/` | Android CameraX 检测端 |
| `receiver/android/` | Android Compose 接收端 |
| `server/` | HTTP / WebSocket 中继服务 |
| `scripts/` | 版本、构建、发行、发布和模型导出脚本 |
| `tests/` | 跨模块约束测试 |
| `.agents/skills/` | 当前项目级 Agent 技能 |
| `docs/codex/` | 已验真的项目事实文档 |
| `docs/design/` | 当前设计规范入口，不保存历史探索素材 |
| `icon/` | 当前应用图标素材 |

`artifacts/`、`models/`、`server/data/releases/`、`server/data/models/`、各端 `bin/`、`obj/`、`build/`、`.gradle/`、`.vs/`、`node_modules/` 是本地生成或缓存目录，不作为源码结构维护。

## 核心链路

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

## 统一概念

- 遮罩使用相对坐标 `[0,1]`
- 遮罩在推理前涂黑，同时影响识别结果与告警截图
- WS 角色固定为 `windows`、`android`、`android-detector`
- VisionGuard 正式服务域名固定为 `https://visionguard.xgwnje.cn`
- 根域 `https://xgwnje.cn` 属于个人主页，不再作为新客户端的 VisionGuard 服务地址

## 不变边界

- `VERSION` 是权威版本源，不能自动 bump
- `server/` 和 Android 端是强耦合，协议变更必须联动核对
- 心跳策略按当前实现固定：检测端 3s、接收端 30s、幽灵阈值 45s
- 客户端 `SERVER_URL`、自动更新地址和 Nginx 部署目标必须保持同一项目子域名
- Win7 只作为 WinForms Visual Detector 的兼容边界；WPF、Android、Linux Edge Detector、Server 和 Web 控制台均不承担 Win7 兼容义务
- 报警协议必须与具体传输解耦；WSS、HTTPS 补发和 Receiver 系统推送共享事件 ID、ACK、重试和幂等语义，所有公网业务数据统一通过 Server 并进入权威数据存储
- 允许一定误报，漏报风险是检测效果与故障处置的最高优先级；设备、传感器、推理或网络故障不能静默形成探测盲区
- Server 根据心跳状态生成设备离线报警和恢复事件；Server 自身故障由独立外部监控通道发现
- 新传感器先输出统一观察值，再由融合层产生报警；不得让传感器适配器直接耦合 Server 或接收端 UI

## 文档可信度规则

- 先信源码，再信 `docs/codex/`
- 不信历史说明里的分支名、时间戳、手工维护版本号
- 涉及模型、资源、类目映射时，以项目文件和源码内静态表为准
