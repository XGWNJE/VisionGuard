param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 3100,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
    [string]$Channel = 'account-stream-e2e',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRoot = Join-Path $repoRoot 'server'
$dataRoot = Join-Path $repoRoot ".local\e2e-server\$Channel"

New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$env:BIND_HOST = '0.0.0.0'
$env:PORT = $Port.ToString()
$env:VISIONGUARD_CHANNEL = $Channel
$env:VISIONGUARD_DATA_DIR = $dataRoot
Remove-Item Env:VISIONGUARD_IDENTITIES_FILE -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TEST_CONSOLE_AUTOLOGIN -ErrorAction SilentlyContinue
Remove-Item Env:API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TLS_CERT_FILE -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TLS_KEY_FILE -ErrorAction SilentlyContinue

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use; the existing process was left running." }
if (-not $SkipBuild) {
    & npm.cmd --prefix $serverRoot run build
    if ($LASTEXITCODE -ne 0) { throw "Server build failed with exit code $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath (Join-Path $serverRoot 'dist/index.js'))) { throw 'Build the service before using -SkipBuild.' }

$testAccountsPath = Join-Path $dataRoot 'test-accounts.json'
if (-not (Test-Path -LiteralPath $testAccountsPath)) {
    $taskRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $taskAccounts = @('vg-test', 'vg-isolation') | ForEach-Object {
            $taskBytes = New-Object byte[] 24
            $taskRandom.GetBytes($taskBytes)
            [pscustomobject]@{ username = $_; password = [Convert]::ToBase64String($taskBytes); }
        }
    } finally { $taskRandom.Dispose() }
    [System.IO.File]::WriteAllText($testAccountsPath, ($taskAccounts | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
}
$taskAccounts = Get-Content -LiteralPath $testAccountsPath -Raw -Encoding UTF8 | ConvertFrom-Json
try {
    foreach ($taskAccount in $taskAccounts) {
        $taskAccountStorePath = Join-Path $dataRoot 'accounts.json'
        if (Test-Path -LiteralPath $taskAccountStorePath) {
            try { $taskExistingAccounts = (Get-Content -LiteralPath $taskAccountStorePath -Raw -Encoding UTF8 | ConvertFrom-Json).accounts }
            catch { throw 'The private account store could not be read. Its contents were not displayed.' }
            if ($taskExistingAccounts | Where-Object { $_.username -eq $taskAccount.username }) { continue }
        }
        $env:VISIONGUARD_ACCOUNT_PASSWORD = $taskAccount.password
        $taskOutput = & node.exe (Join-Path $repoRoot 'scripts/provision-account.js') $taskAccount.username 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Failed to provision test account $($taskAccount.username)." }
    }
} finally { Remove-Item Env:VISIONGUARD_ACCOUNT_PASSWORD -ErrorAction SilentlyContinue }

Write-Host "Starting isolated VisionGuard '$Channel' on port $Port"
Write-Host "Data directory: $dataRoot"
Write-Host "Private test account file: $testAccountsPath"
Write-Host "Web: http://127.0.0.1:$Port/console/"
Write-Host "Windows service URL: http://127.0.0.1:$Port"
Write-Host "Emulator service URL: http://10.0.2.2:$Port"

& node.exe (Join-Path $serverRoot 'dist\index.js')
exit $LASTEXITCODE
