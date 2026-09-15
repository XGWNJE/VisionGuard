---
name: visionguard-e2e
description: Run VisionGuard runtime, device, emulator, server smoke, or Windows multi-source person-detection verification and capture evidence.
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

# Host-side machine-checkable constraints: WindowsConfig.Tests (net10) and the WinForms multi-source coordinator tests (net472). No device, emulator or Server is required.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WindowsTests

# The configured number of independent visible browser windows must each produce at least one person detection; four remains the default regression baseline.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection -WpfSourceCount 6 -WpfFixtureDirectory <directory-with-at-least-6-person-images>

# WinForms uses net472 test windows and the production YOLOv5 CPU pipeline; the model must already be cached or supplied explicitly.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WinFormsPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WinFormsPersonDetection -WinFormsSourceCount 6 -WinFormsModelPath <model-path>

# Sustained multi-source run: report per-source frames, FPS, frame-interval P95 and stall detection instead of stopping at a frame count.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WinFormsPersonDetection -WinFormsSourceCount 6 -WinFormsDurationSeconds 300 -WinFormsModelPath <model-path>
```

`ServerSmoke` remains only as a compatibility alias for `ServerBuild`; do not describe it as an HTTP/WS runtime test.

## Evidence and boundaries

- Prefer authorized physical Android devices, then `VisionGuard_API36`, then `Pixel_3a_XL`. Specify `-DeviceSerial` when multiple physical devices are connected.
- Emulator runs must keep the window visible and close an emulator started by the script when the run ends.
- Android smoke proves installation, launch, foreground activity/process state, and absence of an observed crash during the capture window. It does not prove the detector→Server→receiver alert chain.
- WPF person-window smoke proves the requested number of independent `WindowHandle` captures, person inference, per-source FPS and runtime isolation. Four sources remain the default regression baseline. The fixture directory must hold at least as many person images as requested sources; the script fails with the actual count instead of lowering the source count. It does not prove dynamic-video behavior, alert delivery, long-running stability, or UI appearance.
- WinForms person-window smoke uses only .NET Framework 4.7.2 helpers and the production WinForms CPU coordinator. It proves independent `WindowHandle` capture, YOLOv5 person inference, per-source FPS, capacity warning and stop isolation for the requested source count; with `-WinFormsDurationSeconds` it also records per-source measured FPS, frame-interval P95 and stalled sources over a sustained run. It does not replace Win7 UI, WSS, long-running or production alert-chain acceptance.
- Both Windows person-detection modes use DPI-unaware net472 fixture windows deliberately. At 150%/200% this verifies that capture geometry follows the target window's PrintWindow client plane instead of DWM's stretched screen bounds.
- `WindowsTests` only runs host-side constraint tests; it proves nothing about devices, capture hardware or the alert chain.
- Full feature E2E requires deterministic test data plus every participating component; report missing links as skipped.
- Do not use the production VPS or public service unless the user explicitly requests it. Do not change versions or publish from this skill.
- Evidence is written under `artifacts/e2e/`. Report the selected device, build type, pass/fail/skip results, evidence paths, and any remaining manual visual checks.
