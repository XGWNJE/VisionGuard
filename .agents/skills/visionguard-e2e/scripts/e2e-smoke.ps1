param(
    [ValidateSet('Discover', 'ServerBuild', 'ServerSmoke', 'AndroidDetectorSmoke', 'WpfPersonDetection', 'WpfParserContract', 'ResidentLaunch', 'ModelDownload', 'SourceAutoSave', 'CardLayoutPlan', 'PerformanceWatchdog', 'WebCameraChain', 'WindowsPushChain')]
    [string]$Mode = 'Discover',

    [ValidateSet('Auto', 'Physical', 'Emulator', 'None')]
    [string]$Device = 'Auto',

    [ValidateSet('Debug', 'Release')]
    [string]$BuildType = 'Debug',

    [string]$DeviceSerial = '',
    [string]$Avd = 'VisionGuard_API36',
    [switch]$ClearAppData,
    [switch]$NoLaunchEmulator,
    [int]$BootTimeoutSeconds = 180,
    [ValidateRange(2,16)][int]$WpfSourceCount = 4,
    [string]$WpfFixtureDirectory = 'artifacts\v5\fixtures',
    [string]$WpfModelPath = 'artifacts\v0\yolo26n_320.onnx',
    [string]$WpfReportPath = 'artifacts\e2e\wpf-window-person-detection.json',
    [ValidateRange(0.0,1.0)][float]$WpfConfidenceThreshold = 0.25,
    # WpfParserContract：两档模型 + 真实图片，断言解析出的业务目标。
    [string]$WpfLegacyModelPath = 'detector\windows-wpf\Assets\yolov5nu_320.onnx',
    [string]$WpfModernModelPath = 'artifacts\v0\yolo26n_320.onnx',
    [string]$WpfContractImagePath = 'artifacts\v5\fixtures\vg-v5-person-zidane.jpg',
    [string]$WpfContractExpectedLabel = 'person',
    # ResidentLaunch：拉起探针 + 隔离 Server，断言驻留在服务端可见。
    [ValidateRange(1024,65535)][int]$ResidentPort = 3123,
    [string]$ResidentChannel = 'vnext-resident-e2e',
    # ModelDownload：打真实生产服务器验证模型下载与装配（界面下载按钮背后的同一条路径）。
    [string]$ModelDownloadKey = 'yolo26n_320',
    [switch]$ModelDownloadLegacyProfile,
    # WebCameraChain：真机浏览器摄像头 → 隔离服务中继 → 真实 Windows 视觉推理节点 → 人员报警落库。
    [ValidateRange(1024,65535)][int]$WebCameraChainPort = 3124,
    [string]$WebCameraChainChannel = 'web-camera-chain-e2e',
    [ValidateSet('front','back','default')][string]$WebCameraChainCamera = 'front',
    [string]$WebCameraChainModelsDirectory = '',
    [ValidateRange(30,900)][int]$WebCameraChainPermissionTimeoutSeconds = 180,
    [ValidateRange(30,900)][int]$WebCameraChainAlertTimeoutSeconds = 180,
    [switch]$WebCameraChainKeepStreaming,
    # WindowsPushChain：真实 Windows 节点推流态 → 隔离服务中继 → 另一个推理设备真实收到合法帧。
    [ValidateRange(1024,65535)][int]$WindowsPushChainPort = 3125,
    [string]$WindowsPushChainChannel = 'windows-push-chain-e2e',
    [string]$WindowsPushChainFixtureImage = 'artifacts\v5\fixtures\vg-v5-person-zidane.jpg',
    [ValidateRange(30,900)][int]$WindowsPushChainTimeoutSeconds = 180,
    # 验证完成后保持推流这么多秒，供脚本抓两个真实实例的窗口截图（界面目检证据）。
    [ValidateRange(0,300)][int]$WindowsPushChainCaptureDelaySeconds = 30
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repoRoot "artifacts\e2e\$timestamp"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

$effectiveBuildType = if ($Mode -in @('WpfPersonDetection', 'WpfParserContract')) { 'Release' } else { $BuildType }
$summary = [ordered]@{
    mode = $Mode
    buildType = $effectiveBuildType
    startedAt = (Get-Date).ToString('o')
    artifactRoot = $artifactRoot
    selectedDevice = $null
    selectedAvd = $null
    launchedEmulator = $false
    results = @()
}
$launchedEmulatorSerial = ''
$launchedEmulatorProcess = $null
$exitCode = 0

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Note = '', [string]$Evidence = '')
    $script:summary.results += [ordered]@{ name = $Name; status = $Status; note = $Note; evidence = $Evidence }
    Write-Host "[$Status] $Name $Note"
}

function Save-Summary {
    $summary.finishedAt = (Get-Date).ToString('o')
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $artifactRoot 'summary.json')
}

function Invoke-NativeLogged {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$LogPath
    )
    $previousPreference = $ErrorActionPreference
    Push-Location $WorkingDirectory
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $FilePath @Arguments 2>&1
        $nativeExitCode = $LASTEXITCODE
        $output | ForEach-Object { $_.ToString() } | Set-Content -Encoding UTF8 $LogPath
        if ($nativeExitCode -ne 0) {
            throw "$FilePath exited with code $nativeExitCode."
        }
    }
    finally {
        $ErrorActionPreference = $previousPreference
        Pop-Location
    }
}

function Get-AndroidSdkCandidates {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    return @($candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique)
}

function Get-AndroidSdk {
    foreach ($candidate in Get-AndroidSdkCandidates) {
        $adb = Join-Path $candidate 'platform-tools\adb.exe'
        $emulator = Join-Path $candidate 'emulator\emulator.exe'
        if ((Test-Path -LiteralPath $adb) -and (Test-Path -LiteralPath $emulator)) {
            return $candidate
        }
    }
    throw 'Android SDK with both adb and emulator was not found through ANDROID_HOME, ANDROID_SDK_ROOT, or the standard local SDK location.'
}

function Get-AndroidTools {
    $sdk = Get-AndroidSdk
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $emulator = Join-Path $sdk 'emulator\emulator.exe'

    $javaCandidates = @($env:JAVA_HOME)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $javaCandidates += Split-Path -Parent $javaBin
    }
    $javaCandidates = @($javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -Unique)

    return [pscustomobject]@{
        AndroidSdk = $sdk
        Adb = $adb
        Emulator = $emulator
        JavaHome = if ($javaCandidates.Count -gt 0) { $javaCandidates[0] } else { '' }
    }
}

function Get-AdbDevices {
    param([string]$Adb)
    $raw = & $Adb devices -l
    $raw | Set-Content -Encoding UTF8 (Join-Path $artifactRoot 'adb-devices.txt')
    $devices = @()
    foreach ($line in $raw) {
        if ($line -match '^(\S+)\s+(\S+)(.*)$' -and $line -notmatch '^List') {
            $devices += [pscustomobject]@{
                Serial = $Matches[1]
                State = $Matches[2]
                Detail = $Matches[3].Trim()
                IsEmulator = $Matches[1] -like 'emulator-*'
            }
        }
    }
    return $devices
}

function Wait-ForEmulator {
    param([string]$Adb, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $emulator = @(Get-AdbDevices -Adb $Adb | Where-Object { $_.State -eq 'device' -and $_.IsEmulator }) | Select-Object -First 1
        if ($emulator) {
            $booted = & $Adb -s $emulator.Serial shell getprop sys.boot_completed 2>$null
            if (($booted -join '').Trim() -eq '1') { return $emulator }
        }
        Start-Sleep -Seconds 3
    }
    throw "Emulator did not finish booting within $TimeoutSeconds seconds."
}

function Select-AndroidDevice {
    param([pscustomobject]$Tools)
    $devices = @(Get-AdbDevices -Adb $Tools.Adb)

    if ($DeviceSerial) {
        $selected = $devices | Where-Object { $_.Serial -eq $DeviceSerial } | Select-Object -First 1
        if (-not $selected) { throw "Requested device not found: $DeviceSerial" }
        if ($selected.State -ne 'device') { throw "Requested device is not ready: $DeviceSerial state=$($selected.State)" }
        return $selected
    }

    $physical = @($devices | Where-Object { $_.State -eq 'device' -and -not $_.IsEmulator })
    $unavailablePhysical = @($devices | Where-Object { -not $_.IsEmulator -and $_.State -ne 'device' })
    foreach ($item in $unavailablePhysical) {
        Add-Result -Name 'Physical device unavailable' -Status 'SKIP' -Note "$($item.Serial) state=$($item.State)"
    }
    if ($Device -in @('Auto', 'Physical')) {
        if ($physical.Count -eq 1) { return $physical[0] }
        if ($physical.Count -gt 1) { throw 'Multiple physical devices found; use -DeviceSerial.' }
        if ($Device -eq 'Physical') { throw 'No ready physical device found.' }
    }
    if ($Device -eq 'None') { return $null }

    $readyEmulator = @($devices | Where-Object { $_.State -eq 'device' -and $_.IsEmulator }) | Select-Object -First 1
    if ($readyEmulator) { return $readyEmulator }
    if ($NoLaunchEmulator) { return $null }

    $avds = @(& $Tools.Emulator -list-avds)
    $avds | Set-Content -Encoding UTF8 (Join-Path $artifactRoot 'emulator-avds.txt')
    $selectedAvd = if ($avds -contains $Avd) { $Avd } elseif ($avds -contains 'VisionGuard_API36') { 'VisionGuard_API36' } elseif ($avds -contains 'Pixel_3a_XL') { 'Pixel_3a_XL' } else { '' }
    if (-not $selectedAvd) { throw 'No configured VisionGuard emulator is available.' }

    $summary.selectedAvd = $selectedAvd
    $summary.launchedEmulator = $true
    $script:launchedEmulatorProcess = Start-Process -FilePath $Tools.Emulator -ArgumentList @('-avd', $selectedAvd, '-no-snapshot-save') -WindowStyle Normal -PassThru
    Add-Result -Name 'Launch emulator' -Status 'PASS' -Note $selectedAvd
    $selected = Wait-ForEmulator -Adb $Tools.Adb -TimeoutSeconds $BootTimeoutSeconds
    $script:launchedEmulatorSerial = $selected.Serial
    return $selected
}

function Run-Discover {
    $inventory = [ordered]@{
        dotnet = (& dotnet --version 2>$null)
        node = (& node --version 2>$null)
        npm = (& npm --version 2>$null)
        git = (& git --version 2>$null)
        android = $null
        adbDevices = @()
    }
    try {
        $tools = Get-AndroidTools
        $inventory.android = $tools
        $inventory.adbDevices = @(Get-AdbDevices -Adb $tools.Adb)
        $inventory.avds = @(& $tools.Emulator -list-avds)
    }
    catch {
        $inventory.androidError = $_.Exception.Message
    }
    $path = Join-Path $artifactRoot 'inventory.json'
    $inventory | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $path
    Add-Result -Name 'Environment discovery' -Status 'PASS' -Evidence $path
}

