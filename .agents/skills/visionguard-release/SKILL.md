---
name: visionguard-release
description: Publish a VisionGuard version, deploy Server code, update VPS/GitHub release assets, or verify public update endpoints. Requires explicit release authorization.
---

# VisionGuard Release

Use the maintained pipeline from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\publish-release.ps1 -Version <version> -Target All -UploadVps
```

It performs preflight before version sync, builds selected targets, prepares signed packages, updates release metadata, uploads requested assets, deploys Server code when in scope, and verifies public endpoints.

## Authorization boundary

- A build, bug fix, or completed milestone does not authorize a version change or release.
- Run `publish-release.ps1`, `sync-version.js`, Git push/tag/Release, VPS upload, or deployment only when explicitly requested.
- `-PreflightOnly` is read-only with respect to versions and publication. Use it to validate release prerequisites.
- GitHub publication is disabled by default and remains opt-in through `-PushGitHub`, `-CreateTag`, and `-CreateGitHubRelease`.
- Use `-SkipServerDeploy` only for an explicitly client-only release.

## Non-negotiable gates

- Use `D:\ObjectCode\Server-infra\server.local.env` for current VPS connection facts and `/opt/visionguard-server` as the runtime root. Never print secrets.
- Android packages must be signed and pass `apksigner verify`; `app-release-unsigned.apk` is compile-only evidence and never a release artifact.
- Windows ZIPs must include the Resident runtime and exclude `.pdb`, `.lib`, `.dll.config`, `.onnx`, `Assets/`, and `alerts/`.
- Release metadata size must match local assets and be replaced atomically.
- Public `/health`, `/api/update`, package `HEAD 200`, and byte-range `206` checks must pass for the released scope.
- If Server code was deployed, verify the VPS runtime version and active service before reporting success.

Detailed target switches and implementation logic belong to `scripts/publish-release.ps1`; do not duplicate them here. Report exact published targets, version, verification evidence, and anything skipped.
