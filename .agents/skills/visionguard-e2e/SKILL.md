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

# Both inference profiles: build the parser-contract probe per profile and require a real business label
# (default `person`) out of a real fixture image. No window, no GPU; the legacy profile runs on any Windows host.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfParserContract
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode WpfParserContract -WpfLegacyModelPath <yolov5-320.onnx> -WpfModernModelPath <yolo26-320.onnx> -WpfContractImagePath <person-image>

# Resident lifecycle: start an isolated server, run each profile's real detector in `--resident-launch`
# mode, and require the server's device list to report `components.resident = running` for that device.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ResidentLaunch

# Model download: drive the same ViewModel path the "download" button uses, against the real production
# server. Writes one model (~10-100 MB) into the real %APPDATA%\VisionGuard\models\ cache.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ModelDownload
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ModelDownload -ModelDownloadLegacyProfile -ModelDownloadKey yolov5nu_320

# Source settings contract: parameters persist on change (there is no save button), the capture-target
# reset clears window/region/mask together. Uses an isolated settings file; opens no window.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode SourceAutoSave

# Card layout contract: solve the real-time preview grid for 1/2/4 visible cards across the minimum
# window (1200x880), 1420x880 and 1920x1080 card-area sizes, and assert the 1:1.2~1.2:1 card aspect
# clamp, that every count fits without clipping, the minimum window's 1:1 picture short edge, wide/flat
# and narrow/tall containers, determinism and degenerate input. Uses `CardLayoutPlanner` only; opens no
# window and runs no inference.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode CardLayoutPlan

# Performance watchdog contract: drive `PerformanceWatchdog` (pure arithmetic) and assert the
# 80%-of-target threshold boundary, the 30-second sustained window, the warning text and the three
# calibration constants (ratio / sustained / alert cooldown). Opens no window and runs no inference.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode PerformanceWatchdog
```

`ServerSmoke` remains only as a compatibility alias for `ServerBuild`; do not describe it as an HTTP/WS runtime test.

## Evidence and boundaries

- Prefer authorized physical Android devices, then `VisionGuard_API36`, then `Pixel_3a_XL`. Specify `-DeviceSerial` when multiple physical devices are connected.
- Emulator runs must keep the window visible and close an emulator started by the script when the run ends.
- Android smoke proves installation, launch, foreground activity/process state, and absence of an observed crash during the capture window. It does not prove the detector→Server→receiver alert chain.
- WPF person-window smoke opens one visible `System.Windows.Forms` fixture window per requested source inside the running PowerShell process, captures each source through its real `WindowHandle`, and requires `passed` plus at least one `person` frame per source in the smoke report. It also proves per-source FPS at or above 2.5, source isolation, capacity warning and backend failure isolation. Four sources remain the default regression baseline. The fixture directory must hold at least as many person images as requested sources; the script fails with the actual count instead of lowering the source count. `-WpfModelPath`, `-WpfConfidenceThreshold` and `-WpfReportPath` default to `artifacts\v0\yolo26n_320.onnx`, `0.25` and `artifacts\e2e\wpf-window-person-detection.json`. It does not prove dynamic-video behavior, alert delivery, long-running stability, or UI appearance.
- Fixture windows are created without any DPI-awareness declaration, so the Windows PowerShell host this skill uses keeps them DPI-unaware; at 150%/200% this verifies that capture geometry follows the target window's PrintWindow client plane instead of DWM's stretched screen bounds.
- WPF parser-contract mode builds `tests\WpfInference.Benchmark` once per inference profile (`-p:OrtProfile=legacy` also applies to the referenced `VisionGuard` project, so the probe cannot silently run against the other profile's assemblies) and asserts, per profile, that the output tensor layout is recognized, that it matches the profile (legacy `[1,84,N]` vs modern `[1,300,6]`), that the native ONNX Runtime really came from the profile directory, and that a real fixture image yields the expected label above 0.5 confidence. It proves model→parse→detection boxes only: not window capture, not alert delivery, not UI, not GPU execution.
- Resident-launch mode needs the isolated server script and `scripts\assert-resident-visible.js`. It asserts, per profile, that the detector found the resident next to its own executable, that the resident entered its single-instance handshake within the timeout, and that the server's device list reports `components.resident = running` for the device id the detector wrote. It kills the residents it started and stops the isolated server's listening process afterwards. It proves launch, authentication and server-side registration only — not the remote `open-detector`/`close-detector` actions, not login autostart, and not reboots.
- Model-download mode runs `tests\WpfInference.Benchmark --model-download <key>` against the production server and asserts: the command is invokable, the option enters the downloading state, the download reports success, the file lands in the real cache, the `.tmp` file is cleaned up, the byte count matches the server's `Content-Length`, progress is reported, the available-model list updates, a failure reason is captured (or cleared on success), and the profile lists only its own model family. It does not exercise the click itself — the button binding is covered by the UI probe described below, not by this mode.
- Source-auto-save mode drives the real `SourceViewModel` on a worker dispatcher with an isolated settings file and asserts: no pending parameters on load, a changed parameter is listed as pending, the value is still absent from disk before the debounce window, then present after it (threshold, fps, cooldown), the capture-target reset clears window/region/mask and persists immediately, the pending list never tracks the capture target, and `SaveCommand`/`CancelCommand` no longer exist on the source view model. It does not prove the visual layout — that stays an owner visual check.
- Card-layout mode builds `tests\WpfInference.Benchmark` and runs `--layout-plan <report.json>`, which drives `CardLayoutPlanner` (pure arithmetic) for 1/2/4 visible cards across the minimum window (1200×880), 1420×880 and 1920×1080 card-area sizes. It asserts the card aspect clamp (1:1.2 ~ 1.2:1), that each visible count fits its grid without clipping (1 = 1×1, 2 = 1×2 or 2×1, 3–4 = 2×2), the minimum window's 1:1 picture short edge (≥ 500 for one card, ≥ 380 for two, ≥ 320 for four), that wide/flat and narrow/tall containers stay within the aspect clamp instead of producing malformed cards, determinism, and that degenerate input returns an invalid layout instead of throwing. The four-card case reaches 323 after the card chrome was compressed to 68 DIP (title row 26, action row 28, padding 3, margin 2, spacing 4); changing that chrome requires updating both `CardLayoutPlanner.CardChromeHeight` and the card DataTemplate in `MainWindow.xaml`. It proves layout arithmetic only — the visual result, the splitter feel and the source-selection interaction remain owner visual checks.
- Performance-watchdog mode builds `tests\WpfInference.Benchmark` and runs `--performance-watchdog <report.json>`, which drives `PerformanceWatchdog` (pure arithmetic) and asserts: the 80%-of-target ratio boundary (2.39/3.0 below, 2.40/3.0 and 3.00/3.0 not below, with a floating-point comparison tolerance), that a monitoring flag is required and `fps ≤ 0` is never treated as a performance problem, that the warning only appears after 30 sustained seconds, and that the three calibration constants (0.8 ratio / 30 s / 10 min alert cooldown) are pinned. It proves the judgement arithmetic only — the real dialog, the 10-minute throttle and the actual on-device frame rates remain runtime/owner checks. This replaced the retired capacity-baseline warning (the capacity menu was removed on 2026-09-20).
- Full feature E2E requires deterministic test data plus every participating component; report missing links as skipped.
- Do not use the production VPS or public service unless the user explicitly requests it. Do not change versions or publish from this skill.
- Evidence is written under `artifacts/e2e/`. Report the selected device, build type, pass/fail/skip results, evidence paths, and any remaining manual visual checks.