function Run-ServerBuild {
    $log = Join-Path $artifactRoot 'server-build.txt'
    Invoke-NativeLogged -FilePath 'npm' -Arguments @('--prefix', 'server', 'run', 'build') -WorkingDirectory $repoRoot -LogPath $log
    $artifact = Join-Path $repoRoot 'server\dist\index.js'
    if (-not (Test-Path -LiteralPath $artifact)) { throw "Server artifact missing: $artifact" }
    Add-Result -Name 'Server build smoke' -Status 'PASS' -Evidence $log
}

function Set-GradleJava {
    param([string]$JavaHome)
    if (-not $JavaHome) { throw 'Android Studio JBR/JAVA_HOME was not found.' }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}

function Run-AndroidAppSmoke {
    param(
        [string]$Name,
        [string]$ProjectDirectory,
        [string]$PackageName,
        [string[]]$RuntimePermissions
    )
    $tools = Get-AndroidTools
    Set-GradleJava -JavaHome $tools.JavaHome
    $task = if ($BuildType -eq 'Release') { 'assembleRelease' } else { 'assembleDebug' }
    $apkName = if ($BuildType -eq 'Release') { 'app-release.apk' } else { 'app-debug.apk' }
    $apk = Join-Path $repoRoot "$ProjectDirectory\app\build\outputs\apk\$($BuildType.ToLowerInvariant())\$apkName"
    $buildLog = Join-Path $artifactRoot "$Name-build.txt"

    $projectRoot = Join-Path $repoRoot $ProjectDirectory
    Invoke-NativeLogged -FilePath (Join-Path $projectRoot 'gradlew.bat') -Arguments @($task) -WorkingDirectory $projectRoot -LogPath $buildLog
    if (-not (Test-Path -LiteralPath $apk)) { throw "APK missing: $apk" }
    Add-Result -Name "$Name $BuildType build" -Status 'PASS' -Evidence $buildLog

    $selected = Select-AndroidDevice -Tools $tools
    if (-not $selected) {
        Add-Result -Name "$Name runtime" -Status 'SKIP' -Note 'No usable device or emulator.'
        return
    }
    $summary.selectedDevice = $selected.Serial

    $installLog = Join-Path $artifactRoot "$Name-install.txt"
    # 统一安装入口：处理厂商「USB 安装」倒计时确认（错过一次就要重传整个包），并独立核对
    # 包版本、最后更新时间和已安装 APK 字节数，而不是只看 adb 退出码或弹窗是否消失。
    $installerScript = Join-Path $repoRoot 'scripts\install-android-apk.ps1'
    if (-not (Test-Path -LiteralPath $installerScript)) { throw "Android install entry is missing: $installerScript" }
    $installEvidence = Join-Path $artifactRoot "$Name-install-evidence"
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $installOutput = & powershell -ExecutionPolicy Bypass -File $installerScript -Apk $apk -PackageName $PackageName `
            -Serial $selected.Serial -Adb $tools.Adb -EvidenceDirectory $installEvidence 2>&1
        $installExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
        $installOutput | ForEach-Object { $_.ToString() } | Set-Content -Encoding UTF8 $installLog
    }
    if ($installExit -ne 0) { throw "$Name install failed: $installLog" }
    Add-Result -Name "$Name install" -Status 'PASS' -Note 'vendor confirmation handled; package version and APK bytes verified' -Evidence $installLog

    if ($ClearAppData) {
        & $tools.Adb -s $selected.Serial shell pm clear $PackageName *> (Join-Path $artifactRoot "$Name-clear.txt")
        if ($LASTEXITCODE -ne 0) { throw "$Name app-data clear failed." }
        Add-Result -Name "$Name clear data" -Status 'PASS'
        Start-Sleep -Seconds 1
    }

    $permissionLog = Join-Path $artifactRoot "$Name-permissions.txt"
    $permissionResults = @()
    foreach ($permission in $RuntimePermissions) {
        & $tools.Adb -s $selected.Serial shell pm grant $PackageName $permission *> $permissionLog
        $grantExitCode = $LASTEXITCODE
        $packageDump = (& $tools.Adb -s $selected.Serial shell dumpsys package $PackageName 2>$null) -join "`n"
        $checkExitCode = $LASTEXITCODE
        $permissionState = if ($packageDump -match ([regex]::Escape($permission) + ': granted=true')) {
            'granted'
        } else {
            'not-granted'
        }
        $permissionResults += "$permission grantExit=$grantExitCode checkExit=$checkExitCode state=$permissionState"
        if ($grantExitCode -ne 0 -or $permissionState -notmatch 'granted') {
            throw "$Name runtime permission was not granted: $permission state=$permissionState"
        }
    }
    $permissionResults | Set-Content -Encoding UTF8 $permissionLog
    Add-Result -Name "$Name runtime permissions" -Status 'PASS' -Evidence $permissionLog
    & $tools.Adb -s $selected.Serial shell am force-stop com.android.permissioncontroller *> $null
    & $tools.Adb -s $selected.Serial shell am force-stop $PackageName *> $null

    & $tools.Adb -s $selected.Serial logcat -c | Out-Null
    & $tools.Adb -s $selected.Serial shell am start -W -n "$PackageName/.MainActivity" *> (Join-Path $artifactRoot "$Name-launch.txt")
    if ($LASTEXITCODE -ne 0) { throw "$Name launch failed." }
    Start-Sleep -Seconds 8

    $logcatPath = Join-Path $artifactRoot "$Name-logcat.txt"
    $activityPath = Join-Path $artifactRoot "$Name-activity.txt"
    & $tools.Adb -s $selected.Serial logcat -d -v time *> $logcatPath
    & $tools.Adb -s $selected.Serial shell dumpsys activity activities *> $activityPath
    $processId = (& $tools.Adb -s $selected.Serial shell pidof $PackageName 2>$null) -join ''
    $activity = Get-Content -LiteralPath $activityPath -Raw
    if (-not $processId.Trim() -or $activity -notmatch [regex]::Escape($PackageName)) {
        throw "$Name did not remain running or appear in activity state."
    }

    $remoteXml = '/sdcard/vg-window.xml'
    $remoteScreen = '/sdcard/vg-screen.png'
    Invoke-NativeLogged -FilePath $tools.Adb `
        -Arguments @('-s', $selected.Serial, 'shell', 'uiautomator', 'dump', $remoteXml) `
        -WorkingDirectory $repoRoot `
        -LogPath (Join-Path $artifactRoot "$Name-uiautomator.txt")
    if ($LASTEXITCODE -eq 0) {
        Invoke-NativeLogged -FilePath $tools.Adb `
            -Arguments @('-s', $selected.Serial, 'pull', $remoteXml, (Join-Path $artifactRoot "$Name-window.xml")) `
            -WorkingDirectory $repoRoot `
            -LogPath (Join-Path $artifactRoot "$Name-window-pull.txt")
    }
    Invoke-NativeLogged -FilePath $tools.Adb `
        -Arguments @('-s', $selected.Serial, 'shell', 'screencap', '-p', $remoteScreen) `
        -WorkingDirectory $repoRoot `
        -LogPath (Join-Path $artifactRoot "$Name-screencap.txt")
    if ($LASTEXITCODE -eq 0) {
        Invoke-NativeLogged -FilePath $tools.Adb `
            -Arguments @('-s', $selected.Serial, 'pull', $remoteScreen, (Join-Path $artifactRoot "$Name-screen.png")) `
            -WorkingDirectory $repoRoot `
            -LogPath (Join-Path $artifactRoot "$Name-screen-pull.txt")
    }

    $logcat = Get-Content -LiteralPath $logcatPath -Raw
    if ($logcat -match 'FATAL EXCEPTION|Process: .*visionguard') {
        throw "$Name crash marker found in logcat."
    }
    Add-Result -Name "$Name runtime" -Status 'PASS' -Evidence $artifactRoot
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path $repoRoot $Path
}

