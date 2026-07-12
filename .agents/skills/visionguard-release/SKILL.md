---
name: visionguard-release
description: Use when VisionGuard needs a client update published, a version bumped for release, VPS release metadata changed, GitHub release assets updated, server code deployed, or online update endpoints verified.
---

# VisionGuard Release

VisionGuard release work must use the repository pipeline, not ad-hoc shell fragments. The normal entrypoint is:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

This command runs release preflight before version sync, builds all targets, uploads client assets, deploys server code to the VPS, and then verifies public server/update/download endpoints. Use `-PreflightOnly` to check the release environment without changing versions; use `-SkipServerDeploy` only for an explicitly client-only release.

## Release Rules

- Version changes are explicit only. Do not run `scripts/sync-version.js`, `scripts/release.js`, or `scripts/publish-release.ps1` unless the user asked for a release/version update.
- Default policy is no GitHub publication. Add `-PushGitHub`, `-CreateTag`, or `-CreateGitHubRelease` only when the user explicitly requests those actions.
- Current VPS facts come from `D:\ObjectCode\Server-infra\server.local.env`; do not use stale SSH aliases or print secrets.
- Runtime root is `/opt/visionguard-server`. Release assets go under `data/releases/`; metadata is `data/releases.json`.
- Preflight must pass before version sync: Server-infra env + Paramiko, WinForms `packages.config` restore, Android Java/build-tools/signing material, and non-legacy `RemoteRoot`.
- Android release artifacts must be signed. If Gradle only creates `app-release-unsigned.apk`, the pipeline must sign it from local secret sources, then run `apksigner verify --verbose --print-certs`.
- Normal Android Release packaging fails closed when signing material is unavailable. The `VISIONGUARD_ALLOW_UNSIGNED_RELEASE` Gradle property is compile-only evidence and must never be used by the publish pipeline.
- Android detector and receiver share the local PKCS12 signing identity under `.local/visionguard-android-release.p12`, configured by the Git-ignored `.local/visionguard-release.env`. Initialize it with `scripts/initialize-android-signing.ps1`; use `-Rotate` only when breaking update compatibility is explicitly accepted.
- Never write real Android passwords into tracked `keystore.properties`, scripts, logs, or command-line arguments; use the shared ignored config or environment variables.
- Upload metadata with a temp file and remote `mv -f` so replacing `releases.json` is atomic.
- When server is in target scope and `-UploadVps` is used, deploy server code before public release verification so `/api/update` and `/health` are checked against the new runtime.

## Targets

| Target | Meaning |
|---|---|
| `All` | Server build/deploy plus all four client packages when `-UploadVps` is used |
| `Windows` | WinForms + WPF packages |
| `Android` | Android detector + Android receiver APKs |
| `WinForms`, `WPF`, `AndroidDetector`, `AndroidReceiver` | One client package |
| `Server` | Server build/deploy only when `-UploadVps` is used |

## Verification Gates

Before reporting success, capture evidence for every touched platform:

- Local package exists and `server/data/releases.json` size matches the file size.
- Windows zip files contain no `.pdb`, `.lib`, `.dll.config`, `.onnx`, `Assets/`, or `alerts/`.
- Android APKs pass `apksigner verify --verbose --print-certs`.
- Online `/api/update?platform=<platform>&version=0.0.0` returns the expected version, URL, and size.
- Public package URL answers `HEAD 200`.
- Public package URL answers `Range: bytes=0-0` with `206`.
- If server code was deployed, VPS `/opt/visionguard-server/package.json` matches the release version, `visionguard.service` is active, and public `/health` returns ok.

If GitHub publication is requested, write GitHub Release notes in Chinese and include replaced asset hashes.
