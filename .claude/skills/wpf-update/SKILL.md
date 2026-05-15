---
name: wpf-update
description: 为 WPF (.NET 9+) 桌面程序添加基于自托管服务器的强制自动更新能力。覆盖 Server 端 API、客户端 AutoUpdater、版本管理脚本、部署流程，以及全部踩坑经验。
---

# WPF 自动更新

> 项目级技能。为 WPF 桌面应用添加启动时检查更新 → 强制下载安装的能力，基于自建 Server 静态文件托管。

## 适用场景

- WPF / .NET 9+ 桌面应用
- 自有服务器可托管更新包（无需第三方 CDN）
- 需要强制更新（旧版本无法使用）

## 架构

```
┌──────────┐    GET /api/update?platform=wpf&version=x.x.x    ┌──────────┐
│  WPF App │ ──────────────────────────────────────────────→  │  Server  │
│  启动检查 │ ←── { hasUpdate, latestVersion, downloadUrl } ── │          │
│          │                                                   │          │
│  下载 ZIP│ ─── GET /releases/App-vx.x.x.zip ──────────────→ │          │
│          │ ←── 156MB ZIP ──────────────────────────────── │          │
│          │                                                   │          │
│  解压 → 启动 updater.ps1 → Shutdown + Exit → 替换 → 重启     │          │
└──────────┘                                                   └──────────┘
```

## 涉及文件

| 文件 | 用途 |
|---|---|
| `server/src/routes/update.ts` | `/api/update` 查询接口，读 `data/releases.json` |
| `server/data/releases.json` | 各端版本号 + 下载路径配置 |
| `server/src/services/ConnectionManager.ts` | WS 版本门控：`needs-update` |
| `detector/windows/Utils/AutoUpdater.cs` | 客户端更新逻辑（核心） |
| `detector/windows/VisionGuard.csproj` | 需添加 Version/FileVersion 属性 |
| `detector/windows/App.xaml.cs` | 启动时调用 `CheckUpdateAsync()` |
| `scripts/sync-version.js` | 版本号同步脚本 |
| `scripts/release.js` | 发布脚本（编译+打包+生成 releases.json） |

## Server 端实现

### 1. `/api/update` 接口 (`server/src/routes/update.ts`)

```typescript
// GET /api/update?platform=wpf&version=4.0.0
// 返回 { ok, hasUpdate, latestVersion, downloadUrl, size, forceUpdate }
```

- 读取 `server/data/releases.json` 获取最新版本号和文件名
- `compareVersion(clientVer, serverVer)` 语义化版本比较
- Server 只比较，客户端不自己做版本比较（避免逻辑分散）

### 2. `/releases/` 静态文件 (`server/src/index.ts`)

```typescript
app.use('/releases', express.static('data/releases'));
```

- 无需额外鉴权（下载 URL 已在 `/api/update` 响应中）
- 文件直接放在 `server/data/releases/` 目录

### 3. WS 认证门控 (`server/src/services/ConnectionManager.ts`)

```typescript
// 版本过低 → 不直接拒绝，返回 needs-update
sendJson(ws, {
  type: 'auth-result', success: false,
  reason: 'needs-update', latestVersion: config.minClientVersion
});
```

- WS 认证时检查版本号
- 不通过时返回 `reason: 'needs-update'`（而非直接拒绝）
- 客户端收到后走强制更新流程

### 4. `releases.json` 格式

```json
{
  "wpf": {
    "version": "4.0.1",
    "url": "/releases/VisionGuard-WPF-v4.0.1.zip",
    "size": 156772994
  }
}
```

## 客户端实现 (`AutoUpdater.cs`)

### 核心流程

```
CheckUpdateAsync()
  → GET /api/update → 解析 JSON → 判断 hasUpdate
  → MessageBox 强制确认（仅 OK，无取消）
  → DownloadAsync()
      → HttpClient 下载 ZIP → 解压到 %TEMP%\VisionGuardUpdate\extracted\
      → 写 updater.ps1（PowerShell 替换脚本）
      → Process.Start("powershell.exe", "-File updater.ps1")
      → Shutdown() + 500ms 延迟 Exit(0)
```

