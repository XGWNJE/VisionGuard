# 把量化模型投放到统一服务的模型目录（本地或 VPS）。
#
# 用法：
#   pwsh scripts/deploy-int8-models.ps1 -WhatIf                      # 只校验与预览（默认本地）
#   pwsh scripts/deploy-int8-models.ps1                              # 复制到 server/data/models
#   pwsh scripts/deploy-int8-models.ps1 -Target Vps                  # VPS 只读预检 + 预览命令
#   pwsh scripts/deploy-int8-models.ps1 -Target Vps -Confirm         # 真正上传（需 owner 授权）
#
# 无论哪种目标都先做同一套校验，任何一步失败就整体中止（不做部分投放）：
#   1. 逐个用 ONNX Runtime 真加载 + 跑一帧，确认文件完整可用；
#   2. 校验目标侧 FP32 本体存在（量化版是「并存」，本体缺了就是配置错误）；
#   3. 目标侧余量检查（VPS 上额外检查磁盘余量）。
#
# VPS 目标默认**只做只读预检**：SSH 端点、目录、本体、磁盘余量都会查，但不写任何文件。
# 真正上传必须显式加 -Confirm，且属发布动作，需 owner 授权。
param(
    [string]$Source = '.local/model-quant',
    [string]$Destination = 'server/data/models',
    [ValidateSet('Local', 'Vps')][string]$Target = 'Local',
    [string]$VpsHost = 'xgwnje',
    [string]$VpsDir = '',
    [string[]]$Keys = @(
        'yolo26n_320', 'yolo26n_640',
        'yolo26s_320', 'yolo26s_640',
        'yolo26m_320', 'yolo26m_640'
    ),
    [switch]$WhatIf,
    [switch]$Confirm
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath([string]$path) {
    if ([System.IO.Path]::IsPathRooted($path)) { return $path }
    return (Join-Path $repo $path)
}

$sourceDir = Resolve-RepoPath $Source

# ── 1. 本地校验：每个量化文件真的能加载并推理。只看文件大小会漏掉半截文件。 ──
$violations = @()
$plan = @()
foreach ($key in $Keys) {
    $file = Join-Path $sourceDir "${key}_int8.onnx"
    if (-not (Test-Path $file)) { $violations += "缺少量化产物：$file"; continue }

    $result = & python -c @"
import sys, numpy as np, onnxruntime as ort
path = sys.argv[1]
session = ort.InferenceSession(path, providers=['CPUExecutionProvider'])
shape = session.get_inputs()[0].shape
size = shape[2] if len(shape) >= 4 else 320
out = session.run(None, {session.get_inputs()[0].name: np.zeros((1, 3, size, size), dtype=np.float32)})[0]
print('ok', np.asarray(out).shape)
"@ $file 2>&1
    if ($LASTEXITCODE -ne 0 -or ($result -join ' ') -notmatch 'ok \(') {
        $violations += "加载失败：$file -> $($result -join ' ')"
    } else {
        Write-Host ("校验通过 {0,-22} {1}" -f "${key}_int8.onnx", (($result -join ' ').Trim()))
    }
    $plan += [pscustomobject]@{
        Key    = $key
        File   = "${key}_int8.onnx"
        Path   = $file
        Bytes  = (Get-Item $file).Length
        Sha256 = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
    }
}

# ── 2/3. 目标侧检查 ──
$destinationDir = $null
if ($Target -eq 'Local') {
    $destinationDir = Resolve-RepoPath $Destination
    if (-not (Test-Path $destinationDir)) {
        $violations += "目标目录不存在：$destinationDir"
    } else {
        foreach ($item in $plan) {
            $base = Join-Path $destinationDir "$($item.Key).onnx"
            if (-not (Test-Path $base)) {
                $violations += "目标目录缺少 FP32 本体：$base（量化版是并存，本体必须在）"
            }
        }
    }
} else {
    # VPS：只读预检。不猜路径——先看调用方指定的路径，否则按约定候选逐个探测。
    Write-Host "`nVPS 只读预检（$VpsHost）..."
    $keyList = $Keys -join ' '
    $remoteScript = if ($VpsDir) {
        "set -e; D='$VpsDir'; test -d `"`$D`" || { echo MISSING_DIR; exit 3; }; echo DIR=`$D; " +
        "for k in $keyList; do test -f `"`$D/`$k.onnx`" || echo MISSING_BASE:`$k; done; " +
        "df -Pm `"`$D`" | tail -1 | awk '{print ""FREE_MB="" `$4}'"
    } else {
        # 候选顺序以 2026-10-11 实测为准：visionguard.service 的 WorkingDirectory 是
        # /opt/visionguard-server，模型在它下面的 data/models；其余为旧约定兜底。
        # 全都不存在时拒绝上传并要求 -VpsDir，不靠猜。
        "for c in /opt/visionguard-server/data/models /root/VisionGuard/server/data/models /opt/visionguard/server/data/models /srv/visionguard/server/data/models; do " +
        "if [ -d `"`$c`" ]; then echo DIR=`$c; " +
        "for k in $keyList; do test -f `"`$c/`$k.onnx`" || echo MISSING_BASE:`$k; done; " +
        "df -Pm `"`$c`" | tail -1 | awk '{print ""FREE_MB="" `$4}'; exit 0; fi; done; echo MISSING_DIR; exit 3"
    }
    $remote = & ssh -o ConnectTimeout=8 $VpsHost $remoteScript 2>&1
    $remoteText = ($remote | Out-String)
    $remote | ForEach-Object { Write-Host "  $_" }

    if ($LASTEXITCODE -ne 0 -or $remoteText -match 'MISSING_DIR') {
        $violations += "VPS 模型目录未找到或不可读；请用 -VpsDir 指定实际路径（脚本不猜测路径）"
    } else {
        $VpsDir = ([regex]::Match($remoteText, 'DIR=(\S+)')).Groups[1].Value
        foreach ($match in [regex]::Matches($remoteText, 'MISSING_BASE:(\S+)')) {
            $violations += "VPS 缺少 FP32 本体：$($match.Groups[1].Value)"
        }
        $free = ([regex]::Match($remoteText, 'FREE_MB=(\d+)')).Groups[1].Value
        $needMb = [math]::Ceiling((($plan | Measure-Object Bytes -Sum).Sum) / 1MB) + 64
        if ($free -and [int]$free -lt $needMb) {
            $violations += "VPS 磁盘余量不足：可用 ${free} MiB，需要约 ${needMb} MiB"
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "`n未通过校验，未投放任何文件：" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}

# ── 4. 投放 ──
$totalMb = [math]::Round((($plan | Measure-Object Bytes -Sum).Sum) / 1MB, 1)
Write-Host ""

if ($Target -eq 'Local') {
    foreach ($item in $plan) {
        if ($WhatIf) {
            Write-Host ("[WhatIf] 将投放 {0,-24} {1,7:N1} MiB" -f $item.File, ($item.Bytes / 1MB))
        } else {
            Copy-Item $item.Path (Join-Path $destinationDir $item.File) -Force
            Write-Host ("已投放   {0,-24} {1,7:N1} MiB" -f $item.File, ($item.Bytes / 1MB))
        }
    }
    Write-Host "`n共 $($plan.Count) 个文件，合计 $totalMb MiB"
    if ($WhatIf) { Write-Host "（演练模式，未复制）" }
    else { Write-Host "投放完成；端侧按同名键下载 /models/<键>.onnx" }
    exit 0
}

if (-not $Confirm) {
    Write-Host "VPS 目标默认只做只读预检，未上传任何文件。" -ForegroundColor Yellow
    Write-Host "确认无误后加 -Confirm 执行（属发布动作，需 owner 授权）。将执行："
    foreach ($item in $plan) {
        Write-Host ("  scp `"{0}`" {1}:{2}/" -f $item.Path, $VpsHost, $VpsDir)
    }
    Write-Host "`n上传后脚本会逐个比对远端 sha256，不一致即判失败。"
    exit 0
}

foreach ($item in $plan) {
    & scp $item.Path "${VpsHost}:${VpsDir}/" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "上传失败：$($item.File)" }
    $remoteHash = ((& ssh $VpsHost "sha256sum '$VpsDir/$($item.File)' | cut -d' ' -f1") -join '').Trim().ToLower()
    if ($remoteHash -ne $item.Sha256) {
        throw "上传后校验不一致：$($item.File)（本地 $($item.Sha256) / 远端 $remoteHash）"
    }
    Write-Host ("已上传并校验 {0,-24} {1,7:N1} MiB" -f $item.File, ($item.Bytes / 1MB))
}
Write-Host "`n共 $($plan.Count) 个文件，合计 $totalMb MiB；已逐个比对 sha256。"
Write-Host "服务端 /models 是静态目录，不需要重启 visionguard.service。"
