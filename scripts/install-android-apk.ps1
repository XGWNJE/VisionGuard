# 统一 Android 安装入口：装包、重装都走这里，处理厂商（MIUI / HyperOS 等）的「USB 安装」确认弹窗。
#
# 为什么必须单独固化：部分系统拦截静默安装（`INSTALL_FAILED_USER_RESTRICTED`），只能经系统安装器装，
# 且确认弹窗带倒计时——等 `adb install` 超时再去看就已经错过，一次错过就是一次全量上传重来（几十到几百 MB），
# 反复失败既慢又费资源。所以这里的做法是：
#   1) 安装命令在后台跑，同时按 ~350ms 轮询焦点窗口；只有安装器/安全中心在前台时才解析界面并点确认，
#      避免误触其它界面；
#   2) 按钮位置从 `uiautomator dump` 的节点 bounds 里算，不写死坐标（分辨率、横竖屏、厂商都不同）；
#   3) 普通点击点不动时自动升级为长按（部分安装器丢弃 `input tap` 注入，只接受长按）；
#   4) 判定成功不看「弹窗消失」，也不只看 adb 退出码（带 `-d` 时可能非零）：先认安装输出的 Success，
#      再独立核对包版本、最后更新时间与已安装 APK 字节数；
#   5) 失败后不再重传：包已推到设备就改用设备侧 `pm install` / 系统安装器重试。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\install-android-apk.ps1 -Apk <path> -PackageName <pkg> [-Serial <serial>]
#   补救错过弹窗（不重传）：... -RespondOnly -PackageName <pkg> [-TimeoutSeconds 120]
#   强制走系统安装器（用于验证倒计时确认）：... -ForceSystemInstaller
# 退出码：0 = 安装并校验通过（或 -RespondOnly 成功确认了弹窗）；1 = 失败（原因与证据见报告 JSON）；2 = 参数或环境错误。
#
# 实测提醒：厂商弹窗不一定立刻出现——MIUI 的「未查询到此应用的 ICP 备案信息」要等它自己联网查一次，
# 慢的时候两分钟以上，所以等待窗口默认给 300 秒，且必须用「包是否真的被替换」当完成信号，不能只看弹窗消失。
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$PackageName = '',
    [string]$Serial = '',
    [string]$Adb = '',
    [ValidateRange(30, 1800)][int]$TimeoutSeconds = 300,
    [switch]$AllowTestPackages,
    [switch]$AllowDowngrade,
    # 直接走系统安装器（跳过 adb install 快路径）：部分厂商的倒计时确认只在这一条路径上出现，
    # 需要专门验证「弹窗能被及时确认」时用它。
    [switch]$ForceSystemInstaller,
    # 只等并确认弹窗，不推送、不安装：弹窗被错过时用它补救，不必重传整个包。
    [switch]$RespondOnly,
    [string]$EvidenceDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$startedAt = Get-Date
$script:adbPath = ''
$script:serial = $Serial
$script:lastAdbExit = 0
$approvals = 0
$seenDialogs = @()
$script:dumpIssues = @()
$script:lastClickable = @()
$script:lastScores = @()

function Fail-Usage {
    param([string]$Message)
    [Console]::Error.WriteLine($Message)
    exit 2
}

function Resolve-AdbPath {
    param([string]$Requested)
    if ($Requested) {
        if (-not (Test-Path -LiteralPath $Requested)) { Fail-Usage "找不到 adb：$Requested" }
        return (Resolve-Path -LiteralPath $Requested).Path
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if (-not $candidate) { continue }
        $path = Join-Path $candidate 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $path) { return $path }
    }
    Fail-Usage '找不到 adb：请安装 Android SDK platform-tools，或用 -Adb 指定路径。'
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AsText)
    $command = @()
    if ($script:serial) { $command += @('-s', $script:serial) }
    $command += $Arguments
    # adb 会把 push 进度、警告写到 stderr：在 Stop 偏好下那会变成终止错误，这里临时放宽。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $script:adbPath @command 2>&1
        $script:lastAdbExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    return (($output | ForEach-Object { $_.ToString() }) -join "`n")
}

