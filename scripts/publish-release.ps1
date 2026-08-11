param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [ValidateSet('All','Windows','Android','Server','WinForms','WPF','AndroidDetector','AndroidReceiver')]
    [string]$Target = 'All',

    [switch]$UploadVps,
    [switch]$PushGitHub,
    [switch]$CreateTag,
    [switch]$CreateGitHubRelease,
    [switch]$DeployServer,
    [switch]$SkipServerDeploy,
    [switch]$SkipBuild,
    [switch]$PreflightOnly,
    [switch]$DryRun,
    [switch]$GitHubOnly,

    [string]$ServerEnvPath = 'D:\ObjectCode\Server-infra\server.local.env',
    [string]$RemoteRoot = '/opt/visionguard-server',
    [string]$BaseUrl = 'https://visionguard.xgwnje.cn',
    [string]$GitHubReleaseNotesPath,
    [string]$GitHubTagTarget = 'HEAD',
    [string]$GitHubRepository = 'XGWNJE/VisionGuard'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseDir = Join-Path $repoRoot 'server\data\releases'
$modelsDir = Join-Path $repoRoot 'server\data\models'
$releasesJsonPath = Join-Path $repoRoot 'server\data\releases.json'
$buildScript = Join-Path $repoRoot '.agents\skills\visionguard-build\scripts\build-all.ps1'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message =="
}

