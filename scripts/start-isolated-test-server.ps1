# 本地服务（局域网）入口：起一个隔离的统一服务实例，供预览 / 验收 / 调试共用。
#
# 三条约定：
#   1) 服务绑定 0.0.0.0，端口默认每次自动挑一个空闲端口 —— 地址与端口由 `endpoint.json` 同步给
#      Windows / Web / Android 各端，不再各自手填或靠 adb reverse；
#   2) 同时开启局域网 UDP 信标，Android 端据此自动发现地址与端口（见 android-shared 的
#      LocalServiceDiscovery），端口变化不需要重新构建或安装；
#   3) 本地发行覆盖层（`releases.local.json`）让本地构建的组件包也能被更新接口与客户端取到，
#      跟踪的 `server/data/releases.json` 不被污染；线上实例不设这些变量，行为完全不变。
param(
    # 0 = 每次创建服务时自动挑一个新的空闲端口（本地服务地址按实例变化，靠端点清单同步给各端）。
    [ValidateRange(0, 65535)]
    [int]$Port = 0,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
    [string]$Channel = 'local-service',
    # 调用方（ui-preview.ps1）把自己那轮用过的端口递进来，保证换一轮实例就换一个端口。
    [ValidateRange(0, 65535)]
    [int]$AvoidPort = 0,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverRoot = Join-Path $repoRoot 'server'
$dataRoot = Join-Path $repoRoot ".local\e2e-server\$Channel"
$endpointPath = Join-Path $dataRoot 'endpoint.json'
$localReleasesPath = Join-Path $dataRoot 'releases.local.json'

New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null

function Get-DeviceLanPrefix {
    # 真机与电脑同网段才谈得上局域网直连：有已授权设备时优先选与它同一 /24 的地址。
    try {
        $serialLine = @(& adb devices | Where-Object { $_ -match '^\S+\s+device$' }) | Select-Object -First 1
        if (-not $serialLine) { return '' }
        $serial = ($serialLine -split '\s+')[0]
        $addressLine = @(& adb -s $serial shell ip -4 addr show wlan0 2>$null | Where-Object { $_ -match '\sinet\s' }) | Select-Object -First 1
        if (-not $addressLine) { return '' }
        $address = [regex]::Match($addressLine, 'inet\s+(\d+\.\d+\.\d+)\.\d+').Groups[1].Value
        return $address
    } catch { return '' }
}

function Get-LanAddress {
    # 局域网地址给真机用：优先与已连接设备同网段，其次默认路由网卡，再退 192.168 / 10 私网段，
    # 绕开 Clash 等虚拟网卡（198.18/198.19）与链路本地地址，避免把不可达地址发给设备。
    $blocked = '^(127\.|169\.254\.|198\.1[89]\.|172\.(1[6-9]|2[0-9]|3[01])\.)'
    $addresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch $blocked })
    $devicePrefix = Get-DeviceLanPrefix
    if ($devicePrefix) {
        $sameSubnet = $addresses | Where-Object { $_.IPAddress.StartsWith("$devicePrefix.") } | Select-Object -First 1
        if ($sameSubnet) { return $sameSubnet.IPAddress }
    }
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric | Select-Object -First 1
    if ($route) {
        $preferred = $addresses | Where-Object { $_.InterfaceIndex -eq $route.InterfaceIndex } | Select-Object -First 1
        if ($preferred) { return $preferred.IPAddress }
    }
    foreach ($pattern in @('^192\.168\.', '^10\.')) {
        $match = $addresses | Where-Object { $_.IPAddress -match $pattern } | Select-Object -First 1
        if ($match) { return $match.IPAddress }
    }
    throw '找不到可用于局域网的 IPv4 地址，无法给真机同步服务地址。'
}

function Test-PortFree {
    param([int]$Candidate)
    return -not (Get-NetTCPConnection -LocalPort $Candidate -State Listen -ErrorAction SilentlyContinue)
}