function Get-DeviceList {
    $lines = Invoke-Adb -Arguments @('devices')
    $devices = @()
    foreach ($line in ($lines -split "`n")) {
        if ($line -match '^(\S+)\s+(device|unauthorized|offline)\s*$') { $devices += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] } }
    }
    return $devices
}

function Get-FocusWindow {
    # 比 uiautomator dump 快一个数量级，用来判断「现在该不该去解析界面」。
    # 形如 `mCurrentFocus=Window{94bb127 u0 com.android.chrome/com.google.android.apps.chrome.Main}`：
    # 必须取最后那个「包名/活动名」，不能取前面的窗口 id 或 `u0` 用户号。
    $text = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window')
    $match = [regex]::Match($text, 'mCurrentFocus=Window\{[^}]*?([A-Za-z][\w]*(\.[\w]+)+/[^\s}]+)\}')
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Test-InstallerForeground {
    param([string]$Focus)
    # 只看厂商安装器 / 安全中心 / 权限控制器 / 未知来源设置页；不匹配桌面等其它界面，避免误触。
    return ($Focus -match 'packageinstaller|securitycenter|permissioncontroller|installer|com\.android\.settings')
}

function Get-UiXml {
    # 读当前界面的 XML。首选 `exec-out ... /dev/tty`：不经设备文件，避免上一次 dump 的陈旧文件被当成新结果；
    # 失败再退回「删旧文件 → dump → pull」，并把失败原因记进报告，而不是静默地当作「没有按钮」。
    param([string]$LocalXml)
    $text = Invoke-Adb -Arguments @('exec-out', 'uiautomator', 'dump', '/dev/tty')
    $start = $text.IndexOf('<?xml')
    if ($start -ge 0) {
        $end = $text.LastIndexOf('>')
        if ($end -gt $start) {
            [System.IO.File]::WriteAllText($LocalXml, $text.Substring($start, $end - $start + 1), (New-Object System.Text.UTF8Encoding($false)))
            return $true
        }
    }
    $flat = ($text -replace '\s+', ' ').Trim()
    if ($flat.Length -gt 120) { $flat = $flat.Substring(0, 120) }
    $script:dumpIssues += "exec-out: $flat"
    $remote = '/sdcard/vg-install-ui.xml'
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $remote) | Out-Null
    $dumpText = Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', $remote)
    if ($dumpText -notmatch 'dumped to') {
        $script:dumpIssues += ('dump: ' + (($dumpText -replace '\s+', ' ').Trim()))
        return $false
    }
    Invoke-Adb -Arguments @('pull', $remote, $LocalXml) | Out-Null
    return (Test-Path -LiteralPath $LocalXml)
}