function Invoke-Native {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $repoRoot
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath exited with code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-NativeCapture {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $repoRoot
    )

    Push-Location $WorkingDirectory
    try {
        $output = & $FilePath @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath exited with code $LASTEXITCODE`: $($output -join [Environment]::NewLine)"
        }
        return (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    }
    finally {
        Pop-Location
    }
}

function Test-NativeSuccess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $repoRoot
    )

    Push-Location $WorkingDirectory
    $oldErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $FilePath @Arguments *> $null
        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $oldErrorActionPreference
        Pop-Location
    }
}

function Test-TargetEnabled {
    param([string[]]$Names)
    return ($Target -eq 'All' -or $Names -contains $Target)
}

function Read-EnvFile {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $values
    }

    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or $trimmed -notmatch '=') {
            continue
        }

        $parts = $trimmed.Split([char[]]@('='), 2)
        $key = $parts[0].Trim()
        $value = $parts[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }

    return $values
}

function Get-MapValue {
    param(
        [hashtable]$Map,
        [string[]]$Keys
    )

    foreach ($key in $Keys) {
        if ($Map.ContainsKey($key) -and -not [string]::IsNullOrWhiteSpace($Map[$key])) {
            return $Map[$key]
        }
    }
    return $null
}

function Get-AndroidTool {
    param([string]$Name)

    $sdkRoots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($sdkRoot in $sdkRoots) {
        $buildTools = Join-Path $sdkRoot 'build-tools'
        if (-not (Test-Path -LiteralPath $buildTools)) {
            continue
        }

        $tool = Get-ChildItem -LiteralPath $buildTools -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                foreach ($extension in @('.bat', '.exe')) {
                    Join-Path $_.FullName "$Name$extension"
                }
            } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1

        if ($tool) {
            return $tool
        }
    }

    throw "$Name was not found under Android SDK build-tools."
}

function Set-AndroidJavaHome {
    [object[]]$candidates = @(
        $env:JAVA_HOME,
        'C:\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jre'
    ) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) }

    if ($candidates -and $candidates.Count -gt 0) {
        $env:JAVA_HOME = $candidates[0]
        $javaBin = Join-Path $env:JAVA_HOME 'bin'
        if (($env:Path -split ';') -notcontains $javaBin) {
            $env:Path = "$javaBin;$env:Path"
        }
        return
    }

    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        throw "JAVA_HOME is not set and java was not found on PATH."
    }
}

function Get-MSBuildPath {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $path = & $vswhere -latest -products * -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1
        if ($path -and (Test-Path -LiteralPath $path)) {
            return $path
        }
    }

    foreach ($candidate in @(
        'C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\MSBuild.exe',
        'C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\MSBuild.exe'
    )) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw 'MSBuild.exe not found. Install Visual Studio Build Tools or Visual Studio with MSBuild.'
}

function Get-AndroidSecretMap {
    param([string]$ProjectRoot)

    $map = @{}
    foreach ($file in @(
        (Join-Path $ProjectRoot 'keystore.local.env'),
        (Join-Path $repoRoot '.local\visionguard-release.env'),
        'D:\ObjectCode\Server-infra\visionguard-release.local.env'
    )) {
        foreach ($entry in (Read-EnvFile -Path $file).GetEnumerator()) {
            $map[$entry.Key] = $entry.Value
        }
    }

    foreach ($name in @(
        'VISIONGUARD_ANDROID_STORE_FILE',
        'VISIONGUARD_ANDROID_STORE_PASSWORD',
        'VISIONGUARD_ANDROID_KEY_PASSWORD',
        'VISIONGUARD_ANDROID_KEY_ALIAS',
        'VG_ANDROID_STORE_PASSWORD',
        'VG_ANDROID_KEY_PASSWORD'
    )) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $map[$name] = $value
        }
    }

    return $map
}

function Get-KeystoreConfig {
    param([string]$ProjectRoot)

    $trackedConfig = Read-EnvFile -Path (Join-Path $ProjectRoot 'keystore.properties')
    $secretMap = Get-AndroidSecretMap -ProjectRoot $ProjectRoot

    $storeFile = Get-MapValue -Map $secretMap -Keys @('VISIONGUARD_ANDROID_STORE_FILE', 'storeFile')
    if (-not $storeFile) {
        $storeFile = Get-MapValue -Map $trackedConfig -Keys @('storeFile')
    }

    $keyAlias = Get-MapValue -Map $secretMap -Keys @('VISIONGUARD_ANDROID_KEY_ALIAS', 'keyAlias')
    if (-not $keyAlias) {
        $keyAlias = Get-MapValue -Map $trackedConfig -Keys @('keyAlias')
    }
    if (-not $keyAlias) {
        $keyAlias = 'vg-key'
    }

    $storePassword = Get-MapValue -Map $secretMap -Keys @('VISIONGUARD_ANDROID_STORE_PASSWORD', 'VG_ANDROID_STORE_PASSWORD', 'storePassword')
    $keyPassword = Get-MapValue -Map $secretMap -Keys @('VISIONGUARD_ANDROID_KEY_PASSWORD', 'VG_ANDROID_KEY_PASSWORD', 'keyPassword')

    if (-not $storePassword -or -not $keyPassword) {
        throw "Android signing passwords were not found. Run scripts\initialize-android-signing.ps1 or configure .local\visionguard-release.env."
    }

    $candidatePaths = @()
    if ($storeFile) {
        if ([System.IO.Path]::IsPathRooted($storeFile)) {
            $candidatePaths += $storeFile
        }
        else {
            $candidatePaths += Join-Path $repoRoot $storeFile
            $candidatePaths += Join-Path $ProjectRoot $storeFile
        }
    }
    $candidatePaths += Join-Path $ProjectRoot 'key\vg-release.jks'
    $candidatePaths += Join-Path $ProjectRoot 'app\visonGuard.jks'

    $keystorePath = $candidatePaths | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $keystorePath) {
        throw "Android signing keystore was not found for $ProjectRoot."
    }

    return [pscustomobject]@{
        StoreFile = (Resolve-Path -LiteralPath $keystorePath).Path
        StorePassword = $storePassword
        KeyAlias = $keyAlias
        KeyPassword = $keyPassword
    }
}

function Test-PythonParamiko {
    $python = @'
import paramiko
print("paramiko ok")
'@
    $python | python -
    if ($LASTEXITCODE -ne 0) {
        throw "Python Paramiko is required for VPS upload/deploy."
    }
}

function Restore-WinFormsPackages {
    $msbuild = Get-MSBuildPath
    $solutionDir = (Resolve-Path -LiteralPath (Join-Path $repoRoot 'detector\windows-winforms')).Path + [System.IO.Path]::DirectorySeparatorChar
    Invoke-Native -FilePath $msbuild -Arguments @(
        'detector\windows-winforms\VisionGuard.csproj',
        '/t:Restore',
        '/p:RestorePackagesConfig=true',
        "/p:SolutionDir=$solutionDir",
        '/v:minimal'
    )
}

function Invoke-ReleasePreflight {
    param([bool]$ServerDeployPlanned)

    Write-Host "preflight: checking documentation contract"
    Invoke-Native -FilePath 'node' -Arguments @((Join-Path $repoRoot 'scripts\check-docs.js'))

    if ($RemoteRoot -match 'VisionGuard_Server|/opt/visionguard($|/)') {
        throw "RemoteRoot points to a legacy VisionGuard path: $RemoteRoot. Use /opt/visionguard-server."
    }

    if (($UploadVps -or $ServerDeployPlanned) -and -not (Test-Path -LiteralPath $ServerEnvPath)) {
        throw "Server env file was not found: $ServerEnvPath"
    }

    if ($UploadVps -or $ServerDeployPlanned) {
        Test-PythonParamiko
    }

    if (Test-TargetEnabled @('Windows', 'WinForms')) {
        Write-Host "preflight: restoring WinForms packages.config dependencies"
        Restore-WinFormsPackages
    }

    if (Test-TargetEnabled @('Android', 'AndroidDetector', 'AndroidReceiver')) {
        Set-AndroidJavaHome
        [void](Get-AndroidTool -Name 'apksigner')
        [void](Get-AndroidTool -Name 'zipalign')
    }

    if (Test-TargetEnabled @('Android', 'AndroidDetector')) {
        [void](Get-KeystoreConfig -ProjectRoot (Join-Path $repoRoot 'detector\android'))
        Write-Host "preflight: Android detector signing config resolved"
    }

    if (Test-TargetEnabled @('Android', 'AndroidReceiver')) {
        [void](Get-KeystoreConfig -ProjectRoot (Join-Path $repoRoot 'receiver\android'))
        Write-Host "preflight: Android receiver signing config resolved"
    }

    if ($ServerDeployPlanned -and $SkipBuild -and -not (Test-Path -LiteralPath (Join-Path $repoRoot 'server\dist\index.js'))) {
        throw "Server deploy was requested with -SkipBuild, but server\dist\index.js does not exist."
    }

    Write-Host "preflight: release prerequisites passed"
}

function Verify-AndroidApk {
    param([string]$ApkPath)

    Set-AndroidJavaHome
    $apksigner = Get-AndroidTool -Name 'apksigner'
    Invoke-Native -FilePath $apksigner -Arguments @('verify', '--verbose', '--print-certs', $ApkPath) | Out-Host
}

function Get-SignedAndroidApk {
    param(
        [string]$ProjectRoot,
        [string]$Name
    )

    $releaseOutput = Join-Path $ProjectRoot 'app\build\outputs\apk\release'
    $signedApk = Join-Path $releaseOutput 'app-release.apk'
    if (Test-Path -LiteralPath $signedApk) {
        Verify-AndroidApk -ApkPath $signedApk
        return $signedApk
    }

    $unsignedApk = Join-Path $releaseOutput 'app-release-unsigned.apk'
    if (-not (Test-Path -LiteralPath $unsignedApk)) {
        throw "$Name release APK was not found under $releaseOutput."
    }

    Write-Step "Signing $Name app-release-unsigned.apk"
    $config = Get-KeystoreConfig -ProjectRoot $ProjectRoot
    Set-AndroidJavaHome
    $apksigner = Get-AndroidTool -Name 'apksigner'
    $zipalign = Get-AndroidTool -Name 'zipalign'
    $alignedApk = Join-Path $releaseOutput 'app-release-aligned.apk'

    if (Test-Path -LiteralPath $alignedApk) {
        Remove-Item -LiteralPath $alignedApk -Force
    }
    if (Test-Path -LiteralPath $signedApk) {
        Remove-Item -LiteralPath $signedApk -Force
    }

    Invoke-Native -FilePath $zipalign -Arguments @('-P', '16', '-f', '4', $unsignedApk, $alignedApk)

    $oldStore = $env:VG_STORE_PASS
    $oldKey = $env:VG_KEY_PASS
    try {
        $env:VG_STORE_PASS = $config.StorePassword
        $env:VG_KEY_PASS = $config.KeyPassword
        Invoke-Native -FilePath $apksigner -Arguments @(
            'sign',
            '--ks', $config.StoreFile,
            '--ks-key-alias', $config.KeyAlias,
            '--ks-pass', 'env:VG_STORE_PASS',
            '--key-pass', 'env:VG_KEY_PASS',
            '--v1-signing-enabled', 'false',
            '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true',
            '--v4-signing-enabled', 'false',
            '--out', $signedApk,
            $alignedApk
        )
    }
    finally {
        $env:VG_STORE_PASS = $oldStore
        $env:VG_KEY_PASS = $oldKey
        if (Test-Path -LiteralPath $alignedApk) {
            Remove-Item -LiteralPath $alignedApk -Force
        }
    }

    Verify-AndroidApk -ApkPath $signedApk
    return $signedApk
}

function New-ZipPackage {
    param(
        [string]$SourceDir,
        [string]$Destination
    )

    if (-not (Test-Path -LiteralPath $SourceDir)) {
        throw "Package source does not exist: $SourceDir"
    }

    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Force
    }

    $items = Get-ChildItem -LiteralPath $SourceDir -Force |
        Where-Object { @('Assets', 'alerts') -notcontains $_.Name }

    if (-not $items) {
        throw "Package source is empty after excludes: $SourceDir"
    }

    Compress-Archive -Path $items.FullName -DestinationPath $Destination -Force
}

function Assert-ZipIsClean {
    param([string]$ZipPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $badEntries = $zip.Entries |
            Where-Object {
                $_.FullName -match '(^|/)(Assets|alerts)/' -or
                $_.FullName -match '\.(pdb|lib|dll\.config|onnx)$'
            } |
            Select-Object -ExpandProperty FullName
    }
    finally {
        $zip.Dispose()
    }

    if ($badEntries) {
        throw "Forbidden files were found in $(Split-Path -Leaf $ZipPath): $($badEntries -join ', ')"
    }
}

function Copy-Models {
    New-Item -ItemType Directory -Force -Path $modelsDir | Out-Null
    $modelSources = @(
        (Join-Path $repoRoot 'detector\windows-winforms\Assets'),
        (Join-Path $repoRoot 'detector\windows-wpf\Assets'),
        (Join-Path $repoRoot 'detector\android\app\src\main\assets\models')
    )

    foreach ($source in $modelSources) {
        if (-not (Test-Path -LiteralPath $source)) {
            continue
        }

        Get-ChildItem -LiteralPath $source -Filter '*.onnx' -File |
            ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $modelsDir $_.Name) -Force
            }
    }
}

function Add-ReleaseEntry {
    param(
        [pscustomobject]$Metadata,
        [string]$Key,
        [string]$FileName,
        [string]$FilePath
    )

    $size = (Get-Item -LiteralPath $FilePath).Length
    $entry = [pscustomobject]@{
        version = $Version
        url = "/releases/$FileName"
        size = $size
    }

    if ($Metadata.PSObject.Properties.Name -contains $Key) {
        $Metadata.PSObject.Properties[$Key].Value = $entry
    }
    else {
        $Metadata | Add-Member -NotePropertyName $Key -NotePropertyValue $entry
    }
}

function Save-ReleasesJson {
    param([pscustomobject]$Metadata)

    $json = $Metadata | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($releasesJsonPath, $json + [Environment]::NewLine, $utf8NoBom)
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-GitHubReleaseNotes {
    if ([string]::IsNullOrWhiteSpace($GitHubReleaseNotesPath)) {
        throw "-GitHubReleaseNotesPath is required when creating or updating a GitHub Release."
    }

    $resolvedPath = Resolve-Path -LiteralPath $GitHubReleaseNotesPath -ErrorAction SilentlyContinue
    if (-not $resolvedPath) {
        throw "GitHub release notes file was not found: $GitHubReleaseNotesPath"
    }

    $content = Get-Content -LiteralPath $resolvedPath.Path -Encoding UTF8 -Raw
    if ([string]::IsNullOrWhiteSpace($content)) {
        throw "GitHub release notes file is empty: $($resolvedPath.Path)"
    }
    if ($content -notmatch '[\u4e00-\u9fff]') {
        throw "GitHub release notes must contain Chinese text: $($resolvedPath.Path)"
    }

    return $resolvedPath.Path
}

function Assert-GitHubOnlyWorkingTree {
    $allowedPaths = @(
        'scripts/publish-release.ps1',
        'scripts/release-workflow.test.js',
        'docs/superpowers/specs/2026-07-12-v5-multi-user-p2p-architecture.md'
    )
    $status = Invoke-NativeCapture -FilePath 'git' -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
    $unexpected = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($status -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^[ MADRCU?!]{1,2}\s+(.+)$') {
            throw "Unable to parse git status entry in GitHub-only mode: $line"
        }
        $path = $Matches[1].Trim('"') -replace '\\', '/'
        if ($path -match ' -> ') {
            $path = ($path -split ' -> ')[-1].Trim('"')
        }
        if ($allowedPaths -notcontains $path) {
            $unexpected.Add($path) | Out-Null
        }
    }

    if ($unexpected.Count -gt 0) {
        throw "GitHub-only mode found unexpected working tree changes: $($unexpected -join ', ')"
    }
}

function Get-GitHubOnlyArtifacts {
    if (-not (Test-Path -LiteralPath $releasesJsonPath)) {
        throw "Release metadata was not found: $releasesJsonPath"
    }

    $repositoryVersion = (Get-Content -LiteralPath (Join-Path $repoRoot 'VERSION') -Encoding UTF8 -Raw).Trim()
    if ($repositoryVersion -ne $Version) {
        throw "Requested version $Version does not match repository VERSION $repositoryVersion."
    }

    $metadata = Get-Content -LiteralPath $releasesJsonPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $definitions = @(
        [pscustomobject]@{ Platform = 'winforms'; Targets = @('Windows', 'WinForms'); FileName = "VisionGuard-v$Version.zip"; Kind = 'zip' },
        [pscustomobject]@{ Platform = 'wpf'; Targets = @('Windows', 'WPF'); FileName = "VisionGuard-WPF-v$Version.zip"; Kind = 'zip' },
        [pscustomobject]@{ Platform = 'android-detector'; Targets = @('Android', 'AndroidDetector'); FileName = "VisionGuard-Detector-v$Version.apk"; Kind = 'apk' },
        [pscustomobject]@{ Platform = 'android-receiver'; Targets = @('Android', 'AndroidReceiver'); FileName = "VisionGuard-Receiver-v$Version.apk"; Kind = 'apk' }
    )

    $artifacts = New-Object System.Collections.Generic.List[object]
    foreach ($definition in $definitions) {
        if (-not (Test-TargetEnabled $definition.Targets)) {
            continue
        }
        if ($metadata.PSObject.Properties.Name -notcontains $definition.Platform) {
            throw "Release metadata is missing platform $($definition.Platform)."
        }

        $entry = $metadata.PSObject.Properties[$definition.Platform].Value
        if ($entry.version -ne $Version) {
            throw "$($definition.Platform) metadata version mismatch: $($entry.version) != $Version"
        }
        $metadataFileName = [System.IO.Path]::GetFileName([string]$entry.url)
        if ($metadataFileName -ne $definition.FileName) {
            throw "$($definition.Platform) metadata filename mismatch: $metadataFileName != $($definition.FileName)"
        }

        $path = Join-Path $releaseDir $definition.FileName
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Existing release asset was not found: $path"
        }
        $actualSize = (Get-Item -LiteralPath $path).Length
        if ([long]$entry.size -ne $actualSize) {
            throw "$($definition.Platform) asset size mismatch: $actualSize != $($entry.size)"
        }

        if ($definition.Kind -eq 'zip') {
            Assert-ZipIsClean -ZipPath $path
        }
        else {
            Verify-AndroidApk -ApkPath $path
        }
        $artifacts.Add([pscustomobject]@{
            Platform = $definition.Platform
            Path = $path
            FileName = $definition.FileName
        }) | Out-Null
    }

    if ($artifacts.Count -eq 0) {
        throw "GitHub-only mode requires at least one client release asset; target $Target selected none."
    }
    return $artifacts.ToArray()
}

function Resolve-GitCommit {
    param([string]$Revision)
    return Invoke-NativeCapture -FilePath 'git' -Arguments @('rev-parse', "$Revision^{commit}")
}

function Get-RemoteTagCommit {
    param([string]$TagName)

    $output = Invoke-NativeCapture -FilePath 'git' -Arguments @(
        'ls-remote', '--tags', 'origin', "refs/tags/$TagName", "refs/tags/$TagName^{}"
    )
    if ([string]::IsNullOrWhiteSpace($output)) {
        return $null
    }

    $lines = $output -split "`r?`n"
    $peeled = $lines | Where-Object { $_ -match '\^\{\}$' } | Select-Object -First 1
    $selected = if ($peeled) { $peeled } else { $lines | Select-Object -First 1 }
    return ($selected -split '\s+')[0].Trim()
}

function Ensure-GitTag {
    param(
        [string]$TagName,
        [string]$TargetCommit
    )

    $localTagExists = Test-NativeSuccess -FilePath 'git' -Arguments @('show-ref', '--verify', '--quiet', "refs/tags/$TagName")
    if ($localTagExists) {
        $localCommit = Resolve-GitCommit -Revision $TagName
        if ($localCommit -ne $TargetCommit) {
            throw "Local tag $TagName points to $localCommit, expected $TargetCommit. Refusing to overwrite it."
        }
    }

    $remoteCommit = Get-RemoteTagCommit -TagName $TagName
    if ($remoteCommit -and $remoteCommit -ne $TargetCommit) {
        throw "Remote tag $TagName points to $remoteCommit, expected $TargetCommit. Refusing to force-push it."
    }

    if (-not $localTagExists) {
        Invoke-Native -FilePath 'git' -Arguments @('tag', $TagName, $TargetCommit)
    }
    if (-not $remoteCommit) {
        Invoke-Native -FilePath 'git' -Arguments @('push', 'origin', "refs/tags/$TagName")
    }
}

function Invoke-GitHubOnlyPreflight {
    param([object[]]$Artifacts)

    Assert-GitHubOnlyWorkingTree
    [void](Assert-GitHubReleaseNotes)
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "GitHub CLI (gh) is required for GitHub-only publishing."
    }
    Invoke-Native -FilePath 'gh' -Arguments @('auth', 'status') | Out-Host
    Invoke-Native -FilePath 'git' -Arguments @('fetch', 'origin', 'main', '--quiet') | Out-Host

    $targetCommit = Resolve-GitCommit -Revision $GitHubTagTarget
    if (-not (Test-NativeSuccess -FilePath 'git' -Arguments @('merge-base', '--is-ancestor', $targetCommit, 'origin/main'))) {
        throw "GitHub tag target $targetCommit is not present in origin/main. Push the source commit before publishing."
    }
    if ($Artifacts.Count -eq 0) {
        throw "GitHub-only preflight found no release assets."
    }

    Write-Host "preflight: GitHub-only prerequisites passed target=$targetCommit assets=$($Artifacts.Count)"
    return $targetCommit
}

function Publish-ToVps {
    param([object[]]$Uploads)

    if (-not (Test-Path -LiteralPath $ServerEnvPath)) {
        throw "Server env file was not found: $ServerEnvPath"
    }

    $env:VG_RELEASE_UPLOADS_JSON = ($Uploads | ConvertTo-Json -Compress -Depth 5)
    $env:VG_SERVER_ENV_PATH = $ServerEnvPath
    $env:VG_REMOTE_ROOT = $RemoteRoot

    $python = @'
import hashlib
import json
import os
import posixpath
import shlex
import sys
import time

try:
    import paramiko
except Exception as exc:
    raise SystemExit("Paramiko is required for VPS upload: " + str(exc))

def read_env(path):
    values = {}
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values

def pick(values, *keys, default=None):
    for key in keys:
        value = values.get(key)
        if value:
            return value
    return default

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

values = read_env(os.environ["VG_SERVER_ENV_PATH"])
host = pick(values, "VPS_IP", "SSH_HOST", "VPS_HOST")
user = pick(values, "SSH_USER", "VPS_USER", default="root")
port = int(pick(values, "SSH_PORT", "VPS_PORT", default="22"))
password = pick(values, "SSH_PASSWORD", "VPS_PASSWORD")
key_filename = pick(values, "SSH_KEY", "SSH_KEY_PATH")
remote_root = os.environ["VG_REMOTE_ROOT"].rstrip("/")
uploads = json.loads(os.environ["VG_RELEASE_UPLOADS_JSON"])

if not host:
    raise SystemExit("VPS host was not found in server.local.env")

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(hostname=host, port=port, username=user, password=password or None, key_filename=key_filename or None, timeout=30)

try:
    sftp = client.open_sftp()
    stdin, stdout, stderr = client.exec_command("mkdir -p " + shlex.quote(posixpath.join(remote_root, "data", "releases")))
    status = stdout.channel.recv_exit_status()
    if status != 0:
        error = stderr.read().decode("utf-8", "replace").strip()
        raise SystemExit(error or "remote mkdir failed")
    for item in uploads:
        local = item["local"]
        remote = item["remote"]
        expected = sha256_file(local)
        temp_remote = remote + ".tmp-" + str(int(time.time()))
        sftp.put(local, temp_remote)
        command = "mv -f {tmp} {remote} && sha256sum {remote}".format(
            tmp=shlex.quote(temp_remote),
            remote=shlex.quote(remote),
        )
        stdin, stdout, stderr = client.exec_command(command)
        status = stdout.channel.recv_exit_status()
        output = stdout.read().decode("utf-8", "replace").strip()
        error = stderr.read().decode("utf-8", "replace").strip()
        if status != 0:
            raise SystemExit(error or output or "remote mv -f failed")
        actual = output.split()[0].lower()
        if actual != expected:
            raise SystemExit("remote sha256 mismatch for " + remote)
        print("uploaded " + posixpath.basename(remote) + " " + actual)
finally:
    client.close()
'@

    try {
        $python | python -
        if ($LASTEXITCODE -ne 0) {
            throw "VPS upload verification failed with code $LASTEXITCODE."
        }
    }
    finally {
        Remove-Item Env:\VG_RELEASE_UPLOADS_JSON -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_SERVER_ENV_PATH -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_REMOTE_ROOT -ErrorAction SilentlyContinue
    }
}

function Deploy-ServerCode {
    $serverDist = Join-Path $repoRoot 'server\dist\index.js'
    if (-not (Test-Path -LiteralPath $serverDist)) {
        throw "Server dist was not found: $serverDist. Build the Server target before deployment."
    }
    if (-not (Test-Path -LiteralPath $ServerEnvPath)) {
        throw "Server env file was not found: $ServerEnvPath"
    }

    $archive = Join-Path ([System.IO.Path]::GetTempPath()) ("visionguard-server-deploy-{0}.tgz" -f ([guid]::NewGuid().ToString('N')))
    try {
        Invoke-Native -FilePath 'tar' -Arguments @('-C', (Join-Path $repoRoot 'server'), '-czf', $archive, 'package.json', 'package-lock.json', 'dist')

        $env:VG_DEPLOY_ARCHIVE = $archive
        $env:VG_SERVER_ENV_PATH = $ServerEnvPath
        $env:VG_REMOTE_ROOT = $RemoteRoot
        $env:VG_RELEASE_VERSION = $Version

        $python = @'
import os
import shlex
import sys

try:
    import paramiko
except Exception as exc:
    raise SystemExit("Paramiko is required for server deploy: " + str(exc))

def read_env(path):
    values = {}
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values

def pick(values, *keys, default=None):
    for key in keys:
        value = values.get(key)
        if value:
            return value
    return default

values = read_env(os.environ["VG_SERVER_ENV_PATH"])
host = pick(values, "VPS_IP", "SSH_HOST", "VPS_HOST")
user = pick(values, "SSH_USER", "VPS_USER", default="root")
port = int(pick(values, "SSH_PORT", "VPS_PORT", default="22"))
password = pick(values, "SSH_PASSWORD", "VPS_PASSWORD")
key_filename = pick(values, "SSH_KEY", "SSH_KEY_PATH")
archive = os.environ["VG_DEPLOY_ARCHIVE"]
remote_root = os.environ["VG_REMOTE_ROOT"].rstrip("/")
version = os.environ["VG_RELEASE_VERSION"]
remote_archive = "/tmp/visionguard-server-deploy.tgz"
remote_stage = "/tmp/visionguard-server-deploy"

if not host:
    raise SystemExit("VPS host was not found in server.local.env")

q = shlex.quote
remote_command = f"""
set -euo pipefail
rm -rf {q(remote_stage)}
mkdir -p {q(remote_stage)} {q(remote_root)}
tar -xzf {q(remote_archive)} -C {q(remote_stage)}
rm -rf {q(remote_root + "/dist")}
mv {q(remote_stage + "/dist")} {q(remote_root + "/dist")}
cp {q(remote_stage + "/package.json")} {q(remote_root + "/package.json")}
cp {q(remote_stage + "/package-lock.json")} {q(remote_root + "/package-lock.json")}
cd {q(remote_root)} && npm ci --omit=dev
systemctl restart visionguard
sleep 2
systemctl is-active --quiet visionguard
remote_version="$(cd {q(remote_root)} && node -p "require('./package.json').version")"
if [ "$remote_version" != {q(version)} ]; then
  echo "remote server version mismatch: $remote_version != {version}" >&2
  exit 1
fi
curl -fsS http://127.0.0.1:3000/health
rm -rf {q(remote_stage)} {q(remote_archive)}
"""

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(hostname=host, port=port, username=user, password=password or None, key_filename=key_filename or None, timeout=30)
try:
    with client.open_sftp() as sftp:
        sftp.put(archive, remote_archive)
    stdin, stdout, stderr = client.exec_command(remote_command, timeout=300)
    status = stdout.channel.recv_exit_status()
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    if out:
        print(out, end="")
    if status != 0:
        if err:
            print(err, file=sys.stderr, end="")
        raise SystemExit(status)
finally:
    client.close()
'@

        $python | python -
        if ($LASTEXITCODE -ne 0) {
            throw "Server deploy failed with code $LASTEXITCODE."
        }
    }
    finally {
        Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_DEPLOY_ARCHIVE -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_SERVER_ENV_PATH -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_REMOTE_ROOT -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_RELEASE_VERSION -ErrorAction SilentlyContinue
    }
}

function Verify-OnlineServer {
    $env:VG_BASE_URL = $BaseUrl
    $python = @'
import json
import os
import urllib.request

base_url = os.environ["VG_BASE_URL"].rstrip("/")
with urllib.request.urlopen(base_url + "/health", timeout=20) as response:
    payload = json.loads(response.read().decode("utf-8"))
if not payload.get("ok"):
    raise SystemExit("public health check did not return ok: " + repr(payload))
print("online verified server health")
'@

    try {
        $python | python -
        if ($LASTEXITCODE -ne 0) {
            throw "Online server verification failed with code $LASTEXITCODE."
        }
    }
    finally {
        Remove-Item Env:\VG_BASE_URL -ErrorAction SilentlyContinue
    }
}

function Verify-OnlineRelease {
    param([string[]]$Platforms)

    $env:VG_RELEASES_JSON = $releasesJsonPath
    $env:VG_BASE_URL = $BaseUrl
    $env:VG_VERSION = $Version
    $env:VG_PLATFORMS = ($Platforms -join ',')

    $python = @'
import json
import os
import sys
import urllib.parse
import urllib.request

base_url = os.environ["VG_BASE_URL"].rstrip("/")
version = os.environ["VG_VERSION"]
platforms = [p for p in os.environ["VG_PLATFORMS"].split(",") if p]
with open(os.environ["VG_RELEASES_JSON"], "r", encoding="utf-8") as handle:
    metadata = json.load(handle)

for platform in platforms:
    expected = metadata[platform]
    update_url = base_url + "/api/update?" + urllib.parse.urlencode({"platform": platform, "version": "0.0.0"})
    with urllib.request.urlopen(update_url, timeout=20) as response:
        payload = json.loads(response.read().decode("utf-8"))
    payload_version = payload.get("version", payload.get("latestVersion"))
    payload_url = payload.get("url", payload.get("downloadUrl"))
    if payload_version != version:
        raise SystemExit(f"{platform} update version mismatch: {payload}")
    payload_size = payload.get("size", payload.get("fileSize", -1))
    if payload_url != expected["url"] or int(payload_size) != int(expected["size"]):
        raise SystemExit(f"{platform} update payload mismatch: {payload}")

    asset_url = base_url + expected["url"]
    head = urllib.request.Request(asset_url, method="HEAD")
    with urllib.request.urlopen(head, timeout=20) as response:
        if response.status != 200:
            raise SystemExit(f"{platform} HEAD expected 200, got {response.status}")
        length = int(response.headers.get("Content-Length", "-1"))
        if length != int(expected["size"]):
            raise SystemExit(f"{platform} HEAD size mismatch: {length} != {expected['size']}")

    range_req = urllib.request.Request(asset_url, headers={"Range": "bytes=0-0"})
    with urllib.request.urlopen(range_req, timeout=20) as response:
        if response.status != 206:
            raise SystemExit(f"{platform} Range expected 206, got {response.status}")
    print(f"online verified {platform}")
'@

    try {
        $python | python -
        if ($LASTEXITCODE -ne 0) {
            throw "Online release verification failed with code $LASTEXITCODE."
        }
    }
    finally {
        Remove-Item Env:\VG_RELEASES_JSON -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_BASE_URL -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_VERSION -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_PLATFORMS -ErrorAction SilentlyContinue
    }
}

function Invoke-GitHubSteps {
    param(
        [object[]]$Artifacts,
        [string]$TagTargetCommit = $null
    )

    if ($PushGitHub) {
        Invoke-Native -FilePath 'git' -Arguments @('push')
    }

    $tagName = "v$Version"
    if ($CreateTag) {
        if (-not $TagTargetCommit) {
            $TagTargetCommit = Resolve-GitCommit -Revision $GitHubTagTarget
        }
        Ensure-GitTag -TagName $tagName -TargetCommit $TagTargetCommit
    }

    if ($CreateGitHubRelease) {
        $notesPath = Assert-GitHubReleaseNotes
        $assetPaths = $Artifacts | ForEach-Object { $_.Path }
        $releaseExists = Test-NativeSuccess -FilePath 'gh' -Arguments @('release', 'view', $tagName, '--repo', $GitHubRepository)
        if (-not $releaseExists) {
            Invoke-Native -FilePath 'gh' -Arguments @(
                'release', 'create', $tagName,
                '--repo', $GitHubRepository,
                '--title', "VisionGuard v$Version",
                '--notes-file', $notesPath,
                '--verify-tag',
                '--latest'
            )
        }
        else {
            Invoke-Native -FilePath 'gh' -Arguments @(
                'release', 'edit', $tagName,
                '--repo', $GitHubRepository,
                '--title', "VisionGuard v$Version",
                '--notes-file', $notesPath,
                '--draft=false',
                '--prerelease=false',
                '--latest'
            )
        }
        Invoke-Native -FilePath 'gh' -Arguments (@(
            'release', 'upload', $tagName,
            '--repo', $GitHubRepository
        ) + $assetPaths + @('--clobber'))
    }
}

if ($Version -notmatch '^\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$') {
    throw "Invalid version: $Version"
}

if ($DeployServer -and $SkipServerDeploy) {
    throw "Use either -DeployServer or -SkipServerDeploy, not both."
}

if ($CreateGitHubRelease -and [string]::IsNullOrWhiteSpace($GitHubReleaseNotesPath)) {
    throw "-GitHubReleaseNotesPath is required with -CreateGitHubRelease."
}

if ($GitHubOnly) {
    if ($UploadVps -or $PushGitHub -or $DeployServer -or $SkipServerDeploy -or $SkipBuild) {
        throw "-GitHubOnly cannot be combined with VPS upload, source push, Server deployment, or build-control switches."
    }
    if (-not $CreateTag -or -not $CreateGitHubRelease) {
        throw "-GitHubOnly requires both -CreateTag and -CreateGitHubRelease."
    }
}

Set-Location $repoRoot
$serverDeployPlanned = (($UploadVps -and (Test-TargetEnabled @('Server')) -and -not $SkipServerDeploy) -or $DeployServer)

if ($GitHubOnly) {
    Write-Step "Validate existing GitHub release assets"
    $githubArtifacts = @(Get-GitHubOnlyArtifacts)

    Write-Step "GitHub-only preflight"
    $tagTargetCommit = Invoke-GitHubOnlyPreflight -Artifacts $githubArtifacts

    if ($PreflightOnly) {
        Write-Host "GitHub-only preflight complete for v$Version target=$Target tagTarget=$tagTargetCommit."
        return
    }
    if ($DryRun) {
        Write-Host "Dry run: GitHub-only would publish v$Version from existing assets with tag target $tagTargetCommit."
        return
    }

    Write-Step "Publish GitHub tag and Release"
    Invoke-GitHubSteps -Artifacts $githubArtifacts -TagTargetCommit $tagTargetCommit

    Write-Step "Release summary"
    foreach ($artifact in $githubArtifacts) {
        Write-Host "$($artifact.Platform) $($artifact.FileName) sha256=$(Get-Sha256 -Path $artifact.Path)"
    }
    return
}

if ($DryRun) {
    Write-Host "Dry run: publish-release.ps1 would release v$Version target=$Target upload=$UploadVps deployServer=$serverDeployPlanned githubPush=$PushGitHub tag=$CreateTag githubRelease=$CreateGitHubRelease."
    Write-Host "Server env: $ServerEnvPath"
    Write-Host "Remote root: $RemoteRoot"
    return
}

Write-Step "Preflight"
Invoke-ReleasePreflight -ServerDeployPlanned $serverDeployPlanned

if ($PreflightOnly) {
    Write-Host "Preflight only complete for v$Version target=$Target upload=$UploadVps deployServer=$serverDeployPlanned."
    return
}

Write-Step "Sync version"
Invoke-Native -FilePath 'node' -Arguments @((Join-Path $repoRoot 'scripts\sync-version.js'), $Version)

if (-not $SkipBuild) {
    Write-Step "Build $Target"
    Invoke-Native -FilePath 'powershell' -Arguments @('-ExecutionPolicy', 'Bypass', '-File', $buildScript, '-Target', $Target)
}

$artifacts = New-Object System.Collections.Generic.List[object]
$platforms = New-Object System.Collections.Generic.List[string]
New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
Copy-Models

$metadata = Get-Content -Encoding UTF8 -LiteralPath $releasesJsonPath -Raw | ConvertFrom-Json

if (Test-TargetEnabled @('Windows', 'WinForms')) {
    $fileName = "VisionGuard-v$Version.zip"
    $zipPath = Join-Path $releaseDir $fileName
    New-ZipPackage -SourceDir (Join-Path $repoRoot 'detector\windows-winforms\bin\Release') -Destination $zipPath
    Assert-ZipIsClean -ZipPath $zipPath
    Add-ReleaseEntry -Metadata $metadata -Key 'winforms' -FileName $fileName -FilePath $zipPath
    $artifacts.Add([pscustomobject]@{ Platform = 'winforms'; Path = $zipPath; FileName = $fileName }) | Out-Null
    $platforms.Add('winforms') | Out-Null
}

if (Test-TargetEnabled @('Windows', 'WPF')) {
    $fileName = "VisionGuard-WPF-v$Version.zip"
    $zipPath = Join-Path $releaseDir $fileName
    New-ZipPackage -SourceDir (Join-Path $repoRoot 'detector\windows-wpf\bin\x64') -Destination $zipPath
    Assert-ZipIsClean -ZipPath $zipPath
    Add-ReleaseEntry -Metadata $metadata -Key 'wpf' -FileName $fileName -FilePath $zipPath
    $artifacts.Add([pscustomobject]@{ Platform = 'wpf'; Path = $zipPath; FileName = $fileName }) | Out-Null
    $platforms.Add('wpf') | Out-Null
}

if (Test-TargetEnabled @('Android', 'AndroidDetector')) {
    $fileName = "VisionGuard-Detector-v$Version.apk"
    $apkPath = Get-SignedAndroidApk -ProjectRoot (Join-Path $repoRoot 'detector\android') -Name 'Android detector'
    $dest = Join-Path $releaseDir $fileName
    Copy-Item -LiteralPath $apkPath -Destination $dest -Force
    Verify-AndroidApk -ApkPath $dest
    Add-ReleaseEntry -Metadata $metadata -Key 'android-detector' -FileName $fileName -FilePath $dest
    $artifacts.Add([pscustomobject]@{ Platform = 'android-detector'; Path = $dest; FileName = $fileName }) | Out-Null
    $platforms.Add('android-detector') | Out-Null
}

if (Test-TargetEnabled @('Android', 'AndroidReceiver')) {
    $fileName = "VisionGuard-Receiver-v$Version.apk"
    $apkPath = Get-SignedAndroidApk -ProjectRoot (Join-Path $repoRoot 'receiver\android') -Name 'Android receiver'
    $dest = Join-Path $releaseDir $fileName
    Copy-Item -LiteralPath $apkPath -Destination $dest -Force
    Verify-AndroidApk -ApkPath $dest
    Add-ReleaseEntry -Metadata $metadata -Key 'android-receiver' -FileName $fileName -FilePath $dest
    $artifacts.Add([pscustomobject]@{ Platform = 'android-receiver'; Path = $dest; FileName = $fileName }) | Out-Null
    $platforms.Add('android-receiver') | Out-Null
}

if ($artifacts.Count -gt 0) {
    Save-ReleasesJson -Metadata $metadata
}

if ($UploadVps -and $artifacts.Count -gt 0) {
    Write-Step "Upload release assets"
    $uploads = New-Object System.Collections.Generic.List[object]
    foreach ($artifact in $artifacts) {
        $uploads.Add([pscustomobject]@{
            local = $artifact.Path
            remote = "$RemoteRoot/data/releases/$($artifact.FileName)"
        }) | Out-Null
    }
    $uploads.Add([pscustomobject]@{
        local = $releasesJsonPath
        remote = "$RemoteRoot/data/releases.json"
    }) | Out-Null
    Publish-ToVps -Uploads $uploads.ToArray()
}

if ($serverDeployPlanned) {
    Write-Step "Deploy server code"
    Deploy-ServerCode

    Write-Step "Verify online server"
    Verify-OnlineServer
}

if ($UploadVps -and $artifacts.Count -gt 0) {
    Write-Step "Verify online release"
    Verify-OnlineRelease -Platforms $platforms.ToArray()
}

Invoke-GitHubSteps -Artifacts $artifacts.ToArray()

Write-Step "Release summary"
foreach ($artifact in $artifacts) {
    Write-Host "$($artifact.Platform) $($artifact.FileName) sha256=$(Get-Sha256 -Path $artifact.Path)"
}
if ($artifacts.Count -eq 0) {
    Write-Host "No client packages were produced for target $Target."
}
if ($serverDeployPlanned) {
    Write-Host "server deployed to $RemoteRoot version=$Version"
}
