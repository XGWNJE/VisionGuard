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
    [int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $repoRoot "artifacts\e2e\$timestamp"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

$summary = [ordered]@{
    mode = $Mode
    buildType = $BuildType
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

function Get-AndroidSdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
        Select-Object -Unique
    if ($candidates.Count -eq 0) {
        throw 'Android SDK not found through ANDROID_HOME, ANDROID_SDK_ROOT, or %LOCALAPPDATA%\Android\Sdk.'
    }
    return $candidates[0]
}

function Get-AndroidTools {
    $sdk = Get-AndroidSdk
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    $emulator = Join-Path $sdk 'emulator\emulator.exe'
    if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found: $adb" }
    if (-not (Test-Path -LiteralPath $emulator)) { throw "emulator not found: $emulator" }

    $javaCandidates = @($env:JAVA_HOME, 'C:\Android\Android Studio\jbr', 'C:\Program Files\Android\Android Studio\jbr') |
        Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } |
        Select-Object -Unique

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
        [string]$PackageName
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
    }

    & $tools.Adb -s $selected.Serial logcat -c | Out-Null
    & $tools.Adb -s $selected.Serial shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 *> (Join-Path $artifactRoot "$Name-launch.txt")
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
    & $tools.Adb -s $selected.Serial shell uiautomator dump $remoteXml *> (Join-Path $artifactRoot "$Name-uiautomator.txt")
    if ($LASTEXITCODE -eq 0) {
        & $tools.Adb -s $selected.Serial pull $remoteXml (Join-Path $artifactRoot "$Name-window.xml") *> $null
    }
    & $tools.Adb -s $selected.Serial shell screencap -p $remoteScreen *> $null
    if ($LASTEXITCODE -eq 0) {
        & $tools.Adb -s $selected.Serial pull $remoteScreen (Join-Path $artifactRoot "$Name-screen.png") *> $null
    }

    $logcat = Get-Content -LiteralPath $logcatPath -Raw
    if ($logcat -match 'FATAL EXCEPTION|Process: .*visionguard') {
        throw "$Name crash marker found in logcat."
    }
    Add-Result -Name "$Name runtime" -Status 'PASS' -Evidence $artifactRoot
}

function Run-WpfPersonDetection {
    $script = Join-Path $repoRoot 'scripts\test-wpf-person-detection.ps1'
    $report = Join-Path $artifactRoot 'wpf-person-detection.json'
    $log = Join-Path $artifactRoot 'wpf-person-detection.txt'
    if (-not (Test-Path -LiteralPath $script)) { throw "WPF person test script missing: $script" }
    Invoke-NativeLogged -FilePath 'powershell' -Arguments @('-ExecutionPolicy', 'Bypass', '-File', $script, '-PrepareFixtures', '-ReportPath', $report) -WorkingDirectory $repoRoot -LogPath $log
    Add-Result -Name 'WPF three-source person detection' -Status 'PASS' -Evidence $report
}

try {
    switch ($Mode) {
        'Discover' { Run-Discover }
        'ServerBuild' { Run-ServerBuild }
        'ServerSmoke' {
            Add-Result -Name 'ServerSmoke compatibility alias' -Status 'PASS' -Note 'Running compile/artifact smoke only.'
            Run-ServerBuild
        }
        'AndroidDetectorSmoke' { Run-AndroidAppSmoke -Name 'android-detector' -ProjectDirectory 'detector\android' -PackageName 'com.xgwnje.visionguard' }
        'AndroidReceiverSmoke' { Run-AndroidAppSmoke -Name 'android-receiver' -ProjectDirectory 'receiver\android' -PackageName 'com.xgwnje.visionguard_android' }
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
