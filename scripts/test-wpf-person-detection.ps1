[CmdletBinding()]
param(
    [string]$FixtureDirectory = (Join-Path (Get-Location) 'artifacts\e2e\person-fixtures-valid'),
    [string]$ModelPath = (Join-Path (Get-Location) 'artifacts\v0\yolo26n_320.onnx'),
    [string]$ReportPath = (Join-Path (Get-Location) 'artifacts\e2e\wpf-person-detection.json'),
    [switch]$PrepareFixtures,
    [float]$ConfidenceThreshold = 0.25
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Location).Path
$smokeProject = Join-Path $repoRoot 'detector\windows-wpf-smoke\VisionGuard.WpfSmoke.csproj'

function Download-Fixture([string]$Name, [string]$Uri) {
    $target = Join-Path $FixtureDirectory $Name
    if (-not (Test-Path -LiteralPath $target)) {
        Write-Host "Downloading $Name"
        Invoke-WebRequest -Uri $Uri -OutFile $target
    }
}

function Prepare-CocoPersonFixture {
    $archive = Join-Path $FixtureDirectory '.coco12-formats.zip'
    $extractDirectory = Join-Path $FixtureDirectory '.coco12-formats'
    $target = Join-Path $FixtureDirectory 'person-umbrella.jpg'
    if (Test-Path -LiteralPath $target) {
        return
    }

    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Host 'Downloading COCO format fixture archive'
        Invoke-WebRequest -Uri 'https://github.com/ultralytics/assets/releases/download/v0.0.0/coco12-formats.zip' -OutFile $archive
    }
    if (-not (Test-Path -LiteralPath $extractDirectory)) {
        Expand-Archive -LiteralPath $archive -DestinationPath $extractDirectory -Force
    }

    # COCO class 0 is person; this fixture is the labelled umbrella/person image
    # 000000000036.jpeg from the archive.
    $source = Get-ChildItem -LiteralPath $extractDirectory -Recurse -File -Filter '000000000036.jpeg' | Select-Object -First 1
    if ($null -eq $source) {
        throw 'COCO person fixture 000000000036.jpeg was not found after extraction.'
    }
    Copy-Item -LiteralPath $source.FullName -Destination $target
}

if ($PrepareFixtures) {
    New-Item -ItemType Directory -Force -Path $FixtureDirectory | Out-Null

    # Public Ultralytics sample assets: bus (multiple pedestrians), Zidane/Ancelotti
    # (two prominent people), and a road scene with a small pedestrian target.
    Download-Fixture 'person-bus.jpg' 'https://raw.githubusercontent.com/ultralytics/assets/main/im/bus.jpg'
    Download-Fixture 'person-zidane.jpg' 'https://raw.githubusercontent.com/ultralytics/assets/main/im/zidane.jpg'
    Prepare-CocoPersonFixture
}

if (-not (Test-Path -LiteralPath $smokeProject)) {
    throw "WPF smoke project not found: $smokeProject"
}
if (-not (Test-Path -LiteralPath $ModelPath)) {
    throw "ONNX model not found: $ModelPath"
}
if (-not (Test-Path -LiteralPath $FixtureDirectory)) {
    throw "Fixture directory not found: $FixtureDirectory. Run with -PrepareFixtures or provide three person images."
}

$images = @(Get-ChildItem -LiteralPath $FixtureDirectory -File |
    Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.bmp' } |
    Sort-Object Name)
if ($images.Count -ne 3) {
    throw "Expected exactly three jpg/jpeg/png/bmp person fixtures, found $($images.Count) in $FixtureDirectory"
}

$fullReportPath = [System.IO.Path]::GetFullPath($ReportPath)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $fullReportPath) | Out-Null

Write-Host "Running WPF person-detection smoke with three image sources..."
& dotnet run --project $smokeProject -c Release -- $FixtureDirectory $ModelPath $fullReportPath $ConfidenceThreshold
if ($LASTEXITCODE -ne 0) {
    throw "WPF person-detection smoke failed with exit code $LASTEXITCODE. Report: $fullReportPath"
}

$report = Get-Content -LiteralPath $fullReportPath -Raw | ConvertFrom-Json
$missingPersonHits = @($report.sources | Where-Object { $_.personHitFrames -lt 1 })
if (-not $report.passed -or $missingPersonHits.Count -gt 0) {
    $missing = ($missingPersonHits | ForEach-Object { $_.SourceId }) -join ', '
    throw "Person-detection smoke did not pass. Sources without a person hit: $missing. Report: $fullReportPath"
}

Write-Host "PASS: all three image sources produced person detections."
Write-Host "Report: $fullReportPath"
