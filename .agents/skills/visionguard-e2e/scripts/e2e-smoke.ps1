param(
    [ValidateSet('Discover', 'ServerBuild', 'ServerSmoke', 'AndroidDetectorSmoke', 'AndroidReceiverSmoke', 'WpfPersonDetection')]
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
    [ValidateRange(0.0,1.0)][float]$WpfConfidenceThreshold = 0.25
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repoRoot "artifacts\e2e\$timestamp"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

$effectiveBuildType = if ($Mode -eq 'WpfPersonDetection') { 'Release' } else { $BuildType }
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
