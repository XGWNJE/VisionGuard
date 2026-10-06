#requires -Version 5.1
<#
.SYNOPSIS
Preview narrowly allowlisted repository cleanup; add -Apply to delete.
.DESCRIPTION
Keeps sources, credentials, models, signed/test packages, evidence, dependencies
and shared caches. Build intermediates require -IncludeBuildIntermediates.
Never follows links, stops processes, changes Git state or contacts devices/VPS.
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [string[]]$RepositoryRoot = @(),
    [switch]$IncludeBuildIntermediates,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false)
if (!$RepositoryRoot.Count) { $RepositoryRoot = @((Split-Path $PSScriptRoot -Parent)) }

function Assert-PlainPath([string]$Path, [string]$Root) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = $Root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($absolute -ne $Root -and !$absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path leaves repository: $absolute"
    }
    $ancestor = $absolute
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked path refused: $ancestor" }
        }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if ($parent -eq $ancestor) { break }
        $ancestor = $parent
    }
}

function Read-Tree([string]$Path) {
    $files = New-Object 'System.Collections.Generic.List[System.IO.FileInfo]'
    $stack = New-Object 'System.Collections.Generic.Stack[string]'
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked target refused: $Path" }
    if (!$item.PSIsContainer) { $files.Add($item) }
    else {
        $stack.Push($Path)
        while ($stack.Count) {
            $directory = New-Object IO.DirectoryInfo($stack.Pop())
            foreach ($entry in $directory.EnumerateFileSystemInfos()) {
                if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked descendant refused: $($entry.FullName)" }
                if ($entry.Attributes -band [IO.FileAttributes]::Directory) { $stack.Push($entry.FullName) }
                else { $files.Add([IO.FileInfo]$entry) }
            }
        }
    }
    return [pscustomobject]@{ Files = $files; IsDirectory = $item.PSIsContainer; Bytes = [long](($files | Measure-Object Length -Sum).Sum) }
}

function Read-Tracked([string]$Root) {
    $paths = @(& git -C $Root -c core.quotepath=false ls-files --cached)
    if ($LASTEXITCODE -ne 0) { throw "Cannot read tracked files: $Root" }
    return $paths
}

function Assert-Candidate($Candidate, [string[]]$Tracked) {
    Assert-PlainPath $Candidate.AbsolutePath $Candidate.Root
    $relative = $Candidate.RelativePath
    if (@($Tracked | Where-Object { $_ -eq $relative -or $_.StartsWith($relative + '/', [StringComparison]::OrdinalIgnoreCase) }).Count) {
        throw 'Contains a tracked file; preserved'
    }
    & git -C $Candidate.Root check-ignore -q -- $relative
    if ($LASTEXITCODE -ne 0) { throw 'Not Git-ignored; preserved' }
    $tree = Read-Tree $Candidate.AbsolutePath
    foreach ($file in $tree.Files) {
        if ($Candidate.Kind -eq 'HelperClasses' -and $file.Extension -ne '.class') { throw 'Unexpected helper file; preserved' }
        if ($Candidate.Kind -eq 'HelperDex' -and $file.Extension -ne '.dex') { throw 'Unexpected helper file; preserved' }
        if ($Candidate.Kind -eq 'BuildIntermediate' -and
            ($file.Extension -match '^\.(apk|aab|onnx|p12|pfx|jks|keystore|pftrace)$' -or
             $file.Name -match '(^\.env($|\.)|local\.properties$|keystore\.properties$|accounts\.json$)')) {
            throw 'Contains a package, model, credential or trace; preserved'
        }
    }
    return $tree
}

function Add-Candidate([string]$Root, [string]$Relative, [string]$Kind, [string]$Reason, [string[]]$Tracked) {
    $absolute = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    if (!(Test-Path -LiteralPath $absolute)) { return }
    $candidate = [pscustomobject]@{
        Root = $Root; RelativePath = $Relative; AbsolutePath = $absolute; Kind = $Kind
        Reason = $Reason; Bytes = 0L; Files = 0; Eligible = $false; Status = 'Preserved'; Detail = ''
    }
    try {
        $tree = Assert-Candidate $candidate $Tracked
        $candidate.Bytes = $tree.Bytes; $candidate.Files = $tree.Files.Count
        $candidate.Eligible = $true; $candidate.Status = 'Preview'
    } catch { $candidate.Detail = $_.Exception.Message }
    $script:candidates.Add($candidate)
}

