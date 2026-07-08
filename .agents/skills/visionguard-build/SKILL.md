---
name: visionguard-build
description: Use when VisionGuard needs build or compile verification for Server, Windows WinForms/WPF, Android Detector, Android Receiver, Windows-only, Android-only, or all targets.
---

# VisionGuard Build

Use this skill for VisionGuard build verification only. It compiles targets and reports evidence; it does not publish anything.

- `server/`
- `detector/windows-winforms/`
- `detector/windows-wpf/`
- `detector/android/`
- `receiver/android/`

## Boundaries

- Do not modify `VERSION`.
- Do not run `scripts/sync-version.js`, `scripts/release.js`, `scripts/publish-release.ps1`, or `scripts/bump-version.sh`.
- Do not package, upload, deploy, commit, or push unless the user explicitly asks.
- Release builds are required for client verification. Do not substitute Debug builds.
- If Android fails only because Java is missing from the current shell, use the repo script or set `JAVA_HOME` for that command only. Do not change global environment variables.

## Preferred Command

From the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1
```

Use `-Target` for a subset:

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Server
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Android
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
```

## Manual Fallback

If the script is unavailable, run these from the repo root:

```powershell
npm --prefix server run build

$msbuild = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.Component.MSBuild -find MSBuild\**\Bin\MSBuild.exe | Select-Object -First 1
& $msbuild detector\windows-winforms\VisionGuard.csproj /p:Configuration=Release /p:Platform=x64 /m

dotnet build detector\windows-wpf\VisionGuard.sln -c Release

$env:JAVA_HOME = "C:\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location detector\android; .\gradlew.bat assembleRelease; Pop-Location
Push-Location receiver\android; .\gradlew.bat assembleRelease; Pop-Location
```

## Expected Artifacts

- Server: `server/dist/index.js`
- WinForms: `detector/windows-winforms/bin/Release/VisionGuard.exe`
- WPF: `detector/windows-wpf/bin/x64/VisionGuard.exe`
- Android Detector: `detector/android/app/build/outputs/apk/release/app-release.apk`
- Android Receiver: `receiver/android/app/build/outputs/apk/release/app-release.apk`

## Reporting

Final response should include:

- command(s) run
- per-target pass/fail
- notable warnings count or blocking error
- artifact paths for successful targets
- explicit note that no version bump, release, deploy, commit, or push was performed unless explicitly requested

