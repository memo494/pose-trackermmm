# M0b — screen-capture overlay (handoff, IN PROGRESS)

**Plan:** `docs/plans/M0b.md` (approved 2026-11-10; T4 split added 2026-11-13). **Branch:** `m0b-screen-overlay`,
draft PR #3. **Product (owner, 2026-11-12):** dots drawn over other apps on the screen; the first target app is
the Tuya camera app. **Builds:** no local Gradle in cloud or phone sessions, so CI is the build.

## Status (2026-11-13; lead ran on Fable 5.2 this session, the owner's choice)
- **Done and green:** T1, T2, T3, T6 and T4 (as T4a, T4b, T4c). Last green run: 43380160634 on `dad3ea9`.
  `@Test` = 271.
- **Next:** T5 (screen-capture service) → T7 (control activity, permissions) → T8 (diagnostics + checklist) →
  android-reviewer on the full diff → PR #3 ready → owner merges → APK on `latest` → phone test.
  The first on-phone run needs T5 and T7. Ask the owner before each task.

## What T4 added
- `core`: `json/Json` (strict reader, no dependency), `replay/ReplayFixture` (format v1), `capture/CaptureFrame`
  (reused frame holder), `modelpack/` (`ModelInfo`, `Sha256`, `ModelStager`), `TrackerConfig.modelInputSize`
  (default 416) and `MAX_DETECTIONS_PER_FRAME`.
- `app/build.gradle.kts`: `stage<Variant>ModelAssets` checks every model against `model_info.json` (sha256) and
  packages the two `.tflite` files and `model_info.json` as assets.
- `detector`: `PoseDetector` (with `lastInferenceMs`), `PoseDetectors.fromAssets(context, config)` (the entry
  point for T5; call it on the capture thread), `LiteRtPoseDetector` (GPU → CPU ladder, threshold, cap,
  thread-confined), `UltralyticsPoseEngine` (vendored `PoseEstimator`, reused bitmap), `FakePoseDetector`,
  `YoloResultMapper`.
- Vendored change: `BasePredictor.inputUsesNchw` (logged in `third_party/ULTRALYTICS.md`).

## Decisions (lead, 2026-11-13)
- The model is an asset, staged to `noBackupFilesDir/models` with a sha256 check and then opened by path
  (litert-runtime skill; vendored `LiteRtModel` takes a path).
- Own strict JSON reader in `core` instead of a new dependency (the catalog is protected).
- Default model size 416.
- Implementers push the branch (plain push, never force) and read `scripts/ci-status.sh`. After a push wait about
  90 s and check that the run's `head_sha` is the pushed commit; the script otherwise reports the previous run.

## Not verified yet (needs the phone)
- `UltralyticsPoseEngine` and `PoseDetectors.fromAssets` are Android glue with no JVM test; their first real run
  is the phone test. Watch for the NCHW guard accepting the real model (input tensor found by the name `args_0`)
  and for the accelerator shown in the HUD.
- If the HUD shows **CPU**: upstream's own note in `LiteRtModel.kt` says the GPU may not compile end-to-end
  graphs. M1 should then export a non-end-to-end model (the vendored decoder already handles the classic layout
  with NMS) and benchmark both. Measure before changing anything.
- The APK contents were not listed (artifact download is blocked in cloud sessions). Evidence instead: the CI log
  line `Staged model assets: yolo26n-pose_320.tflite, yolo26n-pose_416.tflite, model_info.json` and the APK
  artifact growing by 21 MB.

## Open items / proposals
- **Small people:** in portrait the Tuya video is about a quarter of the screen, so letterboxing the whole screen
  to 416 leaves people very small. Plan a crop to the video region (chosen by the owner or detected); ask the
  consultant when planning M1/M2.
- Branch `fix-export-model-deps` (2026-11-10 night, unmerged): its camera-based doc notes are withdrawn and it
  must not be merged. Its export self-check and JSON goldens are useful for the M1 golden test: port them in a
  separate PR after the owner's OK, then delete the branch. The old merged branches can be deleted by the owner.
- AGENTS.md proposals (never edited mid-session): rule 5 and ARCHITECTURE §11.5 still cite `ResolutionSelector`
  (CameraX) as the example; record that ktlint's effective `max_line_length` is 140 and that it joins a body
  onto its signature line whenever the result fits.
- `Accelerator.kt` KDoc still says "M0a placeholder" (fix in T5). `TrackerConfig` holds a few JSON-reader
  constants (radix, escape digits) that are not tunables: the reviewer decides.
- Per-frame allocations left for M1 (decision D2): the vendored decoder, the `Canvas` in
  `prepareBitmapForModel`, and the `DetectionFrame` / `PoseDetection` / `OverlayState` snapshots.
- Optional repo setting: allow GitHub Actions to create PRs, so `export-model` can open its own PR.
