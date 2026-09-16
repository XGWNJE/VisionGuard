---
name: visionguard-build
description: Build and verify one or more VisionGuard targets without packaging, publishing, deploying, or changing versions.
---

# VisionGuard Build

Compile the requested targets through the maintained project script and verify the expected artifacts exist. The five targets are Server, WPF, Windows Resident, Android Detector, and Android Receiver.

## Boundaries

- Do not modify `VERSION`.
- Do not run version synchronization or the publish pipeline from this build-only workflow.
- Do not package, upload, deploy, commit, or push unless the user explicitly asks.
- Release builds are required for client verification. Do not substitute Debug builds.
- Android Release builds are signed by default from the shared `.local/visionguard-release.env`; missing signing material must fail closed. Use `-PVISIONGUARD_ALLOW_UNSIGNED_RELEASE=true` only for an explicitly compile-only unsigned validation, never for a distributable package.
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
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target WindowsResident
```

The script currently accepts `All`, `Server`, `Windows`, `WPF`, `WindowsResident`, `Android`, `AndroidDetector`, and `AndroidReceiver`.

## Expected Artifacts

- Server: `server/dist/index.js`
- WPF (modern / Windows 10+): `detector/windows-wpf/bin/x64/modern/VisionGuard.exe`
- WPF (legacy / Windows 7 SP1): `detector/windows-wpf/bin/x64/legacy/VisionGuard.exe`
- Windows Resident: `detector/windows-resident/bin/Release/net472/VisionGuard.Resident.exe`
- Android Detector: `detector/android/app/build/outputs/apk/release/app-release.apk`
- Android Receiver: `receiver/android/app/build/outputs/apk/release/app-release.apk`

Report the command, per-target result, artifact paths, important warnings, and any skipped target. State explicitly that no version, release, deployment, commit, or push action occurred unless the user requested it.

