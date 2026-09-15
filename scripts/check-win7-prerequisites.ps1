[CmdletBinding()]
param(
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path

trap {
    Write-Host ("Win7 prerequisite check could not complete: {0}" -f $_.Exception.Message) -ForegroundColor Red
    exit 2
}

function New-StringObjectDictionary {
    return New-Object "System.Collections.Generic.Dictionary[string,object]"
}

function Test-HotFixInstalled {
    param([string]$Id)

    try {
        return [bool](Get-HotFix -Id $Id -ErrorAction Stop)
    }
    catch {
        return $false
    }
}

function Get-RegistryDword {
    param(
        [string]$Path,
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }

    try {
        return [int](Get-ItemProperty -LiteralPath $Path -Name $Name -ErrorAction Stop).$Name
    }
    catch {
        return $null
    }
}

function New-CheckResult {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Actual,
        [string]$Expected
    )

    $item = New-StringObjectDictionary
    $item.Add("name", $Name)
    $item.Add("passed", $Passed)
    $item.Add("actual", $Actual)
    $item.Add("expected", $Expected)
    # PowerShell 2 enumerates IDictionary output; the unary comma keeps it as one JSON object.
    return ,$item
}

$os = Get-WmiObject Win32_OperatingSystem
$isWin7Sp1X64 = ($os.Version -like "6.1.*") -and ([int]$os.ServicePackMajorVersion -ge 1) -and ($env:PROCESSOR_ARCHITECTURE -eq "AMD64")

$netRelease = Get-RegistryDword -Path "HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full" -Name "Release"
$tlsPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Internet Settings\WinHttp"
$tlsWowPath = "HKLM:\SOFTWARE\Wow6432Node\Microsoft\Windows\CurrentVersion\Internet Settings\WinHttp"
$tlsDefault = Get-RegistryDword -Path $tlsPath -Name "DefaultSecureProtocols"
$tlsWowDefault = Get-RegistryDword -Path $tlsWowPath -Name "DefaultSecureProtocols"

$checks = @(
    (New-CheckResult "windows-7-sp1-x64" $isWin7Sp1X64 ("Version={0}; SP={1}; Arch={2}" -f $os.Version, $os.ServicePackMajorVersion, $env:PROCESSOR_ARCHITECTURE) "Windows 7 SP1 x64"),
    (New-CheckResult "KB4490628" (Test-HotFixInstalled "KB4490628") "hotfix query" "installed"),
    (New-CheckResult "KB4474419" (Test-HotFixInstalled "KB4474419") "hotfix query" "installed"),
    (New-CheckResult "KB3140245" (Test-HotFixInstalled "KB3140245") "hotfix query" "installed"),
    (New-CheckResult "winhttp-tls12-x64" (($tlsDefault -band 0x800) -eq 0x800) ([string]$tlsDefault) "DefaultSecureProtocols includes 0x800"),
    (New-CheckResult "winhttp-tls12-x86" (($tlsWowDefault -band 0x800) -eq 0x800) ([string]$tlsWowDefault) "DefaultSecureProtocols includes 0x800"),
    (New-CheckResult "KB4019990" (Test-HotFixInstalled "KB4019990") "hotfix query" "installed"),
    (New-CheckResult "dotnet-framework-4.7.2" (($netRelease -ne $null) -and ($netRelease -ge 461808)) ([string]$netRelease) "Release >= 461808")
)

$passed = @($checks | Where-Object { -not $_["passed"] }).Count -eq 0
Add-Type -AssemblyName System.Web.Extensions
$serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$serializer.MaxJsonLength = 1048576
$checkJson = @($checks | ForEach-Object {
    $passedJson = if ($_['passed']) { 'true' } else { 'false' }
    '{"name":' + $serializer.Serialize([string]$_['name']) +
        ',"passed":' + $passedJson +
        ',"actual":' + $serializer.Serialize([string]$_['actual']) +
        ',"expected":' + $serializer.Serialize([string]$_['expected']) + '}'
}) -join ','
$json = '{"schemaVersion":1' +
    ',"checkedAtUtc":' + $serializer.Serialize([DateTime]::UtcNow.ToString('o')) +
    ',"computerName":' + $serializer.Serialize([string]$env:COMPUTERNAME) +
    ',"passed":' + $(if ($passed) { 'true' } else { 'false' }) +
    ',"checks":[' + $checkJson + ']}'

if ([string]::IsNullOrEmpty($ReportPath)) {
    $ReportPath = Join-Path $scriptDirectory "..\artifacts\e2e\win7-prerequisites.json"
}

$resolvedReportPath = [System.IO.Path]::GetFullPath($ReportPath)
$reportDirectory = Split-Path -Parent $resolvedReportPath
if (-not (Test-Path -LiteralPath $reportDirectory)) {
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
}
[System.IO.File]::WriteAllText($resolvedReportPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ("Win7 prerequisites: {0}" -f $(if ($passed) { "PASS" } else { "FAIL" }))
foreach ($check in $checks) {
    Write-Host ("[{0}] {1}: {2}" -f $(if ($check["passed"]) { "PASS" } else { "FAIL" }), $check["name"], $check["actual"])
}
Write-Host ("Report: {0}" -f $resolvedReportPath)

if (-not $passed) {
    exit 1
}
