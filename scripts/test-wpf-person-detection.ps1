[CmdletBinding()]
param(
    [string]$FixtureDirectory = (Join-Path (Get-Location) 'artifacts\v5\fixtures'),
    [string]$ModelPath = (Join-Path (Get-Location) 'artifacts\v0\yolo26n_320.onnx'),
    [string]$ReportPath = (Join-Path (Get-Location) 'artifacts\e2e\wpf-window-person-detection.json'),
    [ValidateRange(2,16)][int]$SourceCount = 4,
    [float]$ConfidenceThreshold = 0.25
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Location).Path
$smokeProject = Join-Path $repoRoot 'detector\windows-wpf-smoke\VisionGuard.WpfSmoke.csproj'
$localRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.local'))
$runRoot = [System.IO.Path]::GetFullPath((Join-Path $localRoot ("wpf-window-smoke-" + [Guid]::NewGuid().ToString('N'))))
$handleFile = Join-Path $runRoot 'window-handles.txt'
$windowExe = Join-Path $repoRoot 'detector\windows-winforms-smoke\bin\Release\net472\VisionGuard.WinFormsSmoke.exe'
$helper = $null

if (-not (Test-Path -LiteralPath $smokeProject)) { throw "WPF smoke project not found: $smokeProject" }
if (-not (Test-Path -LiteralPath $windowExe)) { throw "DPI-unaware net472 fixture not found: $windowExe" }
if (-not (Test-Path -LiteralPath $ModelPath)) { throw "ONNX model not found: $ModelPath" }
if (-not (Test-Path -LiteralPath $FixtureDirectory)) { throw "Fixture directory not found: $FixtureDirectory" }
$images = @(Get-ChildItem -LiteralPath $FixtureDirectory -File |
    Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.bmp' } | Sort-Object Name)
if ($images.Count -lt $SourceCount) { throw "Expected at least $SourceCount person fixtures, found $($images.Count) in $FixtureDirectory" }
$images = @($images | Select-Object -First $SourceCount)
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$fullReportPath = [System.IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fullReportPath) | Out-Null

try {
    # 使用不感知 DPI 的 net472 独立窗口作为目标。浏览器 --app 对 PrintWindow
    # 可能返回空白暗帧，且无法稳定覆盖 DWM 拉伸与窗口自绘坐标错配。
    $arguments = @($handleFile)
    foreach ($image in $images) { $arguments += $image.FullName }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $windowExe
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.Arguments = [string]::Join(' ', @($arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }))
    $helper = [System.Diagnostics.Process]::Start($startInfo)

    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        if ($helper.HasExited) { throw "DPI-unaware fixture exited early: $($helper.ExitCode)" }
        $handles = if ([System.IO.File]::Exists($handleFile)) { [System.IO.File]::ReadAllLines($handleFile) } else { @() }
    } while ($handles.Length -ne $SourceCount -and (Get-Date) -lt $deadline)

    if ($handles.Length -ne $SourceCount) { throw "Only $($handles.Length)/$SourceCount DPI-unaware fixture windows became visible." }

    Write-Host "Running WPF person-detection smoke with $SourceCount real WindowHandle sources..."
    & dotnet run --project $smokeProject -c Release -- $handleFile $ModelPath $fullReportPath $ConfidenceThreshold
    if ($LASTEXITCODE -ne 0) { throw "WPF $SourceCount-window smoke failed with exit code $LASTEXITCODE. Report: $fullReportPath" }
    $report = Get-Content -LiteralPath $fullReportPath -Raw | ConvertFrom-Json
    $missing = @($report.sources | Where-Object { $_.personHitFrames -lt 1 })
    if (-not $report.passed -or $missing.Count -gt 0) { throw "$SourceCount-window person-detection smoke did not pass: $fullReportPath" }
    Write-Host "PASS: all $SourceCount real window sources produced person detections. Report: $fullReportPath"
}
finally {
    if ($null -ne $helper -and -not $helper.HasExited) {
        Stop-Process -Id $helper.Id -Force -ErrorAction SilentlyContinue
        $helper.WaitForExit(5000) | Out-Null
    }
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    $expectedPrefix = $localRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedRunRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedRunRoot)) {
        [System.IO.Directory]::Delete($resolvedRunRoot, $true)
    }
}
