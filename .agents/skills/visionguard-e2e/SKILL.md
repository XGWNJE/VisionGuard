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

# The script opens the configured number of independent visible fixture windows and requires at least one person detection per source through a real WindowHandle capture; four remains the default regression baseline.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection -WpfSourceCount 6 -WpfFixtureDirectory <directory-with-at-least-6-person-images>

# The ONNX model must already be cached; override the fixture directory, model, threshold and report path for other runs.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfPersonDetection -WpfSourceCount 2 -WpfModelPath <model-path> -WpfConfidenceThreshold 0.25 -WpfReportPath <report-path>
```

`ServerSmoke` remains only as a compatibility alias for `ServerBuild`; do not describe it as an HTTP/WS runtime test.

## Evidence and boundaries

- Prefer authorized physical Android devices, then `VisionGuard_API36`, then `Pixel_3a_XL`. Specify `-DeviceSerial` when multiple physical devices are connected.
- Emulator runs must keep the window visible and close an emulator started by the script when the run ends.
- Android smoke proves installation, launch, foreground activity/process state, and absence of an observed crash during the capture window. It does not prove the detector→Server→receiver alert chain.
- WPF person-window smoke opens one visible `System.Windows.Forms` fixture window per requested source inside the running PowerShell process, captures each source through its real `WindowHandle`, and requires `passed` plus at least one `person` frame per source in the smoke report. It also proves per-source FPS at or above 2.5, source isolation, capacity warning and backend failure isolation. Four sources remain the default regression baseline. The fixture directory must hold at least as many person images as requested sources; the script fails with the actual count instead of lowering the source count. `-WpfModelPath`, `-WpfConfidenceThreshold` and `-WpfReportPath` default to `artifacts\v0\yolo26n_320.onnx`, `0.25` and `artifacts\e2e\wpf-window-person-detection.json`. It does not prove dynamic-video behavior, alert delivery, long-running stability, or UI appearance.
- Fixture windows are created without any DPI-awareness declaration, so the Windows PowerShell host this skill uses keeps them DPI-unaware; at 150%/200% this verifies that capture geometry follows the target window's PrintWindow client plane instead of DWM's stretched screen bounds.
- Full feature E2E requires deterministic test data plus every participating component; report missing links as skipped.
- Do not use the production VPS or public service unless the user explicitly requests it. Do not change versions or publish from this skill.
- Evidence is written under `artifacts/e2e/`. Report the selected device, build type, pass/fail/skip results, evidence paths, and any remaining manual visual checks.
