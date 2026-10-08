#requires -Version 5.1
<#
.SYNOPSIS
Preview the outstanding resource-cleanup items; use -Apply for local deletion.
.DESCRIPTION
Only the three fingerprinted disposable files and two empty helper trees are
eligible. Sources, reusable tools, signing material and evidence are preserved.
Optional -InspectJournal is read-only and never changes the shared journal.
.EXAMPLE
& .\scripts\cleanup-pending-resources.ps1
.EXAMPLE
& .\scripts\cleanup-pending-resources.ps1 -Apply
.EXAMPLE
& .\scripts\cleanup-pending-resources.ps1 -InspectJournal -SshAlias xgwnje
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [string]$RepositoryRoot = '',
    [switch]$Apply,
    [switch]$InspectJournal,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$SshAlias = 'xgwnje'
)
$ErrorActionPreference = 'Stop'

# Find the checkout containing either scripts/ or the delivered .local/ copy.
if (!$RepositoryRoot) {
    $directory = $PSScriptRoot
    while ($directory) {
        if ((Test-Path -LiteralPath (Join-Path $directory 'VERSION') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $directory 'server/package.json') -PathType Leaf)) {
            $RepositoryRoot = $directory
            break
        }
        $directory = [IO.Path]::GetDirectoryName($directory)
    }
}
if (!$RepositoryRoot) { throw 'Cannot find a VisionGuard checkout; specify -RepositoryRoot.' }
$resolvedRoot = Resolve-Path -LiteralPath $RepositoryRoot
if ($resolvedRoot.Provider.Name -ne 'FileSystem') { throw 'RepositoryRoot must be a filesystem directory.' }
$root = [IO.Path]::GetFullPath($resolvedRoot.ProviderPath).TrimEnd('\', '/')
if (!(Test-Path -LiteralPath (Join-Path $root 'VERSION') -PathType Leaf) -or
    !(Test-Path -LiteralPath (Join-Path $root 'server/package.json') -PathType Leaf)) {
    throw 'The target is not a VisionGuard checkout.'
}
$gitRoot = @(& git -C $root rev-parse --show-toplevel 2>$null)
$gitRootExitCode = $LASTEXITCODE
if ($gitRootExitCode -ne 0 -or $gitRoot.Count -ne 1 -or
    [IO.Path]::GetFullPath($gitRoot[0]).TrimEnd('\', '/') -ine $root) {
    throw "RepositoryRoot must be the actual Git checkout root. Requested: $root; Git: $($gitRoot -join ', '); exit: $gitRootExitCode."
}

# Fingerprints from the read-only inventory on 2026-10-09. Changed files stay.
$targets = @(
    @{ Relative = '.local/release-0.6.6/pinned-probe.cjs'; Hash = 'E9A8B60F4D7C33BE4B619D04385A2BC023DB8F0788177BA5E19F40723862FE77' },
    @{ Relative = '.local/release-0.6.6/pinned-probe-address.json'; Hash = 'AA76BF515890CA51CC76CC454C0A1639C09C6AFB0776C7ABB489E21FB0868A04' },
    @{ Relative = '.local/release-0.6.6/preserved-python-cache/server-resource-maintenance.cpython-312.pyc'; Hash = 'D8431138486D9B3C649DF7D10C9B728F12BF7A8F6B474A46B644DA2D0300B354' },
    @{ Relative = '.local/acceptance-direct-input/instrumentation-classes'; Hash = '' },
    @{ Relative = '.local/acceptance-direct-input/instrumentation-dex'; Hash = '' }
)

function Assert-PlainPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = $root + [IO.Path]::DirectorySeparatorChar
    if (!$absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Path leaves the selected checkout.'
    }
    $ancestor = $absolute
    while ($ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
        if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Linked path or junction; preserved.'
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}

function Inspect-Target($Target) {
    $absolute = [IO.Path]::GetFullPath((Join-Path $root $Target.Relative))
    $result = [pscustomobject]@{
        Path = $Target.Relative; Bytes = 0L; Status = 'Preserved'; Detail = ''
        Absolute = $absolute; Eligible = $false; Directories = @()
    }
    try {
        Assert-PlainPath $absolute
        $tracked = @(& git -C $root -c core.quotepath=false ls-files --cached -- $Target.Relative)
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read Git index; preserved.' }
        if ($tracked.Count) { throw 'Contains a tracked file; preserved.' }
        & git -C $root check-ignore -q -- $Target.Relative
        if ($LASTEXITCODE -ne 0) { throw 'Not Git-ignored; preserved.' }
        if (!(Test-Path -LiteralPath $absolute)) {
            $result.Status = 'Absent'
            return $result
        }
        $item = Get-Item -LiteralPath $absolute -Force
        if ($Target.Hash) {
            if ($item.PSIsContainer) { throw 'Expected a file; preserved.' }
            $stream = [IO.File]::OpenRead($absolute)
            $algorithm = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '') }
            finally { $algorithm.Dispose(); $stream.Dispose() }
            if ($hash -ine $Target.Hash) {
                throw 'SHA256 changed since inventory; preserved.'
            }
            $result.Bytes = $item.Length
        } else {
            if (!$item.PSIsContainer) { throw 'Expected an empty directory tree; preserved.' }
            $directories = New-Object 'System.Collections.Generic.List[string]'
            $stack = New-Object 'System.Collections.Generic.Stack[string]'
            $stack.Push($absolute)
            while ($stack.Count) {
                $current = $stack.Pop()
                Assert-PlainPath $current
                $directories.Add($current)
                foreach ($entry in (New-Object IO.DirectoryInfo($current)).EnumerateFileSystemInfos()) {
                    if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                        throw 'Linked descendant; entire directory tree preserved.'
                    }
                    if (!($entry.Attributes -band [IO.FileAttributes]::Directory)) {
                        throw 'Directory contains a file; entire tree preserved.'
                    }
                    $stack.Push($entry.FullName)
                }
            }
            $result.Directories = @($directories | Sort-Object Length -Descending)
        }
        $result.Eligible = $true
        $result.Status = 'Preview'
    } catch { $result.Detail = $_.Exception.Message }
    return $result
}

