[CmdletBinding()]
param(
    [string]$FixtureDirectory = (Join-Path (Get-Location) 'artifacts\v5\fixtures'),
    [string]$ModelPath = (Join-Path (Get-Location) 'artifacts\v0\yolo26n_320.onnx'),
    [string]$ReportPath = (Join-Path (Get-Location) 'artifacts\e2e\wpf-four-window-person-detection.json'),
    [float]$ConfidenceThreshold = 0.25
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Location).Path
$smokeProject = Join-Path $repoRoot 'detector\windows-wpf-smoke\VisionGuard.WpfSmoke.csproj'
$localRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.local'))
$profileRoot = [System.IO.Path]::GetFullPath((Join-Path $localRoot ("wpf-window-smoke-" + [Guid]::NewGuid().ToString('N'))))
$titleFile = Join-Path $profileRoot 'window-titles.txt'

function Resolve-Browser {
    $command = Get-Command chrome.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe')
    )
    return $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
}

if (-not (Test-Path -LiteralPath $smokeProject)) { throw "WPF smoke project not found: $smokeProject" }
if (-not (Test-Path -LiteralPath $ModelPath)) { throw "ONNX model not found: $ModelPath" }
if (-not (Test-Path -LiteralPath $FixtureDirectory)) { throw "Fixture directory not found: $FixtureDirectory" }
$images = @(Get-ChildItem -LiteralPath $FixtureDirectory -File |
    Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.bmp' } | Sort-Object Name)
if ($images.Count -ne 4) { throw "Expected exactly four person fixtures, found $($images.Count) in $FixtureDirectory" }
$browser = Resolve-Browser
if (-not $browser) { throw 'Chrome or Edge was not found.' }

New-Item -ItemType Directory -Force -Path $profileRoot | Out-Null
$fullReportPath = [System.IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fullReportPath) | Out-Null

try {
    for ($i = 0; $i -lt $images.Count; $i++) {
        $profile = Join-Path $profileRoot "profile-$i"
        $uri = [Uri]::new($images[$i].FullName).AbsoluteUri
        $x = 80 + (($i % 2) * 760)
        $y = 80 + ([Math]::Floor($i / 2) * 480)
        Start-Process -FilePath $browser -ArgumentList @(
            "--user-data-dir=$profile", '--no-first-run', '--disable-session-crashed-bubble',
            "--window-position=$x,$y", '--window-size=720,440', "--app=$uri"
        ) | Out-Null
    }

    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        $windowProcesses = @(Get-Process chrome,msedge -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle })
        $matched = @(foreach ($image in $images) {
            $matches = @($windowProcesses | Where-Object { $_.MainWindowTitle -like "*$($image.Name)*" })
            if ($matches.Count -eq 1) { $matches[0] }
        })
    } while ($matched.Count -ne 4 -and (Get-Date) -lt $deadline)

    if ($matched.Count -ne 4) { throw "Only $($matched.Count)/4 independent browser windows became visible." }
    $titles = foreach ($image in $images) {
        ($windowProcesses | Where-Object { $_.MainWindowTitle -like "*$($image.Name)*" } | Select-Object -First 1 -ExpandProperty MainWindowTitle)
    }
    [System.IO.File]::WriteAllLines($titleFile, $titles, [System.Text.UTF8Encoding]::new($false))

    Write-Host 'Running WPF person-detection smoke with four real WindowHandle sources...'
    & dotnet run --project $smokeProject -c Release -- $titleFile $ModelPath $fullReportPath $ConfidenceThreshold
    if ($LASTEXITCODE -ne 0) { throw "WPF four-window smoke failed with exit code $LASTEXITCODE. Report: $fullReportPath" }
    $report = Get-Content -LiteralPath $fullReportPath -Raw | ConvertFrom-Json
    $missing = @($report.sources | Where-Object { $_.personHitFrames -lt 1 })
    if (-not $report.passed -or $missing.Count -gt 0) { throw "Four-window person-detection smoke did not pass: $fullReportPath" }
    Write-Host "PASS: all four real window sources produced person detections. Report: $fullReportPath"
}
finally {
    $owned = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in 'chrome.exe', 'msedge.exe' -and $_.CommandLine -and $_.CommandLine.IndexOf($profileRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    foreach ($process in $owned) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 500
    $resolvedProfile = [System.IO.Path]::GetFullPath($profileRoot)
    $expectedPrefix = $localRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedProfile.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedProfile)) {
        [System.IO.Directory]::Delete($resolvedProfile, $true)
    }
}
