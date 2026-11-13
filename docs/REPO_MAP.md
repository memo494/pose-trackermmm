# REPO_MAP — modules and entry points

A one-screen map so agents open the right file without a wide search. Full design: `ARCHITECTURE.md`.

## Modules (dependencies: `app → detector → core`, `app → core`)
| Module | Type | Holds | Entry points (M0b so far) |
|---|---|---|---|
| `core/` | Kotlin JVM (no `android.*`) | model types, geometry, capture math, redraw policy, `TrackerConfig`, `json`, `replay`, `modelpack` | `TrackerConfig`, `capture/CaptureFrame`, `capture/RowCopy`, `geometry/FitCenter`, `overlay/RedrawPolicy`, `replay/ReplayFixture`, `modelpack/ModelStager` |
| `detector/` | Android library | `PoseDetector` + adapter over vendored Ultralytics code (`com/ultralytics/yolo`), Fake impl | `PoseDetectors.fromAssets`, `PoseDetector`, `LiteRtPoseDetector`, `FakePoseDetector` |
| `app/` | Android application (Compose) | `capture/` (service, session, frame pipeline, teardown), `overlay/` (window, view, HUD), `control/` (reducer + screen), `diagnostics/` (log, crash file, share zip) | `MainActivity`, `PoseTrackerApp`, `capture/CaptureService`, `capture/CaptureSession`, `capture/FramePipeline`, `overlay/OverlayWindow`, `control/ControlReducer`, `diagnostics/DiagnosticsZip` |

Package root: `com.memo494.posetracker`. `applicationId` = `com.memo494.posetracker`.

## Build & tooling
- `gradle/libs.versions.toml` — pinned versions (PROTECTED). `settings.gradle.kts` — module list.
- `scripts/cloud-setup.sh` — installs the Android SDK (paste into the env Setup script, BUILD_PLAN §1.3).
- `scripts/verify.sh` — `spotlessCheck test lint assembleDebug`, short summary, retries proxy 429s.
- `scripts/ci-status.sh` — polls GitHub Actions for the current branch.
- `.github/workflows/android.yml` — CI; `export-model.yml` — manual model export.
- `signing/debug.keystore` — committed debug key (PROTECTED). `model/` — exported models (PROTECTED).
- `app/build.gradle.kts` — `stage<Variant>ModelAssets`: sha256 check of `model/` against `model_info.json`,
  then the `.tflite` files and `model_info.json` become APK assets.

## AI team
`.claude/settings.json` (lead = Opus, subagents = Sonnet), `.claude/agents/*` (implementer,
android-reviewer, debugger, consultant, Explore), `.claude/skills/` (vendored, PROTECTED).
`AGENTS.md` = rules; `CLAUDE.md` = `@AGENTS.md` + lead note.

## Docs
`docs/ARCHITECTURE.md` (design), `docs/AI_TEAM.md` (workflow), `docs/BUILD_PLAN.md` (milestone prompts),
`docs/plans/<M>.md` (lead's plan per milestone), `docs/progress/<M>.md` (handoffs),
`docs/TEST_CHECKLIST.md` (on-phone checks, from M0b).