function Start-WpfFixtureWindows {
    # 夹具窗口在本脚本所在的 PowerShell 宿主进程内创建：每个来源一个 System.Windows.Forms 顶层窗口，
    # 用 GDI 渲染人像图，句柄可被 WindowEnumerator 枚举、被 PrintWindow 稳定捕获。
    # 宿主进程是 DPI 不感知的，与已退役的 net472 夹具窗口工具保持同样的 DPI 语义。
    param(
        [string[]]$ImagePaths,
        [string]$HandleFile
    )

    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing

    $forms = New-Object System.Collections.Generic.List[object]
    $images = New-Object System.Collections.Generic.List[object]
    $total = $ImagePaths.Count
    for ($index = 0; $index -lt $total; $index++) {
        $imagePath = $ImagePaths[$index]

        # 四路以内保持 2 列 640×360 的回归基线；更多来源改用 3 列小窗口，避免窗口落到屏幕外。
        $columns = if ($total -le 4) { 2 } else { 3 }
        $clientWidth = if ($total -le 4) { 640 } else { 480 }
        $clientHeight = if ($total -le 4) { 360 } else { 270 }
        $x = 60 + ($index % $columns) * ($clientWidth + 60)
        $y = 60 + [int][math]::Floor($index / $columns) * ($clientHeight + 70)

        $stream = New-Object System.IO.FileStream($imagePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try { $loaded = [System.Drawing.Image]::FromStream($stream) } finally { $stream.Dispose() }
        $image = New-Object System.Drawing.Bitmap $loaded
        $loaded.Dispose()

        $form = New-Object System.Windows.Forms.Form
        $form.Text = "VisionGuard WPF Smoke Fixture $($index + 1) - $([System.IO.Path]::GetFileName($imagePath))"
        $form.StartPosition = 'Manual'
        $form.Location = New-Object System.Drawing.Point($x, $y)
        $form.ClientSize = New-Object System.Drawing.Size($clientWidth, $clientHeight)
        $form.MinimumSize = New-Object System.Drawing.Size(320, 240)

        $picture = New-Object System.Windows.Forms.PictureBox
        $picture.Dock = [System.Windows.Forms.DockStyle]::Fill
        $picture.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::Zoom
        $picture.BackColor = [System.Drawing.Color]::Black
        $picture.Image = $image
        $form.Controls.Add($picture)

        # 窗口必须先可见再取句柄，否则拿不到 WindowEnumerator 认得的顶层窗口。
        # 宿主进程若以最小化方式启动，窗体可能继承最小化状态并被枚举过滤掉，
        # 因此显式恢复为普通状态并校验客户区，让这种失败变成明确报错。
        $form.Show()
        $form.WindowState = [System.Windows.Forms.FormWindowState]::Normal
        $form.Activate()
        [void]$form.Handle
        [System.Windows.Forms.Application]::DoEvents()
        if ($form.ClientSize.Width -le 0 -or $form.ClientSize.Height -le 0) {
            throw "Fixture window $($index + 1) has an empty client area ($($form.ClientSize.Width)x$($form.ClientSize.Height)); it cannot be captured."
        }

        $forms.Add($form)
        $images.Add($image)
    }

    $handles = @($forms | ForEach-Object { $_.Handle.ToInt64().ToString([System.Globalization.CultureInfo]::InvariantCulture) })
    [System.IO.File]::WriteAllLines($HandleFile, $handles, (New-Object System.Text.UTF8Encoding($false)))

    return [pscustomobject]@{ Forms = $forms; Images = $images }
}

function Run-WpfParserContract {
    # 两档各自的模型输出契约检查：legacy（Win7）= YOLOv5 [1,84,N] 需解析侧 NMS，
    # modern（Win10+）= YOLO26 [1,300,6] 模型内置 NMS。两者都必须从真实图片里解析出业务目标。
    # 这条检查不打开窗口、不依赖 GPU，因此可以在任意机器上对两个档位执行；
    # 它只证明「模型→解析→检测框」这一段，不证明窗口捕获、报警推送或 UI 显示。
    $contractImage = Resolve-RepoPath $WpfContractImagePath
    if (-not (Test-Path -LiteralPath $contractImage)) { throw "解析契约图片不存在：$contractImage" }

    $profiles = @(
        [pscustomobject]@{ Name = 'legacy'; ModelPath = Resolve-RepoPath $WpfLegacyModelPath },
        [pscustomobject]@{ Name = 'modern'; ModelPath = Resolve-RepoPath $WpfModernModelPath }
    )
    foreach ($profile in $profiles) {
        if (-not (Test-Path -LiteralPath $profile.ModelPath)) {
            throw "解析契约缺少 $($profile.Name) 档模型：$($profile.ModelPath)"
        }
    }

    $benchmarkProject = Join-Path $repoRoot 'tests\WpfInference.Benchmark\WpfInference.Benchmark.csproj'
    if (-not (Test-Path -LiteralPath $benchmarkProject)) { throw "解析契约探针工程不存在：$benchmarkProject" }

    foreach ($profile in $profiles) {
        $reportPath = Join-Path $artifactRoot "parser-contract-$($profile.Name).json"
        $stageLogPath = Join-Path $artifactRoot "parser-contract-$($profile.Name)-stages.log"
        $logPath = Join-Path $artifactRoot "parser-contract-$($profile.Name).txt"

        # 档位必须以全局属性传给探针：否则被引用的 VisionGuard 工程会按默认 modern 编译，
        # 变成「探针按 legacy、生产程序集按 modern」的假验证。
        $buildArguments = @('build', $benchmarkProject, '-c', 'Release', '--nologo')
        if ($profile.Name -eq 'legacy') { $buildArguments += '-p:OrtProfile=legacy' }
        Invoke-NativeLogged -FilePath 'dotnet' -Arguments $buildArguments -WorkingDirectory $repoRoot `
            -LogPath (Join-Path $artifactRoot "parser-contract-$($profile.Name)-build.txt")

        $exePath = Join-Path $repoRoot "tests\WpfInference.Benchmark\bin\x64\$($profile.Name)\net472\WpfInference.Benchmark.exe"
        if (-not (Test-Path -LiteralPath $exePath)) { throw "解析契约探针未生成：$exePath" }

        $previousReport = $env:VISIONGUARD_PARSER_CONTRACT_REPORT
        $previousStageLog = $env:VISIONGUARD_PARSER_CONTRACT_STAGE_LOG
        $env:VISIONGUARD_PARSER_CONTRACT_REPORT = $reportPath
        $env:VISIONGUARD_PARSER_CONTRACT_STAGE_LOG = $stageLogPath
        try {
            $startInfo = New-Object System.Diagnostics.ProcessStartInfo
            $startInfo.FileName = $exePath
            $startInfo.WorkingDirectory = $repoRoot
            $startInfo.UseShellExecute = $false
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            $arguments = @($profile.ModelPath, '--parser-contract', $contractImage, $WpfContractExpectedLabel, '0.5')
            $startInfo.Arguments = (@($arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' ')
            $process = [System.Diagnostics.Process]::Start($startInfo)
            $stdoutStream = [System.IO.File]::Create($logPath)
            $errorLogPath = Join-Path $artifactRoot "parser-contract-$($profile.Name)-error.txt"
            $stderrStream = [System.IO.File]::Create($errorLogPath)
            try {
                $copyStdout = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
                $copyStderr = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
                if (-not $process.WaitForExit(600000)) {
                    $process.Kill()
                    throw "解析契约探针（$($profile.Name)）超过 10 分钟未结束。阶段日志：$stageLogPath"
                }
                [void]$copyStdout.Wait(30000)
                [void]$copyStderr.Wait(30000)
            }
            finally {
                $stdoutStream.Dispose()
                $stderrStream.Dispose()
            }
            $exitCode = $process.ExitCode
        }
        finally {
            $env:VISIONGUARD_PARSER_CONTRACT_REPORT = $previousReport
            $env:VISIONGUARD_PARSER_CONTRACT_STAGE_LOG = $previousStageLog
        }

        if ($exitCode -ne 0) {
            throw "解析契约探针（$($profile.Name)）退出码 $exitCode。报告：$reportPath；阶段日志：$stageLogPath"
        }
        if (-not (Test-Path -LiteralPath $reportPath)) {
            throw "解析契约探针（$($profile.Name)）未写出报告：$reportPath；阶段日志：$stageLogPath"
        }

        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $failedChecks = @($report.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
        if (-not $report.passed -or $failedChecks.Count -gt 0) {
            throw "解析契约（$($profile.Name)）未通过：$($failedChecks -join ', ')。报告：$reportPath"
        }
        Add-Result -Name "WPF parser contract ($($profile.Name))" -Status 'PASS' `
            -Note "model=$($report.model) layout=$($report.rawOutputLayout) label=$($report.detectedLabel)" `
            -Evidence $reportPath
    }
}

function Run-ResidentLaunch {
    # Independent account storage also isolates mutexes, lifecycle events and login-startup side effects.
    $serverScript = Join-Path $repoRoot 'scripts\start-isolated-test-server.ps1'
    $assertScript = Join-Path $repoRoot 'scripts\assert-resident-visible.js'
    $accountProbe = Join-Path $repoRoot 'tests\AccountMedia.Probe\bin\Release\net472\AccountMedia.Probe.exe'
    if (-not (Test-Path -LiteralPath $accountProbe)) { throw 'Build tests/AccountMedia.Probe in Release before this mode.' }
    $occupied = @(Get-NetTCPConnection -LocalPort $ResidentPort -State Listen -ErrorAction SilentlyContinue)
    if ($occupied.Count -gt 0) { throw "Port $ResidentPort is occupied; the existing process was left running." }
    $serverLog = Join-Path $artifactRoot 'isolated-server.log'
    $serverErrorLog = Join-Path $artifactRoot 'isolated-server-error.log'
    $server = $null
    $previousNodeEnvironment = @{}
    foreach ($name in @('VISIONGUARD_ACCOUNT_DIR', 'VISIONGUARD_TEST_ACCOUNTS_PATH', 'VISIONGUARD_TEST_USERNAME', 'VISIONGUARD_LOGIN_PASSWORD', 'VISIONGUARD_CHANNEL', 'VISIONGUARD_SERVER_URL', 'VISIONGUARD_SETTINGS_PATH')) {
        $previousNodeEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    $residentConfigPaths = @()
    try {
        $server = Start-Process -FilePath 'powershell' -ArgumentList @('-ExecutionPolicy', 'Bypass', '-File', $serverScript, '-Port', $ResidentPort.ToString(), '-Channel', $ResidentChannel, '-SkipBuild') -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -WindowStyle Hidden -PassThru
        $ready = $false
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            try {
                $health = Invoke-WebRequest -Uri "http://127.0.0.1:$ResidentPort/health" -UseBasicParsing -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            if ($server.HasExited) { throw "Isolated server exited: $serverErrorLog" }
        }
        if (-not $ready) { throw "Isolated server did not become ready: $serverErrorLog" }
        $env:VISIONGUARD_TEST_ACCOUNTS_PATH = Join-Path $repoRoot ".local\e2e-server\$ResidentChannel\test-accounts.json"
        $testAccounts = Get-Content -LiteralPath $env:VISIONGUARD_TEST_ACCOUNTS_PATH -Raw -Encoding UTF8 | ConvertFrom-Json
        $testAccount = $testAccounts | Where-Object { $_.username -eq 'vg-test' } | Select-Object -First 1
        if (-not $testAccount) { throw 'The isolated vg-test account is missing.' }
        $env:VISIONGUARD_TEST_USERNAME = $testAccount.username
        $env:VISIONGUARD_CHANNEL = $ResidentChannel
        $env:VISIONGUARD_SERVER_URL = "http://127.0.0.1:$ResidentPort"
        foreach ($profile in @('modern', 'legacy')) {
            $detectorExe = Resolve-RepoPath "detector\windows-wpf\bin\x64\$profile\VisionGuard.Detector.Windows.exe"
            if (-not (Test-Path -LiteralPath $detectorExe)) { throw "Build the $profile Windows Release artifact first." }
            $env:VISIONGUARD_ACCOUNT_DIR = Join-Path $repoRoot ".local\resident-launch\$ResidentChannel\$profile"
            $env:VISIONGUARD_SETTINGS_PATH = Join-Path $artifactRoot "resident-launch-$profile-settings.ini"
            $env:VISIONGUARD_LOGIN_PASSWORD = $testAccount.password
            $loginOutput = & $accountProbe '--login' $env:VISIONGUARD_SERVER_URL $testAccount.username "Resident acceptance $profile"
            if ($LASTEXITCODE -ne 0) { throw 'Windows account login failed.' }
            $identity = ($loginOutput | Out-String) | ConvertFrom-Json
            Remove-Item Env:VISIONGUARD_LOGIN_PASSWORD -ErrorAction SilentlyContinue
            $residentConfigPath = Join-Path $env:VISIONGUARD_ACCOUNT_DIR 'resident-config.json'
            $residentConfigPaths += $residentConfigPath
            $reportPath = Join-Path $artifactRoot "resident-launch-$profile.json"
            $stdoutLog = Join-Path $artifactRoot "resident-launch-$profile.txt"
            $stderrLog = Join-Path $artifactRoot "resident-launch-$profile-error.txt"
            $probe = Start-Process -FilePath $detectorExe -ArgumentList @('--resident-launch', $reportPath, '6000') -WorkingDirectory (Split-Path -Parent $detectorExe) -NoNewWindow -PassThru -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
            if (-not $probe.WaitForExit(60000)) { try { $probe.Kill() } catch { }; throw "$profile resident-launch timed out." }
            if (-not (Test-Path -LiteralPath $reportPath)) { throw "$profile resident-launch report is missing." }
            $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if (-not $report.isRunning -or -not $report.handshakeSucceeded) { throw "$profile resident failed: $($report.failureReason)" }
            $visibleOutput = & node $assertScript $env:VISIONGUARD_SERVER_URL $identity.deviceId 2>&1
            $visibleExit = $LASTEXITCODE
            $visiblePath = Join-Path $artifactRoot "resident-visibility-$profile.json"
            [System.IO.File]::WriteAllText($visiblePath, ($visibleOutput | Out-String).Trim(), (New-Object System.Text.UTF8Encoding($false)))
            if ($visibleExit -ne 0) { throw "$profile resident is not visible to its account: $visiblePath" }
            Add-Result -Name "Resident launch ($profile)" -Status 'PASS' -Note 'account child authenticated; server reports components.resident=running' -Evidence $visiblePath
            foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Resident.Windows.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($residentConfigPath, [StringComparison]::OrdinalIgnoreCase) -ge 0 })) {
                Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    finally {
        foreach ($residentConfigPath in $residentConfigPaths) {
            foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Resident.Windows.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($residentConfigPath, [StringComparison]::OrdinalIgnoreCase) -ge 0 })) {
                Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
        foreach ($name in $previousNodeEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousNodeEnvironment[$name]) }
        if ($server -and -not $server.HasExited) {
            foreach ($connection in @(Get-NetTCPConnection -LocalPort $ResidentPort -State Listen -ErrorAction SilentlyContinue)) {
                $listener = Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)" -ErrorAction SilentlyContinue
                if ($listener -and $listener.Name -eq 'node.exe' -and $listener.ParentProcessId -eq $server.Id) { Stop-Process -Id $listener.ProcessId -Force -ErrorAction SilentlyContinue }
            }
            try { $server.Kill() } catch { }
        }
    }
}

function Run-ModelDownload {
    # 模型下载与装配：驱动「全局设定」页模型清单里那个下载按钮背后的同一条 ViewModel 路径。
    # 走真实生产服务器（模型下载本来就是线上行为，不在本模式里模拟服务端）。
    # 这会在真实缓存 %APPDATA%\VisionGuard\models\ 里落一个模型文件（约 10-100 MB），并清掉同名临时文件。
    $profile = if ($ModelDownloadLegacyProfile) { 'legacy' } else { 'modern' }
    $benchmarkProject = Join-Path $repoRoot 'tests\WpfInference.Benchmark\WpfInference.Benchmark.csproj'
    if (-not (Test-Path -LiteralPath $benchmarkProject)) { throw "下载契约探针工程不存在：$benchmarkProject" }
    if ($profile -eq 'legacy' -and -not ($ModelDownloadKey -like 'yolov5*')) {
        throw "legacy 档只能下载 yolov5* 模型，收到：$ModelDownloadKey"
    }
    if ($profile -eq 'modern' -and -not ($ModelDownloadKey -like 'yolo26*')) {
        throw "modern 档只能下载 yolo26* 模型，收到：$ModelDownloadKey"
    }

    $buildArguments = @('build', $benchmarkProject, '-c', 'Release', '--nologo')
    if ($profile -eq 'legacy') { $buildArguments += '-p:OrtProfile=legacy' }
    Invoke-NativeLogged -FilePath 'dotnet' -Arguments $buildArguments -WorkingDirectory $repoRoot `
        -LogPath (Join-Path $artifactRoot "model-download-$profile-build.txt")

    $exePath = Join-Path $repoRoot "tests\WpfInference.Benchmark\bin\x64\$profile\net472\WpfInference.Benchmark.exe"
    if (-not (Test-Path -LiteralPath $exePath)) { throw "下载契约探针未生成：$exePath" }

    $reportPath = Join-Path $artifactRoot "model-download-$profile.json"
    $logPath = Join-Path $artifactRoot "model-download-$profile.log"
    $previousReport = $env:VISIONGUARD_MODEL_DOWNLOAD_REPORT
    $env:VISIONGUARD_MODEL_DOWNLOAD_REPORT = $reportPath
    try {
        Invoke-NativeLogged -FilePath $exePath -Arguments @('unused', '--model-download', $ModelDownloadKey) `
            -WorkingDirectory $repoRoot -LogPath $logPath
    }
    finally {
        $env:VISIONGUARD_MODEL_DOWNLOAD_REPORT = $previousReport
    }

    if (-not (Test-Path -LiteralPath $reportPath)) { throw "下载契约探针未写出报告：$reportPath" }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $failedChecks = @($report.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
    if (-not $report.passed -or $failedChecks.Count -gt 0) {
        throw "模型下载（$profile / $ModelDownloadKey）未通过：$($failedChecks -join ', ')。报告：$reportPath"
    }
    Add-Result -Name "Model download ($profile / $($report.modelKey))" -Status 'PASS' `
        -Note ("size={0:N1} MB in {1} ms, progress samples={2}" -f ($report.sizeBytes / 1MB), $report.elapsedMs, $report.progressSamples) `
        -Evidence $reportPath
}

function Run-SourceAutoSave {
    # 来源页参数自动保存与采集目标重置：驱动真实的 SourceViewModel（界面那条路径），
    # 断言「改动即已保存」「防抖前不落盘」「重置把窗口/选区/遮罩一起清掉并立即落盘」「保存按钮确实不存在」。
    # 用隔离 settings 文件，不碰真实配置。
    $benchmarkProject = Join-Path $repoRoot 'tests\WpfInference.Benchmark\WpfInference.Benchmark.csproj'
    if (-not (Test-Path -LiteralPath $benchmarkProject)) { throw "契约探针工程不存在：$benchmarkProject" }

    Invoke-NativeLogged -FilePath 'dotnet' -Arguments @('build', $benchmarkProject, '-c', 'Release', '--nologo') `
        -WorkingDirectory $repoRoot -LogPath (Join-Path $artifactRoot 'source-autosave-build.txt')

    $exePath = Join-Path $repoRoot 'tests\WpfInference.Benchmark\bin\x64\modern\net472\WpfInference.Benchmark.exe'
    if (-not (Test-Path -LiteralPath $exePath)) { throw "契约探针未生成：$exePath" }

    $settingsPath = Join-Path $artifactRoot 'source-autosave-settings.ini'
    $reportPath = Join-Path $artifactRoot 'source-autosave.json'
    $logPath = Join-Path $artifactRoot 'source-autosave.log'
    $previousReport = $env:VISIONGUARD_AUTOSAVE_REPORT
    $env:VISIONGUARD_AUTOSAVE_REPORT = $reportPath
    try {
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $exePath
        $startInfo.WorkingDirectory = $repoRoot
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.Arguments = (@('unused', '--source-autosave', $settingsPath) | ForEach-Object { '"' + $_ + '"' }) -join ' '
        $process = [System.Diagnostics.Process]::Start($startInfo)
        $stdoutStream = [System.IO.File]::Create($logPath)
        $stderrStream = [System.IO.File]::Create((Join-Path $artifactRoot 'source-autosave-error.txt'))
        try {
            $copyStdout = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
            $copyStderr = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
            if (-not $process.WaitForExit(120000)) { try { $process.Kill() } catch { }; throw '自动保存契约探针超过 120 秒未结束' }
            [void]$copyStdout.Wait(30000)
            [void]$copyStderr.Wait(30000)
        }
        finally {
            $stdoutStream.Dispose()
            $stderrStream.Dispose()
        }
    }
    finally {
        $env:VISIONGUARD_AUTOSAVE_REPORT = $previousReport
    }

    if (-not (Test-Path -LiteralPath $reportPath)) { throw "自动保存契约探针未写出报告：$reportPath；日志：$logPath" }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $failedChecks = @($report.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
    if (-not $report.passed -or $failedChecks.Count -gt 0) {
        throw "来源自动保存契约未通过：$($failedChecks -join ', ')。报告：$reportPath"
    }
    Add-Result -Name 'Source auto-save contract' -Status 'PASS' `
        -Note "$($report.checks.Count) checks: 改动即保存 / 防抖 / 目标重置 / 保存按钮已移除" -Evidence $reportPath
}

function Run-CardLayoutPlan {
    # 画面与组件布局契约：驱动等比呈现和真实控件量测（不显示窗口、不建推理会话），
    # 断言等比画面与检测坐标、主卡自适应高度、来源卡边界、五个设置分类和统一控件高度。
    # 它是组件量测补充证据，不证明真实界面的视觉与拖拽手感——那部分必须 owner 目检。
    $benchmarkProject = Join-Path $repoRoot 'tests\WpfInference.Benchmark\WpfInference.Benchmark.csproj'
    if (-not (Test-Path -LiteralPath $benchmarkProject)) { throw "契约探针工程不存在：$benchmarkProject" }

    Invoke-NativeLogged -FilePath 'dotnet' -Arguments @('build', $benchmarkProject, '-c', 'Release', '--nologo') `
        -WorkingDirectory $repoRoot -LogPath (Join-Path $artifactRoot 'card-layout-plan-build.txt')

    $exePath = Join-Path $repoRoot 'tests\WpfInference.Benchmark\bin\x64\modern\net472\WpfInference.Benchmark.exe'
    if (-not (Test-Path -LiteralPath $exePath)) { throw "契约探针未生成：$exePath" }

    $reportPath = Join-Path $artifactRoot 'card-layout-plan.json'
    $logPath = Join-Path $artifactRoot 'card-layout-plan.log'
    $errorLogPath = Join-Path $artifactRoot 'card-layout-plan-error.txt'

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $exePath
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = (@('unused', '--layout-plan', $reportPath) | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutStream = [System.IO.File]::Create($logPath)
    $stderrStream = [System.IO.File]::Create($errorLogPath)
    try {
        $copyStdout = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
        $copyStderr = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
        if (-not $process.WaitForExit(120000)) { try { $process.Kill() } catch { }; throw '卡片布局契约探针超过 120 秒未结束' }
        [void]$copyStdout.Wait(30000)
        [void]$copyStderr.Wait(30000)
    }
    finally {
        $stdoutStream.Dispose()
        $stderrStream.Dispose()
    }

    if (-not (Test-Path -LiteralPath $reportPath)) { throw "卡片布局契约探针未写出报告：$reportPath；日志：$logPath" }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $failedChecks = @($report.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
    if (-not $report.passed -or $failedChecks.Count -gt 0) {
        throw "卡片布局契约未通过：$($failedChecks -join ', ')。报告：$reportPath"
    }
    Add-Result -Name 'Card layout plan contract' -Status 'PASS' `
        -Note "$($report.checks.Count) checks: 单路预览高度 / 1-4 路单页 / 5 路以上分页自洽 / 放大不退化 / 确定性 / 退化输入" -Evidence $reportPath
}

function Run-PerformanceWatchdog {
    # 推理性能看门狗契约：驱动 PerformanceWatchdog（纯计算，不开窗口、不建推理会话），
    # 断言「实测 < 目标×80% 且持续 30 秒才提示」的判定边界、提示文案与三个口径常量。
    # 它取代了原先「容量基线」的性能提示（容量菜单已于 2026-09-20 移除）。
    $benchmarkProject = Join-Path $repoRoot 'tests\WpfInference.Benchmark\WpfInference.Benchmark.csproj'
    if (-not (Test-Path -LiteralPath $benchmarkProject)) { throw "契约探针工程不存在：$benchmarkProject" }

    Invoke-NativeLogged -FilePath 'dotnet' -Arguments @('build', $benchmarkProject, '-c', 'Release', '--nologo') `
        -WorkingDirectory $repoRoot -LogPath (Join-Path $artifactRoot 'performance-watchdog-build.txt')

    $exePath = Join-Path $repoRoot 'tests\WpfInference.Benchmark\bin\x64\modern\net472\WpfInference.Benchmark.exe'
    if (-not (Test-Path -LiteralPath $exePath)) { throw "契约探针未生成：$exePath" }

    $reportPath = Join-Path $artifactRoot 'performance-watchdog.json'
    $logPath = Join-Path $artifactRoot 'performance-watchdog.log'
    $errorLogPath = Join-Path $artifactRoot 'performance-watchdog-error.txt'

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $exePath
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = (@('unused', '--performance-watchdog', $reportPath) | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutStream = [System.IO.File]::Create($logPath)
    $stderrStream = [System.IO.File]::Create($errorLogPath)
    try {
        $copyStdout = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
        $copyStderr = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
        if (-not $process.WaitForExit(60000)) { try { $process.Kill() } catch { }; throw '推理性能看门狗探针超过 60 秒未结束' }
        [void]$copyStdout.Wait(15000)
        [void]$copyStderr.Wait(15000)
    }
    finally {
        $stdoutStream.Dispose()
        $stderrStream.Dispose()
    }

    if (-not (Test-Path -LiteralPath $reportPath)) { throw "推理性能看门狗探针未写出报告：$reportPath；日志：$logPath" }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $failedChecks = @($report.checks | Where-Object { -not $_.passed } | ForEach-Object { $_.name })
    if (-not $report.passed -or $failedChecks.Count -gt 0) {
        throw "推理性能看门狗契约未通过：$($failedChecks -join ', ')。报告：$reportPath"
    }
    Add-Result -Name 'Performance watchdog contract' -Status 'PASS' `
        -Note "$($report.checks.Count) checks: 80% 阈值边界 / 30 秒持续 / 提示文案 / 口径常量" -Evidence $reportPath
}

function Resolve-WebCameraChainModels {
    param([string]$Key)
    # 模型目录只读复用已有缓存：显式参数 → 环境变量 → WPF 默认缓存 → 服务端模型目录。
    $candidates = @()
    if ($WebCameraChainModelsDirectory) { $candidates += (Resolve-RepoPath $WebCameraChainModelsDirectory) }
    if ($env:VISIONGUARD_MODELS_DIR) { $candidates += $env:VISIONGUARD_MODELS_DIR }
    if ($env:APPDATA) { $candidates += (Join-Path $env:APPDATA 'VisionGuard\models') }
    $candidates += (Join-Path $repoRoot 'server\data\models')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate "$Key.onnx"))) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw "找不到 $Key.onnx：先跑 -Mode ModelDownload -ModelDownloadKey $Key，或用 -WebCameraChainModelsDirectory 指定已有模型目录。"
}

function Run-WebCameraChain {
    # 完整业务链：真机浏览器摄像头 → 统一服务中继 → 真实 Windows 视觉推理节点 → 人员检测报警落库。
    # 只用隔离服务（回环明文 HTTP，回环地址对浏览器即安全上下文）、私有测试账号与隔离账号目录。
    # 需要真实设备（模拟器没有可推流的摄像头）、Windows 统一包与 Release 账号探针。
    # 设备首次授权需要人在设备上点 Chrome 的「访问该网站时允许」，脚本会等这一步。
    $modelKey = 'yolo26n_320'
    $tools = Get-AndroidTools
    $devices = @(Get-AdbDevices -Adb $tools.Adb)
    $selected = $null
    if ($DeviceSerial) {
        $selected = $devices | Where-Object { $_.Serial -eq $DeviceSerial -and $_.State -eq 'device' } | Select-Object -First 1
        if (-not $selected) { throw "Requested device is not ready: $DeviceSerial" }
    }
    else {
        $physical = @($devices | Where-Object { $_.State -eq 'device' -and -not $_.IsEmulator })
        if ($physical.Count -eq 0) { throw 'WebCameraChain needs a ready physical device with a camera; an emulator has no camera to publish.' }
        if ($physical.Count -gt 1) { throw 'Multiple physical devices found; use -DeviceSerial.' }
        $selected = $physical[0]
    }
    if ($selected.IsEmulator) { throw 'WebCameraChain needs a physical device with a real camera.' }
    $summary.selectedDevice = $selected.Serial
    Add-Result -Name 'Device' -Status 'PASS' -Note $selected.Serial

    $serverScript = Join-Path $repoRoot 'scripts\start-isolated-test-server.ps1'
    $accountProbe = Join-Path $repoRoot 'tests\AccountMedia.Probe\bin\Release\net472\AccountMedia.Probe.exe'
    if (-not (Test-Path -LiteralPath $accountProbe)) { throw 'Build tests/AccountMedia.Probe in Release before this mode.' }
    $detectorExe = Join-Path $repoRoot 'detector\windows-package\bin\Release\runtimes\modern\VisionGuard.Detector.Windows.exe'
    if (-not (Test-Path -LiteralPath $detectorExe)) { throw 'Build the Windows unified package first (visionguard-build -Target Windows).' }
    $modelsDirectory = Resolve-WebCameraChainModels -Key $modelKey
    if (Get-NetTCPConnection -LocalPort $WebCameraChainPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $WebCameraChainPort is occupied; the existing process was left running." }

    $chainRoot = Join-Path $repoRoot ".local\web-camera-chain\$WebCameraChainChannel"
    $accountDirectory = Join-Path $chainRoot 'account'
    $settingsDirectory = Join-Path $chainRoot 'settings'
    $chainEvidence = Join-Path $artifactRoot 'web-camera-chain'
    $logDirectory = Join-Path $chainEvidence 'logs'
    foreach ($directory in @($accountDirectory, $settingsDirectory, $logDirectory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    $environmentPath = Join-Path $chainEvidence 'isolated-environment.json'
    $environment = [ordered]@{
        serviceUrl = "http://127.0.0.1:$WebCameraChainPort"
        accountDir = $accountDirectory
        settingsPath = $settingsDirectory
        modelsDirectory = $modelsDirectory
        logDirectory = $logDirectory
    }
    [System.IO.File]::WriteAllText($environmentPath, ($environment | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

    $serverLog = Join-Path $chainEvidence 'isolated-server.log'
    $serverErrorLog = Join-Path $chainEvidence 'isolated-server-error.log'
    $server = $null
    $detectorProcess = $null
    $previousEnvironment = @{}
    foreach ($name in @('VISIONGUARD_ACCOUNT_DIR', 'VISIONGUARD_SERVER_URL', 'VISIONGUARD_SETTINGS_PATH', 'VISIONGUARD_MODELS_DIR', 'VISIONGUARD_LOG_DIR', 'VISIONGUARD_LOGIN_PASSWORD', 'VISIONGUARD_MEDIA_DIAGNOSTICS', 'VISIONGUARD_CHAIN_PASSWORD')) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    try {
        $server = Start-Process -FilePath 'powershell' -ArgumentList @('-ExecutionPolicy', 'Bypass', '-File', $serverScript, '-Port', $WebCameraChainPort.ToString(), '-Channel', $WebCameraChainChannel, '-SkipBuild') -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -WindowStyle Hidden -PassThru
        $ready = $false
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            try {
                $health = Invoke-WebRequest -Uri "http://127.0.0.1:$WebCameraChainPort/health" -UseBasicParsing -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            if ($server.HasExited) { throw "Isolated server exited: $serverErrorLog" }
        }
        if (-not $ready) { throw "Isolated server did not become ready: $serverErrorLog" }
        Add-Result -Name 'Isolated service' -Status 'PASS' -Note "http://127.0.0.1:$WebCameraChainPort channel=$WebCameraChainChannel" -Evidence $serverLog

        $testAccountsPath = Join-Path $repoRoot ".local\e2e-server\$WebCameraChainChannel\test-accounts.json"
        $testAccounts = Get-Content -LiteralPath $testAccountsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $testAccount = $testAccounts | Where-Object { $_.username -eq 'vg-test' } | Select-Object -First 1
        if (-not $testAccount) { throw 'The isolated vg-test account is missing.' }

        # 真实 Windows 节点：共享账号探针登录到隔离实例，再由统一入口带着同一账号目录启动。
        $env:VISIONGUARD_ACCOUNT_DIR = $accountDirectory
        $env:VISIONGUARD_SERVER_URL = "http://127.0.0.1:$WebCameraChainPort"
        $env:VISIONGUARD_SETTINGS_PATH = $settingsDirectory
        $env:VISIONGUARD_MODELS_DIR = $modelsDirectory
        $env:VISIONGUARD_LOGIN_PASSWORD = $testAccount.password
        $loginOutput = & $accountProbe '--login' $env:VISIONGUARD_SERVER_URL $testAccount.username
        if ($LASTEXITCODE -ne 0) { throw 'Windows account login failed.' }
        $identity = ($loginOutput | Out-String) | ConvertFrom-Json
        Remove-Item Env:VISIONGUARD_LOGIN_PASSWORD -ErrorAction SilentlyContinue
        Add-Result -Name 'Windows node login' -Status 'PASS' -Note "deviceId=$($identity.deviceId)"
        $env:VISIONGUARD_MEDIA_DIAGNOSTICS = '1'
        $detectorProcess = Start-Process -FilePath $detectorExe -ArgumentList @('--isolated-environment', $environmentPath) -WorkingDirectory (Split-Path -Parent $detectorExe) -PassThru
        Add-Result -Name 'Windows node launch' -Status 'PASS' -Note "pid=$($detectorProcess.Id)"

        $chainScript = Join-Path $repoRoot 'scripts\web-camera-chain.js'
        $env:VISIONGUARD_CHAIN_PASSWORD = $testAccount.password
        $chainArguments = @($chainScript, '--service', $env:VISIONGUARD_SERVER_URL, '--username', $testAccount.username, '--password-env', 'VISIONGUARD_CHAIN_PASSWORD',
            '--evidence', $chainEvidence, '--serial', $selected.Serial, '--adb', $tools.Adb, '--camera', $WebCameraChainCamera,
            '--wpf-device-id', $identity.deviceId, '--permission-timeout', $WebCameraChainPermissionTimeoutSeconds.ToString(),
            '--alert-timeout', $WebCameraChainAlertTimeoutSeconds.ToString())
        if ($WebCameraChainKeepStreaming) { $chainArguments += '--keep-streaming' }
        # 驱动输出必须实时可见（授权提示写在输出里），同时落一份 UTF-8 日志；原生 stderr 不能变成终止错误。
        $chainLog = Join-Path $chainEvidence 'web-camera-chain-console.txt'
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $chainOutput = & node.exe @chainArguments 2>&1 | ForEach-Object { $line = $_.ToString(); Write-Host $line; $line }
            $chainExit = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
            $chainOutput | Set-Content -Encoding UTF8 $chainLog
            Remove-Item Env:VISIONGUARD_CHAIN_PASSWORD -ErrorAction SilentlyContinue
        }

        $reportPath = Join-Path $chainEvidence 'web-camera-chain-report.json'
        if (-not (Test-Path -LiteralPath $reportPath)) { throw "The chain report is missing: $chainLog" }
        $chainReport = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $checks = [ordered]@{
            secureContext = 'browser secure context with getUserMedia'
            cameraRegistered = 'web-camera device registered by the page'
            targetBound = 'page bound to the Windows node'
            streaming = 'device camera publishing'
            relayStreaming = 'service relay reports a live stream'
            windowsSourceBound = 'Windows node created the remote source'
            inferenceRunning = 'Windows node running inference on that source'
            personAlertReceived = 'service stored the person alert'
            serverScreenshot = 'service serves the alert screenshot'
        }
        foreach ($check in $checks.Keys) {
            $value = $chainReport.checks.$check
            $status = if ($value -eq $true) { 'PASS' } else { 'FAIL' }
            Add-Result -Name "Web camera chain: $check" -Status $status -Note $checks[$check]
        }
        if ($chainExit -ne 0) {
            $reasons = @($chainReport.failures | ForEach-Object { "$($_.name): $(($_.detail | Out-String).Trim())" }) -join '; '
            throw "Web camera chain failed: $reasons (see $chainLog)"
        }
        $alertNote = 'person alert stored by the service'
        if ($chainReport.alert) { $alertNote = "alertId=$($chainReport.alert.alertId) source=$($chainReport.alert.sourceName)" }
        Add-Result -Name 'Web camera chain' -Status 'PASS' -Note $alertNote -Evidence $reportPath
    }
    finally {
        Remove-Item Env:VISIONGUARD_CHAIN_PASSWORD -ErrorAction SilentlyContinue
        # 只清理本轮隔离实例：按账号目录与命令行的隔离环境路径定位进程。
        foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Detector.Windows.exe' OR Name = 'VisionGuard.Resident.Windows.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -and ($_.CommandLine.IndexOf($accountDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($environmentPath, [StringComparison]::OrdinalIgnoreCase) -ge 0) })) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($detectorProcess -and -not $detectorProcess.HasExited) { Stop-Process -Id $detectorProcess.Id -Force -ErrorAction SilentlyContinue }
        foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name]) }
        if ($server -and -not $server.HasExited) {
            foreach ($connection in @(Get-NetTCPConnection -LocalPort $WebCameraChainPort -State Listen -ErrorAction SilentlyContinue)) {
                $listener = Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)" -ErrorAction SilentlyContinue
                if ($listener -and $listener.Name -eq 'node.exe' -and $listener.ParentProcessId -eq $server.Id) { Stop-Process -Id $listener.ProcessId -Force -ErrorAction SilentlyContinue }
            }
            try { $server.Kill() } catch { }
        }
        try { & $tools.Adb -s $selected.Serial reverse --remove "tcp:$WebCameraChainPort" *> $null } catch { }
        try { & $tools.Adb -s $selected.Serial forward --remove 'tcp:9333' *> $null } catch { }
    }
}

function Save-WindowScreenshot {
    # 只抓取指定进程的主窗口（GDI PrintWindow）：给推流端 / 接收端的界面现状留证，
    # 脚本断言不能代替界面目检。窗口必须在当前桌面可见（远程桌面最小化或锁屏时抓不到内容）。
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory = $true)][string]$Path
    )

    Add-Type -AssemblyName System.Drawing
    if (-not ('VisionGuardWindowCapture' -as [type])) {
        Add-Type -Namespace '' -Name 'VisionGuardWindowCapture' -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool PrintWindow(System.IntPtr hWnd, System.IntPtr hdcBlt, uint nFlags);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool GetWindowRect(System.IntPtr hWnd, out RECT rect);
[System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
    }

    $Process.Refresh()
    $handle = $Process.MainWindowHandle
    if ($handle -eq [IntPtr]::Zero) { return $false }
    [void][VisionGuardWindowCapture]::SetForegroundWindow($handle)
    Start-Sleep -Milliseconds 400

    $rect = New-Object VisionGuardWindowCapture+RECT
    if (-not [VisionGuardWindowCapture]::GetWindowRect($handle, [ref]$rect)) { return $false }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) { return $false }

    $bitmap = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $hdc = $graphics.GetHdc()
            try { $captured = [VisionGuardWindowCapture]::PrintWindow($handle, $hdc, 2) }
            finally { $graphics.ReleaseHdc($hdc) }
        }
        finally { $graphics.Dispose() }
        if (-not $captured) { return $false }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        return $true
    }
    finally { $bitmap.Dispose() }
}

function Run-WindowsPushChain {
    # Windows 节点的「推流态」整条链：本机真实采集窗口 → 生产媒体契约 → 隔离统一服务中继 → 另一个真实推理节点收到并推理。
    # 不使用合成帧：推流端与接收端都是 Release WPF 实例（各自独立账号目录 / 设备身份）。
    # 断言：按能力放行的推流、显式绑定、帧头/JPEG 契约、序号单调、中继计数推进、推流端不产生本机报警、方向互斥。
    # 同时抓两个实例的主窗口截图，界面现状由 owner 目检（脚本断言不替代目检）。
    $modelKey = 'yolo26m_320'
    $serverScript = Join-Path $repoRoot 'scripts\start-isolated-test-server.ps1'
    $accountProbe = Join-Path $repoRoot 'tests\AccountMedia.Probe\bin\Release\net472\AccountMedia.Probe.exe'
    if (-not (Test-Path -LiteralPath $accountProbe)) { throw 'Build tests/AccountMedia.Probe in Release before this mode.' }
    $detectorExe = Join-Path $repoRoot 'detector\windows-package\bin\Release\runtimes\modern\VisionGuard.Detector.Windows.exe'
    if (-not (Test-Path -LiteralPath $detectorExe)) { throw 'Build the Windows unified package first (visionguard-build -Target Windows).' }
    $fixtureImage = Resolve-RepoPath $WindowsPushChainFixtureImage
    if (-not (Test-Path -LiteralPath $fixtureImage)) { throw "Fixture image missing: $fixtureImage" }
    if (Get-NetTCPConnection -LocalPort $WindowsPushChainPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $WindowsPushChainPort is occupied; the existing process was left running." }

    $chainRoot = Join-Path $repoRoot ".local\windows-push-chain\$WindowsPushChainChannel"
    # 一次性隔离实例：账号目录与服务数据都从零开始，避免上一轮遗留的设备登记或绑定影响本轮断言。
    $serverDataRoot = Join-Path $repoRoot ".local\e2e-server\$WindowsPushChainChannel"
    foreach ($stale in @($chainRoot, $serverDataRoot)) {
        if (Test-Path -LiteralPath $stale) { Remove-Item -LiteralPath $stale -Recurse -Force -ErrorAction SilentlyContinue }
    }
    $accountDirectory = Join-Path $chainRoot 'account'
    # VISIONGUARD_SETTINGS_PATH 是「设置文件」而不是目录：应用登录后会把它解析成
    # <父目录>/accounts/<scope>/settings.ini（见 SettingsStore.ResolveSettingsPath）。
    $settingsPath = Join-Path $chainRoot 'settings.ini'
    $logDirectory = Join-Path $chainRoot 'logs'
    $evidence = Join-Path $artifactRoot 'windows-push-chain'
    foreach ($directory in @($accountDirectory, $chainRoot, $logDirectory, $evidence)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    $environmentPath = Join-Path $evidence 'isolated-environment.json'
    $environment = [ordered]@{
        serviceUrl = "http://127.0.0.1:$WindowsPushChainPort"
        accountDir = $accountDirectory
        settingsPath = $settingsPath
        modelsDirectory = (Join-Path $repoRoot 'server\data\models')
        logDirectory = $logDirectory
    }
    [System.IO.File]::WriteAllText($environmentPath, ($environment | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))

    $serverLog = Join-Path $evidence 'isolated-server.log'
    $serverErrorLog = Join-Path $evidence 'isolated-server-error.log'
    $server = $null
    $detectorProcess = $null
    $fixtures = $null
    $previousEnvironment = @{}
    foreach ($name in @('VISIONGUARD_ACCOUNT_DIR', 'VISIONGUARD_SERVER_URL', 'VISIONGUARD_SETTINGS_PATH', 'VISIONGUARD_MODELS_DIR', 'VISIONGUARD_LOG_DIR', 'VISIONGUARD_LOGIN_PASSWORD', 'VISIONGUARD_CHAIN_PASSWORD', 'VISIONGUARD_MEDIA_DIAGNOSTICS')) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    # 服务数据是删掉重建的，但仍可能有上一轮留下的检测端进程带着旧账号目录在跑；按命令行里的隔离路径先清掉。
    foreach ($staleProcess in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Detector.Windows.exe' OR Name = 'VisionGuard.Resident.Windows.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and ($_.CommandLine.IndexOf($chainRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($serverDataRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) })) {
        Stop-Process -Id $staleProcess.ProcessId -Force -ErrorAction SilentlyContinue
    }
    try {
        # 中继侧发布 / 投递 / 回执都在这个开关后面：推流链路出问题时先拿到服务端视角的逐帧日志。
        $env:VISIONGUARD_MEDIA_DIAGNOSTICS = '1'
        $server = Start-Process -FilePath 'powershell' -ArgumentList @('-ExecutionPolicy', 'Bypass', '-File', $serverScript, '-Port', $WindowsPushChainPort.ToString(), '-Channel', $WindowsPushChainChannel, '-SkipBuild') -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -WindowStyle Hidden -PassThru
        $ready = $false
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            try {
                $health = Invoke-WebRequest -Uri "http://127.0.0.1:$WindowsPushChainPort/health" -UseBasicParsing -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            if ($server.HasExited) { throw "Isolated server exited: $serverErrorLog" }
        }
        if (-not $ready) { throw "Isolated server did not become ready: $serverErrorLog" }
        Add-Result -Name 'Isolated service' -Status 'PASS' -Note "http://127.0.0.1:$WindowsPushChainPort channel=$WindowsPushChainChannel" -Evidence $serverLog

        $testAccountsPath = Join-Path $repoRoot ".local\e2e-server\$WindowsPushChainChannel\test-accounts.json"
        $testAccounts = Get-Content -LiteralPath $testAccountsPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $testAccount = $testAccounts | Where-Object { $_.username -eq 'vg-test' } | Select-Object -First 1
        if (-not $testAccount) { throw 'The isolated vg-test account is missing.' }

        # 推流端使用哪个采集源由设置文件决定：窗口标题与主进程名都要对上夹具窗口。
        $env:VISIONGUARD_ACCOUNT_DIR = $accountDirectory
        $env:VISIONGUARD_SERVER_URL = $environment.serviceUrl
        $env:VISIONGUARD_SETTINGS_PATH = $settingsPath
        $env:VISIONGUARD_MODELS_DIR = $environment.modelsDirectory
        $env:VISIONGUARD_LOG_DIR = $logDirectory
        $handleFile = Join-Path $evidence 'fixture-window-handles.txt'
        $fixtures = Start-WpfFixtureWindows -ImagePaths @($fixtureImage) -HandleFile $handleFile
        $fixtureWindow = $fixtures.Forms[0]

        # 真实登录一次拿到设备身份，设置按「服务 + 账号 + 设备」作用域写入（与 SettingsStore 同一算法）。
        $env:VISIONGUARD_LOGIN_PASSWORD = $testAccount.password
        $loginOutput = & $accountProbe '--login' $env:VISIONGUARD_SERVER_URL $testAccount.username "Push Chain Publisher"
        if ($LASTEXITCODE -ne 0) { throw 'Windows account login failed.' }
        $identity = ($loginOutput | Out-String) | ConvertFrom-Json
        Remove-Item Env:VISIONGUARD_LOGIN_PASSWORD -ErrorAction SilentlyContinue
        Add-Result -Name 'Windows publisher login' -Status 'PASS' -Note "deviceId=$($identity.deviceId)"
        if ($identity.scope) { $scopeKey = $identity.scope } else {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $scopeKey = (($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes("$($identity.service)|$($identity.accountId)|$($identity.deviceId)")) | ForEach-Object { $_.ToString('x2') }) -join '') }
            finally { $sha.Dispose() }
        }
        $scopeDirectory = Join-Path $chainRoot (Join-Path 'accounts' $scopeKey)
        New-Item -ItemType Directory -Force -Path $scopeDirectory | Out-Null
        $scopedSettingsPath = Join-Path $scopeDirectory 'settings.ini'
        $settings = @(
            'Node.PublishMode=Push'
            # 旧键迁移与一次性备份必须标记为已完成：否则应用加载时按「首次运行」重写来源，
            # 把这份推流配置覆盖成默认屏幕来源（ui-preview.ps1 的 PrepareWindows 同样先标好这两个标记）。
            'Source.LegacyMigrationCompleted=True'
            'Source.KeyMigrationCompleted=True'
            'Source.Indexes=1'
            'Source.1.Initialized=True'
            'Source.1.SourceId=default'
            'Source.1.Name=推流窗口'
            'Source.1.CaptureMode=WindowHandle'
            "Source.1.TargetWindowTitle=$($fixtureWindow.Text)"
            'Source.1.TargetWindowProcessName=powershell'
            'Source.1.Fps=5'
            'Source.1.Threshold=45'
            'Source.1.Cooldown=5'
            'Source.1.Targets=person'
            'Source.1.ModelKey=yolo26m_320'
        )
        # 应用启动时已有会话，实际读取的是 <父目录>/accounts/<scope>/settings.ini；两处写成同一组值，
        # 无论设置读取发生在登录前还是登录后都能拿到这份推流配置。
        [System.IO.File]::WriteAllLines($settingsPath, $settings, (New-Object System.Text.UTF8Encoding($false)))
        [System.IO.File]::WriteAllLines($scopedSettingsPath, $settings, (New-Object System.Text.UTF8Encoding($false)))
        Add-Result -Name 'Push mode settings' -Status 'PASS' -Note $scopedSettingsPath

        $env:VISIONGUARD_MEDIA_DIAGNOSTICS = '1'
        $detectorProcess = Start-Process -FilePath $detectorExe -ArgumentList @('--isolated-environment', $environmentPath) -WorkingDirectory (Split-Path -Parent $detectorExe) -PassThru
        Add-Result -Name 'Windows publisher launch' -Status 'PASS' -Note "pid=$($detectorProcess.Id)"

        # 第二个真实实例（接收端）：控制台绑定后，它会把推流端建成远程镜头来源并开始推理。
        $receiverRoot = Join-Path $chainRoot 'receiver'
        $receiverAccountDirectory = Join-Path $receiverRoot 'account'
        $receiverSettingsPath = Join-Path $receiverRoot 'settings.ini'
        $receiverLogDirectory = Join-Path $receiverRoot 'logs'
        foreach ($directory in @($receiverAccountDirectory, $receiverRoot, $receiverLogDirectory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
        $receiverEnvironmentPath = Join-Path $evidence 'isolated-environment-receiver.json'
        $receiverEnvironment = [ordered]@{
            serviceUrl = "http://127.0.0.1:$WindowsPushChainPort"
            accountDir = $receiverAccountDirectory
            settingsPath = $receiverSettingsPath
            modelsDirectory = $environment.modelsDirectory
            logDirectory = $receiverLogDirectory
        }
        [System.IO.File]::WriteAllText($receiverEnvironmentPath, ($receiverEnvironment | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
        # 接收端不先用探针登录：探针写入的会话与 WPF 登录用的是两套设备身份派生，会导致应用另外登记一台设备，
        # 控制台随后把推流绑到探针那台、应用这台就什么都收不到。这里直接让真实实例自己登录，
        # 由驱动从服务端设备列表里找出它（推流端设备 ID 已知，另一个在线推理设备就是接收端）。
        $receiverSettings = @(
            'Node.PublishMode=Inference'
            'Source.LegacyMigrationCompleted=True'
            'Source.KeyMigrationCompleted=True'
            "Source.1.ModelKey=$modelKey"
        )
        [System.IO.File]::WriteAllLines($receiverSettingsPath, $receiverSettings, (New-Object System.Text.UTF8Encoding($false)))
        # 第二个实例没有已保存会话：用与应用预览同一条内置登录通道（账号经环境变量递入，不进隔离配置），
        # 让它自己登录并登记身份，避免探针与应用各自派生一套设备身份。
        $env:VISIONGUARD_PREVIEW_SIGNIN = (@{ endpoint = $environment.serviceUrl; username = $testAccount.username; password = $testAccount.password } | ConvertTo-Json -Compress)
        try {
            $receiverProcess = Start-Process -FilePath $detectorExe -ArgumentList @('--isolated-environment', $receiverEnvironmentPath) -WorkingDirectory (Split-Path -Parent $detectorExe) -PassThru
        }
        finally { Remove-Item Env:VISIONGUARD_PREVIEW_SIGNIN -ErrorAction SilentlyContinue }
        Add-Result -Name 'Windows receiver launch' -Status 'PASS' -Note "pid=$($receiverProcess.Id)"

        $chainScript = Join-Path $repoRoot 'scripts\windows-push-chain.js'
        $env:VISIONGUARD_CHAIN_PASSWORD = $testAccount.password
        $captureMarker = Join-Path $evidence 'chain-capture-pending.txt'
        $chainArguments = @($chainScript, '--service', $env:VISIONGUARD_SERVER_URL, '--username', $testAccount.username, '--password-env', 'VISIONGUARD_CHAIN_PASSWORD',
            '--evidence', $evidence, '--publisher-device-id', $identity.deviceId, '--fixture-window-title', $fixtureWindow.Text,
            '--capture-delay-seconds', $WindowsPushChainCaptureDelaySeconds.ToString(), '--capture-marker', $captureMarker,
            '--timeout', $WindowsPushChainTimeoutSeconds.ToString())
        $chainLog = Join-Path $evidence 'windows-push-chain-console.txt'
        # 驱动在验证完成后进入保持窗口并写标记；脚本据此抓窗口截图，再等驱动退出。
        $chainJob = Start-Job -ScriptBlock {
            param($arguments)
            $previousPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $output = & node.exe @arguments 2>&1 | ForEach-Object { $_.ToString() }
                [pscustomobject]@{ output = $output; exitCode = $LASTEXITCODE }
            }
            finally { $ErrorActionPreference = $previousPreference }
        } -ArgumentList (, $chainArguments)
        try {
            # 驱动验证完成后进入「保持推流」窗口并写标记文件；这里轮询标记，
            # 在两路连接都活着、界面上是推流中的时刻抓两个实例的主窗口，然后再等驱动退出。
            $captureDeadline = (Get-Date).AddSeconds(900)
            $captured = $false
            while ((Get-Date) -lt $captureDeadline) {
                if (-not $captured -and (Test-Path -LiteralPath $captureMarker)) {
                    foreach ($instance in @(
                            @{ Name = 'publisher'; Process = $detectorProcess },
                            @{ Name = 'receiver'; Process = $receiverProcess })) {
                        $shot = Join-Path $evidence ("window-$($instance.Name).png")
                        $ok = Save-WindowScreenshot -Process $instance.Process -Path $shot
                        Add-Result -Name "Window screenshot $($instance.Name)" -Status $(if ($ok) { 'PASS' } else { 'FAIL' }) -Note $shot
                    }
                    $captured = $true
                }
                if ($chainJob.State -ne 'Running') { break }
                Start-Sleep -Milliseconds 500
            }
            if (-not $captured) {
                Add-Result -Name 'Window screenshots' -Status 'FAIL' -Note 'the driver never signalled its capture window'
            }
            Wait-Job -Job $chainJob -Timeout 120 | Out-Null
            $chainResult = Receive-Job -Job $chainJob
            $chainExit = $chainResult.exitCode
            $chainOutput = @($chainResult.output)
            $chainOutput | ForEach-Object { Write-Host $_ }
            $chainOutput | Set-Content -Encoding UTF8 $chainLog
        }
        finally {
            Remove-Job -Job $chainJob -Force -ErrorAction SilentlyContinue
            Remove-Item Env:VISIONGUARD_CHAIN_PASSWORD -ErrorAction SilentlyContinue
        }

        $reportPath = Join-Path $evidence 'windows-push-chain-report.json'
        if (-not (Test-Path -LiteralPath $reportPath)) { throw "The chain report is missing: $chainLog" }
        $chainReport = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $checks = [ordered]@{
            receiverRegistered = 'a second inference device registered as the receiver'
            publisherCapabilities = 'publisher declares video-publish / stream-control and no camera-config'
            publisherUnboundByDefault = 'inference publisher is not auto-bound (no self-loop)'
            publisherStreamBound = 'console bound the publisher to the receiver'
            selfBindRejected = 'binding a node to itself is rejected'
            pushStarted = 'remote start-stream command started the push branch'
            pushStateReported = 'the pushing node self-reports its push state to the console'
            receiverStreamBound = 'the relay keeps the binding to the real receiver'
            frameStreamId = 'relayed frame carries the bound streamId'
            frameSessionId = 'relayed frame carries the publisher media session'
            frameSizeWithinContract = 'frame size stays within 1280x720'
            frameRotationNormalized = 'rotation is normalized to 0 (direction from width/height)'
            frameCapturedAtMonotonic = 'capturedAt is a positive safe integer'
            frameSequenceMonotonic = 'sequence numbers strictly increase'
            frameIdentityStable = 'streamId/sessionId stay stable across frames'
            relayStreaming = 'the relay reports this binding as streaming'
            relayStatsForwarded = 'relay forwarded frame statistics advance'
            receiverInferring = 'the real receiver infers on the pushed stream'
            noLocalAlertFromPublisher = 'the pushing node produced no local alert'
        }
        foreach ($check in $checks.Keys) {
            $value = $chainReport.checks.$check
            $status = if ($value -eq $true) { 'PASS' } else { 'FAIL' }
            Add-Result -Name "Windows push chain: $check" -Status $status -Note $checks[$check]
        }
        if ($chainExit -ne 0) {
            $reasons = @($chainReport.failures | ForEach-Object { "$($_.name): $(($_.detail | Out-String).Trim())" }) -join '; '
            throw "Windows push chain failed: $reasons (see $chainLog)"
        }
        Add-Result -Name 'Windows push chain' -Status 'PASS' -Note "frames validated on the receiver side" -Evidence $reportPath
    }
    finally {
        Remove-Item Env:VISIONGUARD_CHAIN_PASSWORD -ErrorAction SilentlyContinue
        foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Detector.Windows.exe' OR Name = 'VisionGuard.Resident.Windows.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -and ($_.CommandLine.IndexOf($accountDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($receiverAccountDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($environmentPath, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine.IndexOf($receiverEnvironmentPath, [StringComparison]::OrdinalIgnoreCase) -ge 0) })) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($detectorProcess -and -not $detectorProcess.HasExited) { Stop-Process -Id $detectorProcess.Id -Force -ErrorAction SilentlyContinue }
        if ($receiverProcess -and -not $receiverProcess.HasExited) { Stop-Process -Id $receiverProcess.Id -Force -ErrorAction SilentlyContinue }
        if ($fixtures) {
            foreach ($form in $fixtures.Forms) { try { $form.Close(); $form.Dispose() } catch { } }
            foreach ($image in $fixtures.Images) { try { $image.Dispose() } catch { } }
        }
        foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name]) }
        if ($server -and -not $server.HasExited) {
            foreach ($connection in @(Get-NetTCPConnection -LocalPort $WindowsPushChainPort -State Listen -ErrorAction SilentlyContinue)) {
                $listener = Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)" -ErrorAction SilentlyContinue
                if ($listener -and $listener.Name -eq 'node.exe' -and $listener.ParentProcessId -eq $server.Id) { Stop-Process -Id $listener.ProcessId -Force -ErrorAction SilentlyContinue }
            }
            try { $server.Kill() } catch { }
        }
    }
}

function Run-WpfPersonDetection {
    $fixtureDirectory = Resolve-RepoPath $WpfFixtureDirectory
    $modelPath = Resolve-RepoPath $WpfModelPath
    $reportPath = Resolve-RepoPath $WpfReportPath
    $smokeProject = Join-Path $repoRoot 'detector\windows-wpf-smoke\VisionGuard.WpfSmoke.csproj'
    $log = Join-Path $artifactRoot 'wpf-person-detection.txt'
    $errorLog = Join-Path $artifactRoot 'wpf-person-detection-error.txt'
    $runRoot = Join-Path $repoRoot ('.local\wpf-window-smoke-' + [Guid]::NewGuid().ToString('N'))
    $handleFile = Join-Path $runRoot 'window-handles.txt'
    if (-not (Test-Path -LiteralPath $smokeProject)) { throw "WPF smoke project missing: $smokeProject" }
    if (-not (Test-Path -LiteralPath $modelPath)) { throw "ONNX model missing: $modelPath" }
    if (-not (Test-Path -LiteralPath $fixtureDirectory)) { throw "Fixture directory missing: $fixtureDirectory" }

    $images = @(Get-ChildItem -LiteralPath $fixtureDirectory -File |
        Where-Object { $_.Extension -in '.jpg', '.jpeg', '.png', '.bmp' } |
        Sort-Object Name)
    # 夹具人像图数量不得少于来源数量：直接报出实际张数，不自动降低来源数量。
    if ($images.Count -lt $WpfSourceCount) {
        throw "Fixture directory $fixtureDirectory holds $($images.Count) person images, fewer than the requested $WpfSourceCount sources."
    }
    $images = @($images | Select-Object -First $WpfSourceCount)

    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($reportPath))) | Out-Null

    $fixtures = $null
    $smoke = $null
    try {
        $fixtures = Start-WpfFixtureWindows -ImagePaths @($images | ForEach-Object { $_.FullName }) -HandleFile $handleFile

        $arguments = @('run', '--project', $smokeProject, '-c', 'Release', '--', $handleFile, $modelPath, $reportPath, $WpfConfidenceThreshold.ToString([System.Globalization.CultureInfo]::InvariantCulture))
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = 'dotnet'
        $startInfo.WorkingDirectory = $repoRoot
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.Arguments = (@($arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' ')
        $smoke = [System.Diagnostics.Process]::Start($startInfo)

        # 日志按子进程原始字节落盘，避免控制台代码页与 UTF-8 之间的二次转码。
        $stdoutStream = [System.IO.File]::Create($log)
        $stderrStream = [System.IO.File]::Create($errorLog)
        $timedOut = $false
        try {
            $copyStdout = $smoke.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
            $copyStderr = $smoke.StandardError.BaseStream.CopyToAsync($stderrStream)
            # 夹具窗口必须持续处理消息：smoke 会移动、最小化并关闭其中一路。
            $deadline = (Get-Date).AddMinutes(15)
            while (-not $smoke.HasExited) {
                [System.Windows.Forms.Application]::DoEvents()
                if ((Get-Date) -gt $deadline) {
                    $timedOut = $true
                    $smoke.Kill()
                    break
                }
                Start-Sleep -Milliseconds 100
            }
            $smoke.WaitForExit()
            [void]$copyStdout.Wait(30000)
            [void]$copyStderr.Wait(30000)
        }
        finally {
            $stdoutStream.Dispose()
            $stderrStream.Dispose()
        }
        if ($timedOut) { throw "WPF person-detection smoke did not finish within 15 minutes. Log: $log" }
        if ($smoke.ExitCode -ne 0) {
            throw "WPF $WpfSourceCount-window person-detection smoke exited with code $($smoke.ExitCode). Report: $reportPath; log: $log"
        }
        if (-not (Test-Path -LiteralPath $reportPath)) { throw "WPF person-detection report missing: $reportPath" }

        $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $missing = @($report.sources | Where-Object { $_.personHitFrames -lt 1 })
        if (-not $report.passed -or $missing.Count -gt 0) {
            $missingIds = @($missing | ForEach-Object { $_.SourceId }) -join ', '
            throw "WPF $WpfSourceCount-window person-detection smoke did not pass (sources without a person hit: $missingIds). Report: $reportPath"
        }
        Add-Result -Name "WPF $WpfSourceCount-window person detection" -Status 'PASS' -Note "$WpfSourceCount visible fixture windows" -Evidence $reportPath
    }
    finally {
        if ($smoke -and -not $smoke.HasExited) {
            $smoke.Kill()
            $smoke.WaitForExit()
        }
        if ($fixtures) {
            foreach ($form in $fixtures.Forms) {
                if (-not $form.IsDisposed) { $form.Close(); $form.Dispose() }
            }
            foreach ($image in $fixtures.Images) { $image.Dispose() }
            [System.Windows.Forms.Application]::DoEvents()
        }
        if (Test-Path -LiteralPath $runRoot) { Remove-Item -LiteralPath $runRoot -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

try {
    switch ($Mode) {
        'Discover' { Run-Discover }
        'ServerBuild' { Run-ServerBuild }
        'ServerSmoke' {
            Add-Result -Name 'ServerSmoke compatibility alias' -Status 'PASS' -Note 'Running compile/artifact smoke only.'
            Run-ServerBuild
        }
        'AndroidDetectorSmoke' {
            Run-AndroidAppSmoke `
                -Name 'android-detector' `
                -ProjectDirectory 'detector\android' `
                -PackageName 'com.xgwnje.visionguard.detector' `
                -RuntimePermissions @('android.permission.CAMERA', 'android.permission.POST_NOTIFICATIONS')
        }
        'WpfPersonDetection' { Run-WpfPersonDetection }
        'WpfParserContract' { Run-WpfParserContract }
        'ResidentLaunch' { Run-ResidentLaunch }
        'ModelDownload' { Run-ModelDownload }
        'SourceAutoSave' { Run-SourceAutoSave }
        'CardLayoutPlan' { Run-CardLayoutPlan }
        'PerformanceWatchdog' { Run-PerformanceWatchdog }
        'WebCameraChain' { Run-WebCameraChain }
        'WindowsPushChain' { Run-WindowsPushChain }
    }
}
catch {
    $exitCode = 1
    Add-Result -Name 'Run' -Status 'FAIL' -Note $_.Exception.Message
}
finally {
    if ($launchedEmulatorSerial) {
        try {
            $tools = Get-AndroidTools
            & $tools.Adb -s $launchedEmulatorSerial emu kill *> (Join-Path $artifactRoot 'emulator-stop.txt')
            Add-Result -Name 'Stop launched emulator' -Status 'PASS' -Note $launchedEmulatorSerial
        }
        catch {
            Add-Result -Name 'Stop launched emulator' -Status 'FAIL' -Note $_.Exception.Message
            $exitCode = 1
        }
    }
    elseif ($launchedEmulatorProcess -and -not $launchedEmulatorProcess.HasExited) {
        try {
            Stop-Process -Id $launchedEmulatorProcess.Id
            Add-Result -Name 'Stop launched emulator process' -Status 'PASS' -Note "pid=$($launchedEmulatorProcess.Id)"
        }
        catch {
            Add-Result -Name 'Stop launched emulator process' -Status 'FAIL' -Note $_.Exception.Message
            $exitCode = 1
        }
    }
    Save-Summary
    Write-Host "Artifacts: $artifactRoot"
}

exit $exitCode
