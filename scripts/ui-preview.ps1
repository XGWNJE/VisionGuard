param(
    [ValidateSet('Service', 'Windows', 'PrepareWindows', 'AndroidPackages', 'Stop')]
    [string]$Target = 'Service',
    [ValidateSet('normal', 'limits', 'empty')]
    [string]$Scene = 'normal'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskDirectory = Join-Path $taskRoot '.local\console-preview'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
$taskStateFile = Join-Path $taskDirectory 'launcher-processes.json'
$taskState = if (Test-Path -LiteralPath $taskStateFile) { Get-Content -LiteralPath $taskStateFile -Raw -Encoding UTF8 | ConvertFrom-Json } else { [pscustomobject]@{ Service = 0; Fixture = 0 } }

function Save-State { [IO.File]::WriteAllText($taskStateFile, ($taskState | ConvertTo-Json), (New-Object Text.UTF8Encoding($false))) }
function Wait-Local([string]$Url) {
    for ($taskAttempt = 0; $taskAttempt -lt 80; $taskAttempt++) {
        try { return Invoke-RestMethod -Uri $Url -TimeoutSec 1 } catch { Start-Sleep -Milliseconds 250 }
    }
    throw "Local UI preview did not become ready: $Url"
}
function Assert-LocalOwner([int]$Port, [string]$ScriptName) {
    $taskListener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$taskListener) { return $null }
    $taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($taskListener.OwningProcess)"
    if (!$taskProcess -or !$taskProcess.CommandLine.Contains($taskRoot) -or !$taskProcess.CommandLine.Contains($ScriptName)) { throw "Port $Port belongs to an unknown process; it was left running." }
    return $taskListener.OwningProcess
}

