# Project Overview

本文维护 VisionGuard 的当前项目地图、组件命名和实现边界。各模块实现见对应专题；逐项验证证据只在[验证报告](90-verification-report.md)维护。

## 产品定位

VisionGuard 当前由视觉检测、视觉中继、视觉告警和视觉驻留组成：检测端生成告警与截图，视觉中继转发，接收端展示结果。

当前已实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。当前主线使用 `VGSAL-1.0`；历史 MIT 边界由根目录 `LICENSE-HISTORY.md` 维护。WinForms 检测端已退役；Windows 对外只交付一个统一包，由 Win7 兼容启动器按系统选择两个内部 WPF 推理运行时。

## 当前实际组件与验证状态

仓库当前维护五个实际组件。状态词的含义是：`主体实现` 表示源码和局部功能存在；`自动验证` 只表示对应证据通过；`待人工/真机` 不表示失败，也不表示已交付。

| 规范名称 | 平台 | 路径 | 当前状态 | 自动验证边界 |
|---|---|---|---|---|
| 视觉检测 | Windows | `detector/windows-launcher/`、`detector/windows-wpf/` | 多来源主体实现；**单一对外包与入口**，net472 启动器在 Win7 选择 legacy（CPU + 原生 1.1.0 + YOLOv5）、在 Win10/11 选择 modern（DirectML + 原生 1.19.0 + YOLO26），并负责校验、目录切换与失败回滚的整包更新 | 启动器、两套内部运行时和驻留程序 Release 编译通过；统一目录、运行时选择与必要文件校验已自动验证；真实跨版本更新、Win7 启动器与故障回滚仍待实机验证 |
| 视觉驻留 | Windows | `detector/windows-resident/` | .NET Framework 4.7.2 x64 后台进程；独立 WS 身份、单实例和进程握手已接入 | 框架/API 已对齐 Win7 SP1 x64；Win7 实机、重启、崩溃、完整远控链路待验收 |
| 视觉检测 | Android | `detector/android/` | 主体实现；**当前暂缓** | 单测、构建和启动检查可分别报告；当前协议下缺少 `channel` 无法认证、且没有来源维度 |
| 视觉告警 | Android | `receiver/android/` | 主体实现；设备/来源 UI 已接入 | JVM 单测、构建和启动检查可分别报告；完整报警链待补 |
| 视觉中继 | 服务端 | `server/` | 当前 4.x 中继实现 | TypeScript 构建、单测和协议测试可自动验证；生产状态需独立核验 |

### 当前链路

```text
视觉检测（Windows） ──报警、状态──▶ 视觉中继 ──报警、控制──▶ 视觉告警
视觉驻留 ──生命周期状态、控制──▶ 视觉中继
```

视觉检测（Android）源码保留但当前暂缓，缺少通道字段，不能把它画成当前协议下可用的链路。

## 组件名称与技术标识

应用显示名统一为四字中文；文档中的括号只用于区分平台。工程标识采用 `VisionGuard.<角色>.<平台>`，Android 包名采用 `com.xgwnje.visionguard.<角色>`，npm 包名采用 `visionguard-<角色>`。协议角色、更新平台、进程握手和本地配置标识以现有契约为准。

| 组件名称 | 工程标识 | 技术标识 |
|---|---|---|
| 视觉检测（Windows） | `VisionGuard.Detector.Windows`；启动器 `VisionGuard.Detector.Windows.Launcher` | WS 角色 `windows`；更新平台 `wpf`；运行时 `modern` / `legacy` |
| 视觉驻留 | `VisionGuard.Resident.Windows` | WS 角色 `windows-resident`；程序 `VisionGuard.Resident.Windows.exe` |
| 视觉检测（Android） | `VisionGuard.Detector.Android` | WS 角色/更新平台 `android-detector`；包名 `com.xgwnje.visionguard.detector` |
| 视觉告警 | `VisionGuard.Receiver.Android` | WS 角色 `android`；更新平台 `android-receiver`；包名 `com.xgwnje.visionguard.receiver` |
| 视觉中继 | `VisionGuard.Relay` | 源码目录 `server/`；构建目标 `Server`；npm 包名 `visionguard-relay` |

