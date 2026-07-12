[CmdletBinding()]
param(
    [switch]$Rotate,
    [ValidateRange(3650, 36500)]
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $repoRoot '.local'
$keystorePath = Join-Path $localRoot 'visionguard-android-release.p12'
$secretConfigPath = Join-Path $localRoot 'visionguard-release.env'
$alias = 'visionguard-android-release'

function New-SecureRandomText {
    param([int]$ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }

    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Protect-LocalSigningDirectory {
    param([string]$Path)

    $security = New-Object System.Security.AccessControl.DirectorySecurity
    $security.SetAccessRuleProtection($true, $false)
    $rights = [System.Security.AccessControl.FileSystemRights]::FullControl
    $inheritance = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $propagation = [System.Security.AccessControl.PropagationFlags]::None
    $allow = [System.Security.AccessControl.AccessControlType]::Allow
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $localSystem = New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')

    foreach ($identity in @($currentUser, $localSystem)) {
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
            $identity,
            $rights,
            $inheritance,
            $propagation,
            $allow
        )
        [void]$security.AddAccessRule($rule)
    }

    Set-Acl -LiteralPath $Path -AclObject $security
}

function Resolve-Keytool {
    $javaHomes = @(
        $env:JAVA_HOME,
        'C:\Android\Android Studio\jbr',
        'C:\Program Files\Android\Android Studio\jbr'
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($javaHome in $javaHomes) {
        $candidate = Join-Path $javaHome 'bin\keytool.exe'
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $command = Get-Command keytool -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw 'keytool.exe was not found. Install Android Studio or configure JAVA_HOME.'
}

if (-not (Test-Path -LiteralPath $localRoot)) {
    [void](New-Item -ItemType Directory -Path $localRoot)
}

$resolvedLocalRoot = (Resolve-Path -LiteralPath $localRoot).Path
if (-not $resolvedLocalRoot.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Signing directory escaped the repository root: $resolvedLocalRoot"
}

Protect-LocalSigningDirectory -Path $resolvedLocalRoot

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $secretConfigPath)) {
    if (-not $Rotate) {
        throw 'Android signing material already exists. Re-run with -Rotate only when replacing the signing identity is intentional.'
    }
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$temporaryKeystore = Join-Path $resolvedLocalRoot ".visionguard-android-release.$timestamp.tmp.p12"
$temporaryConfig = Join-Path $resolvedLocalRoot ".visionguard-release.$timestamp.tmp.env"
$storePassword = New-SecureRandomText
$keyPassword = $storePassword
$keytool = Resolve-Keytool
$oldStorePassword = [Environment]::GetEnvironmentVariable('VG_SIGN_STORE_PASS', 'Process')
$oldKeyPassword = [Environment]::GetEnvironmentVariable('VG_SIGN_KEY_PASS', 'Process')

try {
    $env:VG_SIGN_STORE_PASS = $storePassword
    $env:VG_SIGN_KEY_PASS = $keyPassword

    & $keytool @(
        '-genkeypair',
        '-noprompt',
        '-keystore', $temporaryKeystore,
        '-storetype', 'PKCS12',
        '-alias', $alias,
        '-keyalg', 'RSA',
        '-keysize', '4096',
        '-sigalg', 'SHA256withRSA',
        '-validity', $ValidityDays,
        '-dname', 'CN=VisionGuard Android Release, OU=Release, O=XGWNJE, C=CN',
        '-storepass:env', 'VG_SIGN_STORE_PASS',
        '-keypass:env', 'VG_SIGN_KEY_PASS'
    )
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }

    $secretConfig = @(
        'VISIONGUARD_ANDROID_STORE_FILE=.local/visionguard-android-release.p12',
        "VISIONGUARD_ANDROID_KEY_ALIAS=$alias",
        "VISIONGUARD_ANDROID_STORE_PASSWORD=$storePassword",
        "VISIONGUARD_ANDROID_KEY_PASSWORD=$keyPassword"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText(
        $temporaryConfig,
        $secretConfig + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false))
    )

    if ($Rotate) {
        if (Test-Path -LiteralPath $keystorePath) {
            Copy-Item -LiteralPath $keystorePath -Destination "$keystorePath.backup-$timestamp"
        }
        if (Test-Path -LiteralPath $secretConfigPath) {
            Copy-Item -LiteralPath $secretConfigPath -Destination "$secretConfigPath.backup-$timestamp"
        }
    }

    Move-Item -LiteralPath $temporaryKeystore -Destination $keystorePath -Force
    Move-Item -LiteralPath $temporaryConfig -Destination $secretConfigPath -Force

    & $keytool @(
        '-list',
        '-v',
        '-keystore', $keystorePath,
        '-alias', $alias,
        '-storepass:env', 'VG_SIGN_STORE_PASS'
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Generated keystore verification failed with exit code $LASTEXITCODE."
    }

    Write-Host 'Android signing identity initialized.'
    Write-Host "Keystore: $keystorePath"
    Write-Host "Secret config: $secretConfigPath"
    Write-Host "Alias: $alias"
    Write-Host 'Legacy project keystores were preserved but are no longer selected while the shared config exists.'
}
finally {
    if ($null -eq $oldStorePassword) {
        Remove-Item Env:\VG_SIGN_STORE_PASS -ErrorAction SilentlyContinue
    }
    else {
        $env:VG_SIGN_STORE_PASS = $oldStorePassword
    }
    if ($null -eq $oldKeyPassword) {
        Remove-Item Env:\VG_SIGN_KEY_PASS -ErrorAction SilentlyContinue
    }
    else {
        $env:VG_SIGN_KEY_PASS = $oldKeyPassword
    }

    if (Test-Path -LiteralPath $temporaryKeystore) {
        Remove-Item -LiteralPath $temporaryKeystore -Force
    }
    if (Test-Path -LiteralPath $temporaryConfig) {
        Remove-Item -LiteralPath $temporaryConfig -Force
    }

    $storePassword = $null
    $keyPassword = $null
}
