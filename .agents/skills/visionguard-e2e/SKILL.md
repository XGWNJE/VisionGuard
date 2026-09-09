---
name: visionguard-e2e
description: Run VisionGuard runtime, device, emulator, server smoke, or WPF multi-source person-detection verification and capture evidence.
---

# VisionGuard E2E

Use this skill when runtime behavior or device evidence matters. Use `visionguard-build` for compile-only requests.

## Maintained modes

```powershell
# Live tool/device inventory; no app installation or emulator launch.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover

# Server compile/artifact smoke. This is not a running HTTP/WS E2E test.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ServerBuild

# Install, launch, and capture evidence from an Android app. Debug is the default runtime build.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode AndroidDetectorSmoke -Device Auto
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode AndroidReceiverSmoke -Device Auto

# Three ImageFile sources must each produce at least one person detection.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
```

`ServerSmoke` remains only as a compatibility alias for `ServerBuild`; do not describe it as an HTTP/WS runtime test.

## Evidence and boundaries

- Prefer authorized physical Android devices, then `VisionGuard_API36`, then `Pixel_3a_XL`. Specify `-DeviceSerial` when multiple physical devices are connected.
- Emulator runs must keep the window visible and close an emulator started by the script when the run ends.
- Android smoke proves installation, launch, foreground activity/process state, and absence of an observed crash during the capture window. It does not prove the detector→Server→receiver alert chain.
- WPF person-image smoke proves the ImageFile inference path and multi-source isolation. It does not prove real window capture.
- Full feature E2E requires deterministic test data plus every participating component; report missing links as skipped.
- Do not use the production VPS or public service unless the user explicitly requests it. Do not change versions or publish from this skill.
- Evidence is written under `artifacts/e2e/`. Report the selected device, build type, pass/fail/skip results, evidence paths, and any remaining manual visual checks.
