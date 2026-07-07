---
name: push-update
description: Use when VisionGuard client release packages, server release metadata, VPS release files, or GitHub Release assets need to be published, replaced, or verified.
---

# Push Update

Project release workflow for VisionGuard. Use only when the user explicitly asks to publish, push an update, replace release packages, update the server package list, or update the GitHub release page.

## Boundaries

- Do not bump `VERSION` unless the user explicitly gives a new version.
- Same-version hotfixes are allowed when the user asks to replace current packages; rebuild only affected platforms and update only their `releases.json` entries.
- Do not use the legacy `VisionGuard_Server` deployment layout.
- Current production server facts live in `D:\ObjectCode\Server-infra`; read `server.local.env` for SSH details without printing secrets.
- Current VisionGuard runtime root is `/opt/visionguard-server`; release files live under `data/releases/`, metadata at `data/releases.json`.
- GitHub Release notes must be Chinese.

## Platform Map

| Request | Targets | Asset |
|---|---|---|
| Windows | WinForms + WPF | `VisionGuard-v<ver>.zip`, `VisionGuard-WPF-v<ver>.zip` |
| WinForms | WinForms only | `VisionGuard-v<ver>.zip` |
| WPF | WPF only | `VisionGuard-WPF-v<ver>.zip` |
| Android | Detector + Receiver | `VisionGuard-Detector-v<ver>.apk`, `VisionGuard-Receiver-v<ver>.apk` |
| Server | Server code only | no release package |
| all / full release | all clients + server build | all assets |

If the user does not identify platforms, inspect the changed files and ask only when the safe target is still ambiguous.

## Build

Prefer the project build skill for compile verification:

```powershell
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Windows
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Android
powershell -ExecutionPolicy Bypass -File .\.agents\skills\visionguard-build\scripts\build-all.ps1 -Target Server
```

For a new full-version release, use the release script after explicit version authorization:

```powershell
node scripts/release.js <version>
```

Android release packages must be signed `app-release.apk`. Never publish `app-release-unsigned.apk`.

## Package And Metadata

Package only targets that were rebuilt. Exclude model and alert data from Windows zip files:

```powershell
$version = (Get-Content -Encoding UTF8 VERSION -Raw).Trim()
$releaseDir = "server\data\releases"
Get-ChildItem "detector\windows-winforms\bin\Release\*" -Exclude "Assets","alerts" |
  Compress-Archive -DestinationPath "$releaseDir\VisionGuard-v$version.zip" -Force
Get-ChildItem "detector\windows-wpf\bin\x64\*" -Exclude "Assets","alerts" |
  Compress-Archive -DestinationPath "$releaseDir\VisionGuard-WPF-v$version.zip" -Force
```

After packaging, update only rebuilt platforms in `server/data/releases.json`; keep untouched platforms unchanged. Size must match the actual file byte length.

## Upload

Upload rebuilt files and `server/data/releases.json` to:

```text
/opt/visionguard-server/data/releases/
/opt/visionguard-server/data/releases.json
```

Use SSH/SFTP details from `D:\ObjectCode\Server-infra\server.local.env`. Do not echo passwords, API keys, or private connection details into chat. Asset-only uploads do not require restarting `visionguard.service`.

## GitHub Release

Commit and push source/metadata if the user requested repository publication. For same-version hotfixes, move the version tag to the fix commit only when the release source archive should match the replaced assets.

```powershell
gh release upload v<version> <asset1> <asset2> --clobber
gh release edit v<version> --notes "<中文发行说明>"
```

Release notes should state which assets were replaced, which assets did not change, and include SHA-256 hashes for replaced packages.

## Verification

Before reporting success:

- `git status --short --branch` is clean after intended commit/push.
- `gh release view v<version> --json assets,body,url` shows the expected asset sizes/hashes and Chinese notes.
- `https://visionguard.xgwnje.cn/api/update?platform=<platform>&version=0.0.0` returns the expected version, URL, and size.
- Public package URLs answer `HEAD 200`; a `Range: bytes=0-0` probe answers `206`.
- For Android packages, `apksigner verify --verbose --print-certs` passes.

If PowerShell `Invoke-WebRequest -Method Head` throws a null-reference error, use Python `urllib.request` for HEAD/Range probes instead.
