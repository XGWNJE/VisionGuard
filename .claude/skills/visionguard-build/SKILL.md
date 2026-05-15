---
name: visionguard-build
description: VisionGuard 全栈多平台编译。支持 Server/WinForms/WPF/Android-Detector/Android-Receiver 五端，可指定 Release/Debug 配置，自动检测工具链并并行编译。
---

# VisionGuard Build

> 项目级技能。编译 VisionGuard 五端项目，自动检测工具链、并行执行、输出结构化报告。

## 触发条件

以下任一情形激活本技能：
- 用户输入 `/visionguard-build [...]`
- 用户说"编译"、"build"、"检查能否编译"、"全端编译"等类似意图
- 提交前验证、CI 前置检查、多平台兼容性验证

## 执行流程（Claude 严格按此执行）

### Step 1: 解析参数

从用户输入中提取：
- `--target`: `all`（默认）| `server` | `winforms` | `wpf` | `android-detector` | `android-receiver`，多选用逗号分隔
- `--config`: `release`（默认）| `debug`

### Step 2: 工具链检测（并行）

对每个目标端，先检测工具链是否存在。使用 **PowerShell** 执行以下检测命令（全部并行）：

| 端 | 检测命令 |
|---|---|
| Server | `node --version` |
| WPF | `dotnet --version` |
| WinForms | `$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"; if (Test-Path $vswhere) { & $vswhere -latest -find "MSBuild\**\Bin\MSBuild.exe" } else { "NOT_FOUND" }` |
| Android-Detector | `$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Android\Android Studio\jbr" }; if (Test-Path "$env:JAVA_HOME\bin\java.exe") { & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 } else { "NOT_FOUND" }` |
| Android-Receiver | 同上 |

**若工具链缺失**：
- 记录为 `[SKIP]`，报告中说明原因
- 不阻塞其他端的编译

### Step 3: 并行编译（全部 `run_in_background`）

五端无依赖，全部并行启动。每端命令如下（替换 `{config}` 为 `Release` 或 `Debug`）：

**Server** (`d:\ObjectCode\VisionGuard\server`):
```powershell
Set-Location "d:\ObjectCode\VisionGuard\server"
npm run build 2>&1
```

**WPF** (`d:\ObjectCode\VisionGuard\detector\windows`):
```powershell
Set-Location "d:\ObjectCode\VisionGuard"
dotnet build "detector\windows\VisionGuard.csproj" -c Release -v minimal 2>&1
```
（`--config debug` 时改用 `-c Debug`）

**WinForms** (`d:\ObjectCode\VisionGuard\detector\windows-winforms`):
```powershell
$msbuild = "C:\Program Files\Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $msbuild) {
  $msbuild = & $msbuild -latest -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
}
Set-Location "d:\ObjectCode\VisionGuard"
& $msbuild "detector\windows-winforms\VisionGuard.csproj" /p:Configuration=Release /v:minimal /nologo 2>&1
```
（`--config debug` 时改用 `/p:Configuration=Debug`）

**Android-Detector** (`d:\ObjectCode\VisionGuard\detector\android`):
```powershell
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Android\Android Studio\jbr" }
Set-Location "d:\ObjectCode\VisionGuard\detector\android"
.\gradlew.bat assembleRelease --console=plain 2>&1
```
（`--config debug` 时改用 `assembleDebug`）

**Android-Receiver** (`d:\ObjectCode\VisionGuard\receiver\android`):
```powershell
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Android\Android Studio\jbr" }
Set-Location "d:\ObjectCode\VisionGuard\receiver\android"
.\gradlew.bat assembleRelease --console=plain 2>&1
```
（`--config debug` 时改用 `assembleDebug`）

> **关键**：Android 端执行前**必须**显式设置 `$env:JAVA_HOME`。即使 `java` 在 PATH 中，Gradle wrapper 也可能依赖 JAVA_HOME。

### Step 4: 结果收割

等待所有后台任务完成。读取每个任务的 exit code 和输出：
- exit code `0` → `[PASS]`
- exit code 非 `0` → 分析输出判断是**代码编译错误**还是**签名/环境配置错误**

