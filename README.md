# VisionGuard

> 面向 Windows、Android 与中继服务的 AI 实时监控系统。

[![Version](https://img.shields.io/badge/version-4.1.1-1f6feb)](./VERSION)
[![Stack](https://img.shields.io/badge/技术栈-Windows%20%7C%20Android%20%7C%20Node.js-0f766e)](#项目结构)
[![Docs](https://img.shields.io/badge/文档-已验真-f59e0b)](./docs/codex/00-index.md)

VisionGuard 是一个多端联动的 AI 监控项目，围绕一条统一链路运作：

`Windows / Android 检测端 -> 中继服务器 -> Android 接收端`

它将本地推理、隐私遮罩、实时告警中继、截图传输与更新分发整合在同一套工程中。

当前正式服务域名为 `https://visionguard.xgwnje.cn`。根域 `https://xgwnje.cn` 留给个人主页；旧客户端可短期通过根域兼容路径访问更新文件，但新客户端和项目配置应统一使用项目子域名。

## 核心能力

| 模块 | 作用 |
|---|---|
| `Windows 检测端` | 屏幕/窗口捕获、ONNX 推理、告警生成 |
| `Android 检测端` | CameraX 采集、ONNX Runtime Mobile 推理、移动端告警上报 |
| `中继服务器` | HTTP + WebSocket 中继、设备状态、告警历史、截图访问 |
| `Android 接收端` | 设备列表、告警流、截图查看、远程控制 |

## 核心链路

所有检测端统一遵循这条运行链路：

`Capture -> MaskApply -> Preprocess -> ONNX Inference -> Parse -> AlertDecision -> Push`

稳定约束：

- 遮罩使用归一化坐标 `[0,1]`
- 遮罩同时影响推理结果与报警截图
- `server/` 与 Android 端协议强耦合
- `VERSION` 是权威版本源，不能被隐式修改

## 项目结构

```text
detector/windows-winforms/   Windows 主力检测端（.NET Framework 4.7.2）
detector/windows-wpf/        Windows 升级检测端（.NET 9, WPF, MVVM）
detector/android/            Android 检测端
server/                      HTTP + WebSocket 中继服务
receiver/android/            Android 接收端
docs/codex/                  当前维护中的已验真项目文档
AGENTS.md                    顶层协作约束
VERSION                      权威版本号来源
```

## 平台概览

| 端 | 运行时 | 说明 |
|---|---|---|
| `windows-winforms` | .NET Framework 4.7.2 | 主力线，YOLOv5 |
| `windows-wpf` | .NET 9 | 升级线，WPF + MVVM |
| `android detector` | Android 9+ | CameraX + ONNX Runtime Mobile |
| `android receiver` | Android 9+ | Compose + 前台服务 |
| `server` | Node.js 20+ | Express + ws |

## 服务地址

- 正式服务域名：`https://visionguard.xgwnje.cn`
- WebSocket：由客户端基于正式域名拼接 `/ws`
- 健康检查：`https://visionguard.xgwnje.cn/health`
- 更新查询：`https://visionguard.xgwnje.cn/api/update`
- 更新文件：`https://visionguard.xgwnje.cn/releases/*`

## 快速入口

### 建议先看

- [docs/codex/00-index.md](./docs/codex/00-index.md)
- [docs/codex/90-verification-report.md](./docs/codex/90-verification-report.md)
- [AGENTS.md](./AGENTS.md)

### 构建入口

- Server：`cd server && npm ci && npm run build`
- WinForms：打开 `detector/windows-winforms/VisionGuard.slnx`
- WPF：打开 `detector/windows-wpf/VisionGuard.sln`
- Android：分别使用 `detector/android/` 与 `receiver/android/`

## 文档入口

当前可维护文档统一放在 `docs/codex/`：

- `00-index.md`：总入口
- `20-server.md`：中继服务器
- `30-windows-detector.md`：Windows 检测端
- `35-model-assets.md`：模型、目标类、COCO 映射
- `40-android-detector.md`：Android 检测端
- `50-android-receiver.md`：Android 接收端
- `60-operations.md`：构建、验证、发布边界
- `90-verification-report.md`：源码验真结论

## 说明

根目录 `README.md` 保持“项目首页”定位，强调可读性和入口性。
更细的工程事实统一维护在 `docs/codex/`，避免双份文档长期失同步。
