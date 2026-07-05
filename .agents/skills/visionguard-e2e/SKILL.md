---
name: visionguard-e2e
description: VisionGuard end-to-end and smoke verification workflow. Use when the user asks to run E2E tests, emulator/real-device validation, automated verification, Android receiver/detector runtime checks, server smoke tests, logcat evidence capture, WSL-backed server checks, or asks whether a change should be verified with a device, emulator, desktop control, or only builds.
---

# VisionGuard E2E

Use this skill for verification beyond normal compilation. Keep `visionguard-build` for pure builds; use this skill when runtime evidence, devices, emulators, logs, UI interaction, or server smoke checks matter.

## Core Rule

Use the cheapest reliable evidence first:

1. **Data first**: build output, HTTP/WS probes, files, logs, `adb`, `dumpsys`, `uiautomator`, screenshots.
2. **Device automation second**: Android real device if connected, otherwise emulator.
3. **Desktop control when useful**: visual UI, emulator window state, system permission dialogs, notification shade, Android Studio AVD Manager, WinForms/WPF windows, or cases where command output cannot prove behavior.
4. **Report skipped checks** with the reason and what the user can do manually.

Do not touch production VPS or public `visionguard.xgwnje.cn` unless the user explicitly asks. Prefer local or test server configuration for E2E.

## Preferred Script

From repo root:

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover
```

Common modes:

```powershell
# Environment/device inventory only, no app install, no emulator launch.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode Discover

# Server build plus local environment probe. Does not deploy.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode ServerSmoke

# Install/start Android receiver and capture logcat/screenshot evidence.
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-e2e\scripts\e2e-smoke.ps1 -Mode AndroidReceiverSmoke -Device Auto
```

Artifacts are written under `artifacts/e2e/<timestamp>/`.

## Device Policy

Default `-Device Auto` order:

1. Connected Android physical device in `device` state.
2. `VisionGuard_API36` emulator.
3. `Pixel_3a_XL` emulator.
4. Build/server-only fallback.

If multiple physical devices are connected, use `-DeviceSerial <serial>` or report that selection is ambiguous. If a device is `unauthorized`, tell the user to approve USB debugging on the device. If a device is `offline`, try one reconnect before skipping it.

iOS is not part of current VisionGuard runtime. Discover iOS tooling/devices only when the user mentions iOS; otherwise do not include it in the E2E path.

## Verification Tiers

- **Build**: run `visionguard-build` or target-specific build commands.
- **ServerSmoke**: compile server; optionally probe local HTTP/WS endpoints when a local test server is configured.
- **AndroidSmoke**: install APK, start app, collect `logcat`, `dumpsys activity`, optional `uiautomator dump`, optional screenshot.
- **Feature E2E**: use a local/test server plus deterministic test data. For alert/screenshot flows, simulate detector messages, verify server persistence, then verify receiver UI/behavior.

Do not claim full E2E unless the test actually exercises all participating components.

## Required User/Project Preparation

These are intentionally not guessed:

- A debug/test configuration that points Android apps at a local or test Server URL and test API key.
- Stable test fixtures for alert metadata and a small screenshot image.
- Optional instrumentation tests under `androidTest` for UI assertions. The repo already has Compose test dependencies, but no tests are currently present.
- Optional real Android/iOS devices connected and authorized when the user wants physical-device validation.

If these are missing, run lower-tier checks and report what could not be proven.

## Desktop Control Guidance

Use Codex desktop control only when it materially improves evidence:

- Android permission dialogs, notification shade, visual image rendering, emulator black screen/stuck boot, Android Studio AVD Manager issues.
- WinForms/WPF visible windows, tray behavior, modal update prompts.
- Visual layout checks where logs and UIAutomator XML are insufficient.

When using desktop control, capture or describe the visual evidence. Prefer `adb screencap` or Browser screenshots when they can prove the same point more repeatably.

## Reporting

Include:

- Mode, commands run, selected device/AVD, and artifact directory.
- Pass/fail/skip per tier.
- Evidence files created.
- Explicit note if no version bump, release, deploy, or production traffic was performed.
- Manual steps left for the user, especially physical-device authorization or visual checks that were skipped for ROI.
