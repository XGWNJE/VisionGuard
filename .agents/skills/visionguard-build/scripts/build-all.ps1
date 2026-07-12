param(
    [ValidateSet("All", "Server", "Windows", "WinForms", "WPF", "Android", "AndroidDetector", "AndroidReceiver")]
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
        Add-Result -Name $Name -Status "PASS" -Command $CommandText -Artifact $Artifact -Note $Note
    }
    catch {
        Add-Result -Name $Name -Status "FAIL" -Command $CommandText -Artifact $Artifact -Note $_.Exception.Message
        throw
    }
}

function Get-MSBuildPath {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $path = & $vswhere -latest -products * -requires Microsoft.Component.MSBuild -find "MSBuild\**\Bin\MSBuild.exe" | Select-Object -First 1
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    $fallbacks = @(
        "C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\MSBuild.exe",
        "C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe"
    )
    foreach ($candidate in $fallbacks) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "MSBuild.exe not found. Install Visual Studio Build Tools or Visual Studio with MSBuild."
}

function Set-CommandJavaHome {
    $candidates = @(@(
        $env:JAVA_HOME,
        "C:\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre"
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) })

    if (-not $candidates -or $candidates.Count -eq 0) {
        throw "JAVA_HOME is not set and no Android Studio JBR was found."
    }

    $env:JAVA_HOME = $candidates[0]
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Host "JAVA_HOME=$env:JAVA_HOME"
}

function Restore-WinFormsPackages {
    param([string]$MSBuild)

    $solutionDir = (Resolve-Path "detector\windows-winforms").Path + [System.IO.Path]::DirectorySeparatorChar
    Invoke-Step `
        -Name "WinForms NuGet Restore" `
        -CommandText "`"$MSBuild`" detector\windows-winforms\VisionGuard.csproj /t:Restore /p:RestorePackagesConfig=true /p:SolutionDir=$solutionDir /v:minimal" `
        -Script { & $MSBuild "detector\windows-winforms\VisionGuard.csproj" /t:Restore /p:RestorePackagesConfig=true "/p:SolutionDir=$solutionDir" /v:minimal }
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

    if (Should-Run @("Windows", "WinForms")) {
        $msbuild = Get-MSBuildPath
        Restore-WinFormsPackages -MSBuild $msbuild
        Invoke-Step `
            -Name "WinForms" `
            -CommandText "`"$msbuild`" detector\windows-winforms\VisionGuard.csproj /p:Configuration=Release /p:Platform=x64 /m" `
            -Artifact "detector/windows-winforms/bin/Release/VisionGuard.exe" `
            -Script { & $msbuild "detector\windows-winforms\VisionGuard.csproj" /p:Configuration=Release /p:Platform=x64 /m }
    }

    if (Should-Run @("Windows", "WPF")) {
        Invoke-Step `
            -Name "WPF" `
            -CommandText "dotnet build detector\windows-wpf\VisionGuard.sln -c Release" `
            -Artifact "detector/windows-wpf/bin/x64/VisionGuard.exe" `
            -Script { dotnet build "detector\windows-wpf\VisionGuard.sln" -c Release }
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
