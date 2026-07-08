---
name: visionguard-release
description: Use when VisionGuard needs a client update published, a version bumped for release, VPS release metadata changed, GitHub release assets updated, server code deployed, or online update endpoints verified.
---

# VisionGuard Release

VisionGuard release work must use the repository pipeline, not ad-hoc shell fragments. The normal entrypoint is:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

## Release Rules

- Version changes are explicit only. Do not run `scripts/sync-version.js`, `scripts/release.js`, or `scripts/publish-release.ps1` unless the user asked for a release/version update.
- Default policy is no GitHub publication. Add `-PushGitHub`, `-CreateTag`, or `-CreateGitHubRelease` only when the user explicitly requests those actions.
- Current VPS facts come from `D:\ObjectCode\Server-infra\server.local.env`; do not use stale SSH aliases or print secrets.
- Runtime root is `/opt/visionguard-server`. Release assets go under `data/releases/`; metadata is `data/releases.json`.
- Android release artifacts must be signed. If Gradle only creates `app-release-unsigned.apk`, the pipeline must sign it from local secret sources, then run `apksigner verify --verbose --print-certs`.
- Never write real Android passwords into tracked `keystore.properties`; use environment variables or ignored local secret files.
- Upload metadata with a temp file and remote `mv -f` so replacing `releases.json` is atomic.

## Targets

| Target | Meaning |
|---|---|
| `All` | Server build plus all four client packages |
| `Windows` | WinForms + WPF packages |
| `Android` | Android detector + Android receiver APKs |
| `WinForms`, `WPF`, `AndroidDetector`, `AndroidReceiver` | One client package |
| `Server` | Server build/deploy only; use `server/deploy.sh` for code deployment |

## Verification Gates

Before reporting success, capture evidence for every touched platform:

- Local package exists and `server/data/releases.json` size matches the file size.
- Windows zip files contain no `.pdb`, `.lib`, `.dll.config`, `.onnx`, `Assets/`, or `alerts/`.
- Android APKs pass `apksigner verify --verbose --print-certs`.
- Online `/api/update?platform=<platform>&version=0.0.0` returns the expected version, URL, and size.
- Public package URL answers `HEAD 200`.
- Public package URL answers `Range: bytes=0-0` with `206`.

If GitHub publication is requested, write GitHub Release notes in Chinese and include replaced asset hashes.
