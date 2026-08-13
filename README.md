<div align="center">
  <img src="./icon/visionguard-windows.png" alt="VisionGuard" width="120">
  <h1>VisionGuard</h1>
  <p>面向 Windows 与 Android 的 AI 实时监控与报警系统。</p>

  [![Version](https://img.shields.io/badge/version-4.4.4-1f6feb)](./VERSION)
  [![License](https://img.shields.io/badge/license-VGSAL--1.0-7c3aed)](./LICENSE)
  [![Docs](https://img.shields.io/badge/docs-verified-f59e0b)](./docs/codex/00-index.md)
</div>

VisionGuard 由本地视觉探测器、Server 和 Android 接收端组成。检测在设备本地完成，报警、截图、模型和客户端更新通过 Server 统一传输与分发。

## 架构

```text
Windows / Android Visual Detector
                │
                ▼
       VisionGuard Server
                │
                ▼
         Android Receiver
```

| 组件 | 技术栈 | 入口 |
|---|---|---|
| Windows WinForms 探测器 | .NET Framework 4.7.2 | [`detector/windows-winforms/`](./detector/windows-winforms/) |
| Windows WPF 探测器 | .NET 9 / WPF | [`detector/windows-wpf/`](./detector/windows-wpf/) |
| Android 探测器 | Kotlin / CameraX / ONNX Runtime | [`detector/android/`](./detector/android/) |
| Server | Node.js / TypeScript / Express / WebSocket | [`server/`](./server/) |
| Android 接收端 | Kotlin / Jetpack Compose / OkHttp | [`receiver/android/`](./receiver/android/) |

所有公网业务数据统一通过 Server，不再规划 P2P。Win7 仅由 WinForms Visual Detector 兼容。产品允许可管理的误报，但漏报风险是最高优先级；Server 负责设备状态感知与离线报警。完整架构与实现边界见[项目概览](./docs/codex/10-project-overview.md)。

## 快速开始

本地验证 Server：

```powershell
cd server
npm ci
npm test
npm run build
```

Server 需要通过本地 `.env` 或部署环境提供 `API_KEY`，不要提交真实密钥。各客户端的构建、配置和验证方式见[运维文档](./docs/codex/60-operations.md)。

## 产品与文档

目前已实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。详细产品方向和阶段计划见[产品路线图](./docs/codex/15-product-roadmap.md)。

- [项目文档索引](./docs/codex/00-index.md)
- [设计规范](./docs/design/README.md)
- [贡献说明](./CONTRIBUTING.md)

正式服务地址：`https://visionguard.xgwnje.cn`

## 许可证

当前主线采用 [VisionGuard Source Available License 1.0](./LICENSE)（`VGSAL-1.0`）。这是源码可见许可证，不是开源许可证。

纯软件视觉方案允许免费内部使用；硬件探测器接入、再分发、对外托管或产品集成需要[商业授权](./COMMERCIAL-LICENSE.md)。历史 MIT 授权边界见 [LICENSE-HISTORY.md](./LICENSE-HISTORY.md) 和 [LICENSE-MIT](./LICENSE-MIT)。
