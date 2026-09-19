<div align="center">
  <img src="./icon/visionguard-windows.png" alt="VisionGuard" width="120">
  <h1>VisionGuard</h1>
  <p>面向 Windows 与 Android 的 AI 实时监控与报警系统。</p>

  [![Version](https://img.shields.io/badge/version-4.5.0-1f6feb)](./VERSION)
  [![License](https://img.shields.io/badge/license-VGSAL--1.0-7c3aed)](./LICENSE)
  [![Docs](https://img.shields.io/badge/docs-verified-f59e0b)](./docs/codex/00-index.md)
</div>

VisionGuard 由本地视觉检测端、Server、Android 接收端和 Windows 驻留程序组成。检测在设备本地完成，报警、截图、模型和客户端更新通过 Server 统一传输与分发。

## 当前组件

| 组件 | 技术栈 | 入口 |
|---|---|---|
| Windows 检测端 | .NET Framework 4.7.2 / WPF / MVVM | [`detector/windows-wpf/`](./detector/windows-wpf/) |
| Windows 驻留程序 | .NET Framework 4.7.2 x64 / 当前用户后台进程 | [`detector/windows-resident/`](./detector/windows-resident/) |
| Android 检测端 | Kotlin / CameraX / ONNX Runtime | [`detector/android/`](./detector/android/) |
| Android 接收端 | Kotlin / Jetpack Compose / OkHttp | [`receiver/android/`](./receiver/android/) |
| Server | Node.js / TypeScript / Express / WebSocket | [`server/`](./server/) |

当前 4.x 的正式数据链路为：

```text
Windows / Android Visual Detector ──报警、状态──▶ VisionGuard Server ──报警、控制──▶ Android Receiver
Windows Resident ───────────────生命周期状态、控制──────────────▶ VisionGuard Server
```

Windows 驻留程序只负责受控的主程序打开/关闭与运行状态，不会因远程打开而自动开始监控。Server 当前维护连接、状态、告警和截图/更新路由；路线图中的权威离线报警、Web 管理控制台、可靠 outbox 和硬件探测器仍是未来能力，不应写成当前已交付。当前连接离线状态不等于 `DeviceOfflineAlert` 已生成或送达。

Windows 检测端只有一份 net472 WPF 构建，Win7 与 Win10/11 的差异收敛为两个推理档位：Win7 SP1 x64 用 legacy 档（原生 ONNX Runtime 1.1.0 + 纯 CPU + YOLOv5），Win10/11 用 modern 档（原生 1.19.0 + DirectML + YOLO26），启动时由 `Runtime/NativeLibrarySelector.cs` 自动判定。Windows 驻留程序当前尚未兼容 Win7，但路线图要求其后续交付前必须补齐 Win7 SP1 x64 兼容；所有公网业务数据统一通过 Server，当前不再规划 P2P。

WinForms 检测端已退役，Windows 只剩上述单一 WPF 构建与两个推理档位，依据见[产品路线图 8.13](./docs/codex/15-product-roadmap.md#813-windows-检测端统一v102026-09-16-确认)。

组件当前实现状态与验证边界见[项目概览](./docs/codex/10-project-overview.md)，产品方向与阶段验收见[产品路线图](./docs/codex/15-product-roadmap.md)。

## 常用入口

本地验证 Server：

```powershell
cd server
npm ci
npm test
npm run build
```

Windows 检测端的 Release 编译与产物核验：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target WPF
```

该入口按 `OrtProfile` 构建 modern（默认，输出 `detector/windows-wpf/bin/x64/modern/`）与 legacy（`-p:OrtProfile=legacy`，输出 `detector/windows-wpf/bin/x64/legacy/`）两个档位，只编译并检查产物。人员检测 smoke 以 COCO 的 `person` 类标签取证：要求每个来源窗口在捕获到的帧里实际命中该标签并达到约定的帧率，而不是只看进程起没起来。人员检测与设备 smoke、隔离 Server 链路和各项证据边界见[运维文档](./docs/codex/60-operations.md)，逐项验证结论见[验证报告](./docs/codex/90-verification-report.md)。漏报风险仍是检测效果与故障处置的最高优先级。这些验证都不替代动态视频、完整报警链或 UI 目检。

## 产品与文档

目前已实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。当前主线采用 [VGSAL-1.0](./LICENSE)，这是源码可见许可证，不是开源许可证；再分发、对外托管或产品集成需要[商业授权](./COMMERCIAL-LICENSE.md)。历史 MIT 授权边界见 [LICENSE-HISTORY.md](./LICENSE-HISTORY.md) 和 [LICENSE-MIT](./LICENSE-MIT)。

- [项目文档索引](./docs/codex/00-index.md)
- [设计规范](./docs/design/README.md)
- [贡献说明](./CONTRIBUTING.md)

正式服务地址：`https://visionguard.xgwnje.cn`
