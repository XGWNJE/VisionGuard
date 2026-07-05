param(
    [ValidateSet("Discover", "ServerSmoke", "AndroidReceiverSmoke")]
    [string]$Mode = "Discover",

    [ValidateSet("Auto", "Physical", "Emulator", "None")]
    [string]$Device = "Auto",

    [string]$DeviceSerial = "",
    [string]$Avd = "VisionGuard_API36",
    [switch]$ClearAppData,
    [switch]$NoLaunchEmulator,
    [int]$BootTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactRoot = Join-Path $repoRoot "artifacts\e2e\$timestamp"
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null

$summary = [ordered]@{
    mode = $Mode
    startedAt = (Get-Date).ToString("o")
    artifactRoot = $artifactRoot
    selectedDevice = $null
    selectedAvd = $null
    results = @()
    skips = @()
}

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Note = "", [string]$Evidence = "")
    $script:summary.results += [ordered]@{
        name = $Name
        status = $Status
        note = $Note
        evidence = $Evidence
    }
    Write-Host "[$Status] $Name $Note"
}

function Add-Skip {
    param([string]$Name, [string]$Reason)
    $script:summary.skips += [ordered]@{ name = $Name; reason = $Reason }
    Add-Result -Name $Name -Status "SKIP" -Note $Reason
}

function Save-Summary {
    $summary.finishedAt = (Get-Date).ToString("o")
    $summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $artifactRoot "summary.json")
}

function Get-AndroidSdk {
    $candidates = @(@(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ -and (Test-Path $_) })
    if (-not $candidates -or $candidates.Count -eq 0) {
        throw "Android SDK not found. Expected under %LOCALAPPDATA%\Android\Sdk or ANDROID_HOME."
    }
    return $candidates[0]
}

function Get-ToolPaths {
    $sdk = Get-AndroidSdk
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    $emulator = Join-Path $sdk "emulator\emulator.exe"
    $javaHomeCandidates = @(@(
        $env:JAVA_HOME,
        "C:\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr"
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) })

    [pscustomobject]@{
        AndroidSdk = $sdk
        Adb = $adb
        Emulator = $emulator
        JavaHome = if ($javaHomeCandidates.Count -gt 0) { $javaHomeCandidates[0] } else { "" }
    }
}

function Invoke-Capture {
    param([string]$Name, [scriptblock]$Script)
    $out = Join-Path $artifactRoot $Name
    try {
        & $Script *> $out
        Add-Result -Name $Name -Status "PASS" -Evidence $out
    }
    catch {
        Add-Result -Name $Name -Status "FAIL" -Note $_.Exception.Message -Evidence $out
    }
}

function Get-AdbDevices {
    param([string]$Adb)
    $raw = & $Adb devices -l
    $raw | Set-Content -Encoding UTF8 (Join-Path $artifactRoot "adb-devices.txt")
    $devices = @()
    foreach ($line in $raw) {
        if ($line -match "^(\S+)\s+(\S+)(.*)$" -and $line -notmatch "^List") {
            $devices += [pscustomobject]@{
                Serial = $Matches[1]
                State = $Matches[2]
                Detail = $Matches[3].Trim()
                IsEmulator = $Matches[1] -like "emulator-*"
            }
        }
    }
    return $devices
}

