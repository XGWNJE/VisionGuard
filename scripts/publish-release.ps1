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
    [switch]$SkipBuild,
    [switch]$DryRun,

    [string]$ServerEnvPath = 'D:\ObjectCode\Server-infra\server.local.env',
    [string]$RemoteRoot = '/opt/visionguard-server',
    [string]$BaseUrl = 'https://visionguard.xgwnje.cn'
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
            ForEach-Object { Join-Path $_.FullName "$Name.bat" } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1

        if ($tool) {
            return $tool
        }
    }

    throw "$Name.bat was not found under Android SDK build-tools."
}

function Get-AndroidSecretMap {
    param([string]$ProjectRoot)

    $map = @{}
    foreach ($name in @(
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

    foreach ($file in @(
        (Join-Path $ProjectRoot 'keystore.local.env'),
        (Join-Path $repoRoot '.local\visionguard-release.env'),
        'D:\ObjectCode\Server-infra\visionguard-release.local.env'
    )) {
        foreach ($entry in (Read-EnvFile -Path $file).GetEnumerator()) {
            $map[$entry.Key] = $entry.Value
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
        throw "Android signing passwords were not found. Set VISIONGUARD_ANDROID_STORE_PASSWORD and VISIONGUARD_ANDROID_KEY_PASSWORD, or create an ignored keystore.local.env."
    }

    $candidatePaths = @()
    if ($storeFile) {
        $candidatePaths += $(if ([System.IO.Path]::IsPathRooted($storeFile)) { $storeFile } else { Join-Path $ProjectRoot $storeFile })
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

function Verify-AndroidApk {
    param([string]$ApkPath)

    $apksigner = Get-AndroidTool -Name 'apksigner'
    Invoke-Native -FilePath $apksigner -Arguments @('verify', '--verbose', '--print-certs', $ApkPath)
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
    $apksigner = Get-AndroidTool -Name 'apksigner'
    $zipalign = Get-AndroidTool -Name 'zipalign'
    $alignedApk = Join-Path $releaseOutput 'app-release-aligned.apk'

    if (Test-Path -LiteralPath $alignedApk) {
        Remove-Item -LiteralPath $alignedApk -Force
    }
    if (Test-Path -LiteralPath $signedApk) {
        Remove-Item -LiteralPath $signedApk -Force
    }

    Invoke-Native -FilePath $zipalign -Arguments @('-p', '-f', '4', $unsignedApk, $alignedApk)

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
    client.exec_command("mkdir -p " + shlex.quote(posixpath.join(remote_root, "data", "releases")))
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
    }
    finally {
        Remove-Item Env:\VG_RELEASE_UPLOADS_JSON -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_SERVER_ENV_PATH -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_REMOTE_ROOT -ErrorAction SilentlyContinue
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
    if payload.get("version") != version:
        raise SystemExit(f"{platform} update version mismatch: {payload}")
    if payload.get("url") != expected["url"] or int(payload.get("size", -1)) != int(expected["size"]):
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
    }
    finally {
        Remove-Item Env:\VG_RELEASES_JSON -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_BASE_URL -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_VERSION -ErrorAction SilentlyContinue
        Remove-Item Env:\VG_PLATFORMS -ErrorAction SilentlyContinue
    }
}

function Invoke-GitHubSteps {
    param([object[]]$Artifacts)

    if ($PushGitHub) {
        Invoke-Native -FilePath 'git' -Arguments @('push')
    }

    if ($CreateTag) {
        Invoke-Native -FilePath 'git' -Arguments @('tag', '-f', "v$Version")
        Invoke-Native -FilePath 'git' -Arguments @('push', 'origin', "v$Version", '--force')
    }

    if ($CreateGitHubRelease) {
        $assetPaths = $Artifacts | ForEach-Object { $_.Path }
        Invoke-Native -FilePath 'gh' -Arguments (@('release', 'upload', "v$Version") + $assetPaths + @('--clobber'))
    }
}

if ($Version -notmatch '^\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$') {
    throw "Invalid version: $Version"
}

Set-Location $repoRoot

if ($DryRun) {
    Write-Host "Dry run: publish-release.ps1 would release v$Version target=$Target upload=$UploadVps githubPush=$PushGitHub tag=$CreateTag githubRelease=$CreateGitHubRelease."
    Write-Host "Server env: $ServerEnvPath"
    Write-Host "Remote root: $RemoteRoot"
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

    Write-Step "Verify online release"
    Verify-OnlineRelease -Platforms $platforms.ToArray()
}

if ($DeployServer) {
    Write-Step "Deploy server code"
    Invoke-Native -FilePath 'bash' -Arguments @((Join-Path $repoRoot 'server\deploy.sh'), '--install')
}

Invoke-GitHubSteps -Artifacts $artifacts.ToArray()

Write-Step "Release summary"
foreach ($artifact in $artifacts) {
    Write-Host "$($artifact.Platform) $($artifact.FileName) sha256=$(Get-Sha256 -Path $artifact.Path)"
}
if ($artifacts.Count -eq 0) {
    Write-Host "No client packages were produced for target $Target."
}
