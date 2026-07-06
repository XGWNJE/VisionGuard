# Android Receiver Status Update Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Inline execution in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove redundant alert-page metric cards, move manual update checking into the alert-page connection banner, and remove the Settings tab.

**Architecture:** Keep update networking in `AutoUpdater`. Add small UI model helpers for update-result text and main tab definitions so behavior is testable without Compose instrumentation.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4.

## Global Constraints

- Do not modify Server, WebSocket protocol, API key injection, version files, release scripts, or deployment.
- During UI debugging, run Debug verification only: `:app:testDebugUnitTest :app:assembleDebug`.
- Do not build Release in this iteration.
- Do not commit.

---

### Task 1: Testable UI Models

**Files:**
- Modify: `receiver/android/app/src/test/java/com/xgwnje/visionguard_android/ui/home/ReceiverHomeModelsTest.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/home/ReceiverHomeModels.kt`

- [x] Add failing tests that main tabs are only `警报` and `设备`.
- [x] Add failing tests that update feedback text includes the current version for no-update, failure, and update-available cases.
- [x] Implement model helpers.

### Task 2: Compose Integration

**Files:**
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertListScreen.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/component/ConnectionBanner.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/MainActivity.kt`
- Delete: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/SettingsScreen.kt`

- [x] Remove the three metric cards from the alert page.
- [x] Make the connection banner clickable and show update-checking state.
- [x] Move update dialog / toast handling to `AlertListScreen`.
- [x] Remove the Settings tab and route from `MainActivity`.

### Task 3: Docs and Verification

**Files:**
- Modify docs that still describe a Settings tab.

- [x] Update receiver docs to say the bottom tabs are `警报 / 设备`.
- [x] Run Debug tests/build.
- [x] Install Debug APK on `VisionGuard_API36`.
- [x] Capture screenshot.
