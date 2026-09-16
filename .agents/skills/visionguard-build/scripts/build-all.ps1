param(
    [ValidateSet("All", "Server", "Windows", "WPF", "WindowsResident", "Android", "AndroidDetector", "AndroidReceiver")]
    [string]$Target = "All"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..\..")).Path
Set-Location $repoRoot

$results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Command,
        [string]$Artifact = "",
        [string]$Note = ""
    )

    $results.Add([pscustomobject]@{
        Target = $Name
        Status = $Status
        Command = $Command
        Artifact = $Artifact
        Note = $Note
    })
}

function Invoke-Step {
    param(
        [string]$Name,
        [string]$CommandText,
        [scriptblock]$Script,
        [string]$Artifact = "",
        [string]$Note = ""
    )

    Write-Host ""
    Write-Host "=== $Name ==="
    Write-Host $CommandText

    try {
        & $Script
        if ($LASTEXITCODE -ne $null -and $LASTEXITCODE -ne 0) {
            throw "Command exited with code $LASTEXITCODE"
        }
        if ($Artifact -and -not (Test-Path -LiteralPath (Join-Path $repoRoot $Artifact))) {
            throw "Expected artifact was not created: $Artifact"
        }
        Add-Result -Name $Name -Status "PASS" -Command $CommandText -Artifact $Artifact -Note $Note
    }
    catch {
        Add-Result -Name $Name -Status "FAIL" -Command $CommandText -Artifact $Artifact -Note $_.Exception.Message
        throw
    }
}

function Get-MSBuildPath {
    $vswhereCommand = Get-Command vswhere.exe -ErrorAction SilentlyContinue
    if (-not $vswhereCommand -and ${env:ProgramFiles(x86)}) {
        $candidate = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
        if (Test-Path -LiteralPath $candidate) {
            $vswhereCommand = Get-Item -LiteralPath $candidate
        }
    }
    if ($vswhereCommand) {
        $vswherePath = if ($vswhereCommand.PSObject.Properties.Name -contains 'Source') { $vswhereCommand.Source } else { $vswhereCommand.FullName }
        $path = & $vswherePath -latest -products * -requires Microsoft.Component.MSBuild -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    $msbuildCommand = Get-Command msbuild.exe -ErrorAction SilentlyContinue
    if ($msbuildCommand) {
        return $msbuildCommand.Source
    }

    throw "MSBuild.exe not found. Install Visual Studio Build Tools or Visual Studio with MSBuild."
}

function Set-CommandJavaHome {
    $candidates = @($env:JAVA_HOME)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaBin = Split-Path -Parent $javaCommand.Source
        $candidates += Split-Path -Parent $javaBin
    }
    $candidates = @($candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) } | Select-Object -Unique)

    if (-not $candidates -or $candidates.Count -eq 0) {
        throw "JAVA_HOME is not set and no Android Studio JBR was found."
    }

    $env:JAVA_HOME = $candidates[0]
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
}

function Should-Run {
    param([string[]]$Names)
    return ($Target -eq "All" -or $Names -contains $Target)
}

try {
    if (Should-Run @("Server")) {
        Invoke-Step `
            -Name "Server" `
            -CommandText "npm --prefix server run build" `
            -Artifact "server/dist/index.js" `
            -Script { npm --prefix server run build }
    }

    if (Should-Run @("Windows", "WPF")) {
        # V10：Windows 检测端有两套推理档位，都从同一份源码构建，输出到 bin\x64\<档位>\。
        #   modern（默认）= Windows 10 及以上：托管 ORT 1.19.0 + 原生 1.19.0 + DirectML，模型 YOLO26。
        #   legacy        = Windows 7 SP1 x64：托管 ORT 1.2.0 + 原生 1.1.0，固定 CPU，模型 YOLOv5。
        Invoke-Step `
            -Name "WPF (modern)" `
            -CommandText "dotnet build detector\windows-wpf\VisionGuard.sln -c Release" `
            -Artifact "detector/windows-wpf/bin/x64/modern/VisionGuard.exe" `
            -Script { dotnet build "detector\windows-wpf\VisionGuard.sln" -c Release }

        Invoke-Step `
            -Name "WPF (legacy / Win7)" `
            -CommandText "dotnet build detector\windows-wpf\VisionGuard.csproj -c Release -p:OrtProfile=legacy" `
            -Artifact "detector/windows-wpf/bin/x64/legacy/VisionGuard.exe" `
            -Script { dotnet build "detector\windows-wpf\VisionGuard.csproj" -c Release -p:OrtProfile=legacy }
    }

    if (Should-Run @("Windows", "WindowsResident", "WPF")) {
        Invoke-Step `
            -Name "Windows Resident" `
            -CommandText "dotnet build detector\windows-resident\VisionGuard.Resident.csproj -c Release" `
            -Artifact "detector/windows-resident/bin/Release/net472/VisionGuard.Resident.exe" `
            -Script { dotnet build "detector\windows-resident\VisionGuard.Resident.csproj" -c Release }
    }

    if (Should-Run @("Android", "AndroidDetector")) {
        Set-CommandJavaHome
        Invoke-Step `
            -Name "Android Detector" `
            -CommandText "detector\android\gradlew.bat assembleRelease" `
            -Artifact "detector/android/app/build/outputs/apk/release/app-release.apk" `
            -Script {
                Push-Location "detector\android"
                try { .\gradlew.bat assembleRelease }
                finally { Pop-Location }
            }
    }

    if (Should-Run @("Android", "AndroidReceiver")) {
        Set-CommandJavaHome
        Invoke-Step `
            -Name "Android Receiver" `
            -CommandText "receiver\android\gradlew.bat assembleRelease" `
            -Artifact "receiver/android/app/build/outputs/apk/release/app-release.apk" `
            -Script {
                Push-Location "receiver\android"
                try { .\gradlew.bat assembleRelease }
                finally { Pop-Location }
            }
    }
}
finally {
    Write-Host ""
    Write-Host "=== VisionGuard Build Summary ==="
    $results | Format-Table -AutoSize
}