if ($Target -eq 'AndroidPackages') {
    if (!$env:GRADLE_USER_HOME) { $taskGradle = Join-Path $taskRoot '.local\acceptance-gradle'; $env:GRADLE_USER_HOME = if (Test-Path $taskGradle) { $taskGradle } else { Join-Path $taskDirectory 'gradle-cache' } }
    if (!$env:JAVA_HOME) { $taskJava = Get-Command java.exe -ErrorAction Stop; $env:JAVA_HOME = Split-Path (Split-Path $taskJava.Source -Parent) -Parent }
    foreach ($taskComponent in @('detector', 'receiver', 'notifier')) {
        Push-Location (Join-Path $taskRoot "$taskComponent\android")
        try {
            & .\gradlew.bat -I (Join-Path $taskRoot 'scripts\ui-preview.init.gradle') :app:assembleRelease :app:testDebugUnitTest --console=plain
            if ($LASTEXITCODE -ne 0) { throw "$taskComponent UI preview build or tests failed." }
        } finally { Pop-Location }
    }
    $taskPackages = Join-Path $taskDirectory 'packages'
    New-Item -ItemType Directory -Force -Path $taskPackages | Out-Null
    $taskPackageNames = @{ Detector = 'camera'; Receiver = 'console'; Notifier = 'notifier' }
    foreach ($taskName in @('Detector', 'Receiver', 'Notifier')) {
        $taskApk = Join-Path $taskDirectory "android\VisionGuard.$taskName.Android\app\outputs\apk\release\app-release.apk"
        if (!(Test-Path $taskApk)) { throw "Missing preview APK: $taskName" }
        Copy-Item -LiteralPath $taskApk -Destination (Join-Path $taskPackages ($taskPackageNames[$taskName] + '-ui-preview.apk')) -Force
    }
    $taskManifest = @(Get-ChildItem $taskPackages -Filter *.apk | ForEach-Object { [pscustomobject]@{ filename = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } })
    [IO.File]::WriteAllText((Join-Path $taskPackages 'manifest.json'), ($taskManifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Write-Host "Isolated .uipreview APKs: $taskPackages"
    return
}
if ($Target -eq 'Stop') {
    # Only signal the two instance-specific events derived from our private preview account path.
    $taskPreviewAccounts = [IO.Path]::GetFullPath((Join-Path $taskDirectory 'windows\accounts'))
    $taskSha = [Security.Cryptography.SHA256]::Create()
    try { $taskSuffix = ([BitConverter]::ToString($taskSha.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskPreviewAccounts)))).Replace('-','').ToLowerInvariant().Substring(0,16) } finally { $taskSha.Dispose() }
    foreach ($taskEventName in @("Local\VisionGuard.Detector.$taskSuffix.Shutdown", "Local\VisionGuard.Resident.Shutdown.$taskSuffix")) {
        try { $taskEvent = [Threading.EventWaitHandle]::OpenExisting($taskEventName); try { $taskEvent.Set() | Out-Null } finally { $taskEvent.Dispose() } } catch [Threading.WaitHandleCannotBeOpenedException] { }
    }
    $taskFixturePid = Assert-LocalOwner 4319 'ui-preview-data.js'
    if ($taskFixturePid) { Invoke-RestMethod -Uri 'http://127.0.0.1:4319/stop' | Out-Null }
    if ($taskState.Service) {
        $taskServicePid = Assert-LocalOwner 4318 'server\dist\index.js'
        if ($taskServicePid -and $taskServicePid -eq $taskState.Service) { Stop-Process -Id $taskServicePid }
    }
    $taskState.Service = 0; $taskState.Fixture = 0; Save-State
    Write-Host 'UI preview helpers stopped; isolated Windows instances received their shutdown request.'
    return
}
$taskExisting = Assert-LocalOwner 4318 'server\dist\index.js'
if (!$taskExisting) {
    $taskArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + (Join-Path $taskRoot 'scripts\start-isolated-test-server.ps1') + '"'), '-Port', '4318', '-Channel', 'console-preview', '-SkipBuild')
    Start-Process powershell.exe -WindowStyle Hidden -WorkingDirectory $taskRoot -ArgumentList $taskArguments -RedirectStandardOutput (Join-Path $taskDirectory 'service.stdout.log') -RedirectStandardError (Join-Path $taskDirectory 'service.stderr.log') | Out-Null
    $taskHealth = Wait-Local 'http://127.0.0.1:4318/health'
    if ($taskHealth.channel -ne 'console-preview') { throw 'The local service is not the UI preview instance.' }
    $taskState.Service = Assert-LocalOwner 4318 'server\dist\index.js'; Save-State
} else {
    $taskHealth = Invoke-RestMethod 'http://127.0.0.1:4318/health'
    if ($taskHealth.channel -ne 'console-preview') { throw 'The local service is not the UI preview instance.' }
}
$taskFixturePid = Assert-LocalOwner 4319 'ui-preview-data.js'
if (!$taskFixturePid) {
    $taskFixture = Start-Process node.exe -WindowStyle Hidden -WorkingDirectory $taskRoot -ArgumentList ('"' + (Join-Path $taskRoot 'scripts\ui-preview-data.js') + '"') -RedirectStandardOutput (Join-Path $taskDirectory 'data.stdout.log') -RedirectStandardError (Join-Path $taskDirectory 'data.stderr.log') -PassThru
    $taskState.Fixture = $taskFixture.Id; Save-State
    Wait-Local 'http://127.0.0.1:4319/' | Out-Null
}
Invoke-RestMethod "http://127.0.0.1:4319/scene?mode=$Scene" | Out-Null
Write-Host 'UI scenarios: http://127.0.0.1:4319/ ; console: http://127.0.0.1:4318/console/'
Write-Host "Private preview accounts: $taskRoot\.local\e2e-server\console-preview\test-accounts.json"

