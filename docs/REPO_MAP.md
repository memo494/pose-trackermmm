# REPO_MAP — modules and entry points

A one-screen map so agents open the right file without a wide search. Full design: `ARCHITECTURE.md`.

## Modules (dependencies: `app → detector → core`, `app → core`)
| Module | Type | Holds | Entry points (M0c so far) |
|---|---|---|---|
| `core/` | Kotlin JVM (no `android.*`) | model types, geometry, capture math, redraw policy, marks math, `TrackerConfig`, `json`, `replay`, `modelpack`, `modelimport` (rules for an imported model: file sniffer, tensor rules, meta codec, crash-marker state machine) | `TrackerConfig`, `capture/CaptureFrame`, `capture/RowCopy`, `geometry/FitCenter`, `overlay/RedrawPolicy`, `overlay/HeadRing`, `overlay/CornerBrackets`, `replay/ReplayFixture`, `modelpack/ModelStager`, `modelimport/ImportedModelRules`, `modelimport/ImportGuard` |
| `detector/` | Android library | `PoseDetector` + adapter over vendored Ultralytics code (`com/ultralytics/yolo`), Fake impl | `PoseDetectors.fromAssets`, `PoseDetectors.fromImported`, `ModelProbe` (reads the real tensor shapes, CPU), `PoseDetector`, `LiteRtPoseDetector`, `FakePoseDetector` |
| `app/` | Android application (Compose) | `capture/` (service, session, frame pipeline, teardown), `overlay/` (window, view, HUD), `control/` (reducer + screen + model block), `modelimport/` (store, importer, which model a session uses), `diagnostics/` (log, crash file, share zip) | `MainActivity`, `PoseTrackerApp`, `capture/CaptureService`, `capture/CaptureSession`, `capture/FramePipeline`, `overlay/OverlayWindow`, `control/ControlReducer`, `control/ControlModelMapper`, `control/ImportResults`, `modelimport/ModelImporter`, `modelimport/ImportedModelStore`, `modelimport/ModelChoice`, `modelimport/SessionModelLoader`, `diagnostics/DiagnosticsZip` |

Package root: `com.memo494.posetracker`. `applicationId` = `com.memo494.posetracker`.

## Added in M1a
- `core/model/ModelInputText` — the one size-text rule (`640` or `832x384`, WIDTHxHEIGHT).
- `core/stats/` — `TimingWindow`, `TimingSummary`, `ThermalSampler`, `ThermalStatusText`; filled by
  `app/capture/FramePipeline`, published through `app/diagnostics/CaptureStatsHolder`.
- `core/modelimport/` — layout version 2 (`[1,3,H,W]`) and meta v2; `detector` passes width and height.
- `tools/export_model.py --sizes S|WxH --out DIR` (`--self-test`); rectangular files live in the `models` release.

## Build & tooling
- `gradle/libs.versions.toml` — pinned versions (PROTECTED). `settings.gradle.kts` — module list.
- `scripts/cloud-setup.sh` — installs the Android SDK (paste into the env Setup script, BUILD_PLAN §1.3).
- `scripts/verify.sh` — `spotlessCheck test lint assembleDebug`, short summary, retries proxy 429s.
- `scripts/ci-status.sh` — polls GitHub Actions for the current branch.
- `.github/workflows/android.yml` — CI; `export-model.yml` — manual model export; with `sizes` and a
  `publish_tag` that starts with `models` it only publishes the files to that pre-release (the 640 model to import).
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
`docs/TEST_CHECKLIST.md` (on-phone checks: sections M0b, M0c and M1a).