**Android Receiver Release 特殊处理**：
若输出包含 `keystore password was incorrect`：
1. 确认 `compileReleaseKotlin` / `compileReleaseJavaWithJavac` 已通过（代码本身无误）
2. **自动 fallback**：后台执行 `assembleDebug` 验证代码完整性
3. 结果标记为 `[PARTIAL]` —— "编译通过，签名失败(keystore)"

### Step 5: 输出报告

统一格式输出：

```
=== VisionGuard Build Report ===
Target: {target} | Config: {config}

[✅ PASS] Server              (X.Xs)  0 errors, 0 warnings
[✅ PASS] WPF                 (X.Xs)  0 errors, 0 warnings
[❌ FAIL] WinForms            ————    MSBuild not found
[✅ PASS] Android-Detector    (XXs)   0 errors, X warnings
[⚠️ PARTIAL] Android-Receiver (XXs)  编译通过, 签名失败(keystore)

Summary: X/Y passed, Z failed, W code errors
```

状态定义：
| 状态 | 含义 |
|------|------|
| `[PASS]` | 编译成功，输出产物正确生成 |
| `[FAIL]` | 编译失败（代码错误或工具链缺失） |
| `[PARTIAL]` | 代码编译通过，但后续步骤失败（签名、打包等） |
| `[SKIP]` | 工具链未检测到，未尝试编译 |

## 五端详情

### 1. Server (Node.js 20+ / TypeScript)

- **路径**: `server/`
- **工具**: `npm` + `tsc`
- **命令**: `npm run build`
- **产物**: `dist/` 目录下的 JS 文件
- **注意**: Server 无 Release/Debug 之分，`--config` 对此端无效

### 2. WinForms 检测端 (C# / .NET Framework 4.7.2)

- **路径**: `detector/windows-winforms/`
- **工具**: MSBuild (Visual Studio 2022+)
- **命令**: `msbuild VisionGuard.csproj /p:Configuration=Release /v:minimal /nologo`
- **产物**: `bin/Release/VisionGuard.exe`
- **注意**: MSBuild 路径通过 `vswhere -latest -find` 动态定位，避免硬编码 VS 版本

### 3. WPF 检测端 (C# / .NET 9)

- **路径**: `detector/windows/`
- **工具**: `dotnet` CLI
- **命令**: `dotnet build -c Release`
- **产物**: `bin/Release/net9.0-windows/VisionGuard.dll`
- **注意**: .NET 10 SDK 向后兼容 net9.0

### 4. Android 检测端 (Kotlin / CameraX / ONNX)

- **路径**: `detector/android/`
- **工具**: Gradle wrapper
- **命令**: `gradlew.bat assembleRelease --console=plain`
- **产物**: `app/build/outputs/apk/release/app-release.apk`
- **注意**: 首次编译可能下载 Gradle wrapper，耗时较长；必须设置 `JAVA_HOME`

### 5. Android 接收端 (Kotlin / Jetpack Compose)

- **路径**: `receiver/android/`
- **工具**: Gradle wrapper
- **命令**: `gradlew.bat assembleRelease --console=plain`
- **产物**: `app/build/outputs/apk/release/app-release.apk`
- **注意**: Release 需 keystore 密码；签名失败时自动 fallback Debug 验证

## 故障速查

| 现象 | 原因 | 解决 |
|------|------|------|
| `msbuild: command not found` | 未安装 VS2022 | 打开 VS2022 安装器补充 MSBuild 工作负载 |
| `java: command not found` | JDK 未安装或不在 PATH | 安装 JDK 17+ 并设置 `$env:JAVA_HOME`；或使用 Android Studio 自带 JDK |
| `tsc: not found` | Server `node_modules` 缺失 | `cd server && npm install` |
| `keystore password was incorrect` | Android Release 签名配置不匹配 | 检查 `keystore.properties` 或改用 `--config debug` |
| `gradlew: permission denied` | Linux/Mac 下 wrapper 无执行权限 | `chmod +x gradlew` |
| `dotnet: command not found` | .NET SDK 未安装 | 安装 .NET 9 SDK |
