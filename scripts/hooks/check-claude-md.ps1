# Stop hook: 检测 VisionGuard 项目变更是否需要同步 CLAUDE.md
# 项目级，覆盖各端核心模块的详细路径规则
param($rawInput)

$ErrorActionPreference = 'SilentlyContinue'

$changed = @(git diff --name-only HEAD 2>$null) +
           @(git diff --name-only --cached 2>$null) +
           @(git diff --name-only 2>$null)
$changed = ($changed | Where-Object { $_ -and $_ -ne '' } | Sort-Object -Unique)

if (-not $changed) { exit 0 }

$rules = @(
    @{Pattern='\.csproj$|\.slnx?$|\.gradle\.kts$|package\.json$|^scripts/';       Section='构建 / 项目结构'},
    @{Pattern='/(Capture|Inference|Services|Models|UI|Data|Utils)/.*\.(cs|kt)$';   Section='检测端核心模块'},
    @{Pattern='/(ViewModels|Views)/.*\.(cs|xaml)$';                                Section='WPF MVVM 架构'},
    @{Pattern='server/src/services/|server/src/routes/|server/src/middleware/';     Section='Server 架构'},
    @{Pattern='receiver/android/.*/(service|ui|data)/';                            Section='接收端架构'},
    @{Pattern='ConnectionManager\.ts$|models/types\.ts$|WebSocketClient\.kt$';     Section='WS 协议'},
    @{Pattern='AppConstants\.kt$|deploy\.sh$|\.env';                               Section='关键常量 / 部署'},
    @{Pattern='CLAUDE\.md$';                                                       Section='CLAUDE.md 自身'}
)

$hits = @()
foreach ($rule in $rules) {
    foreach ($f in $changed) {
        if ($f -match $rule.Pattern) { $hits += $rule.Section; break }
    }
}
$hits = $hits | Sort-Object -Unique

if (-not $hits) { exit 0 }

Write-Host ''
Write-Host "[CLAUDE.md] 变更命中: $($hits -join ' · ')" -ForegroundColor DarkYellow
Write-Host ''

exit 0