视觉中继当前实现连接认证、心跳、告警广播、截图/更新路由和设备在线状态；连接列表中的离线状态不等于独立的设备离线报警。完整报警链的覆盖边界见[验证报告](90-verification-report.md)。

## 目录职责

| 目录 | 职责 |
|---|---|
| `detector/windows-launcher/` | 视觉检测（Windows）统一入口与整包更新器（net472 / Win7+） |
| `detector/windows-wpf/` | 视觉检测（Windows）两套内部 WPF 运行时源码 |
| `detector/windows-package/` | 构建生成的统一 Windows 目录；不作为独立源码组件 |
| `detector/windows-resident/` | 视觉驻留 |
| `detector/windows-shared/` | 视觉检测（Windows）与驻留程序共用的进程/身份代码 |
| `detector/windows-wpf-smoke/` | 人员检测 smoke 工具，按配置的来源数量取证；用独立 net472 窗口承载来源，legacy 档因此也能在 Win7 上运行 |
| `detector/android/` | 视觉检测（Android） |
| `receiver/android/` | 视觉告警 |
| `server/` | HTTP / WebSocket 视觉中继 |
| `scripts/` | 版本、构建、验证、发行和模型导出脚本 |
| `tests/` | 跨模块约束、WPF 报警链与推理辅助探针 |
| `.agents/skills/` | 三个需要脚本化或授权边界的项目级 Skill |
| `docs/codex/` | 项目事实、操作和验证文档 |
| `docs/design/` | 当前设计规范入口 |
| `icon/` | 当前应用图标素材 |

`artifacts/`、`server/data/releases/`、`server/data/models/`、各端 `bin/`、`obj/`、`build/`、`.gradle/` 和 `node_modules/` 是本地生成或缓存目录，不是源码结构。

## 统一概念与不变边界

- 推理链：`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`。
- 遮罩使用相对坐标 `[0,1]`，推理前涂黑，同时影响识别结果和报警截图。
- 视觉中继 WS 角色当前为 `windows`、`android`、`android-detector` 和 `windows-resident`；其中 `windows` 代表 视觉检测（Windows）（Windows 只剩一个检测端），`android` 代表 视觉告警。
- 正式服务域名为 `https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端的 VisionGuard 服务地址。
- `VERSION` 是唯一权威版本源，构建、修复和提交不得自动 bump。
- `server/` 与 Android 端协议强耦合；协议变化必须联动源码、测试和专题文档。
- 当前心跳实现为检测端 3 秒、接收端 30 秒、视觉中继幽灵阈值 45 秒；这只代表在线状态判定，不代表离线报警已经实现。
- 当前 Win7 兼容实现属于 视觉检测（Windows）的 legacy 档位：同一份 WPF 源码在 net472 上构建，按运行环境选择推理档位——Win7 用 CPU + ONNX Runtime 原生 1.1.0 + YOLOv5，Windows 10 及以上用 DirectML + 1.19 + YOLO26。已把 WinForms 检测端退役并删除其工程与验证入口，Windows 只剩这一份构建；视觉驻留的目标框架同为 .NET Framework 4.7.2 x64；Android 和视觉中继不承担 Win7 兼容义务。
- 所有公网业务数据统一通过视觉中继转发；不使用 P2P、ICE、STUN 或 TURN。
- 允许可管理的误报，漏报风险是检测效果与故障处置的最高优先级。

## 文档可信度

先核对源码、构建产物和测试结果，再维护当前 `docs/codex/`。截图或帧循环不能替代功能验收。模型、资源和类目映射以对应项目文件和源码静态表为准。
