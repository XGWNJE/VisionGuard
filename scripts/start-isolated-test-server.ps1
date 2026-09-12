param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 3100,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
    [string]$Channel = 'vnext-e2e'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRoot = Join-Path $repoRoot 'server'
$dataRoot = Join-Path $repoRoot ".local\e2e-server\$Channel"

if ([string]::IsNullOrWhiteSpace($env:VISIONGUARD_API_KEY)) {
    throw 'Set VISIONGUARD_API_KEY in the current process before starting the isolated test server.'
}

New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$env:BIND_HOST = '0.0.0.0'
$env:PORT = $Port.ToString()
$env:API_KEY = $env:VISIONGUARD_API_KEY
$env:VISIONGUARD_CHANNEL = $Channel
$env:VISIONGUARD_DATA_DIR = $dataRoot

Write-Host "Starting isolated VisionGuard channel '$Channel' on port $Port"
Write-Host "Data directory: $dataRoot"
Write-Host "WPF/WinForms: VISIONGUARD_SERVER_URL=http://127.0.0.1:$Port; VISIONGUARD_CHANNEL=$Channel"
Write-Host "Android emulator build: VISIONGUARD_SERVER_URL=http://10.0.2.2:$Port; VISIONGUARD_CHANNEL=$Channel"

& npm.cmd --prefix $serverRoot run build
if ($LASTEXITCODE -ne 0) { throw "Server build failed with exit code $LASTEXITCODE" }
& node.exe (Join-Path $serverRoot 'dist\index.js')
exit $LASTEXITCODE
