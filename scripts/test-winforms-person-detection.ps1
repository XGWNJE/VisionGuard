[CmdletBinding()]
param(
    [string]$RepoRoot = (Get-Location).Path,
    [string]$ModelPath = (Join-Path $env:APPDATA 'VisionGuard\models\yolov5nu_320.onnx'),
    [string]$FixturePath = '',
    [string]$ReportPath = '',
    [ValidateRange(2,16)][int]$SourceCount = 4,
    [ValidateRange(0,3600)][int]$DurationSeconds = 0,
    [float]$ConfidenceThreshold = 0.25
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Web.Extensions
$RepoRoot = [System.IO.Path]::GetFullPath($RepoRoot)
if ([string]::IsNullOrEmpty($FixturePath)) {
    $FixturePath = Join-Path $RepoRoot 'artifacts\v5\fixtures\vg-v5-person-zidane.jpg'
}
if ([string]::IsNullOrEmpty($ReportPath)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ReportPath = Join-Path $RepoRoot ("artifacts\e2e\" + $stamp + '\winforms-person-detection.json')
}

$windowExe = Join-Path $RepoRoot 'detector\windows-winforms-smoke\bin\Release\net472\VisionGuard.WinFormsSmoke.exe'
$inferenceExe = Join-Path $RepoRoot 'detector\windows-winforms-smoke\bin\Release\net472\VisionGuard.WinFormsInferenceSmoke.exe'
$localRoot = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot '.local'))
$runRoot = Join-Path $localRoot ('winforms-smoke-' + [Guid]::NewGuid().ToString('N'))
$handleFile = Join-Path $runRoot 'handles.txt'
$helper = $null

foreach ($required in @($windowExe, $inferenceExe, $ModelPath, $FixturePath)) {
    if (-not [System.IO.File]::Exists($required)) { throw "Required file not found: $required" }
}
[System.IO.Directory]::CreateDirectory($runRoot) | Out-Null
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($ReportPath))) | Out-Null

try {
    $arguments = @('"' + $handleFile + '"')
    for ($i = 0; $i -lt $SourceCount; $i++) { $arguments += '"' + $FixturePath + '"' }
    $helper = Start-Process -FilePath $windowExe -ArgumentList $arguments -PassThru

    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        if ($helper.HasExited) { throw "Test-window helper exited early: $($helper.ExitCode)" }
        $handles = if ([System.IO.File]::Exists($handleFile)) { [System.IO.File]::ReadAllLines($handleFile) } else { @() }
    } while ($handles.Length -ne $SourceCount -and [DateTime]::UtcNow -lt $deadline)
    if ($handles.Length -ne $SourceCount) { throw "Only $($handles.Length)/$SourceCount HWND values were created." }

    $inferenceArguments = @($handleFile, $ModelPath, [System.IO.Path]::GetFullPath($ReportPath), $ConfidenceThreshold.ToString([System.Globalization.CultureInfo]::InvariantCulture))
    if ($DurationSeconds -gt 0) { $inferenceArguments += $DurationSeconds.ToString([System.Globalization.CultureInfo]::InvariantCulture) }
    & $inferenceExe @inferenceArguments
    if ($LASTEXITCODE -ne 0) { throw "WinForms person-detection smoke failed. Report: $ReportPath" }
    $json = [System.IO.File]::ReadAllText([System.IO.Path]::GetFullPath($ReportPath), [System.Text.Encoding]::UTF8)
    $report = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $result = $report.DeserializeObject($json)
    if (-not [bool]$result['passed']) { throw "WinForms report did not pass: $ReportPath" }
    Write-Host "PASS: $SourceCount WinForms WindowHandle sources detected person. Report: $ReportPath"
}
finally {
    if ($null -ne $helper -and -not $helper.HasExited) {
        Stop-Process -Id $helper.Id -Force -ErrorAction SilentlyContinue
        $helper.WaitForExit(5000) | Out-Null
    }
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    $prefix = $localRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedRunRoot.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and [System.IO.Directory]::Exists($resolvedRunRoot)) {
        [System.IO.Directory]::Delete($resolvedRunRoot, $true)
    }
}
