---
name: version-alignment
description: 全端版本号对齐检查与批量修改。跨 5 个子项目统一版本号，包括 bump-version.sh 脚本维护。
---

# 版本号对齐
> VisionGuard 跨 5 个子项目，版本号分散在 15+ 个文件中。以根目录 `VERSION` 文件为权威来源。

## 版本号位置清单

### 根目录（权威来源）
| 文件 | 格式 | 示例 |
|---|---|---|
| `VERSION` | 纯文本 | `4.0.0` |

### Server (Node.js)

| 文件 | 位置 | 格式 |
|---|---|---|
| `server/package.json` | `"version"` 字段 | `"4.0.0"` |
| `server/package-lock.json` | `"version"` 字段 (2处) | `"4.0.0"` |
| `server/src/config.ts` | `minClientVersion` | `'4.0.0'` |
| `server/src/index.ts` | 启动日志字符串 | `v4.0.0` |

### Windows WPF (.NET 9)

| 文件 | 位置 | 格式 |
|---|---|---|
| `detector/windows-wpf/Services/ServerPushService.cs` | WS 认证硬编码 | `["version"] = "4.0.0"` |

无 `.csproj` 版本字段，无 `AssemblyInfo.cs`。仅 WS 认证字符串一处。

### Windows WinForms (.NET Framework 4.7.2)

| 文件 | 位置 | 格式 |
|---|---|---|
| `detector/windows-winforms/Properties/AssemblyInfo.cs` | `AssemblyVersion` + `AssemblyFileVersion` | `"4.0.0.0"`（4段式） |
| `detector/windows-winforms/VisionGuard.csproj` | `<ApplicationVersion>` | `4.0.0.%2a` |
| `detector/windows-winforms/Services/ServerPushService.cs` | WS 认证硬编码 | `["version"] = "4.0.0"` |

### Android 检测端 (Kotlin)

| 文件 | 位置 | 格式 |
|---|---|---|
| `detector/android/app/build.gradle.kts` | `versionName` + `versionCode` | `"4.0.0"` / `4000` |

`WsMessage.kt` 通过 `BuildConfig.VERSION_NAME` 动态读取，无需手动更新。

### Android 接收端 (Kotlin)

| 文件 | 位置 | 格式 |
|---|---|---|
| `receiver/android/app/build.gradle.kts` | `versionName` + `versionCode` | `"4.0.0"` / `4000` |

### 文档

| 文件 | 位置 | 格式 |
|---|---|---|
| `AGENTS.md` | `当前全端版本` + `minClientVersion` | `4.0.0` |
| `README.md` | shields.io badge | `v4.0.0` |

## 版本号命名规则
- **三段式**：`MAJOR.MINOR.PATCH`（如 `4.0.0`）
- **四段式**（仅 .NET AssemblyInfo）：`MAJOR.MINOR.PATCH.0`（如 `4.0.0.0`）
- **versionCode**（Android）：`MAJ*1000 + MIN*100 + PATCH`（如 `4000`）
- `VERSION` 文件为权威来源，其余所有位置必须与之一致

## 版本 bump 规则

| 提交前缀 | bump 类型 |
|---|---|
| `fix:` / `perf:` / `refactor:` / `chore:` | patch |
| `feat:` | minor |
| `BREAKING CHANGE:` | major |

协议变更（WS 消息格式变更）= BREAKING CHANGE → 主版本 +1。

## bump-version.sh 使用

```bash
bash scripts/bump-version.sh patch    # 0.0.1+
bash scripts/bump-version.sh minor    # 0.1.0
bash scripts/bump-version.sh major    # 1.0.0
bash scripts/bump-version.sh          # 交互式
```

**脚本已知局限**：`bump-version.sh` 目前只覆盖 6 个位置（VERSION、server/package.json、2 个 build.gradle.kts、WPF csproj、WPF AssemblyInfo）。以下位置需手动更新：
- `server/package-lock.json`
- `server/src/config.ts`
- `server/src/index.ts`
- `detector/windows-wpf/Services/ServerPushService.cs`（WPF）
- `detector/windows-winforms/Properties/AssemblyInfo.cs`（WinForms）
- `detector/windows-winforms/VisionGuard.csproj`（WinForms）
- `detector/windows-winforms/Services/ServerPushService.cs`（WinForms）
- `AGENTS.md`
- `README.md`

## 对齐检查命令
```bash
# 当前版本
CUR=$(cat VERSION)
echo "权威版本: $CUR"

# 搜索所有可能包含旧版本号的文件（排除 node_modules / .git / obj / bin / gradle 缓存）
rg "3\.[0-9]+\.[0-9]+" --type-add 'code:*.{cs,json,kt,kts,xml,xaml,ts,sh,md,toml,txt}' \
   -t code -l --no-ignore-vcs \
   -g '!node_modules' -g '!obj' -g '!bin' -g '!.gradle' -g '!build' -g '!package-lock.json'
```

注意：`libs.versions.toml` 中的 `espressoCore` 是 Android 测试库版本，不是项目版本，忽略。