if ($Target -in @('Windows', 'PrepareWindows')) {
    $taskWindows = Join-Path $taskDirectory 'windows'
    New-Item -ItemType Directory -Force -Path $taskWindows | Out-Null
    $taskSettings = Join-Path $taskWindows 'settings.ini'
    $taskNamePrefix = '[UI preview] '
    $taskCount = if ($Scene -eq 'limits') { 16 } elseif ($Scene -eq 'empty') { 1 } else { 4 }
    $taskLines = @('Source.Indexes=' + ((1..$taskCount) -join ','), 'MonitorOnStartup=False')
    foreach ($taskIndex in 1..$taskCount) {
        $taskName = if ($Scene -eq 'limits') { ($taskNamePrefix + ('SourceName0123456789' * 5)).Substring(0,64) } else { "$taskNamePrefix Source $taskIndex" }
        $taskLines += "Source.$taskIndex.Name=$taskName", "Source.$taskIndex.Initialized=True", "Source.$taskIndex.SourceId=preview-$taskIndex", "Source.$taskIndex.Threshold=95", "Source.$taskIndex.Cooldown=300", "Source.$taskIndex.Fps=5", "Source.$taskIndex.ModelKey=yolo26n_320", "Source.$taskIndex.Targets=person", "Source.$taskIndex.CaptureMode=ScreenRegion", "Source.$taskIndex.ScreenRegion=", "Source.$taskIndex.Masks="
    }
    $taskConfiguration = Join-Path $taskWindows 'environment.json'
    $taskValues = @{ serviceUrl = 'http://127.0.0.1:4318'; accountDir = (Join-Path $taskWindows 'accounts'); settingsPath = $taskSettings; modelsDirectory = (Join-Path $taskWindows 'models'); logDirectory = (Join-Path $taskWindows 'logs') }
    New-Item -ItemType Directory -Force -Path $taskValues.modelsDirectory | Out-Null
    $taskCachedModel = Join-Path $env:APPDATA 'VisionGuard\models\yolo26n_320.onnx'
    $taskPreviewModel = Join-Path $taskValues.modelsDirectory 'yolo26n_320.onnx'
    if ((Test-Path $taskCachedModel) -and !(Test-Path $taskPreviewModel)) { Copy-Item -LiteralPath $taskCachedModel -Destination $taskPreviewModel }
    [IO.File]::WriteAllText($taskSettings, ($taskLines -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($taskConfiguration, ($taskValues | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    # Use the production session store so the first login also has account-scoped temporary sources.
    Add-Type -AssemblyName System.Web.Extensions
    Add-Type -AssemblyName System.Net.Http
    [Reflection.Assembly]::LoadFrom((Join-Path $taskRoot 'detector\windows-resident\bin\Release\net472\VisionGuard.Resident.Windows.exe')) | Out-Null
    [VisionGuard.Detector.Windows.Utils.AccountSession]::ConfigureIsolatedEnvironment($taskConfiguration)
    $taskPrivateAccounts = Get-Content (Join-Path $taskRoot '.local\e2e-server\console-preview\test-accounts.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskPreviewAccount = $taskPrivateAccounts | Where-Object username -eq 'vg-test'
    $taskSession = [VisionGuard.Detector.Windows.Utils.AccountSession]::Login('http://127.0.0.1:4318', 'vg-test', $taskPreviewAccount.password, 'UI-preview-Windows')
    [VisionGuard.Detector.Windows.Utils.AccountSession]::RenameDevice('[UI preview] Windows')
    $taskScoped = Join-Path $taskWindows ('accounts\' + [VisionGuard.Detector.Windows.Utils.AccountSession]::ScopeKey)
    New-Item -ItemType Directory -Force -Path $taskScoped | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskScoped 'settings.ini'), ($taskLines -join "`r`n"), (New-Object Text.UTF8Encoding($false)))
    if ($Target -eq 'PrepareWindows') { Write-Host "Windows temporary preview sources prepared: $taskCount"; return }
    $taskExecutable = Join-Path $taskRoot 'detector\windows-package\bin\Release\VisionGuard.Detector.Windows.exe'
    if (!(Test-Path -LiteralPath $taskExecutable)) { throw 'Build the Windows Release package first.' }
    Start-Process -FilePath $taskExecutable -WindowStyle Normal -ArgumentList @('--isolated-environment', ('"' + $taskConfiguration + '"')) -WorkingDirectory $taskWindows | Out-Null
    Write-Host 'A visible Release WPF window was opened using temporary preview settings.'
}
