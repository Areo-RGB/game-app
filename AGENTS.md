# Release Flow (GitHub Actions)

This repo releases APKs from the **Build APK** workflow and publishes them to GitHub Releases.

## One-command flow

Use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/init-release-flow.ps1
```

Default behavior:
1. Triggers `.github/workflows/build-apk.yml` via `workflow_dispatch`.
2. Waits for workflow completion.
3. Downloads `app-debug-apk` artifact.
4. Publishes/updates release tag derived from `app/build.gradle.kts` `versionName` (e.g. `v1.1`).

## Inputs

- `-Tag v1.2.3` override release tag.
- `-Title "v1.2.3"` override release title.
- `-Notes "..."` override release notes.
- `-Branch main` workflow ref/branch.
- `-Workflow build-apk.yml` workflow file.
- `-ArtifactName app-debug-apk` workflow artifact name.
- `-DownloadRoot .build-outputs/releases` artifact download folder.

## Guardrails

- Ensure `gh` is authenticated (`gh auth status`).
- Keep `versionName` and `versionCode` bumped before running the flow.
- If release tag already exists, the script **uploads/replaces** the APK asset on that release.

## Manual checks

- Workflow runs: `gh run list --workflow build-apk.yml --limit 5`
- Latest release: `gh release view --json tagName,name,assets`