function Assert-NoActiveTask {
    try {
        $busy = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ProcessId -ne $PID -and $_.CommandLine -match
                '(pinned-probe|server-resource-maintenance|build-input-helper|DirectInstrumentation|instrumentation-(classes|dex))'
        })
    } catch { throw 'Cannot check active processes. Deletion stopped; retry when process inspection is available.' }
    if ($busy.Count) {
        $ids = ($busy | ForEach-Object { $_.ProcessId }) -join ', '
        throw "Related task processes are active (PID: $ids). Deletion stopped; finish those tasks first."
    }
}

function Show-Plan($Items) {
    $Items | Select-Object Path, Bytes, Status | Format-Table -AutoSize -Wrap
    foreach ($item in $Items) {
        if ($item.Detail) { Write-Output ("{0}: {1}" -f $item.Path, $item.Detail) }
    }
}

Write-Output "Repository: $root"
$plan = @($targets | ForEach-Object { Inspect-Target $_ })
Show-Plan $plan
$eligibleBytes = [long](($plan | Where-Object Eligible | Measure-Object Bytes -Sum).Sum)
Write-Output ("Eligible file bytes: {0}. Apply: {1}." -f $eligibleBytes, [bool]$Apply)

if ($InspectJournal) {
    Write-Output "Shared journal: read-only inspection via SSH alias $SshAlias."
    # No log contents, private config, rotation, vacuum, restart or sudo writes.
    $remoteCommand = @'
set -eu
df -h /
journalctl --disk-usage
systemctl show systemd-journald.service -p ActiveState -p SubState
systemd-analyze cat-config systemd/journald.conf | awk '/^[[:space:]]*\[Journal\]/ || /^[[:space:]]*#?[[:space:]]*(Storage|SystemMaxUse|SystemKeepFree|SystemMaxFileSize|RuntimeMaxUse|RuntimeKeepFree|MaxRetentionSec|MaxFileSec)[[:space:]]*=/ {print}'
'@
    & ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes $SshAlias ($remoteCommand -replace "`r", '')
    if ($LASTEXITCODE -ne 0) { throw 'Journal inspection failed. No local deletion was performed.' }
    Write-Output 'Journal limits and deletion require separate Server-infra authorization; this script has no journal write action.'
}

$refused = @($plan | Where-Object { $_.Status -eq 'Preserved' }).Count
if ($Apply) {
    if (!$WhatIfPreference -and @($plan | Where-Object Eligible).Count) { Assert-NoActiveTask }
    try {
        for ($index = 0; $index -lt $targets.Count; $index++) {
            if (!$plan[$index].Eligible) { continue }
            $current = Inspect-Target $targets[$index]
            $plan[$index] = $current
            if (!$current.Eligible) { continue }
            try {
                if ($PSCmdlet.ShouldProcess($current.Absolute, 'Delete the inventoried disposable file or empty directory tree')) {
                    Assert-NoActiveTask
                    if ($targets[$index].Hash) {
                        # Refuse a currently open file; never stop another task.
                        $handle = [IO.File]::Open($current.Absolute, 'Open', 'Read', 'None')
                        $handle.Dispose()
                        Remove-Item -LiteralPath $current.Absolute -Force
                    } else {
                        foreach ($directory in $current.Directories) {
                            Assert-PlainPath $directory
                            # Non-recursive delete refuses files created after inspection.
                            [IO.Directory]::Delete($directory, $false)
                        }
                    }
                    $current.Status = 'Deleted'
                } else { $current.Status = 'WhatIfOrDeclined' }
            } catch {
                $current.Status = 'Failed'; $current.Detail = $_.Exception.Message
                throw
            }
        }
    } finally {
        Show-Plan $plan
        $deletedBytes = [long](($plan | Where-Object { $_.Status -eq 'Deleted' } | Measure-Object Bytes -Sum).Sum)
        Write-Output "Deleted file bytes: $deletedBytes (logical size; physical disk reclamation not measured)."
    }
    $refused = @($plan | Where-Object { $_.Status -eq 'Preserved' }).Count
}
if ($refused) { throw "$refused target(s) preserved by safety checks; review the details above." }