function Wait-ForBoot {
    param([string]$Adb, [string]$Serial, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $booted = & $Adb -s $Serial shell getprop sys.boot_completed 2>$null
            if (($booted -join "").Trim() -eq "1") { return $true }
        }
        catch { }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Select-Device {
    param([string]$Adb, [string]$Emulator)

    $devices = Get-AdbDevices -Adb $Adb
    if ($DeviceSerial) {
        $match = $devices | Where-Object { $_.Serial -eq $DeviceSerial } | Select-Object -First 1
        if (-not $match) { throw "Requested device serial not found: $DeviceSerial" }
        if ($match.State -ne "device") { throw "Requested device is not ready: $DeviceSerial state=$($match.State)" }
        return $match
    }

    $physical = @($devices | Where-Object { $_.State -eq "device" -and -not $_.IsEmulator })
    $unauthorized = @($devices | Where-Object { $_.State -eq "unauthorized" })
    $offline = @($devices | Where-Object { $_.State -eq "offline" })

    if ($unauthorized.Count -gt 0) {
        Add-Skip -Name "Android physical device" -Reason "Device unauthorized; approve USB debugging on the phone."
    }
    if ($offline.Count -gt 0) {
        & $Adb reconnect | Out-File -Encoding UTF8 (Join-Path $artifactRoot "adb-reconnect.txt")
        $devices = Get-AdbDevices -Adb $Adb
        $physical = @($devices | Where-Object { $_.State -eq "device" -and -not $_.IsEmulator })
    }

    if ($Device -in @("Auto", "Physical")) {
        if ($physical.Count -eq 1) { return $physical[0] }
        if ($physical.Count -gt 1) {
            throw "Multiple physical Android devices found. Re-run with -DeviceSerial."
        }
        if ($Device -eq "Physical") {
            throw "No ready physical Android device found."
        }
    }

    if ($Device -eq "None") { return $null }

    $emulatorReady = @($devices | Where-Object { $_.State -eq "device" -and $_.IsEmulator }) | Select-Object -First 1
    if ($emulatorReady) { return $emulatorReady }

    if ($NoLaunchEmulator) {
        Add-Skip -Name "Android emulator" -Reason "No emulator online and -NoLaunchEmulator was set."
        return $null
    }

    $avds = & $Emulator -list-avds
    $avds | Set-Content -Encoding UTF8 (Join-Path $artifactRoot "avds.txt")
    $selectedAvd = if ($avds -contains $Avd) { $Avd } elseif ($avds -contains "VisionGuard_API36") { "VisionGuard_API36" } elseif ($avds -contains "Pixel_3a_XL") { "Pixel_3a_XL" } else { "" }
    if (-not $selectedAvd) {
        Add-Skip -Name "Android emulator" -Reason "No known VisionGuard AVD available."
        return $null
    }

    $script:summary.selectedAvd = $selectedAvd
    $args = @("-avd", $selectedAvd, "-no-snapshot-save")
    Start-Process -FilePath $Emulator -ArgumentList $args -WindowStyle Hidden | Out-Null
    Add-Result -Name "Launch emulator" -Status "PASS" -Note $selectedAvd
    & $Adb wait-for-device
    Start-Sleep -Seconds 3
    $devices = Get-AdbDevices -Adb $Adb
    $emu = @($devices | Where-Object { $_.State -eq "device" -and $_.IsEmulator }) | Select-Object -First 1
    if (-not $emu) { throw "Emulator launched but no adb device appeared." }
    if (-not (Wait-ForBoot -Adb $Adb -Serial $emu.Serial -TimeoutSeconds $BootTimeoutSeconds)) {
        throw "Emulator did not finish booting within $BootTimeoutSeconds seconds."
    }
    return $emu
}

function Set-JavaForGradle {
    param([string]$JavaHome)
    if (-not $JavaHome) { throw "Java home not found for Gradle." }
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

function Run-Discover {
    $tools = Get-ToolPaths
    $adb = $tools.Adb
    $emulator = $tools.Emulator
    $tools | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $artifactRoot "tools.json")
    Add-Result -Name "Tool discovery" -Status "PASS" -Evidence (Join-Path $artifactRoot "tools.json")

    Invoke-Capture -Name "adb-version.txt" -Script { & $adb version }
    Invoke-Capture -Name "adb-devices.txt" -Script { & $adb devices -l }
    Invoke-Capture -Name "emulator-version.txt" -Script { & $emulator -version }
    Invoke-Capture -Name "emulator-avds.txt" -Script { & $emulator -list-avds }
    Invoke-Capture -Name "wsl-status.txt" -Script { wsl.exe -l -v; wsl.exe --status }
    Invoke-Capture -Name "vswhere.json" -Script {
        $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
        if (Test-Path $vswhere) { & $vswhere -products * -format json } else { "vswhere not found" }
    }
}

function Run-ServerSmoke {
    Push-Location $repoRoot
    try {
        npm --prefix server run build *> (Join-Path $artifactRoot "server-build.txt")
        if ($LASTEXITCODE -ne 0) { throw "Server build failed." }
        Add-Result -Name "Server build" -Status "PASS" -Evidence (Join-Path $artifactRoot "server-build.txt")
        $artifact = Join-Path $repoRoot "server\dist\index.js"
        if (Test-Path $artifact) {
            Add-Result -Name "Server artifact" -Status "PASS" -Evidence $artifact
        } else {
            throw "Missing server/dist/index.js"
        }
    }
    finally {
        Pop-Location
    }
}

function Run-AndroidReceiverSmoke {
    $tools = Get-ToolPaths
    Set-JavaForGradle -JavaHome $tools.JavaHome
    $apk = Join-Path $repoRoot "receiver\android\app\build\outputs\apk\release\app-release.apk"

    Push-Location (Join-Path $repoRoot "receiver\android")
    try {
        .\gradlew.bat assembleRelease *> (Join-Path $artifactRoot "receiver-build.txt")
        if ($LASTEXITCODE -ne 0) { throw "Android receiver build failed." }
        Add-Result -Name "Android receiver build" -Status "PASS" -Evidence (Join-Path $artifactRoot "receiver-build.txt")
    }
    finally {
        Pop-Location
    }

    if (-not (Test-Path $apk)) { throw "Receiver APK missing: $apk" }

    $selected = Select-Device -Adb $tools.Adb -Emulator $tools.Emulator
    if (-not $selected) {
        Add-Skip -Name "Android receiver runtime" -Reason "No usable Android device or emulator."
        return
    }
    $summary.selectedDevice = $selected.Serial

    & $tools.Adb -s $selected.Serial install -r $apk *> (Join-Path $artifactRoot "adb-install.txt")
    if ($LASTEXITCODE -ne 0) { throw "adb install failed." }
    Add-Result -Name "Install receiver APK" -Status "PASS" -Evidence (Join-Path $artifactRoot "adb-install.txt")

    if ($ClearAppData) {
        & $tools.Adb -s $selected.Serial shell pm clear com.xgwnje.visionguard_android *> (Join-Path $artifactRoot "pm-clear.txt")
        Add-Result -Name "Clear app data" -Status "PASS" -Evidence (Join-Path $artifactRoot "pm-clear.txt")
    }

    & $tools.Adb -s $selected.Serial logcat -c | Out-Null
    & $tools.Adb -s $selected.Serial shell monkey -p com.xgwnje.visionguard_android -c android.intent.category.LAUNCHER 1 *> (Join-Path $artifactRoot "app-start.txt")
    Start-Sleep -Seconds 8
    & $tools.Adb -s $selected.Serial logcat -d -v time *> (Join-Path $artifactRoot "logcat.txt")
    & $tools.Adb -s $selected.Serial shell dumpsys activity activities *> (Join-Path $artifactRoot "dumpsys-activity.txt")
    & $tools.Adb -s $selected.Serial shell uiautomator dump /sdcard/vg-window.xml *> (Join-Path $artifactRoot "uiautomator-dump.txt")
    & $tools.Adb -s $selected.Serial pull /sdcard/vg-window.xml (Join-Path $artifactRoot "window.xml") *> (Join-Path $artifactRoot "uiautomator-pull.txt")
    & $tools.Adb -s $selected.Serial exec-out screencap -p > (Join-Path $artifactRoot "screen.png")

    $logcat = Get-Content (Join-Path $artifactRoot "logcat.txt") -Raw
    if ($logcat -match "FATAL EXCEPTION|AndroidRuntime") {
        Add-Result -Name "Runtime crash scan" -Status "FAIL" -Note "AndroidRuntime crash found." -Evidence (Join-Path $artifactRoot "logcat.txt")
    } else {
        Add-Result -Name "Runtime crash scan" -Status "PASS" -Evidence (Join-Path $artifactRoot "logcat.txt")
    }
    Add-Result -Name "Runtime evidence capture" -Status "PASS" -Evidence $artifactRoot
}

try {
    Run-Discover
    if ($Mode -eq "ServerSmoke") {
        Run-ServerSmoke
    } elseif ($Mode -eq "AndroidReceiverSmoke") {
        Run-AndroidReceiverSmoke
    }
}
finally {
    Save-Summary
    Write-Host ""
    Write-Host "Artifacts: $artifactRoot"
}