$candidates = New-Object 'System.Collections.Generic.List[object]'
$roots = New-Object 'System.Collections.Generic.List[string]'
foreach ($requestedRoot in $RepositoryRoot) {
    $root = (Resolve-Path -LiteralPath $requestedRoot).Path.TrimEnd('\', '/')
    Assert-PlainPath $root $root
    if (!(Test-Path -LiteralPath (Join-Path $root 'VERSION')) -or !(Test-Path -LiteralPath (Join-Path $root 'server/package.json'))) {
        throw "Not a VisionGuard checkout: $root"
    }
    if (@($roots | Where-Object { $_ -ieq $root }).Count) { continue }
    $roots.Add($root)
    $tracked = @(Read-Tracked $root)
    foreach ($helper in @('.local/direct-input', '.local/acceptance-direct-input')) {
        # Exact outputs from the reusable helper build script, including its own disposable signing key.
        foreach ($name in @('helper-aligned.apk','helper-unsigned.apk','instrumentation-classes.jar',
            'helper-keystore.p12','signing-password.txt','vg-direct-input.apk','vg-direct-input.apk.idsig')) {
            Add-Candidate $root "$helper/$name" 'HelperOutput' 'Regenerated by input-helper build; source files are retained' $tracked
        }
        Add-Candidate $root "$helper/instrumentation-classes" 'HelperClasses' 'Generated helper classes only' $tracked
        Add-Candidate $root "$helper/instrumentation-dex" 'HelperDex' 'Generated helper dex only' $tracked
    }
    if ($IncludeBuildIntermediates) {
        foreach ($project in ($tracked | Where-Object { $_.EndsWith('.csproj', [StringComparison]::OrdinalIgnoreCase) })) {
            $relative = ((Split-Path $project -Parent).Replace('\','/') + '/obj').TrimStart('/')
            Add-Candidate $root $relative 'BuildIntermediate' 'MSBuild intermediate; runtime bin is retained' $tracked
        }
        foreach ($component in @('detector','receiver','notifier')) {
            foreach ($directory in @('intermediates','tmp','generated','kotlin')) {
                Add-Candidate $root "$component/android/app/build/$directory" 'BuildIntermediate' 'Gradle intermediate; outputs/reports/test-results are retained' $tracked
            }
        }
    }
}

if ($Apply -and !$WhatIfPreference -and $IncludeBuildIntermediates -and @($candidates | Where-Object { $_.Eligible -and $_.Kind -eq 'BuildIntermediate' }).Count) {
    $builders = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(MSBuild|csc|vbc)\.exe$' -or
        $_.Name -eq 'dotnet.exe' -and $_.CommandLine -match '(build|msbuild|test|publish)' -or
        $_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match '(GradleDaemon|KotlinCompileDaemon|GradleWrapperMain)'
    })
    if ($builders.Count) { throw 'A build/Gradle/Kotlin process is active. No files were deleted; finish or stop builds before intermediate cleanup.' }
}

$reportDirectory = Join-Path $roots[0] '.local'
Assert-PlainPath $reportDirectory $roots[0]
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
$reportPath = Join-Path $reportDirectory 'repository-cleanup-plan.json'
Assert-PlainPath $reportPath $roots[0]
if (@(Read-Tracked $roots[0]) -contains '.local/repository-cleanup-plan.json') { throw 'Report path is tracked; refusing to overwrite it' }
$report = [ordered]@{
    CreatedAt = [DateTime]::UtcNow.ToString('o'); Apply = [bool]$Apply
    IncludeBuildIntermediates = [bool]$IncludeBuildIntermediates; Roots = $roots.ToArray()
    Protected = @('source/tracked files','reusable tools','models','release signing/credentials/accounts',
        'signed/test packages and runtime bin','evidence/reports/test-results','node_modules',
        '.local/acceptance-gradle','.local/android-maven-cache','other .local data','Git state','devices and VPS')
    Candidates = $candidates.ToArray()
}
function Save-Report { [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 7), $utf8) }
Save-Report
try {
    if ($Apply) {
        foreach ($candidate in $candidates) {
            if (!$candidate.Eligible) { continue }
            # Recheck the live tree and Git index immediately before each exact-path deletion.
            try {
                $tree = Assert-Candidate $candidate @(Read-Tracked $candidate.Root)
                if ($PSCmdlet.ShouldProcess($candidate.AbsolutePath, 'Delete allowlisted generated output')) {
                    Remove-Item -LiteralPath $candidate.AbsolutePath -Force -Recurse:$tree.IsDirectory
                    $candidate.Status = 'Deleted'
                } else { $candidate.Status = 'WhatIfOrDeclined' }
            } catch {
                $candidate.Status = 'Failed'; $candidate.Detail = $_.Exception.Message
                throw
            }
        }
    }
} finally { Save-Report }
foreach ($root in $roots) {
    Write-Output "Repository: $root"
    $candidates | Where-Object { $_.Root -eq $root } | Select-Object RelativePath,@{Name='MiB';Expression={[Math]::Round($_.Bytes/1MB,2)}},Status,Detail | Format-Table -AutoSize -Wrap
}
$eligibleBytes = [long](($candidates | Where-Object Eligible | Measure-Object Bytes -Sum).Sum)
Write-Output ("Eligible: {0:N2} MiB. Apply: {1}. Report: {2}" -f ($eligibleBytes/1MB),[bool]$Apply,$reportPath)