function Get-InstallDialogNodes {
    # 解析当前界面里「真正可点的确认按钮」；返回按匹配强度排序的节点（含屏幕坐标）。
    # 只收 clickable 节点：进度/状态文案（如「安装包扫描中，请稍候」）不是按钮，点它只会制造噪声。
    param([string]$LocalXml)
    if (Test-Path -LiteralPath $LocalXml) { Remove-Item -LiteralPath $LocalXml -Force -ErrorAction SilentlyContinue }
    if (-not (Get-UiXml -LocalXml $LocalXml)) { return @() }
    try { [xml]$ui = Get-Content -LiteralPath $LocalXml -Raw -Encoding UTF8 } catch { $script:dumpIssues += 'xml parse failed'; return @() }

    $confirmText = @('继续安装', '仍要安装', '安装', '更新', '确定', '允许', '同意', '继续', 'install', 'update', 'continue', 'ok', 'allow', 'agree', 'accept')
    $cancelText = @('取消', '放弃', '暂不', '稍后', '拒绝', '退出', 'cancel', 'deny', 'not now', 'later', 'exit')
    $statusText = @('扫描', '请稍候', '正在', '检测中', 'loading', 'scanning', 'please wait', 'installing')
    $confirmId = @('install_confirm', 'button_ok', 'btn_ok', 'ok_button', 'install_button', 'confirm', 'install_')
    $cancelId = @('cancel', 'button_cancel', 'btn_cancel', 'close')

    $candidates = @()
    # 每次解析都重置诊断，避免心跳里显示上一轮的旧分数/旧节点。
    $script:lastClickable = @()
    $script:lastScores = @()
    $script:bestNode = $null
    foreach ($node in $ui.SelectNodes('//node')) {
        $text = ''; $resourceId = ''; $contentDesc = ''; $bounds = ''; $clickable = ''
        if ($node.Attributes) {
            foreach ($attribute in $node.Attributes) {
                switch ($attribute.Name) {
                    'text' { $text = $attribute.Value }
                    'resource-id' { $resourceId = $attribute.Value }
                    'content-desc' { $contentDesc = $attribute.Value }
                    'bounds' { $bounds = $attribute.Value }
                    'clickable' { $clickable = $attribute.Value }
                }
            }
        }
        if ($clickable -ne 'true') { continue }
        $labelRaw = (($text, $contentDesc | Where-Object { $_ }) -join ' ').Trim()
        if ($labelRaw) { $script:lastClickable += $labelRaw }
        $label = $labelRaw.ToLowerInvariant()
        $id = $resourceId.ToLowerInvariant()
        if (-not $label -and -not $id) { continue }
        # Android's update question is a clickable TextView, not the Update button.
        if ($id -match '(?:^|[/_])question(?:_|$)') { continue }
        if (-not ($bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')) { continue }
        $left = [int]$Matches[1]; $top = [int]$Matches[2]; $right = [int]$Matches[3]; $bottom = [int]$Matches[4]
        if ($right -le $left -or $bottom -le $top) { continue }

        $score = 0
        foreach ($pattern in $cancelText) { if ($label -like "*$pattern*") { $score -= 100 } }
        foreach ($pattern in $cancelId) { if ($id -like "*$pattern*") { $score -= 100 } }
        foreach ($pattern in $statusText) { if ($label -like "*$pattern*") { $score -= 100 } }
        foreach ($pattern in $confirmText) { if ($label -like "*$pattern*") { $score += ($pattern.Length * 2) } }
        foreach ($pattern in $confirmId) { if ($id -like "*$pattern*") { $score += 20 } }
        $script:lastScores += "$labelRaw=$score"
        if ($score -le 0) { continue }
        $candidate = [pscustomobject]@{
            Label = $labelRaw
            ResourceId = $resourceId
            X = [int](($left + $right) / 2)
            Y = [int](($top + $bottom) / 2)
            Score = $score
        }
        $candidates += $candidate
        # 同时放到脚本作用域：调用方直接用这一份，避免依赖函数返回值的数组包装。
        if (-not $script:bestNode -or $candidate.Score -gt $script:bestNode.Score) { $script:bestNode = $candidate }
    }
    return @($candidates | Sort-Object -Property Score -Descending)
}

function Approve-DialogsUntil {
    # 在等待安装结果期间持续响应弹窗：倒计时窗口内必须立刻确认，不能等命令超时。
    # 判定「点没点动」看弹窗按钮签名是否变化——安装器点完会切到下一屏，焦点仍在安装器里，
    # 用「焦点没变」判失败会把正常的界面推进误判成点击无效。
    # -PackageName/-FreshSince 给出完成硬信号：目标包在本次运行后被替换就立刻结束。
    # -RequireDialog 用于系统安装器路径：还没处理过任何弹窗时不许因「安静」提前退出。
    param([DateTime]$Deadline, [string]$LocalXml, [string]$Stage, [switch]$RequireDialog, [string]$PackageName = '', [Nullable[datetime]]$FreshSince)
    $quiet = 0
    $handledAny = $false
    $lastSignature = ''
    $sameSignatureAttempts = 0
    $lastHeartbeat = Get-Date
    while ((Get-Date) -lt $Deadline) {
        if ($PackageName -and $FreshSince) {
            $state = Get-PackageState -Name $PackageName
            if ($state -and $state.LastUpdateTime) {
                $updated = $null
                $updated = Convert-PackageUpdateTime $state.LastUpdateTime
                if ($updated -and $updated.UtcDateTime -ge ([datetime]$FreshSince).ToUniversalTime()) { return }
            }
        }
        $focus = Get-FocusWindow
        if (Test-InstallerForeground -Focus $focus) {
            $quiet = 0
            $script:bestNode = $null
            $null = Get-InstallDialogNodes -LocalXml $LocalXml
            if ($script:bestNode) {
                $node = $script:bestNode
                $signature = "$($node.Label)@$($node.X),$($node.Y)"
                if ($signature -eq $lastSignature) { $sameSignatureAttempts++ } else { $lastSignature = $signature; $sameSignatureAttempts = 1 }
                if ($sameSignatureAttempts -le 3) {
                    Invoke-Adb -Arguments @('shell', 'input', 'tap', $node.X.ToString(), $node.Y.ToString()) | Out-Null
                    Start-Sleep -Milliseconds 700
                    # 同一签名点了两次还在：升级为长按（部分厂商安装器丢弃 input tap 注入）。
                    if ($sameSignatureAttempts -ge 2) {
                        Invoke-Adb -Arguments @('shell', 'input', 'swipe', $node.X.ToString(), $node.Y.ToString(), $node.X.ToString(), $node.Y.ToString(), '900') | Out-Null
                        Start-Sleep -Milliseconds 700
                    }
                    $script:approvals++
                    $script:seenDialogs += "$Focus -> $($node.Label) [$($node.ResourceId)] at $($node.X),$($node.Y)"
                    Write-Host "[$Stage] 确认「$($node.Label)」（第 $sameSignatureAttempts 次，$($node.X),$($node.Y)）"
                    $handledAny = $true
                }
                else { Start-Sleep -Milliseconds 1500 }
            }
            else {
                # 安装器在前台却没有可点按钮（进度页/取不到界面）：定期说明一次，别让人以为卡死。
                if (((Get-Date) - $lastHeartbeat).TotalSeconds -ge 5) {
                    $issue = if ($script:dumpIssues.Count -gt 0) { $script:dumpIssues[-1] }
                        elseif ($script:lastScores.Count -gt 0) { '得分：' + (($script:lastScores | Select-Object -First 6) -join ' / ') }
                        elseif ($script:lastClickable.Count -gt 0) { '可点节点：' + (($script:lastClickable | Select-Object -First 6) -join ' / ') }
                        else { '界面里没有 clickable 节点' }
                    Write-Host "[$Stage] 安装器在前台（$focus），暂无可点确认按钮：$issue"
                    $lastHeartbeat = Get-Date
                }
                Start-Sleep -Milliseconds 500
            }
        }
        else {
            $quiet++
            if ($handledAny -and $quiet -ge 6) { return }
            if (-not $RequireDialog -and $quiet -ge 8) { return }
            Start-Sleep -Milliseconds 350
        }
    }
}

function Convert-PackageUpdateTime {
    param([string]$Value)
    try { return [datetimeoffset]::Parse("$Value $script:deviceUtcOffset", [Globalization.CultureInfo]::InvariantCulture) }
    catch { return $null }
}

function Get-PackageState {
    param([string]$Name)
    if (-not $Name) { return $null }
    $text = Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Name)
    if ($text -notmatch 'versionName=') { return $null }
    $versionName = ''; $versionCode = ''; $lastUpdateTime = ''
    if ($text -match 'versionName=(\S+)') { $versionName = $Matches[1] }
    if ($text -match 'versionCode=(\d+)') { $versionCode = $Matches[1] }
    if ($text -match 'lastUpdateTime=([^\r\n]+)') { $lastUpdateTime = $Matches[1].Trim() }
    $pathText = Invoke-Adb -Arguments @('shell', 'pm', 'path', $Name)
    $apkPath = ''
    if ($pathText -match 'package:(.+\.apk)') { $apkPath = $Matches[1].Trim() }
    $installedBytes = $null
    if ($apkPath) {
        $sizeText = Invoke-Adb -Arguments @('shell', 'stat', '-c', '%s', $apkPath)
        $sizeMatch = [regex]::Match($sizeText, '\d{4,}')
        if ($sizeMatch.Success) { $installedBytes = [int64]$sizeMatch.Value }
    }
    return [pscustomobject]@{ VersionName = $versionName; VersionCode = $versionCode; LastUpdateTime = $lastUpdateTime; ApkPath = $apkPath; InstalledBytes = $installedBytes }
}

function Start-InstallProcess {
    param([string[]]$InstallArguments, [string]$StdoutPath, [string]$StderrPath)
    return Start-Process -FilePath $script:adbPath -ArgumentList (@('-s', $script:serial) + $InstallArguments) `
        -WindowStyle Hidden -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath -PassThru
}

function Read-LogText {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) { return ([System.IO.File]::ReadAllText($Path)).Trim() }
    return ''
}

# ── 准备 ─────────────────────────────────────────────────────────────────────
$apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
if (-not (Test-Path -LiteralPath $apkPath)) { Fail-Usage "APK 不存在：$apkPath" }
$apkPath = (Resolve-Path -LiteralPath $apkPath).Path
$apkItem = Get-Item -LiteralPath $apkPath

$script:adbPath = Resolve-AdbPath -Requested $Adb
if (-not $Serial) {
    $ready = @(Get-DeviceList | Where-Object { $_.State -eq 'device' })
    if ($ready.Count -eq 0) { Fail-Usage '没有已就绪的设备：请连接设备或开启 USB 调试。' }
    if ($ready.Count -gt 1) { Fail-Usage ('检测到多台设备，请用 -Serial 指定：' + (($ready | ForEach-Object { $_.Serial }) -join ', ')) }
    $Serial = $ready[0].Serial
}
$script:serial = $Serial
$script:deviceUtcOffset = (Invoke-Adb -Arguments @('shell', 'date', '+%z')).Trim()
if ($script:deviceUtcOffset -notmatch '^[+-]\d{4}$') { throw 'Cannot read the selected Android device timezone.' }

$evidence = if ($EvidenceDirectory) { $EvidenceDirectory } else { Join-Path $repoRoot ('.local\apk-install\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$uiXmlPath = Join-Path $evidence 'installer-ui.xml'
$deviceApkPath = '/data/local/tmp/' + $apkItem.Name
$installerApkPath = '/sdcard/Download/' + $apkItem.Name

$report = [ordered]@{
    apk = $apkPath
    apkBytes = $apkItem.Length
    apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    serial = $Serial
    packageName = $PackageName
    timeoutSeconds = $TimeoutSeconds
    startedAt = $startedAt.ToString('o')
    attempts = @()
    approvals = 0
    dialogs = @()
    verification = @{}
    result = 'failed'
    reason = ''
}
$method = ''

Write-Host "安装 $($apkItem.Name)（$([math]::Round($apkItem.Length / 1MB, 1)) MiB）到 $Serial"

try {
    # 唤醒屏幕：锁屏时确认弹窗无法响应，也不越权解锁，直接报清楚。
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '224') | Out-Null
    Start-Sleep -Milliseconds 600
    $initialFocus = Get-FocusWindow
    if ($initialFocus -match 'keyguard|statusbar') {
        throw "设备处于锁屏（焦点：$initialFocus），无法响应厂商安装确认弹窗；请在设备上解锁后重试。"
    }
    $report.initialFocus = $initialFocus

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    if ($RespondOnly) {
        # 补救路径：弹窗（例如厂商的备案/倒计时确认）被错过时只补确认，不重新上传安装包。
        Approve-DialogsUntil -Deadline $deadline -LocalXml $uiXmlPath -Stage 'respond-only' -RequireDialog -PackageName $PackageName -FreshSince $startedAt
        $report.method = 'respond-only'
        $report.verification = [ordered]@{ method = 'respond-only'; approvals = $approvals; package = (Get-PackageState -Name $PackageName) }
        if ($approvals -eq 0) { throw "在 $TimeoutSeconds 秒内没有出现可确认的厂商安装弹窗。" }
        $report.result = 'passed'
        Write-Host "已确认 $approvals 次弹窗（未重新安装）。"
        exit 0
    }

    # 1) 快路径：一次上传 + 同时响应弹窗。
    $installArguments = @('install', '-r')
    if ($AllowDowngrade) { $installArguments += '-d' }
    if ($AllowTestPackages) { $installArguments += '-t' }
    $installArguments += $apkPath
    if (-not $ForceSystemInstaller) {
        $stdoutPath = Join-Path $evidence 'adb-install.stdout.txt'
        $stderrPath = Join-Path $evidence 'adb-install.stderr.txt'
        $process = Start-InstallProcess -InstallArguments $installArguments -StdoutPath $stdoutPath -StderrPath $stderrPath
        Approve-DialogsUntil -Deadline $deadline -LocalXml $uiXmlPath -Stage 'adb install' -PackageName $PackageName -FreshSince $startedAt
        if (-not $process.HasExited) {
            if ((Get-Date) -ge $deadline) { try { $process.Kill() } catch { } }
            else { $process.WaitForExit(30000) | Out-Null }
        }
        $process.Refresh()
        # 重定向文件在进程刚退出时可能仍被占用，用 .NET 读整份。
        $stdout = Read-LogText -Path $stdoutPath
        $stderr = Read-LogText -Path $stderrPath
        $report.attempts += [ordered]@{ stage = 'adb install'; arguments = ($installArguments -join ' '); exitCode = $process.ExitCode; stdout = $stdout; stderr = $stderr }
        Write-Host "[adb install] exit=$($process.ExitCode) $((($stdout + ' ' + $stderr) -replace '\s+', ' ').Trim())"
        if ($stdout -match 'Success') { $method = 'adb install' }
    }
    else {
        $report.attempts += [ordered]@{ stage = 'adb install'; arguments = 'skipped by -ForceSystemInstaller'; exitCode = $null; stdout = ''; stderr = '' }
        Write-Host '[adb install] 已按 -ForceSystemInstaller 跳过'
    }

    # 2) 失败后不再重传：包已推到设备，改用设备侧路径继续尝试。
    if (-not $method -and -not $ForceSystemInstaller) {
        Invoke-Adb -Arguments @('push', $apkPath, $deviceApkPath) | Out-Null
        $pushExit = $script:lastAdbExit
        $report.attempts += [ordered]@{ stage = 'push'; arguments = "push $apkPath $deviceApkPath"; exitCode = $pushExit; stdout = ''; stderr = '' }
        if ($pushExit -eq 0) {
            $pmArguments = @('shell', 'pm', 'install', '-r')
            if ($AllowDowngrade) { $pmArguments += '-d' }
            if ($AllowTestPackages) { $pmArguments += '-t' }
            $pmArguments += $deviceApkPath
            $pmStdoutPath = Join-Path $evidence 'pm-install.stdout.txt'
            $pmStderrPath = Join-Path $evidence 'pm-install.stderr.txt'
            $pmProcess = Start-InstallProcess -InstallArguments $pmArguments -StdoutPath $pmStdoutPath -StderrPath $pmStderrPath
            $pmDeadline = (Get-Date).AddSeconds([math]::Max(60, [int]($TimeoutSeconds / 2)))
            Approve-DialogsUntil -Deadline $pmDeadline -LocalXml $uiXmlPath -Stage 'pm install' -PackageName $PackageName -FreshSince $startedAt
            if (-not $pmProcess.HasExited) { try { $pmProcess.Kill() } catch { } }
            $pmProcess.Refresh()
            $pmOut = Read-LogText -Path $pmStdoutPath
            $pmErr = Read-LogText -Path $pmStderrPath
            $report.attempts += [ordered]@{ stage = 'pm install'; arguments = ($pmArguments -join ' '); exitCode = $pmProcess.ExitCode; stdout = $pmOut; stderr = $pmErr }
            Write-Host "[pm install] exit=$($pmProcess.ExitCode) $((($pmOut + ' ' + $pmErr) -replace '\s+', ' ').Trim())"
            if ($pmOut -match 'Success') { $method = 'pm install' }
        }

        # 3) 仍失败：走系统安装器，弹窗由轮询确认；这一步必须等弹窗真的出现。
        if (-not $method) {
            # 安装器读不到 /data/local/tmp（属 shell 私有目录），推到它能读的共享目录再打开。
            Invoke-Adb -Arguments @('push', $apkPath, $installerApkPath) | Out-Null
            $installerPushExit = $script:lastAdbExit
            $report.attempts += [ordered]@{ stage = 'push (installer)'; arguments = "push $apkPath $installerApkPath"; exitCode = $installerPushExit; stdout = ''; stderr = '' }
            Invoke-Adb -Arguments @('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', "file://$installerApkPath", '-t', 'application/vnd.android.package-archive') | Out-Null
            $report.attempts += [ordered]@{ stage = 'system installer'; arguments = "am start VIEW file://$installerApkPath"; exitCode = $script:lastAdbExit; stdout = ''; stderr = '' }
            Approve-DialogsUntil -Deadline $deadline -LocalXml $uiXmlPath -Stage 'system installer' -RequireDialog -PackageName $PackageName -FreshSince $startedAt
            $method = 'system installer'
        }
    }
    elseif ($ForceSystemInstaller) {
        Invoke-Adb -Arguments @('push', $apkPath, $installerApkPath) | Out-Null
        $installerPushExit = $script:lastAdbExit
        $report.attempts += [ordered]@{ stage = 'push (installer)'; arguments = "push $apkPath $installerApkPath"; exitCode = $installerPushExit; stdout = ''; stderr = '' }
        Invoke-Adb -Arguments @('shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', "file://$installerApkPath", '-t', 'application/vnd.android.package-archive') | Out-Null
        $report.attempts += [ordered]@{ stage = 'system installer'; arguments = "am start VIEW file://$installerApkPath"; exitCode = $script:lastAdbExit; stdout = ''; stderr = '' }
        Approve-DialogsUntil -Deadline $deadline -LocalXml $uiXmlPath -Stage 'system installer' -RequireDialog -PackageName $PackageName -FreshSince $startedAt
        $method = 'system installer'
    }

    # ── 独立核验：不认「弹窗消失」，认包状态与已安装 APK 字节数 ───────────────
    $state = Get-PackageState -Name $PackageName
    $report.method = $method
    $report.verification = [ordered]@{ method = $method; approvals = $approvals; package = $state }
    if ($PackageName) {
        if (-not $state) { throw '安装流程结束后设备上仍查不到该包。' }
        if ($state.InstalledBytes -and $state.InstalledBytes -ne $apkItem.Length) {
            throw "已安装 APK 字节数（$($state.InstalledBytes)）与本地包（$($apkItem.Length)）不一致，判为替换未生效。"
        }
        $report.verification.sizesMatch = ($state.InstalledBytes -eq $apkItem.Length)
        $updated = $null
        $updated = Convert-PackageUpdateTime $state.LastUpdateTime
        if ($updated) {
            $report.verification.lastUpdateTime = $updated.ToString('o')
            # 容差只留 60 秒：真正装上的一刻会刷新这个时间，放太宽会把「上一轮的安装」当成本轮成功。
            if ($updated.UtcDateTime -lt $startedAt.ToUniversalTime().AddSeconds(-60)) { throw "包的最后更新时间（$($state.LastUpdateTime)）明显早于本次安装，判为没有真正替换。" }
        }
        else {
            $report.verification.lastUpdateTimeUnparsed = $state.LastUpdateTime
        }
    }
    else {
        Write-Host '未提供 -PackageName：跳过包版本与字节数核验，仅以安装命令结果为准。'
    }

    $report.result = 'passed'
    Write-Host "安装完成：方法=$method，确认弹窗处理=$approvals 次"
    exit 0
}
catch {
    $report.reason = $_.Exception.Message
    Write-Host "安装失败：$($report.reason)"
    exit 1
}
finally {
    $report.finishedAt = (Get-Date).ToString('o')
    $report.approvals = $approvals
    $report.dialogs = $seenDialogs
    $report.dumpIssues = @($script:dumpIssues | Select-Object -Unique -First 5)
    $report.evidence = $evidence
    [System.IO.File]::WriteAllText((Join-Path $evidence 'apk-install-report.json'), ($report | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "证据：$evidence"
}
