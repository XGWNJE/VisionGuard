param(
    [ValidateSet('Discover', 'ServerBuild', 'ServerSmoke', 'AndroidDetectorSmoke', 'AndroidReceiverSmoke', 'WpfPersonDetection', 'WpfParserContract', 'ResidentLaunch', 'ModelDownload', 'SourceAutoSave', 'CardLayoutPlan')]
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
    [switch]$ModelDownloadLegacyProfile
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
    & $tools.Adb -s $selected.Serial install -r $apk *> $installLog
    if ($LASTEXITCODE -ne 0) { throw "$Name adb install failed." }
    Add-Result -Name "$Name install" -Status 'PASS' -Evidence $installLog

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
    # 「检测端拉起同目录驻留 → 驻留认证 → 服务端 device-list 出现 resident=running」这一段。
    # 为什么需要它：驻留是独立进程，检测端本地只能证明互斥体出现了，无法证明它真的连上了服务端；
    # 而 Win7 上的历史事实是「驻留没起来，界面上与接收端都毫无提示」，必须从服务端视角取证。
    # 两档产物都要跑：legacy 档是 Win7 包，是这条链路真正要服务的环境。
    $apiKey = if ($env:VISIONGUARD_API_KEY) { $env:VISIONGUARD_API_KEY } else { 'vg-e2e-resident-launch' }
    $serverScript = Join-Path $repoRoot 'scripts\start-isolated-test-server.ps1'
    $assertScript = Join-Path $repoRoot 'scripts\assert-resident-visible.js'
    if (-not (Test-Path -LiteralPath $serverScript)) { throw "隔离 Server 脚本不存在：$serverScript" }
    if (-not (Test-Path -LiteralPath $assertScript)) { throw "驻留可见性断言脚本不存在：$assertScript" }

    $serverLog = Join-Path $artifactRoot 'isolated-server.log'
    $serverErrorLog = Join-Path $artifactRoot 'isolated-server-error.log'
    $server = $null
    $residentProcesses = @()
    # 端口必须空闲：残留的隔离 Server（例如上次手工验证留下的 node 进程）会先占住端口，
    # 新实例只会在 stderr 里写 EADDRINUSE，而探针会去连旧实例，结论就被污染。
    $occupied = @(Get-NetTCPConnection -LocalPort $ResidentPort -State Listen -ErrorAction SilentlyContinue)
    if ($occupied.Count -gt 0) {
        $owners = @($occupied | ForEach-Object { $_.OwningProcess } | Select-Object -Unique) -join ', '
        throw "端口 $ResidentPort 已被占用（PID: $owners）。请先结束该监听进程再运行本模式。"
    }
    try {
        $previousApiKey = $env:VISIONGUARD_API_KEY
        $env:VISIONGUARD_API_KEY = $apiKey
        $server = Start-Process -FilePath 'powershell' `
            -ArgumentList @('-ExecutionPolicy', 'Bypass', '-File', $serverScript, '-Port', $ResidentPort.ToString(), '-Channel', $ResidentChannel) `
            -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -WindowStyle Hidden -PassThru

        $ready = $false
        $deadline = (Get-Date).AddSeconds(60)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 500
            try {
                $health = Invoke-WebRequest -Uri "http://127.0.0.1:$ResidentPort/health" -UseBasicParsing -TimeoutSec 2
                if ($health.StatusCode -eq 200) { $ready = $true; break }
            }
            catch { }
            if ($server.HasExited) { throw "隔离 Server 提前退出（退出码 $($server.ExitCode)），日志：$serverLog" }
        }
        if (-not $ready) { throw "隔离 Server 未在 60 秒内就绪，日志：$serverLog" }

        $profiles = @('modern', 'legacy')
        $paths = @{ modern = 'detector\windows-wpf\bin\x64\modern\VisionGuard.exe'; legacy = 'detector\windows-wpf\bin\x64\legacy\VisionGuard.exe' }
        foreach ($profile in $profiles) {
            $detectorExe = Resolve-RepoPath $paths[$profile]
            if (-not (Test-Path -LiteralPath $detectorExe)) { throw "$profile 档检测端不存在：$detectorExe（先运行 visionguard-build -Target Windows）" }
            $residentExe = Join-Path (Split-Path -Parent $detectorExe) 'VisionGuard.Resident.exe'
            if (-not (Test-Path -LiteralPath $residentExe)) { throw "$profile 档产物缺少配套驻留程序：$residentExe" }

            $reportPath = Join-Path $artifactRoot "resident-launch-$profile.json"
            $stdoutLog = Join-Path $artifactRoot "resident-launch-$profile.txt"
            $stderrLog = Join-Path $artifactRoot "resident-launch-$profile-error.txt"
            $settingsPath = Join-Path $artifactRoot "resident-launch-$profile-settings.ini"

            # 每次用独立 settings 文件，避免干扰本机真实配置。
            # 必须预置测试专属 DeviceId：探针驻留与探针检测端共用同一设备身份，
            # 否则服务端会把驻留当成另一台设备，而清理时又可能误杀用户真实运行的驻留。
            # 探针启动时会读取（不覆盖）这个文件，因此这里写入的 DeviceId 就是两端共同身份。
            [System.IO.File]::WriteAllText($settingsPath,
                "# VisionGuard 用户设置（E2E 隔离文件）`r`nDeviceId=$('e2e-resident-' + [Guid]::NewGuid().ToString('N').Substring(0, 12))`r`n",
                (New-Object System.Text.UTF8Encoding($false)))

            $env:VISIONGUARD_CHANNEL = $ResidentChannel
            $env:VISIONGUARD_SERVER_URL = "http://127.0.0.1:$ResidentPort"
            $env:VISIONGUARD_SETTINGS_PATH = $settingsPath

            $probe = Start-Process -FilePath $detectorExe `
                -ArgumentList @('--resident-launch', $reportPath, '6000') `
                -WorkingDirectory (Split-Path -Parent $detectorExe) -NoNewWindow -PassThru `
                -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
            if (-not $probe.WaitForExit(60000)) { try { $probe.Kill() } catch { }; throw "$profile 档拉起探针超过 60 秒未结束" }
            # 探针是 WinExe：AttachConsole/FreeConsole 之后 Start-Process 的 ExitCode 可能为空，
            # 因此以 JSON 报告作为权威依据，退出码只作为附加诊断信息。
            $probe.Refresh()
            $probeExit = $probe.ExitCode
            if (-not (Test-Path -LiteralPath $reportPath)) {
                throw "$profile 档拉起探针未写出报告（退出码 $probeExit）：$reportPath"
            }
            $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if (-not $report.isRunning -or -not $report.handshakeSucceeded) {
                throw "$profile 档驻留未进入运行状态（退出码 $probeExit）：$($report.failureReason)"
            }

            # 以隔离 settings 里实际生效的设备身份去服务端核对（探针与驻留共用该身份）。
            $deviceId = ((Get-Content -LiteralPath $settingsPath -Encoding UTF8 |
                Select-String -Pattern '^DeviceId=(.+)$').Matches[0].Groups[1].Value).Trim()
            if (-not $deviceId) { throw "$profile 档隔离 settings 中没有 DeviceId：$settingsPath" }

            $visibleOutput = & node $assertScript "ws://127.0.0.1:$ResidentPort" $ResidentChannel $apiKey $deviceId 2>&1
            $visibleExit = $LASTEXITCODE
            $visiblePath = Join-Path $artifactRoot "resident-visibility-$profile.json"
            ($visibleOutput | Out-String).Trim() | Set-Content -Encoding UTF8 $visiblePath
            if ($visibleExit -ne 0) { throw "$profile 档驻留在服务端不可见：$($visibleOutput | Out-String)" }

            Add-Result -Name "Resident launch ($profile)" -Status 'PASS' `
                -Note "detector launched resident, server reports components.resident=running" -Evidence $visiblePath

            # 收尾：只结束由本轮隔离配置拉起的驻留（按驻留自身配置路径匹配），不碰用户真实运行的驻留。
            $residentConfigPath = Join-Path $env:LOCALAPPDATA 'VisionGuard\resident-config.json'
            $ours = @()
            try {
                $ours = @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Resident.exe'" -ErrorAction SilentlyContinue |
                    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$residentConfigPath*" })
            }
            catch { }
            foreach ($process in $ours) { try { Stop-Process -Id $process.ProcessId -Force } catch { } }
        }

        $env:VISIONGUARD_API_KEY = $previousApiKey
    }
    finally {
        # 本模式只清理自己拉起的驻留：按驻留自身配置路径匹配，绝不按进程名清空，
        # 以免杀掉用户真实运行中的驻留（那会让接收端的「打开/关闭检测端」静默失效）。
        try {
            $residentConfigPath = Join-Path $env:LOCALAPPDATA 'VisionGuard\resident-config.json'
            foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'VisionGuard.Resident.exe'" -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -and $_.CommandLine -like "*$residentConfigPath*" })) {
                try { Stop-Process -Id $process.ProcessId -Force } catch { }
            }
        }
        catch { }
        if ($server -and -not $server.HasExited) {
            # npm/node 子进程不随 powershell 一起退出，按端口结束监听进程。
            try {
                foreach ($connection in @(Get-NetTCPConnection -LocalPort $ResidentPort -State Listen -ErrorAction SilentlyContinue)) {
                    $owner = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
                    if ($owner) { $owner.Kill() }
                }
            }
            catch { }
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
    # 卡片区布局契约：驱动 CardLayoutPlanner（纯计算，不开窗口、不建推理会话），
    # 断言「卡片宽高比落在 1:1.2~1.2:1」「1/2/4 张的网格都恰好排得下且不裁剪」「最小窗口下 1:1 画面短边达标」
    # 「宽扁/窄高容器不出现畸形卡片」「同输入结果确定」「零可用空间返回无效布局」。
    # 它证明布局数学，不证明真实界面的视觉与拖拽手感——那部分必须 owner 目检。
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
                -PackageName 'com.xgwnje.visionguard' `
                -RuntimePermissions @('android.permission.CAMERA', 'android.permission.POST_NOTIFICATIONS')
        }
        'AndroidReceiverSmoke' {
            Run-AndroidAppSmoke `
                -Name 'android-receiver' `
                -ProjectDirectory 'receiver\android' `
                -PackageName 'com.xgwnje.visionguard_android' `
                -RuntimePermissions @('android.permission.POST_NOTIFICATIONS')
        }
        'WpfPersonDetection' { Run-WpfPersonDetection }
        'WpfParserContract' { Run-WpfParserContract }
        'ResidentLaunch' { Run-ResidentLaunch }
        'ModelDownload' { Run-ModelDownload }
        'SourceAutoSave' { Run-SourceAutoSave }
        'CardLayoutPlan' { Run-CardLayoutPlan }
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
