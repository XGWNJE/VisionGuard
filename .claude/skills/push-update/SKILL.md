---
name: push-update
description: 推送客户端更新——编译、打包、上传发行包到VPS、更新releases.json，使各端客户端能自动检测并更新。
---

# 推送更新

> 项目级技能。**必须开发者主动触发**，不会自动执行。
> 触发词："推送更新"、"发布新版本"、"上线"、"部署更新"、"更新到服务器"

## 核心概念

1. **releases.json 是唯一真相** — Server 据此告诉各端"当前最新版本是什么"
2. **发行包放在 Server 本地** — `server/data/releases/`，由 express.static 直接提供下载
3. **客户端主动轮询** — 启动时 `GET /api/update?platform=xxx&version=yyy`，Server 对比版本号决定是否返回更新

## 平台选择语法

用户可指定编译范围，灵活组合：

| 说法 | 对应端 |
|------|--------|
| "全端" / "全部" / "所有端" / "五端" | winforms + wpf + detector + receiver + server |
| "Windows" / "桌面端" | winforms + wpf |
| "WinForms" / "传统端" | winforms |
| "WPF" / "新界面" | wpf |
| "Android" / "安卓端" / "移动端" | detector + receiver |
| "检测端" / "安卓检测" | detector |
| "接收端" / "安卓接收" | receiver |
| "Server" / "服务器" | server (仅代码部署，无发行包) |

未明确指定时，询问用户需要编译哪些端。

## 执行流程

### 0. 确定版本号

- 读取根目录 `VERSION` 获取当前版本
- 如果用户指定了新版本号，先跑 `node scripts/sync-version.js <version>` 同步全端
- 如果只是推送当前版本（代码修复后重新打包），不修改版本号

### 1. 编译

#### 环境准备

```powershell
# Android 编译必须
$env:JAVA_HOME = 'C:\Android\Android Studio\jbr'

# 先停掉所有 Gradle 守护进程（避免文件锁导致 clean 失败）
# --stop 是全局命令，无需 cd 到特定项目
./gradlew.bat --stop   # 在任一 android/ 目录下执行
```

#### 各端编译命令与产物路径

| 端 | 编译命令 | 产物路径 |
|----|----------|----------|
| **WinForms** | MSBuild (见下方) | `detector/windows-winforms/bin/Release/VisionGuard.exe` |
| **WPF** | `dotnet build detector/windows-wpf/VisionGuard.csproj -c Release` | `detector/windows-wpf/bin/x64/VisionGuard.exe` |
| **Detector** | `./gradlew.bat assembleRelease` (在 `detector/android/`) | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| **Receiver** | `./gradlew.bat assembleRelease` (在 `receiver/android/`) | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| **Server** | `cd server && npm run build` | `server/dist/` (仅代码，无发行包) |

**WinForms MSBuild 路径发现**（vswhere 定位，避免硬编码）：

```powershell
$msbuild = & "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe" -latest -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
& $msbuild "detector/windows-winforms/VisionGuard.csproj" /p:Configuration=Release /v:minimal /nologo
```

**WPF 注意**：`.csproj` 中 `OutputPath` 是 `bin\x64\`（无 Debug/Release 子目录），`<AppendTargetFrameworkToOutputPath>false</AppendTargetFrameworkToOutputPath>`。

**Android 注意**：
- 产物固定名为 `app-release-unsigned.apk`，复制时需重命名
- 接收端已移除签名配置（与检测端一致，统一编译 unsigned APK）
- 如遇 lint 文件锁：加 `-x lintVitalAnalyzeRelease -x lintAnalyzeRelease` 跳过
- 如遇 clean 文件锁：跳过 clean 直接 `assembleRelease`（增量编译）
- 使用 `./gradlew.bat` 或 `./gradlew`（Windows / WSL），本项目用 `.bat`
- 编译前先 `./gradlew.bat --stop` 停掉旧 daemon

### 2. 打包到 releases 目录

```powershell
# 目标目录
$releases = "server/data/releases"
New-Item -ItemType Directory -Force $releases | Out-Null

# WinForms — 整个 Release 目录打包
Compress-Archive -Path "detector/windows-winforms/bin/Release/*" -DestinationPath "$releases/VisionGuard-v<ver>.zip" -Force

# WPF — bin\x64\ 目录打包
Compress-Archive -Path "detector/windows-wpf/bin/x64/*" -DestinationPath "$releases/VisionGuard-WPF-v<ver>.zip" -Force

# Android 检测端 — 复制并重命名
Copy-Item "detector/android/app/build/outputs/apk/release/app-release-unsigned.apk" "$releases/VisionGuard-Detector-v<ver>.apk" -Force

