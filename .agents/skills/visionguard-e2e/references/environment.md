# VisionGuard Local E2E Environment

Current known local paths and devices. Verify live before relying on them if the environment may have changed.

## Windows Tools

- Android Studio: `C:\Android\Android Studio`
- Android JBR: `C:\Android\Android Studio\jbr`
- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- ADB: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Emulator: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe`
- Visual Studio: `C:\Program Files\Microsoft Visual Studio\18\Community`
- MSBuild: `C:\Program Files\Microsoft Visual Studio\18\Community\MSBuild\Current\Bin\MSBuild.exe`
- vswhere: `C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe`

## AVDs

- `VisionGuard_API36`: Android 36.1, x86_64, Google Play, 1080x2400, 4096 MB RAM. Preferred modern emulator.
- `Pixel_3a_XL`: Android 28, x86, Google APIs, 1080x2160, 2048 MB RAM. Use for minSdk/older OS compatibility smoke checks.

## WSL

- Distro: `Debian` on WSL2.
- Use WSL for Linux-side probes or helper scripts when useful.
- Keep Android emulator and adb orchestration on Windows because SDK and AVDs live in the Windows user profile.

## Notes

- `adb` and `emulator` may not be on PATH; use absolute paths from the SDK.
- Physical Android devices may appear only when the user is local and has connected/authorized them.
- Current project has Android Compose test dependencies but no `androidTest` test classes yet.
