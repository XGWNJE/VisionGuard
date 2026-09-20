<div align="center">
  <img src="./icon/visionguard-windows.png" alt="VisionGuard" width="120">
  <h1>VisionGuard</h1>
  <p>本地 AI 视觉检测、实时告警与移动端接收。</p>

  [![Version](https://img.shields.io/badge/version-4.5.1-1f6feb)](./VERSION)
  [![License](https://img.shields.io/badge/license-VGSAL--1.0-7c3aed)](./LICENSE)
  [![Docs](https://img.shields.io/badge/docs-verified-f59e0b)](./docs/codex/00-index.md)
</div>

VisionGuard 在采集设备本地完成 Visual Detector 推理，把告警、截图、状态、模型和更新统一经 Server 转发给 Android 接收端。正式服务地址：`https://visionguard.xgwnje.cn`。

## 当前组件

| 组件 | 当前状态 | 入口 |
|---|---|---|
| Windows 检测端 | 已发布；WPF 单一构建，modern / legacy 两个推理档位 | [`detector/windows-wpf/`](./detector/windows-wpf/) |
| Windows 驻留程序 | 已发布；随 Windows 包分发，负责受控打开、关闭与驻留状态 | [`detector/windows-resident/`](./detector/windows-resident/) |
| Android 检测端 | 当前暂缓；4.5.1 不发布，更新保持在已验证的 4.4.4 | [`detector/android/`](./detector/android/) |
| Android 接收端 | 已发布；接收告警、截图与设备状态 | [`receiver/android/`](./receiver/android/) |
| Server | 已发布；HTTP / WebSocket 中继、模型与更新路由 | [`server/`](./server/) |

## 实时链路

```text
Windows Visual Detector ── 告警、截图、状态 ──▶ VisionGuard Server ── 告警、控制 ──▶ Android Receiver
Windows Resident ───────── 生命周期状态、控制 ───────────────▶ VisionGuard Server
```

- 推理链：`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`。
- Windows 来源预览与模型输入都等比缩放、黑边填充；不会拉伸画面。当前人员检测验证以 `person` 类为准。
- Win7 SP1 x64 使用 WPF legacy（CPU + YOLOv5），Windows 10/11 使用 WPF modern（DirectML + YOLO26）。
- 所有公网业务数据统一通过 Server，不再规划 P2P、ICE、STUN 或 TURN。

连接离线只表示连接状态；它不等于已产生或送达离线报警，`DeviceOfflineAlert` 仍是未来能力。允许在可管理范围内误报，漏报风险是检测效果与故障处置的最高优先级。

## 快速开始

验证 Server：

```powershell
cd server
npm ci
npm test
npm run build
```

构建 Windows 检测端的两个 Release 推理档位：

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target WPF
```

检查文档、版本、组件入口和发布契约：

```powershell
node scripts/check-docs.js
node --test scripts/check-docs.test.js scripts/release-workflow.test.js scripts/online-release-contract.test.js
```

构建、真实窗口采集、真机 UI、完整报警链和正式发布是不同级别的验证；操作命令与边界见[运维文档](./docs/codex/60-operations.md)，逐项证据见[验证报告](./docs/codex/90-verification-report.md)。

## 文档与发布状态

- [项目文档索引](./docs/codex/00-index.md)：当前事实的导航入口。
- [项目概览](./docs/codex/10-project-overview.md)：组件状态与实现边界。
- [产品路线图](./docs/codex/15-product-roadmap.md)：产品方向和验收闸门的唯一来源。
- [发布说明](./docs/releases/v4.5.1.md)：当前版本的上线范围；Android 检测端不在 4.5.1 发布范围内。

CI 的文档巡检和定时线上发布契约检查入口、频率与覆盖范围见[运维文档](./docs/codex/60-operations.md)。

## 版本与授权

目前已经实现的纯软件视觉方案为免费版；接入检测硬件探测器后进入付费版。当前主线采用 [VGSAL-1.0](./LICENSE)，这是源码可见许可证，不是开源许可证；再分发、对外托管或产品集成需要[商业授权](./COMMERCIAL-LICENSE.md)。历史 MIT 授权边界见 [LICENSE-HISTORY.md](./LICENSE-HISTORY.md) 和 [LICENSE-MIT](./LICENSE-MIT)。
