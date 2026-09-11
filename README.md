<div align="center">
  <img src="./icon/visionguard-windows.png" alt="VisionGuard" width="120">
  <h1>VisionGuard</h1>
  <p>面向 Windows 与 Android 的 AI 实时监控与报警系统。</p>

  [![Version](https://img.shields.io/badge/version-4.4.4-1f6feb)](./VERSION)
  [![License](https://img.shields.io/badge/license-VGSAL--1.0-7c3aed)](./LICENSE)
  [![Docs](https://img.shields.io/badge/docs-verified-f59e0b)](./docs/codex/00-index.md)
</div>

VisionGuard 由本地视觉检测端、Server、Android 接收端和 Windows 驻留程序组成。检测在设备本地完成，报警、截图、模型和客户端更新通过 Server 统一传输与分发。

## 当前组件

| 组件 | 技术栈 | 入口 |
|---|---|---|
| Windows WinForms 检测端 | .NET Framework 4.7.2 / WinForms | [`detector/windows-winforms/`](./detector/windows-winforms/) |
| Windows WPF 检测端 | .NET 9 / WPF / MVVM | [`detector/windows-wpf/`](./detector/windows-wpf/) |
| Windows 驻留程序 | .NET 9 / 当前用户后台进程 | [`detector/windows-resident/`](./detector/windows-resident/) |
| Android 检测端 | Kotlin / CameraX / ONNX Runtime | [`detector/android/`](./detector/android/) |
| Android 接收端 | Kotlin / Jetpack Compose / OkHttp | [`receiver/android/`](./receiver/android/) |
| Server | Node.js / TypeScript / Express / WebSocket | [`server/`](./server/) |

当前 4.x 的正式数据链路为：

```text
Windows / Android Visual Detector ──报警、状态──▶ VisionGuard Server ──报警、控制──▶ Android Receiver
Windows Resident ───────────────生命周期状态、控制──────────────▶ VisionGuard Server
```

Windows 驻留程序只负责受控的主程序打开/关闭与运行状态，不会因远程打开而自动开始监控。Server 当前维护连接、状态、告警和截图/更新路由；路线图中的权威离线报警、Web 管理控制台、可靠 outbox 和硬件探测器仍是未来能力，不应写成当前已交付。当前连接离线状态不等于 `DeviceOfflineAlert` 已生成或送达。

当前 Win7 兼容实现仅由 Windows WinForms 检测端承担；Windows 驻留程序当前尚未兼容 Win7，但路线图要求其后续交付前必须补齐 Win7 SP1 x64 兼容；所有公网业务数据统一通过 Server，当前不再规划 P2P。

组件当前实现状态与验证边界见[项目概览](./docs/codex/10-project-overview.md)，产品方向与阶段验收见[产品路线图](./docs/codex/15-product-roadmap.md)。

## 常用入口

本地验证 Server：

```powershell
cd server
npm ci
npm test
npm run build
```

WPF 四路真实窗口人员检测 smoke：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\test-wpf-person-detection.ps1
```

该工具打开四个独立可见浏览器窗口，经 `WindowHandle` 捕获并要求每路实际产生 `person` 检测且达到 2.5 FPS；同时断言停一路、单路重配、CPU 并行拒绝和 DirectML 回退。漏报风险仍是检测效果与故障处置的最高优先级。它不替代动态视频、完整报警链或 UI 目检。构建、环境发现、设备 smoke 和证据边界见[运维文档](./docs/codex/60-operations.md)。

## 产品与文档

目前已实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。当前主线采用 [VGSAL-1.0](./LICENSE)，这是源码可见许可证，不是开源许可证；再分发、对外托管或产品集成需要[商业授权](./COMMERCIAL-LICENSE.md)。历史 MIT 授权边界见 [LICENSE-HISTORY.md](./LICENSE-HISTORY.md) 和 [LICENSE-MIT](./LICENSE-MIT)。

- [项目文档索引](./docs/codex/00-index.md)
- [设计规范](./docs/design/README.md)
- [贡献说明](./CONTRIBUTING.md)

正式服务地址：`https://visionguard.xgwnje.cn`
