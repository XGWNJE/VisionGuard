# Project Overview

本文维护 VisionGuard 的当前项目地图、组件命名和实现边界。产品方向、阶段顺序和验收闸门只在[产品路线图](15-product-roadmap.md)维护；逐项验证证据只在[验证报告](90-verification-report.md)维护。

## 产品定位

VisionGuard 当前是由视觉检测端、Server、Android 接收端和 Windows 驻留程序组成的 AI 实时监控系统；长期定位是可部署、可扩展、可运营的边缘智能探测平台。

产品闭环为：

`现场感知 -> 本地判断 -> 证据生成 -> 可靠送达 -> 用户处置 -> 设备运维`

长期产品方向包括 Detector Platform、Reliable Event Network 和 Device & Fleet Cloud。视觉检测是当前首要能力，但不是未来产品定义的全部。

产品对象统一定义如下：

- **Detector**：能够产生标准 Observation / AlertEvent 的探测器总称。
- **Visual Detector**：现有 Windows WPF 和 Android 检测端。
- **Edge Detector**：未来 Linux ARM64 开发板探测器，可接入视觉和非视觉传感器。
- **Receiver**：接收、展示和处置报警的终端。
- **Web Management Console**：未来的系统管理控制面；当前仓库还没有独立 Web 控制台实现。

商业分层、硬件探测器方向和长期网络约束由[产品路线图](15-product-roadmap.md)维护。当前已实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。当前主线使用 `VGSAL-1.0`；历史 MIT 边界由根目录 `LICENSE-HISTORY.md` 维护。WinForms 检测端已退役，Windows 只剩 `detector/windows-wpf/` 的单一 WPF 构建与两个推理档位（见[路线图 8.13](15-product-roadmap.md)）。

## 当前实际组件与验证状态

仓库当前维护五个实际组件。状态词的含义是：`主体实现` 表示源码和局部功能存在；`自动验证` 只表示对应证据通过；`待人工/真机` 不表示失败，也不表示已交付。

| 规范名称 | 路径 | 当前状态 | 自动验证边界 |
|---|---|---|---|
| Windows 检测端（WPF Visual Detector） | `detector/windows-wpf/` | 多来源主体实现；来源数量按 Server 协商上限生成；**net472 单一构建，按 `OrtProfile` 产出 legacy（Win7 SP1 x64：CPU + 原生 1.1.0 + YOLOv5）与 modern（Win10/11：DirectML + 原生 1.19.0 + YOLO26）两个推理档位** | 两档位各自 Release 编译 0 警告 0 错误、原生库档位路径与 UI 档位文案已取证；legacy 档与服务端 `● 已连接`、隔离 Server 下的驻留拉起与 `open-detector` 重新拉起链路已取证；动态视频、多路帧率、完整报警链和 Win7 真机验收仍待独立验证 |
| Windows 驻留程序（Windows Resident） | `detector/windows-resident/` | .NET Framework 4.7.2 x64 后台进程；独立 WS 身份、单实例和进程握手已接入 | 框架/API 已对齐 Win7 SP1 x64；Win7 实机、重启、崩溃、完整远控链路待验收 |
| Android 检测端（Android Visual Detector） | `detector/android/` | 主体实现；**当前暂缓**（[路线图决策 19](15-product-roadmap.md)） | 单测、构建和历史启动证据可分别报告；当前协议下缺少 `channel` 无法认证、且没有来源维度；恢复实施时先统一三端语义 |
| Android 接收端（Android Receiver） | `receiver/android/` | 主体实现；设备/来源 UI 已接入 | JVM 单测、构建和历史启动证据可分别报告；完整报警链待补 |
| Server | `server/` | 当前 4.x 中继实现 | TypeScript 构建、单测和协议测试可自动验证；生产状态需独立核验 |

### 当前链路

```text
Windows / Android Visual Detector ──报警、状态──▶ VisionGuard Server ──报警、控制──▶ Android Receiver
Windows Resident ───────────────生命周期状态、控制──────────────▶ VisionGuard Server
```

Server 当前实现连接认证、心跳、告警广播、截图/更新路由和设备在线状态；它尚未生成路线图定义的 `DeviceOfflineAlert`，也没有权威多租户事件库或独立 Web 管理控制台。上述能力属于未来路线，不能把连接列表中的离线状态写成离线报警已交付。

## 目录职责

| 目录 | 职责 |
|---|---|
| `detector/windows-wpf/` | Windows 检测端（WPF，net472 单一构建，按 `OrtProfile` 产出 legacy 与 modern 两个推理档位） |
| `detector/windows-resident/` | Windows 驻留程序 |
| `detector/windows-shared/` | Windows 检测端与驻留程序共用的进程/身份代码 |
| `detector/windows-wpf-smoke/` | 人员检测 smoke 工具，按配置的来源数量取证；用独立 net472 窗口承载来源，legacy 档因此也能在 Win7 上运行 |
| `detector/android/` | Android 检测端 |
| `receiver/android/` | Android 接收端 |
| `server/` | HTTP / WebSocket 中继服务 |
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
- Server WS 角色当前为 `windows`、`android`、`android-detector` 和 `windows-resident`；其中 `windows` 代表 Windows 检测端（Windows 只剩一个检测端），`android` 代表 Android 接收端。
- 正式服务域名为 `https://visionguard.xgwnje.cn`；根域 `https://xgwnje.cn` 不是新客户端的 VisionGuard 服务地址。
- `VERSION` 是唯一权威版本源，构建、修复和提交不得自动 bump。
- `server/` 与 Android 端协议强耦合；协议变化必须联动源码、测试和专题文档。
- 当前心跳实现为检测端 3 秒、接收端 30 秒、Server 幽灵阈值 45 秒；这只代表在线状态判定，不代表离线报警已经实现。
- 当前 Win7 兼容实现属于 Windows WPF 检测端的 legacy 档位（V10，见[路线图 8.13](15-product-roadmap.md)）：同一份 WPF 源码在 net472 上构建，按运行环境选择推理档位——Win7 用 CPU + ONNX Runtime 原生 1.1.0 + YOLOv5，Windows 10 及以上用 DirectML + 1.19 + YOLO26。V10 已把 WinForms 检测端退役并删除其工程与验证入口，Windows 只剩这一份构建；Windows 驻留程序的目标框架同为 .NET Framework 4.7.2 x64；Android、Server 和未来硬件不承担 Win7 兼容义务。
- 所有公网业务数据统一通过 Server 中继；路线图不再规划 P2P、ICE、STUN 或 TURN。
- 允许可管理的误报，漏报风险是检测效果与故障处置的最高优先级。

## 文档可信度

先信源码、构建产物和测试结果，再信当前 `docs/codex/`。不把历史方案、手工版本号、截图或帧循环当成功能验收证据。模型、资源和类目映射以对应项目文件和源码静态表为准。