if ($Port -eq 0) {
    # 每次创建端口：跳过上一轮实例占用的端口（清单里的或调用方指定的），一眼就能分辨本轮实例。
    $previousPort = [Math]::Max(0, $AvoidPort)
    if (Test-Path -LiteralPath $endpointPath) {
        try {
            $recorded = [int](Get-Content -LiteralPath $endpointPath -Raw -Encoding UTF8 | ConvertFrom-Json).port
            if ($recorded -gt 0) { $previousPort = $recorded }
        } catch { }
    }
    foreach ($candidate in 4320..4399) {
        if ($candidate -eq $previousPort) { continue }
        if (Test-PortFree -Candidate $candidate) { $Port = $candidate; break }
    }
    if ($Port -eq 0) { throw '4320-4399 没有空闲端口，先停掉不再使用的本地服务。' }
}
elseif (-not (Test-PortFree -Candidate $Port)) {
    throw "Port $Port is already in use; the existing process was left running."
}
$env:BIND_HOST = '0.0.0.0'
$env:PORT = $Port.ToString()
$env:VISIONGUARD_CHANNEL = $Channel
$env:VISIONGUARD_DATA_DIR = $dataRoot
Remove-Item Env:VISIONGUARD_IDENTITIES_FILE -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TEST_CONSOLE_AUTOLOGIN -ErrorAction SilentlyContinue
Remove-Item Env:API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TLS_CERT_FILE -ErrorAction SilentlyContinue
Remove-Item Env:VISIONGUARD_TLS_KEY_FILE -ErrorAction SilentlyContinue
# 局域网信标 + 本地发行覆盖层：只有本地服务实例设置，生产实例没有这两个变量。
$env:VISIONGUARD_LAN_BEACON = '1'
if (-not $env:VISIONGUARD_LAN_BEACON_PORT) { $env:VISIONGUARD_LAN_BEACON_PORT = '45899' }
$env:VISIONGUARD_LOCAL_RELEASES_FILE = $localReleasesPath

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

# 本地预览免登录：把测试账号作为内置凭据交给本实例，控制台与 Web 相机据此跳过手填登录。
# 端点只服务回环请求（server/src/routes/account.ts），生产实例不设该变量、返回 404。
$taskPreviewAccount = @($taskAccounts) | Where-Object { $_.username -eq 'vg-test' } | Select-Object -First 1
if (-not $taskPreviewAccount) { $taskPreviewAccount = @($taskAccounts) | Select-Object -First 1 }
$env:VISIONGUARD_PREVIEW_SIGNIN = (@{ username = $taskPreviewAccount.username; password = $taskPreviewAccount.password } | ConvertTo-Json -Compress)

# 端点清单是各端唯一的对齐入口：本地服务每次创建端口，地址一变就靠这份文件同步，
# 不再让构建脚本各自猜地址、也不再出现「预览包指向哪里」的歧义。
$lanAddress = Get-LanAddress
$endpoint = [ordered]@{
    channel = $Channel
    port = $Port
    createdAt = (Get-Date).ToString('o')
    lanUrl = "http://${lanAddress}:$Port"
    loopbackUrl = "http://127.0.0.1:$Port"
    emulatorUrl = "http://10.0.2.2:$Port"
    accountsFile = ".local/e2e-server/$Channel/test-accounts.json"
    releasesFile = ".local/e2e-server/$Channel/releases.local.json"
    username = $taskPreviewAccount.username
    dataRoot = ".local/e2e-server/$Channel"
}
[System.IO.File]::WriteAllText($endpointPath, ($endpoint | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Starting isolated VisionGuard '$Channel' on port $Port"
Write-Host "Data directory: $dataRoot"
Write-Host "Private test account file: $testAccountsPath"
Write-Host "Endpoint manifest: $endpointPath"
Write-Host "Android (真机局域网): $($endpoint.lanUrl)"
Write-Host "Android (模拟器): $($endpoint.emulatorUrl)"
Write-Host "Windows / Web (同机回环): $($endpoint.loopbackUrl) 控制台 $($endpoint.loopbackUrl)/console/"
Write-Host "局域网发现信标: udp/$($env:VISIONGUARD_LAN_BEACON_PORT) channel=$Channel"
Write-Host "本地发行覆盖层: $localReleasesPath（不存在表示只提供线上发行元数据）"

& node.exe (Join-Path $serverRoot 'dist\index.js')
exit $LASTEXITCODE
