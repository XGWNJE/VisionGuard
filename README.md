# VisionGuard

[![Version](https://img.shields.io/badge/version-v4.0.0-blue)](VERSION)
[![License](https://img.shields.io/badge/license-MIT-green)]()

> AI 实时监控系统。检测端发现目标 → 服务器秒级推送 → 接收端报警通知。
>
> 支持 **Windows PC**（WinForms 主力线 / WPF 视觉线）和 **Android 手机**（检测端 / 接收端）。

---

## 系统架构

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ Windows 检测  │    │              │    │ Android 接收  │
│ Win7+ WinForms│───▶│   VPS 服务器  │───▶│ 实时报警通知  │
│ Win10+ WPF    │    │  WebSocket   │    │ 远程控制     │
│               │    │  报警记录    │    │              │
└──────────────┘    └──────────────┘    └──────────────┘
        ▲                                      │
        │           ┌──────────────┐           │
        └───────────│ Android 检测  │◀──────────┘
                    │ 后置摄像头    │
                    │ 实时推理     │
                    └──────────────┘
```

## 下载

前往 [Releases](https://github.com/XGWNJE/VisionGuard-RemoteAlarm/releases/latest) 获取最新版本。

| 端 | 平台 | 文件 |
|---|---|---|
| 检测端 | Windows (Win7+) | `VisionGuard-Windows-vX.X.X.zip` |
| 检测端 | Windows (Win10+) | `VisionGuard-WPF-vX.X.X.zip` |
| 检测端 | Android (API 28+) | `VisionGuard-Detector-vX.X.X.apk` |
| 接收端 | Android (API 28+) | `VisionGuard-Receiver-vX.X.X.apk` |

## 快速开始

### 1. 部署服务器

```bash
cd server
cp .env.example .env
# 编辑 .env 设置 API_KEY（必须）和 PORT（默认 3000）
npm install
npm run build
npm start
```

### 2. 启动检测端

- Windows：解压运行 `VisionGuard.exe`
- Android：安装 APK，打开后选择模型开始检测

### 3. 启动接收端

安装 APK 后打开，自动连接服务器接收报警。

## 核心功能

| 检测端 | 接收端 | 服务器 |
|---|---|---|
| 屏幕捕获 / 后置摄像头推理 | 实时报警推送 | 设备管理 + 心跳 |
| 遮罩区域排除（隐私保护） | 查看截图 + 端到端延迟 | 报警记录持久化 |
| 目标类别过滤（人/车/动物等） | 远程控制（暂停/恢复/调参） | 截图 HTTP 下载 |
| 本地截图缓存（7天 TTL） | 历史报警列表（7天） | 三角色隔离 |
| 多模型切换（YOLOv5nu / YOLO26） | 网络自适应重连 | 72h 截图清理 |

## 部署要求

| 端 | 最低要求 |
|---|---|
| Server | Ubuntu 20.04+ / Debian 11+，Node.js 20+ |
| WinForms 检测 | Windows 7+，x64，.NET Framework 4.7.2 |
| WPF 检测 | Windows 10+，x64，.NET 9 |
| Android 检测 | Android 9.0+，推荐骁龙 7/8 Gen 或天玑 8/9 |
| Android 接收 | Android 9.0+ |

## 项目结构

```
VisionGuard/
├── detector/
│   ├── windows-winforms/    C# / .NET Framework 4.7.2 / WinForms
│   ├── windows/             C# / .NET 9 / WPF / MVVM
│   └── android/             Kotlin / CameraX / ONNX Runtime
├── server/                  Node.js 20+ / TypeScript / Express / ws
└── receiver/
    └── android/             Kotlin / Jetpack Compose / OkHttp
```

## 版本

当前版本见 [VERSION](VERSION) 文件。

## License

MIT © [xgwnje](https://github.com/xgwnje)
