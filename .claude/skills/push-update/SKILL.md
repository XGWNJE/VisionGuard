---
name: push-update
description: 推送客户端更新——编译、打包、上传发行包到VPS、更新releases.json，使各端客户端能自动检测并更新。
---

# 推送更新

> 项目级技能。**必须开发者主动触发**（说"推送更新"、"发布新版本"等），不会自动执行。

## 概念

当你改了代码，想让所有客户端自动更新到新版本时，需要做三件事：

1. **告诉 Server"有新版本了"** → 更新 `server/data/releases.json`
2. **把新版安装包放到 Server 上** → 上传 ZIP/APK 到 `server/data/releases/`
3. **生成新版安装包** → 编译各端 + 打包

客户端每次启动都会查询 Server "有没有新版本？"，Server 根据 `releases.json` 回答。

## 触发条件

- 用户说"推送更新"、"发布新版本"、"上线新版本"、"部署更新"
- 用户说"升级版本到 x.x.x"
- **绝不会**在编译、提交代码时自动触发——必须开发者明确要求

## 执行指南

以下不是固定流程，而是**可选步骤**。根据实际情况选择执行。

### 步骤 1：确定新版本号

- 根据 `VERSION` 文件确定当前版本
- 按语义化版本规则确定新版本号（`feat→minor / fix→patch / BREAKING→major`）

### 步骤 2：同步版本号（可选）

```bash
node scripts/sync-version.js <新版本号>
```

如果只更新某一端（如只修了 Android），可以只手动改那一端的版本号，不用全端同步。

### 步骤 3：编译需要的端

| 端 | 编译命令 |
|---|---|
| WPF | `dotnet build detector\windows-wpf -c Release` |
| WinForms | `msbuild detector\windows-winforms /p:Configuration=Release /p:Platform=x64` |
| Android 检测 | `cd detector\android && gradlew assembleRelease` |
| Android 接收 | `cd receiver\android && gradlew assembleRelease` |
| Server | `cd server && npm run build` |

产物位置：
- WPF: `detector\windows-wpf\bin\x64\Release\`
- WinForms: `detector\windows-winforms\bin\x64\Release\`
- Android: `app\build\outputs\apk\release\`

### 步骤 4：打包并放到 releases 目录

将编译产物打包成 ZIP/APK，复制到 `server\data\releases\`，命名格式：
- WinForms: `VisionGuard-v<version>.zip`
- WPF: `VisionGuard-WPF-v<version>.zip`
- Android 检测: `VisionGuard-Detector-v<version>.apk`
- Android 接收: `VisionGuard-Receiver-v<version>.apk`

### 步骤 5：更新 releases.json

```json
{
  "wpf": {
    "version": "<新版本号>",
    "url": "/releases/VisionGuard-WPF-v<version>.zip",
    "size": <文件字节数>
  }
}
```

只需更新**实际编译了的端**，没变的端保持原版本号。

### 步骤 6：部署到 VPS

```bash
# 上传发行包
scp server/data/releases/*.zip visionguard:/opt/visionguard/VisionGuard_Server/data/releases/

# 上传 releases.json
scp server/data/releases.json visionguard:/opt/visionguard/VisionGuard_Server/data/releases.json

# 更新 Server 代码（如果有改）
bash server/deploy.sh

# 重启服务
ssh visionguard "systemctl restart visionguard"
```

### 步骤 7：提交版本变更

```bash
git add -A
git commit -m "release: v<新版本号>"
```

## 核心文件

| 文件 | 作用 |
|---|---|
| `server/data/releases.json` | **核心**——Server 据此告知客户端当前最新版本 |
| `server/data/releases/` | 各端安装包存放目录 |
| `scripts/sync-version.js` | 版本号同步工具（辅助，非必须） |

## 设计原则

- **灵活性优先**：不需要每次更新全部五端，按需选择
- **releases.json 是唯一真相**：客户端只认这个文件，其他都是辅助
- **编译路径可能变化**：`release.js` 脚本中的硬编码路径仅供参考，以实际 `.csproj`/`build.gradle.kts` 中的 `OutputPath` 为准
