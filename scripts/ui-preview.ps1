# 本地服务版单一入口：起/停本地服务、校验或补齐资源、构建并安装指向本机局域网服务的各端。
#
# 为什么只留这一个入口：预览、验收、调试统一用「服务指向本地服务」这一种方式 ——
# 地址与端口由本地服务启动时确定并写进端点清单，各端都从清单取；不再手填地址、不再 adb reverse、
# 不再有第二种包形态。Android 端额外内置局域网自动发现（见 android-shared 的 LocalServiceDiscovery），
# 因此服务换端口后不需要重新构建或安装。
#
# 常用：
#   .\scripts\ui-preview.ps1 -Target Service
#   .\scripts\ui-preview.ps1 -Target Android -Component Inference -Install -DeviceSerial <序号>
#   .\scripts\ui-preview.ps1 -Target Resources -Refresh
#   .\scripts\ui-preview.ps1 -Target Stop
param(
    [ValidateSet('Service', 'Stop', 'Resources', 'Android', 'AndroidPackages', 'Windows', 'PrepareWindows')]
    [string]$Target = 'Service',
    [ValidateSet('normal', 'limits', 'empty')]
    [string]$Scene = 'normal',
    # Windows 预览：显式要求停在登录界面调登录流程（默认自动登录）。
    [switch]$LoginUi,
    # 要构建/安装的 Android 端；AndroidPackages 忽略它，三个端都做。
    [ValidateSet('All', 'Camera', 'Inference', 'Notifier')]
    [string]$Component = 'Inference',
    # 构建后直接装到真机：先按规则清理同组件实例，再走统一安装入口。
    [switch]$Install,
    [string]$DeviceSerial = '',
    # 保留设备上已装的发行版/本地版实例（默认清理，避免新旧实例互相干扰）。
    [switch]$SkipInstanceReset,
    # Resources：补齐缺失的模型与组件包；默认只校验并报告，不动已有缓存。
    [switch]$Refresh,
    # Resources：把本地构建产物登记成本地夹具组件包，供更新流程在本地服务上演练。
    [ValidateSet('None', 'Camera', 'Inference', 'Notifier')]
    [string]$FixtureComponent = 'None',
    [string]$FixtureVersion = '',
    # Resources：清掉本地夹具包与其覆盖层条目。
    [switch]$ClearFixtures
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskDirectory = Join-Path $taskRoot '.local\console-preview'
$taskChannel = 'local-service'
$taskStateRoot = Join-Path $taskRoot ".local\e2e-server\$taskChannel"
$taskEndpointPath = Join-Path $taskStateRoot 'endpoint.json'
$taskAccountsPath = Join-Path $taskStateRoot 'test-accounts.json'
$taskReleasesPath = Join-Path $taskStateRoot 'releases.local.json'
$taskResourceReport = Join-Path $taskStateRoot 'resources.json'
$taskPackages = Join-Path $taskDirectory 'packages'
$taskModelsDir = Join-Path $taskRoot 'server\data\models'
$taskReleasesDir = Join-Path $taskRoot 'server\data\releases'
$taskStateFile = Join-Path $taskDirectory 'launcher-processes.json'
$taskRepository = 'XGWNJE/VisionGuard'
New-Item -ItemType Directory -Force -Path $taskDirectory, $taskPackages, $taskStateRoot | Out-Null

# 三个 Android 端：工程路径、Gradle 工程名、正式包名、产物简称（预览包名 = 正式包名 + .uipreview）。
$taskComponents = [ordered]@{
    Camera    = @{ Path = 'detector\android';           Project = 'VisionGuard.Detector.Android';  Package = 'com.xgwnje.visionguard.detector';  Apk = 'camera' }
    Inference = @{ Path = 'detector\android-inference'; Project = 'VisionGuard.Inference.Android'; Package = 'com.xgwnje.visionguard.inference'; Apk = 'inference' }
    Notifier  = @{ Path = 'notifier\android';           Project = 'VisionGuard.Notifier.Android';  Package = 'com.xgwnje.visionguard.notifier';  Apk = 'notifier' }
}

function Save-State {
    param($State)
    [IO.File]::WriteAllText($taskStateFile, ($State | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
}
function Get-State {
    if (Test-Path -LiteralPath $taskStateFile) { return Get-Content -LiteralPath $taskStateFile -Raw -Encoding UTF8 | ConvertFrom-Json }
    return [pscustomobject]@{ Service = 0; Fixture = 0 }
}
function Wait-Local([string]$Url) {
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        try { return Invoke-RestMethod -Uri $Url -TimeoutSec 1 } catch { Start-Sleep -Milliseconds 300 }
    }
    throw "本地服务未就绪：$Url"
}
function Assert-LocalOwner([int]$Port, [string]$ScriptName) {
    # 只认路径属于本项目、且命令行含指定脚本的进程；未知占用一律不碰。
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$listener) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if (!$process -or !$process.CommandLine.Contains($taskRoot) -or !$process.CommandLine.Contains($ScriptName)) {
        throw "端口 $Port 属于未知进程，未做处理（PID $($listener.OwningProcess)）。"
    }
    return $listener.OwningProcess
}
function Get-Endpoint {
    if (!(Test-Path -LiteralPath $taskEndpointPath)) { throw "本地服务端点清单不存在：$taskEndpointPath；先运行 -Target Service。" }
    return Get-Content -LiteralPath $taskEndpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
function Sync-ServerBuild {
    # 本地服务新增了信标与本地发行源：dist 落后于源码时只重编 tsc，不重建控制台（那条路慢且没必要）。
    $dist = Join-Path $taskRoot 'server\dist\index.js'
    $stale = -not (Test-Path -LiteralPath $dist)
    if (-not $stale) {
        $distTime = (Get-Item -LiteralPath $dist).LastWriteTime
        $stale = @(Get-ChildItem (Join-Path $taskRoot 'server\src') -Recurse -File -Filter *.ts |
            Where-Object { $_.LastWriteTime -gt $distTime }).Count -gt 0
    }
    if (-not $stale) { return }
    Write-Host '统一服务 dist 落后于源码，重建中…'
    $log = Join-Path $taskDirectory 'service-build.log'
    Push-Location (Join-Path $taskRoot 'server')
    try { & (Join-Path $taskRoot 'server\node_modules\.bin\tsc.cmd') *> $log } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "统一服务构建失败，详见 $log" }
}
function Start-Detached {
    # 长驻子进程必须完全脱离当前控制台：用 Start-Process 起的子进程只要还活着，外层
    # `powershell -File` 就一直不返回（脚本早就跑完，终端却停在那里）。改用 WMI 创建进程，
    # 日志交给 cmd 重定向；代价是子进程拿不到本进程的环境变量，所以参数一律走命令行。
    param([string]$CommandLine)
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $CommandLine; CurrentDirectory = $taskRoot }
    if ($result.ReturnValue -ne 0) { throw "启动后台进程失败：Win32_Process.Create 返回 $($result.ReturnValue)" }
    return [int]$result.ProcessId
}
function Start-LocalService {
    if (Test-Path -LiteralPath $taskEndpointPath) {
        $existing = Get-Content -LiteralPath $taskEndpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if (Assert-LocalOwner ([int]$existing.port) 'server\dist\index.js') {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$($existing.port)/health" -TimeoutSec 2
            if ($health.channel -eq $taskChannel) { return $existing }
            throw "端口 $($existing.port) 上的实例通道是 $($health.channel)，不是 $taskChannel；先 -Target Stop 或换端口。"
        }
    }
    Sync-ServerBuild
    # 不删旧清单：记下上一轮的端口与生成时间，既让新实例避开这个端口，也能认出「这是新一轮的清单」。
    $previousPort = 0
    $previousCreatedAt = ''
    if (Test-Path -LiteralPath $taskEndpointPath) {
        try {
            $previous = Get-Content -LiteralPath $taskEndpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $previousPort = [int]$previous.port
            $previousCreatedAt = [string]$previous.createdAt
        } catch { }
    }
    $serviceScript = Join-Path $taskRoot 'scripts\start-isolated-test-server.ps1'
    $stdout = Join-Path $taskDirectory 'service.stdout.log'
    $stderr = Join-Path $taskDirectory 'service.stderr.log'
    $commandLine = "cmd.exe /c powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$serviceScript`" -Channel $taskChannel -SkipBuild -AvoidPort $previousPort > `"$stdout`" 2> `"$stderr`""
    $state = Get-State; $state.Service = Start-Detached $commandLine; Save-State $state
    for ($attempt = 0; $attempt -lt 160; $attempt++) {
        if (Test-Path -LiteralPath $taskEndpointPath) {
            $endpoint = Get-Content -LiteralPath $taskEndpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($endpoint.createdAt -eq $previousCreatedAt) { Start-Sleep -Milliseconds 300; continue }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$($endpoint.port)/health" -TimeoutSec 1
                if ($health.channel -eq $taskChannel) { return $endpoint }
            } catch { }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "本地服务启动失败，详见 $(Join-Path $taskDirectory 'service.stderr.log')"
}
function Start-Fixture {
    # 控制台模拟数据只服务 Web 预览；它自己登录时可能因设备归属冲突报 403，不拦其它端。
    try {
        if (Assert-LocalOwner 4319 'ui-preview-data.js') { Invoke-RestMethod "http://127.0.0.1:4319/scene?mode=$Scene" | Out-Null; return }
        $endpoint = Get-Endpoint
        $fixtureScript = Join-Path $taskRoot 'scripts\ui-preview-data.js'
        $stdout = Join-Path $taskDirectory 'data.stdout.log'
        $stderr = Join-Path $taskDirectory 'data.stderr.log'
        # 模拟数据只在本机用回环地址（不占局域网）；账号文件与各端取同一份。
        $commandLine = "cmd.exe /c node.exe `"$fixtureScript`" --endpoint $($endpoint.loopbackUrl) --accounts `"$taskAccountsPath`" > `"$stdout`" 2> `"$stderr`""
        $state = Get-State; $state.Fixture = Start-Detached $commandLine; Save-State $state
        Wait-Local 'http://127.0.0.1:4319/' | Out-Null
        Invoke-RestMethod "http://127.0.0.1:4319/scene?mode=$Scene" | Out-Null
    } catch {
        Write-Warning "控制台模拟数据未就绪，Web 控制台预览不可用（$($_.Exception.Message)）；其余端继续。"
    }
}
function Get-ReleaseEntries {
    # 跟踪的 releases.json 是线上原件，本地覆盖层叠加在它之上（同平台覆盖）。
    $entries = [ordered]@{}
    foreach ($file in @((Join-Path $taskRoot 'server\data\releases.json'), $taskReleasesPath)) {
        if (!(Test-Path -LiteralPath $file)) { continue }
        $parsed = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($property in $parsed.PSObject.Properties) {
            $fileName = if ($property.Value.fileName) { $property.Value.fileName } else { Split-Path $property.Value.url -Leaf }
            $entries[$property.Name] = [pscustomobject]@{
                platform = $property.Name
                version = $property.Value.version
                fileName = $fileName
                size = [int64]$property.Value.size
                sha256 = ($property.Value.sha256 | Out-String).Trim().ToUpperInvariant()
                local = ($file -eq $taskReleasesPath)
            }
        }
    }
    return $entries
}
function Get-ResourceReport {
    param([switch]$Fetch)
    $models = @(Get-ChildItem $taskModelsDir -File -Filter *.onnx -ErrorAction SilentlyContinue | Sort-Object Name |
        ForEach-Object { [pscustomobject]@{ name = $_.Name; bytes = $_.Length } })
    $packages = @()
    foreach ($entry in (Get-ReleaseEntries).Values) {
        $path = Join-Path $taskReleasesDir $entry.fileName
        $state = 'missing'
        $bytes = 0
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path
            $bytes = $item.Length
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            if ($entry.size -gt 0 -and $item.Length -ne $entry.size) { $state = 'size-mismatch' }
            elseif ($entry.sha256 -and $hash -ne $entry.sha256) { $state = 'hash-mismatch' }
            else { $state = 'reused' }
        }
        if ($state -eq 'missing' -and $Fetch) {
            # 只补缺：与线上同一份发行资产，逐个核对大小与 sha256，失败即留 missing 并报告。
            $url = "https://github.com/$taskRepository/releases/download/v$($entry.version)/$([uri]::EscapeDataString($entry.fileName))"
            $temporary = "$path.download"
            try {
                Write-Host "补齐组件包：$($entry.fileName) ← $url"
                Invoke-WebRequest -Uri $url -OutFile $temporary -UseBasicParsing
                $item = Get-Item -LiteralPath $temporary
                $hash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash
                if (($entry.size -gt 0 -and $item.Length -ne $entry.size) -or ($entry.sha256 -and $hash -ne $entry.sha256)) {
                    $state = 'download-mismatch'
                } else {
                    Move-Item -LiteralPath $temporary -Destination $path -Force
                    $bytes = $item.Length
                    $state = 'fetched'
                }
            } catch {
                $state = "download-failed: $($_.Exception.Message)"
            } finally {
                if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue }
            }
        }
        $packages += [pscustomobject]@{ platform = $entry.platform; version = $entry.version; fileName = $entry.fileName; bytes = $bytes; state = $state; local = $entry.local }
    }
    $report = [ordered]@{
        createdAt = (Get-Date).ToString('o')
        channel = $taskChannel
        models = $models
        packages = $packages
    }
    [IO.File]::WriteAllText($taskResourceReport, ($report | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    return [pscustomobject]$report
}
function Show-ResourceSummary($Report) {
    $reused = @($Report.packages | Where-Object { $_.state -eq 'reused' }).Count
    $fetched = @($Report.packages | Where-Object { $_.state -eq 'fetched' }).Count
    $missing = @($Report.packages | Where-Object { $_.state -eq 'missing' }).Count
    Write-Host ("资源：模型 {0} 个；组件包 {1} 个（复用 {2} / 新取 {3} / 缺失 {4}）" -f $Report.models.Count, $Report.packages.Count, $reused, $fetched, $missing)
    foreach ($package in @($Report.packages | Where-Object { $_.state -ne 'reused' })) {
        Write-Host ("  - {0} {1}: {2}" -f $package.platform, $package.version, $package.state)
    }
    Write-Host "资源报告：$taskResourceReport"
}
function Add-Fixture {
    param([string]$Name, [string]$Version)
    $component = $taskComponents[$Name]
    $source = Join-Path $taskPackages ($component.Apk + '-ui-preview.apk')
    if (!(Test-Path -LiteralPath $source)) { $source = Join-Path $taskRoot (".local\console-preview\android\$($component.Project)\app\outputs\apk\release\app-release.apk") }
    if (!(Test-Path -LiteralPath $source)) { throw "缺少本地构建产物：$source（先构建 $Name）" }
    $platform, $artifact = @{ Camera = @('android-detector', 'Detector'); Inference = @('android-inference', 'Inference'); Notifier = @('android-notifier', 'Notifier') }[$Name]
    $fileName = "VisionGuard-$artifact-v$Version.apk"
    $destination = Join-Path $taskReleasesDir $fileName
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $overlay = [ordered]@{}
    if (Test-Path -LiteralPath $taskReleasesPath) {
        $existing = Get-Content -LiteralPath $taskReleasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($property in $existing.PSObject.Properties) { $overlay[$property.Name] = $property.Value }
    }
    $item = Get-Item -LiteralPath $destination
    $overlay[$platform] = [pscustomobject]@{
        version = $Version
        fileName = $fileName
        size = $item.Length
        sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    [IO.File]::WriteAllText($taskReleasesPath, ($overlay | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
    Write-Host "本地夹具包：$fileName（$platform / v$Version）已就绪，更新检查会优先看到它。"
}
function Clear-Fixtures {
    if (!(Test-Path -LiteralPath $taskReleasesPath)) { Write-Host '没有本地夹具包。'; return }
    $overlay = Get-Content -LiteralPath $taskReleasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($property in $overlay.PSObject.Properties) {
        $path = Join-Path $taskReleasesDir $property.Value.fileName
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    Remove-Item -LiteralPath $taskReleasesPath -Force
    Write-Host '本地夹具包与覆盖层已清理。'
}
function Reset-DeviceInstances {
    param([string]$Package, [string]$Serial)
    $allowlist = @('com.xgwnje.visionguard.detector', 'com.xgwnje.visionguard.inference', 'com.xgwnje.visionguard.notifier')
    if ($allowlist -notcontains $Package) { throw "拒绝清理白名单外的包：$Package" }
    $prefix = if ($Serial) { @('-s', $Serial) } else { @() }
    foreach ($name in @($Package, "$Package.uipreview")) {
        $listed = & adb @prefix shell pm list packages $name 2>&1
        if (($listed -join ' ') -notmatch "package:$([regex]::Escape($name))(\s|$)") { Write-Host "设备上未安装：$name"; continue }
        $output = & adb @prefix uninstall $name 2>&1
        Write-Host "已清理 $name -> $((($output -join ' ') -replace '\s+', ' ').Trim())"
    }
}
function Build-Android {
    param([string]$Name)
    $component = $taskComponents[$Name]
    if (!$env:GRADLE_USER_HOME) {
        $gradleCache = Join-Path $taskRoot '.local\acceptance-gradle'
        $env:GRADLE_USER_HOME = if (Test-Path $gradleCache) { $gradleCache } else { Join-Path $taskDirectory 'gradle-cache' }
    }
    if (!$env:JAVA_HOME) { $env:JAVA_HOME = Split-Path (Split-Path (Get-Command java.exe -ErrorAction Stop).Source -Parent) -Parent }
    $endpoint = Get-Endpoint
    if (!(Test-Path -LiteralPath $taskAccountsPath)) { throw "缺少本地服务预览账号：$taskAccountsPath" }
    $env:VISIONGUARD_PREVIEW_ENDPOINT = $endpoint.lanUrl
    $env:VISIONGUARD_PREVIEW_ACCOUNTS_FILE = $taskAccountsPath
    $log = Join-Path $taskDirectory ("build-$($component.Apk).log")
    Push-Location (Join-Path $taskRoot $component.Path)
    try { & .\gradlew.bat -I (Join-Path $taskRoot 'scripts\ui-preview.init.gradle') :app:assembleRelease :app:testDebugUnitTest --console=plain *> $log }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $log -Tail 40 | ForEach-Object { Write-Host $_ }
        throw "$Name 构建或单元测试失败，详见 $log"
    }
    $apk = Join-Path $taskRoot ".local\console-preview\android\$($component.Project)\app\outputs\apk\release\app-release.apk"
    if (!(Test-Path -LiteralPath $apk)) { throw "缺少产物：$apk" }
    $packaged = Join-Path $taskPackages ($component.Apk + '-ui-preview.apk')
    Copy-Item -LiteralPath $apk -Destination $packaged -Force
    Write-Host ("构建完成 {0}：{1}（{2:N1} MiB，服务地址 {3}）" -f $Name, $packaged, ((Get-Item $packaged).Length / 1MB), $endpoint.lanUrl)
    return $packaged
}
function Install-Android {
    param([string]$Name, [string]$Apk, [string]$Serial)
    $component = $taskComponents[$Name]
    if (!$Serial) {
        $ready = @(adb devices | Where-Object { $_ -match '^\S+\s+device$' })
        if ($ready.Count -eq 0) { throw '没有已就绪的设备：请连接设备或开启 USB 调试。' }
        if ($ready.Count -gt 1) { throw '检测到多台设备，请用 -DeviceSerial 指定。' }
    }
    if (!$SkipInstanceReset) { Reset-DeviceInstances -Package $component.Package -Serial $Serial }
    $arguments = @('-ExecutionPolicy', 'Bypass', '-File', (Join-Path $taskRoot 'scripts\install-android-apk.ps1'), '-Apk', $Apk, '-PackageName', "$($component.Package).uipreview")
    if ($Serial) { $arguments += @('-Serial', $Serial) }
    & powershell.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw '安装失败：原因与证据见 .local\apk-install 下的报告。' }
}

if ($Target -eq 'Stop') {
    $endpoint = $null
    if (Test-Path -LiteralPath $taskEndpointPath) { $endpoint = Get-Content -LiteralPath $taskEndpointPath -Raw -Encoding UTF8 | ConvertFrom-Json }
    $fixturePid = Assert-LocalOwner 4319 'ui-preview-data.js'
    if ($fixturePid) { try { Invoke-RestMethod -Uri 'http://127.0.0.1:4319/stop' -TimeoutSec 3 | Out-Null } catch { Stop-Process -Id $fixturePid -ErrorAction SilentlyContinue } }
    if ($endpoint) {
        $servicePid = Assert-LocalOwner ([int]$endpoint.port) 'server\dist\index.js'
        if ($servicePid) { Stop-Process -Id $servicePid; Write-Host "已停止本地服务（端口 $($endpoint.port)，PID $servicePid）。" }
    }
    Save-State ([pscustomobject]@{ Service = 0; Fixture = 0 })
    Write-Host '本地服务与模拟数据已停止。'
    return
}

if ($Target -eq 'Resources') {
    if ($ClearFixtures) { Clear-Fixtures }
    if ($FixtureComponent -ne 'None') {
        if (!$FixtureVersion) { throw '登记夹具包需要 -FixtureVersion（客户端只认 x.y.z 形式的版本）。' }
        Add-Fixture -Name $FixtureComponent -Version $FixtureVersion
    }
    Show-ResourceSummary (Get-ResourceReport -Fetch:$Refresh)
    return
}

$endpoint = Start-LocalService
if ($Target -in @('Service', 'Windows', 'PrepareWindows')) { Start-Fixture }

if ($Target -eq 'Service') {
    Show-ResourceSummary (Get-ResourceReport)
    Write-Host "本地服务：$($endpoint.lanUrl)（通道 $taskChannel，端口 $($endpoint.port)）"
    Write-Host "控制台：$($endpoint.loopbackUrl)/console/    场景页：http://127.0.0.1:4319/"
    Write-Host "预览账号：$taskAccountsPath（用户 $($endpoint.username)）"
    Write-Host "端点清单：$taskEndpointPath"
    Write-Host 'Android：.\scripts\ui-preview.ps1 -Target Android -Component Inference -Install'
    return
}

if ($Target -in @('Android', 'AndroidPackages')) {
    $names = if ($Target -eq 'AndroidPackages' -or $Component -eq 'All') { @('Camera', 'Inference', 'Notifier') } else { @($Component) }
    foreach ($name in $names) {
        $apk = Build-Android -Name $name
        if ($Install) { Install-Android -Name $name -Apk $apk -Serial $DeviceSerial }
    }
    $manifest = @(Get-ChildItem $taskPackages -Filter *.apk | ForEach-Object { [pscustomobject]@{ filename = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } })
    [IO.File]::WriteAllText((Join-Path $taskPackages 'manifest.json'), ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Write-Host "预览包目录：$taskPackages"
    return
}

if ($Target -in @('Windows', 'PrepareWindows')) {
    $taskWindows = Join-Path $taskDirectory 'windows'
    New-Item -ItemType Directory -Force -Path $taskWindows | Out-Null
    $taskSettings = Join-Path $taskWindows 'settings.ini'
    $taskNamePrefix = '[UI preview] '
    $taskCount = if ($Scene -eq 'limits') { 16 } elseif ($Scene -eq 'empty') { 1 } else { 4 }
    $taskLines = @(
        ('Source.Indexes=' + ((1..$taskCount) -join ','))
        'MonitorOnStartup=False'
        'Source.LegacyMigrationCompleted=True'
        'Source.KeyMigrationCompleted=True'
    )
    foreach ($taskIndex in 1..$taskCount) {
        $taskName = if ($Scene -eq 'limits') { ($taskNamePrefix + ('SourceName0123456789' * 5)).Substring(0,64) } else { "$taskNamePrefix Source $taskIndex" }
        $taskLines += "Source.$taskIndex.Name=$taskName", "Source.$taskIndex.Initialized=True", "Source.$taskIndex.SourceId=preview-$taskIndex", "Source.$taskIndex.Threshold=95", "Source.$taskIndex.Cooldown=300", "Source.$taskIndex.Fps=5", "Source.$taskIndex.ModelKey=yolo26m_320", "Source.$taskIndex.Targets=person", "Source.$taskIndex.CaptureMode=ScreenRegion", "Source.$taskIndex.ScreenRegion=", "Source.$taskIndex.Masks="
    }
    $taskConfiguration = Join-Path $taskWindows 'environment.json'
    # 模型目录直接用统一缓存 server/data/models：不再每次预览都复制一份模型。
    $taskValues = @{ serviceUrl = $endpoint.loopbackUrl; accountDir = (Join-Path $taskWindows 'accounts'); settingsPath = $taskSettings; modelsDirectory = $taskModelsDir; logDirectory = (Join-Path $taskWindows 'logs') }
    New-Item -ItemType Directory -Force -Path $taskValues.logDirectory | Out-Null
    [IO.File]::WriteAllText($taskSettings, ($taskLines -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($taskConfiguration, ($taskValues | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    # 预览账号经环境变量递给应用（隔离配置的字段契约不含账号密码，见 docs/60），启动即静默登录。
    if ($LoginUi) {
        Remove-Item Env:VISIONGUARD_PREVIEW_SIGNIN -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath $taskValues.accountDir -Filter 'session-*.bin' -ErrorAction SilentlyContinue | Remove-Item -Force
        Write-Host '预览登录已关闭并清掉已保存会话：应用会停在登录页。'
    } else {
        $accounts = Get-Content -LiteralPath $taskAccountsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $previewAccount = $accounts | Where-Object username -eq 'vg-test'
        $env:VISIONGUARD_PREVIEW_SIGNIN = (@{ endpoint = $endpoint.loopbackUrl; username = 'vg-test'; password = $previewAccount.password } | ConvertTo-Json -Compress)
    }
    if ($Target -eq 'PrepareWindows') { Write-Host "Windows 本地服务环境已准备：$taskConfiguration"; return }
    $taskExecutable = Join-Path $taskRoot 'detector\windows-package\bin\Release\VisionGuard.Detector.Windows.exe'
    if (!(Test-Path -LiteralPath $taskExecutable)) { throw '先构建 Windows Release 整包。' }
    Start-Process -FilePath $taskExecutable -WindowStyle Normal -ArgumentList @('--isolated-environment', ('"' + $taskConfiguration + '"')) -WorkingDirectory $taskWindows | Out-Null
    Write-Host '已用临时预览环境打开可见 Release WPF 窗口。'
}