### ⚠️ 踩坑经验

#### 坑 1：不能用 cmd.exe bat 脚本做替换

**现象**：批处理的 `start "" "path"` 引号转义极不可靠，中文 Windows 下 `chcp 65001` 不稳定。

**解决**：改用 **PowerShell `.ps1` 脚本**：
```powershell
Copy-Item -Recurse -Force 'extracted\*' 'appDir\'
Remove-Item -Recurse -Force 'tempDir' -ErrorAction SilentlyContinue
Start-Process 'appExe'
```

#### 坑 2：`Environment.Exit(0)` 在 WPF 异步方法中不生效

**现象**：`await` 的异步上下文中调用 `Environment.Exit(0)`，进程没有退出。

**解决**：**双保险退出**：
```csharp
// 1. 正常 Shutdown
Application.Current?.Dispatcher.Invoke(() => Application.Current.Shutdown());
// 2. 500ms 后强制杀死
Task.Delay(500).ContinueWith(_ => Environment.Exit(0));
```

#### 坑 3：WPF .csproj 缺少版本属性 → 文件属性显示 1.0

**现象**：Windows 文件属性中版本号显示 `1.0.0.0` 而非 `4.0.1`。

**原因**：.NET SDK 项目中 `.csproj` 默认不带版本号。

**解决**：在 `.csproj` 的 `<PropertyGroup>` 中添加：
```xml
<Version>4.0.1</Version>
<FileVersion>4.0.1</FileVersion>
<AssemblyVersion>4.0.1</AssemblyVersion>
```

#### 坑 4：临时文件残留

**现象**：`%TEMP%\VisionGuardUpdate\` 每次都残留 200MB+ 文件。

**解决**：在 updater.ps1 中添加 `Remove-Item -Recurse -Force`，在 AutoUpdater.cs 的 catch 块中也添加清理。

#### 坑 5：在线程池线程调用 UI

**现象**：`MessageBox.Show()` 从后台线程调用有线程安全问题。

**解决**：必须通过 `Application.Current.Dispatcher.InvokeAsync()` 回到 UI 线程：
```csharp
await Application.Current.Dispatcher.InvokeAsync(() =>
{
    MessageBox.Show(msg, "Title", MessageBoxButton.OK, MessageBoxImage.Warning);
});
```

### 关键实现细节

1. **HttpClient 生命周期**：每次用 `using` 创建新实例（不在 DI 中注册，因为更新是一次性操作）
2. **JsonDocument 释放**：`using var doc = JsonDocument.Parse(json)` 防止内存泄漏
3. **空安全**：用 `TryGetProperty` 替代 `GetProperty`，服务器响应缺失字段时不崩溃

## 启动集成 (`App.xaml.cs`)

在 `OnStartup` 中 fire-and-forget 调用：
```csharp
_ = Utils.AutoUpdater.CheckUpdateAsync();
```

- 不阻塞 UI 启动
- 网络异常静默忽略（catch 块只写日志）

## 部署流程

```
1. node scripts/sync-version.js 4.1.0              # 同步版本号
2. # 手动编译各端
3. node scripts/release.js 4.1.0                   # 打包+生成 releases.json
4. scp server/data/releases/*.zip visionguard:...   # 上传
5. ssh visionguard "systemctl restart visionguard"  # 重启 Server
```

## 验证方法

1. 将测试客户端版本设为旧版本（如 4.0.0）
2. 确保 Server `releases.json` 中版本更新（如 4.0.1）
3. 启动测试客户端 → 应弹窗"发现新版本 4.0.1"
4. 点击确定 → 应看到 PowerShell 窗口"VisionGuard Updater"
5. 程序应退出 → 文件替换 → 新版本启动
6. 文件属性中版本号应显示新版本
7. 检查 `%TEMP%\VisionGuardUpdate\` 应不存在
