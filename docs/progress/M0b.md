# M0b — screen-capture overlay (handoff: READY FOR THE OWNER)

**Plan:** `docs/plans/M0b.md` (approved 2026-11-10; splits and details added 2026-11-13). **Branch:**
`m0b-screen-overlay`, PR #3. **Product (owner, 2026-11-12):** dots drawn over other apps on the screen; the
first target app is the Tuya camera app. **Builds:** no local Gradle in cloud or phone sessions, so CI is the
build.

## Status (2026-11-13; lead ran on Fable 5.2 this session, the owner's choice)
- **All tasks done and green:** T1–T8 (T4 as a/b/c, T5 as a/b) and the review fixes R1. Last green run:
  43392588721 on `bfe5ba7`. `@Test` = 404. No protected file changed; `main` untouched.
- **Review:** android-reviewer (Opus) on the full diff. Nothing breaks the Android 14/15 rules. One critical
  design defect (HUD feedback loop) and two warnings were fixed in R1 (plan → Progress).
- **Next step is the owner's:** mark PR #3 ready, merge it, install the APK from the `latest` release and run
  `docs/TEST_CHECKLIST.md` (section M0b); send the diagnostics zip if anything fails.
- **After the phone test:** fix what it finds (one branch and PR per problem), then plan M1 (benchmark, golden
  decode test, allocation-free decoder).

## Where things are
- `core`: `TrackerConfig`, geometry, capture math, `json`, `replay`, `modelpack`.
- `detector`: `PoseDetectors.fromAssets` → `LiteRtPoseDetector` over the vendored `PoseEstimator`.
- `app`: `capture/` (`CaptureService`, `CaptureSession`, `FramePipeline`, `CaptureTeardown`), `overlay/`,
  `control/` (`ControlReducer`), `diagnostics/` (Share diagnostics zip, crash file, log), `MainActivity`.
- `app/build.gradle.kts`: `stage<Variant>ModelAssets` (sha256 check, models as assets).

## Decisions (lead, 2026-11-13)
- The model is an asset, staged to `noBackupFilesDir/models` with a sha256 check and then opened by path.
- Own strict JSON reader in `core` instead of a new dependency (the catalog is protected). Default model 416.
- Teardown never blocks the main thread (main → capture → main). A detector failure shows
  `DETECTOR_UNAVAILABLE` instead of stopping; a failed start shows a notice in the app.
- HUD idle rule: a capture rate below `hudRateHz + 2` prints as 0 fps and does not refresh the HUD numbers
  (the HUD's own redraws are captured and would keep the pipeline busy).
- Implementers push the branch (plain push, never force) and read `scripts/ci-status.sh`. After a push wait about
  90 s and check that the run's `head_sha` is the pushed commit; the script otherwise reports the previous run.

## Not verified yet (needs the phone)
- Nothing has run on a device. The Android glue (`CaptureSession`, `CaptureService`, `UltralyticsPoseEngine`,
  `PoseDetectors`, `OverlayWindow`, the share sheet) has no JVM test; the lead and the reviewer read it.
- Detector: the NCHW guard must accept the real model (input tensor found by the name `args_0`). If the HUD shows
  **CPU**: upstream's note in `LiteRtModel.kt` says the GPU may not compile end-to-end graphs; M1 should then
  export a non-end-to-end model (the vendored decoder handles the classic layout with NMS) and benchmark both.
  Measure before changing anything.
- Landscape: the geometry follows `onCapturedContentResize` (API 34+), the display listener and
  `onConfigurationChanged`; the diagnostics log has one `capture geometry:` line per change.
- Lock screen: stock Android 15 QPR1+ stops the projection on lock; HyperOS may keep it. Both are fine.
- Detection feedback: the rings are part of the captured picture. If the fps does not fall to 0 on a still
  picture with people, the rings are moving the detections: measure, then widen the deadband or change the ring.
- The APK contents were not listed (artifact download is blocked in cloud sessions). Evidence: the CI log line
  `Staged model assets: yolo26n-pose_320.tflite, yolo26n-pose_416.tflite, model_info.json` and the APK size.

## Open items / proposals
- **Small people:** in portrait the Tuya video is about a quarter of the screen, so letterboxing the whole screen
  to 416 leaves people very small. Plan a crop to the video region (chosen by the owner or detected); ask the
  consultant when planning M1/M2.
- Branch `fix-export-model-deps` (2026-11-10 night, unmerged): its camera-based doc notes are withdrawn and it
  must not be merged. Its export self-check and JSON goldens are useful for the M1 golden test: port them in a
  separate PR after the owner's OK, then delete the branch. The old merged branches can be deleted by the owner.
- AGENTS.md proposals (never edited mid-session): rule 5 and ARCHITECTURE §11.5 still cite `ResolutionSelector`
  (CameraX) as the example; record that ktlint's effective `max_line_length` is 140, that it joins a body onto
  its signature line whenever the result fits, and that it wraps chains of four or more calls.
- Reviewer suggestion not taken: catch `Throwable` in the `LiteRtPoseDetector` ladder (the vendored model
  already falls back inside; an `Error` there ends as "detector unavailable", not as a crash).
- Per-frame allocations left for M1 (decision D2): the vendored decoder, the `Canvas` in
  `prepareBitmapForModel`, and the `DetectionFrame` / `PoseDetection` / `OverlayState` snapshots.
- The app's texts and the checklist are English only; an Arabic version is an owner decision.
- Optional repo setting: allow GitHub Actions to create PRs, so `export-model` can open its own PR.
