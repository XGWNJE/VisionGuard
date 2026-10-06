param([string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$source = Join-Path $RepositoryRoot 'scripts/publish-release.ps1'
$tokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Release script parse failed.' }
foreach ($name in @('Invoke-GitHubSteps', 'Get-GitHubReleaseByTag', 'Assert-GitHubUploadedAssets', 'Get-Sha256', 'Test-TargetEnabled')) {
    $function = $ast.Find({ param($item) $item -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $item.Name -eq $name }, $true)
    if (-not $function) { throw "Production function missing: $name" }
    . ([ScriptBlock]::Create($function.Extent.Text))
}
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('vg-release-draft-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $temporary)
try {
    $package = Join-Path $temporary 'VisionGuard-WPF-v0.7.0.zip'
    [IO.File]::WriteAllText($package, 'upload fixture')
    $Version = '0.7.0'; $GitHubRepository = 'fixture/repository'; $PushGitHub = $false; $CreateTag = $false; $CreateGitHubRelease = $true
    function Assert-GitHubReleaseNotes { return 'fixture-notes.md' }
    function Test-NativeSuccess { return $script:alreadyExists }
    function Invoke-Native { param($FilePath, $Arguments) [void]$script:calls.Add(($Arguments -join ' ')) }
    function Verify-OnlineWebSockets {
        if ($script:case -eq 'media-ingress-failure') { throw 'media ingress HTTP 404' }
        [void]$script:calls.Add('websocket entrypoints verified')
    }
    function Invoke-NativeCapture {
        param($FilePath, $Arguments)
        if ($Arguments[0] -eq 'release' -and $Arguments[1] -eq 'view') {
            if ($script:case -eq 'invalid-id') { return 'invalid' }
            return '23'
        }
        if ($Arguments[0] -eq 'api' -and $Arguments[1] -eq 'repos/fixture/repository/releases/23') { return ($script:release | ConvertTo-Json -Depth 8) }
        throw 'Drafts are unavailable through the published-release tag endpoint.'
    }
    foreach ($script:case in @('valid', 'existing-draft', 'draft-only', 'draft-only-wrong-digest', 'wrong-digest', 'extra-asset', 'already-public', 'wrong-tag', 'invalid-id', 'media-ingress-failure')) {
        $DraftOnly = $case -like 'draft-only*'
        $script:calls = New-Object 'System.Collections.Generic.List[string]'
        $script:alreadyExists = $case -in @('existing-draft', 'already-public')
        $script:release = [pscustomobject]@{ tag_name = 'v0.7.0'; draft = $case -ne 'already-public'; assets = @([pscustomobject]@{ name = [IO.Path]::GetFileName($package); state = 'uploaded'; size = (Get-Item $package).Length; digest = 'sha256:' + (Get-Sha256 $package) }) }
        if ($case -eq 'wrong-tag') { $script:release.tag_name = 'v0.8.0' }
        if ($case -in @('wrong-digest', 'draft-only-wrong-digest')) { $script:release.assets[0].digest = 'sha256:' + ('0' * 64) }
        if ($case -eq 'extra-asset') { $script:release.assets += [pscustomobject]@{ name = 'unexpected.apk' } }
        $failed = $false
        try { Invoke-GitHubSteps -Artifacts @([pscustomobject]@{ Path = $package }) } catch { $failed = $true }
        $published = @($script:calls | Where-Object { $_ -match '--draft=false' }).Count -gt 0
        if ($case -in @('valid', 'existing-draft')) {
            if ($failed -or -not $published -or $script:calls[0] -notmatch '--draft' -or $script:calls[1] -notmatch 'release upload') { throw 'Valid upload order failed.' }
            if ($script:calls[2] -ne 'websocket entrypoints verified' -or $script:calls[3] -notmatch '--draft=false') { throw 'Published before verifying WebSocket ingress.' }
        } elseif ($case -eq 'draft-only') {
            if ($failed -or $published -or $script:calls.Count -ne 2 -or $script:calls[1] -notmatch 'release upload') { throw 'Draft-only upload was not retained.' }
        } elseif (-not $failed -or $published) { throw "Failed validation was published: $case" }
    }
    Write-Output 'PASS: production release functions create/resume drafts by ID, verify uploads, reject corrupt/extra/public/tag-mismatched assets, then publish; all external commands mocked.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $allowed = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\vg-release-draft-'
    if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Temporary cleanup path escaped its expected prefix.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
