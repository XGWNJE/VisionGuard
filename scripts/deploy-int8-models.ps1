# 把量化模型投放到统一服务的模型目录。
#
# 用法（先在本地演练，确认无误再对生产执行；生产属发布动作，需 owner 授权）：
#   pwsh scripts/deploy-int8-models.ps1 -WhatIf          # 只校验与预览，不复制
#   pwsh scripts/deploy-int8-models.ps1                 # 校验并复制到 server/data/models
#   pwsh scripts/deploy-int8-models.ps1 -Destination <其他目录>
#
# 做三件事，任何一步失败就整体中止（不做部分投放）：
#   1. 逐个用 ONNX Runtime 真加载 + 跑一帧，确认文件完整可用；
#   2. 校验目标目录里的 FP32 本体存在（量化版是「并存」，本体缺了就是配置错误）；
#   3. 复制并打印大小与 SHA256，供投放后核对。
param(
    [string]$Source = '.local/model-quant',
    [string]$Destination = 'server/data/models',
    [string[]]$Keys = @(
        'yolo26n_320', 'yolo26n_640',
        'yolo26s_320', 'yolo26s_640',
        'yolo26m_320', 'yolo26m_640'
    ),
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

function Resolve-RepoPath([string]$path) {
    if ([System.IO.Path]::IsPathRooted($path)) { return $path }
    return (Join-Path $repo $path)
}

$sourceDir = Resolve-RepoPath $Source
$destinationDir = Resolve-RepoPath $Destination
if (-not (Test-Path $destinationDir)) { throw "目标目录不存在：$destinationDir" }

# 1. 校验每个量化文件真的能加载并推理——只看文件大小会漏掉半截文件。
$violations = @()
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
    if ($LASTEXITCODE -ne 0 -or ($result -join ' ') -notmatch '^ok ') {
        $violations += "加载失败：$file -> $($result -join ' ')"
    } else {
        Write-Host ("校验通过 {0,-18} {1}" -f "${key}_int8.onnx", (($result -join ' ').Trim()))
    }

    $base = Join-Path $destinationDir "$key.onnx"
    if (-not (Test-Path $base)) {
        $violations += "目标目录缺少 FP32 本体：$base（量化版是并存，本体必须在）"
    }
}

if ($violations.Count -gt 0) {
    Write-Host "`n未通过校验，未复制任何文件：" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}

# 2. 复制并输出清单。
$manifest = @()
foreach ($key in $Keys) {
    $file = Join-Path $sourceDir "${key}_int8.onnx"
    $target = Join-Path $destinationDir "${key}_int8.onnx"
    $size = (Get-Item $file).Length
    $hash = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()

    if ($WhatIf) {
        Write-Host ("[WhatIf] 将投放 {0,-24} {1,8:N1} MiB  sha256={2}" -f "${key}_int8.onnx", ($size / 1MB), $hash)
    } else {
        Copy-Item $file $target -Force
        Write-Host ("已投放   {0,-24} {1,8:N1} MiB  sha256={2}" -f "${key}_int8.onnx", ($size / 1MB), $hash)
    }
    $manifest += [pscustomobject]@{ file = "${key}_int8.onnx"; bytes = $size; sha256 = $hash }
}

Write-Host "`n共 $($manifest.Count) 个文件，合计 $([math]::Round(($manifest | Measure-Object bytes -Sum).Sum / 1MB, 1)) MiB"
if ($WhatIf) { Write-Host "（演练模式，未复制）" } else { Write-Host "投放完成；端侧按同名键下载 /models/<键>.onnx" }
