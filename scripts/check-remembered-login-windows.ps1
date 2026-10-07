param()
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$probeRoot = Join-Path $repoRoot '.local/remember-login-windows-probe'
New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
$accountSource = [System.Security.SecurityElement]::Escape((Join-Path $repoRoot 'detector/windows-shared/Utils/AccountSession.cs'))
$project = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net472</TargetFramework><LangVersion>12</LangVersion><PlatformTarget>x64</PlatformTarget><EnableDefaultCompileItems>false</EnableDefaultCompileItems></PropertyGroup>
  <ItemGroup><Compile Include="$accountSource"/><Compile Include="Probe.cs"/><Reference Include="System.Web.Extensions"/><Reference Include="System.Net.Http"/><Reference Include="System.Security"/><PackageReference Include="Microsoft.NETFramework.ReferenceAssemblies" Version="1.0.3" PrivateAssets="all"/></ItemGroup>
</Project>
"@
$source = @'
using System;
using System.IO;
using System.Text;
using VisionGuard.Detector.Windows.Utils;
class Probe {
  static void Check(bool value) { if (!value) throw new Exception("Remembered-login assertion failed"); }
  static int Main(string[] args) {
    Environment.SetEnvironmentVariable("VISIONGUARD_ACCOUNT_DIR", Path.GetFullPath(args[0]));
    string endpoint = "https://remember-login.invalid", other = "https://other-login.invalid";
    string password = Guid.NewGuid().ToString("N") + "-private-fixture";
    Check(AccountSession.ReadRememberedLogin(endpoint) == null);
    AccountSession.SaveRememberedLogin(endpoint, new string('u', 64), new string('p', 256));
    Check(AccountSession.ReadRememberedLogin(endpoint).Password.Length == 256);
    AccountSession.SaveRememberedLogin(endpoint, "Test-user", password);
    var saved = AccountSession.ReadRememberedLogin(endpoint);
    Check(saved.Username == "test-user" && saved.Password == password);
    Check(!saved.ToString().Contains(password));
    string file = Path.Combine(AccountSession.Root, "remembered-login-" + AccountSession.Hash(endpoint) + ".bin");
    Check(!Encoding.UTF8.GetString(File.ReadAllBytes(file)).Contains(password));
    Check(AccountSession.ReadRememberedLogin(other) == null);
    AccountSession.SaveRememberedLogin(other, "other-user", "other-fixture-password");
    byte[] bytes = File.ReadAllBytes(file); bytes[0] ^= 1; File.WriteAllBytes(file, bytes);
    Check(AccountSession.ReadRememberedLogin(endpoint) == null);
    AccountSession.SaveRememberedLogin(endpoint, "test-user", password);
    AccountSession.SaveRememberedLogin(endpoint, null, null);
    Check(!File.Exists(file) && AccountSession.ReadRememberedLogin(endpoint) == null);
    Check(AccountSession.ReadRememberedLogin(other).Username == "other-user");
    try { AccountSession.SaveRememberedLogin(endpoint, "test-user", new string('x', 257)); return 2; } catch (ArgumentException) { }
    try { AccountSession.SaveRememberedLogin(endpoint, new string('x', 65), password); return 2; } catch (ArgumentException) { }
    try { AccountSession.SaveRememberedLogin(endpoint, "ab", password); return 2; } catch (ArgumentException) { }
    Check(!File.Exists(file)); AccountSession.SaveRememberedLogin(other, null, null);
    Console.WriteLine("PASS: current-user DPAPI, endpoint isolation, tamper rejection, opt-out, bounds, redaction");
    return 0;
  }
}
'@
[IO.File]::WriteAllText((Join-Path $probeRoot 'Probe.csproj'), $project, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $probeRoot 'Probe.cs'), $source, [Text.UTF8Encoding]::new($false))
dotnet build (Join-Path $probeRoot 'Probe.csproj') -c Release --nologo --verbosity quiet
if ($LASTEXITCODE -ne 0) { throw 'Remembered-login probe build failed' }
& (Join-Path $probeRoot 'bin/Release/net472/Probe.exe') (Join-Path $probeRoot 'accounts')
if ($LASTEXITCODE -ne 0) { throw 'Remembered-login probe failed' }
