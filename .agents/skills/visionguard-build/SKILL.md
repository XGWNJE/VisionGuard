---
name: visionguard-build
description: Build and verify one or more VisionGuard targets without packaging, publishing, deploying, or changing versions.
---

# VisionGuard Build

Compile the requested targets through the maintained project script and verify the expected artifacts exist. 组件名称统一为 视觉检测（Windows）、视觉驻留、视觉检测（Android）、视觉告警、视觉中继；脚本目标标识保持原值。

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

- 视觉中继： `server/dist/index.js`
- Windows unified package: `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`
- Internal modern runtime: `detector/windows-package/bin/Release/runtimes/modern/VisionGuard.Detector.Windows.exe`
- Internal legacy runtime: `detector/windows-package/bin/Release/runtimes/legacy/VisionGuard.Detector.Windows.exe`
- 视觉驻留： `detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe`
- 视觉检测（Android）： `detector/android/app/build/outputs/apk/release/app-release.apk`
- 视觉告警： `receiver/android/app/build/outputs/apk/release/app-release.apk`

Report the command, per-target result, artifact paths, important warnings, and any skipped target. State explicitly that no version, release, deployment, commit, or push action occurred unless the user requested it.

