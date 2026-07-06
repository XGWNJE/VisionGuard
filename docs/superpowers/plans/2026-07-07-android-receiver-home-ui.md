# Android Receiver Home UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use inline execution in this session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android 接收端首页改造成设计稿里的浅色操作台视觉，并保留警报、设备导航；设置页已在后续状态条更新入口方案中取消。

**Architecture:** 新增纯 Kotlin UI 模型层，把统计和卡片派生逻辑从 Compose 中拆出并测试。Compose 层只消费这些模型，避免触碰 WebSocket service、协议和截图缓存逻辑。

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX material icons extended, JUnit 4.

## Global Constraints

- 不修改 `VERSION`、`sync-version.js`、`release.js` 或任何发布流程。
- 不修改 Server、检测端、WebSocket 协议或 API Key 配置。
- 图标使用现有 Compose Material Icons Extended。
- 运行时代码不引用 `docs/design/assets/app-assets/`。
- 列表页不预加载截图。
- 计划和代码不自动提交。

---

### Task 1: UI Model Tests

**Files:**
- Create: `receiver/android/app/src/test/java/com/xgwnje/visionguard_android/ui/home/ReceiverHomeModelsTest.kt`
- Create: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/home/ReceiverHomeModels.kt`

**Interfaces:**
- Produces: `buildReceiverHomeSummary(alerts, devices, now)` and `buildAlertCardUiModel(alert)`.

- [ ] Write tests for today's alert count, online device count, latest alert time, detection chip limit, and screenshot state.
- [ ] Run `:app:testDebugUnitTest` with command-local `JAVA_HOME`; expect unresolved production symbols.
- [ ] Implement minimal production models.
- [ ] Re-run `:app:testDebugUnitTest`; expect pass.

### Task 2: Home Screen Components

**Files:**
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/screen/AlertListScreen.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/component/AlertCard.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/component/ConnectionBanner.kt`

**Interfaces:**
- Consumes: `ReceiverHomeSummary`, `AlertCardUiModel`, `DetectionChipUiModel`.

- [ ] Replace the plain top bar with a connection banner as the first visible module.
- [ ] Replace simple alert cards with a three-column layout: device name, date plus target tags, and a single detail cue icon.
- [ ] Replace character status markers with Compose icons.
- [ ] Re-run unit tests.

### Task 3: Theme and Bottom Navigation

**Files:**
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/MainActivity.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/theme/Color.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/theme/Theme.kt`
- Modify: `receiver/android/app/src/main/java/com/xgwnje/visionguard_android/ui/theme/Type.kt`

**Interfaces:**
- Keeps routes: `alertList`, `deviceList`, `alertDetail/{alertId}`.

- [ ] Apply the fixed VisionGuard receiver palette.
- [ ] Restyle bottom navigation as a rounded operation bar with two tabs.
- [ ] Keep device navigation behavior unchanged.
- [ ] Re-run unit tests.

### Task 4: Build and Emulator Preview

**Files:**
- No production file changes expected.

- [ ] Run Android receiver build with command-local `JAVA_HOME`.
- [ ] Start or reuse an Android emulator.
- [ ] Install the receiver APK.
- [ ] Launch `com.xgwnje.visionguard_android/.MainActivity`.
- [ ] Capture a screenshot and show it to the user.