# Android 接收端 — 复制并重命名
Copy-Item "receiver/android/app/build/outputs/apk/release/app-release-unsigned.apk" "$releases/VisionGuard-Receiver-v<ver>.apk" -Force
```

> 注意 PowerShell `Compress-Archive` 和 `Copy-Item` 在跨驱动器或长路径时可能静默失败。
> 验证命令：`bash -c "ls -la server/data/releases/VisionGuard-*v<ver>.*"`

### 3. 更新 releases.json

```json
{
  "winforms": {
    "version": "<ver>",
    "url": "/releases/VisionGuard-v<ver>.zip",
    "size": <实际字节数>
  },
  "wpf": {
    "version": "<ver>",
    "url": "/releases/VisionGuard-WPF-v<ver>.zip",
    "size": <实际字节数>
  },
  "android-detector": {
    "version": "<ver>",
    "url": "/releases/VisionGuard-Detector-v<ver>.apk",
    "size": <实际字节数>
  },
  "android-receiver": {
    "version": "<ver>",
    "url": "/releases/VisionGuard-Receiver-v<ver>.apk",
    "size": <实际字节数>
  }
}
```

**原则**：只更新实际编译了的端，其余保持不变。版本号 `version` 字段必须与该端 `build.gradle.kts` / `AssemblyInfo.cs` / `AppConfig.cs` 中的当前版本一致。

### 4. 上传到 VPS

```bash
# 发行包 — 逐个上传（大文件并行 SCP 可能被 VPS 断连）
scp server/data/releases/VisionGuard-v<ver>.zip visionguard:/opt/visionguard/VisionGuard_Server/data/releases/
scp server/data/releases/VisionGuard-WPF-v<ver>.zip visionguard:/opt/visionguard/VisionGuard_Server/data/releases/
scp server/data/releases/VisionGuard-Detector-v<ver>.apk visionguard:/opt/visionguard/VisionGuard_Server/data/releases/
scp server/data/releases/VisionGuard-Receiver-v<ver>.apk visionguard:/opt/visionguard/VisionGuard_Server/data/releases/

# releases.json
scp server/data/releases.json visionguard:/opt/visionguard/VisionGuard_Server/data/releases.json
```

> SCP 上传 250MB+ 文件可能需要数分钟，串行上传避免 VPS 并发连接限制导致断连。

### 5. 部署 Server（如有代码变更）

```bash
bash server/deploy.sh
```

这会同步 `server/src/` → VPS → 远程 `tsc` 编译 → `systemctl restart visionguard`。

### 6. 验证

```bash
# 确认 VPS 上文件到位
ssh visionguard "ls -la /opt/visionguard/VisionGuard_Server/data/releases/ && echo '---' && cat /opt/visionguard/VisionGuard_Server/data/releases.json"
```

## 常见问题速查

| 症状 | 原因 | 解决 |
|------|------|------|
| Gradle `clean` 报 `Unable to delete` | 旧 daemon 或 IDE 持有文件锁 | `./gradlew.bat --stop`，跳过 clean 直接 `assembleRelease` |
| 接收端 `packageRelease` 报 keystore password 错误 | 签名配置密码不匹配 | 已移除接收端签名配置，编译 unsigned APK 即可 |
| SCP 上传大文件时 `Connection closed` | VPS 并发连接限制 | 逐个串行上传，不要并行 SCP |
| PowerShell `Get-ChildItem` 找不到 APK | 长路径或权限问题 | 改用 `bash -c "find ... -name '*.apk'"` |
| MSBuild 未找到 | 未安装 VS 或路径不对 | 用 vswhere 动态定位 |
| `releases.json` 的 `size` 字段与实际文件不匹配 | 重新打包后忘记更新 size | 打包后用 `(Get-Item <path>).Length` 确认 |
| 编译 WPF 输出到非预期路径 | .csproj 中 `OutputPath` 覆盖了标准路径 | WPF 输出统一在 `bin\x64\`，注意 `AppendTargetFrameworkToOutputPath=false` |

## 测试技巧

无需改版本号即可测试自动更新流程：

**Windows**：启动前设环境变量伪装旧版本
```powershell
$env:VISIONGUARD_TEST_VERSION = "0.0.0"
.\VisionGuard.exe  # 必然触发更新
```

**Android**：在 Device Explorer 中编辑 SharedPreferences（Release 版也可用）
```
# 编辑 shared_prefs/vg_debug.xml
<string name="force_version">0.0.0</string>
# 删除该 key 即恢复正常版本
```

**服务端测试**：临时将 `config.ts` 中 `minClientVersion` 改为 `'0.0.0'` 禁用版本门控。

## 设计约束

- **不自动升级版本号** — 版本号变更必须开发者明确指令
- **按需编译** — 不需要每次五端全编
- **releases.json 不自动提交** — 留给开发者确认后手动 git 操作
- **所有编译产物路径以实际 .csproj / build.gradle.kts 为准**，不假设默认约定
- **禁止用 Debug 构建充当发行包** — 发行包必须来自 Release 构建。若 Release 编译受阻（如签名配置错误），修复构建配置而非回退到 Debug
