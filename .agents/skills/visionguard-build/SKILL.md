---
name: visionguard-build
description: Build and verify one or more VisionGuard targets without packaging, publishing, deploying, or changing versions.
---

# VisionGuard Build

Compile the requested targets through the maintained project script and verify the expected artifacts exist. 组件名与构建目标的对应关系见 [命名规范](../../../../docs/15-命名规范.md)。

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

The script currently accepts `All`, `Server`, `Windows`, `WPF`, `WindowsResident`, `Android`, `AndroidDetector`, and `AndroidNotifier`. `Android` includes the camera and notification Android projects.

## Expected Artifacts

- 统一服务及 Web： `server/dist/index.js`、`server/dist/console/index.html`；构建前分别安装 `server/` 与 `receiver/web/` 的 npm 依赖。
- Windows unified package: `detector/windows-package/bin/Release/VisionGuard.Detector.Windows.exe`
- Internal modern runtime: `detector/windows-package/bin/Release/runtimes/modern/VisionGuard.Detector.Windows.exe`
- Internal legacy runtime: `detector/windows-package/bin/Release/runtimes/legacy/VisionGuard.Detector.Windows.exe`
- 视觉推理节点内部驻留程序： `detector/windows-resident/bin/Release/net472/VisionGuard.Resident.Windows.exe`
- 相机推流节点： `detector/android/app/build/outputs/apk/release/app-release.apk`
- 通知节点： `notifier/android/app/build/outputs/apk/release/app-release.apk`

Report the command, per-target result, artifact paths, important warnings, and any skipped target. State explicitly that no version, release, deployment, commit, or push action occurred unless the user requested it.

