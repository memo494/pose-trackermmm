# M1a — rectangular model inputs and timing statistics (handoff: MERGED, READY FOR THE OWNER)

**Plan:** `docs/plans/M1.md`. **Branch:** `m1a-rect-timing`, PR #7. **Builds:** no local Gradle in cloud
sessions, so CI is the build.

## Status (2026-11-15; lead on Fable 5.2, the owner's choice; implementer Sonnet, reviewer Opus)
- **All tasks done and green:** T1–T6 and the review fixes. Last green run: 43734620197 (#85) on `5c63ef4`.
  `@Test` = 905 (792 on `main` before). No protected file changed, no new dependency, no vendored file changed.
- **Owner approvals (chat, 2026-11-15):** run M1 → M3 → M4 in one session without waiting for plan approval;
  the lead merges each milestone after green CI and an independent review; "you are the consultant" (the lead
  on Fable 5.2 answers hard questions itself, no separate consultant agent).
- **Review (android-reviewer):** nothing critical; one major (headroom printed with one decimal) and four
  minors, all fixed in `98b4b95` / `5c63ef4`.
- **Rectangular models** are in the pre-release `models` (export run 43732166410 on this branch):
  `yolo26n-pose_640x288`, `_832x384`, `_960x448` (`.tflite` about 12 MB each, plus `.json`); input
  `[1,3,H,W]`, output `[1,300,57]`, two people at 0.85–0.89 on the sample image. Sha256 values: plan, T4 row.
- **Next step is the owner's (optional, 5 minutes):** `docs/TEST_CHECKLIST.md` section M1a: import
  `yolo26n-pose_832x384.tflite`, compare `ms` / `fps` / `max` with 640 in landscape, share the diagnostics.

## What was built
- **Layout version 2:** an imported model may be `[1, 3, H, W]`; each side a multiple of 32 inside the import
  limits, long/short ≤ `importMaxInputAspect` (4.0). Rejection key `input_aspect` replaces `input_not_square`.
- **Meta v2** (`inputWidth`, `inputHeight`); a stored v1 meta is still read, so the owner's 640 model stays.
- **Width and height** through `PoseEngine` / `PoseDetector` / `LiteRtPoseDetector.create` / `ModelStatus` /
  `OverlayState`. One text rule, `core/model/ModelInputText`: `640` when square, else `832x384`
  (WIDTHxHEIGHT everywhere: HUD, `summary.txt`, control screen, log, file names, `--sizes`).
- **Timing:** `core/stats/TimingWindow` (512 frames); the pipeline records copy, pre, inference, post, whole
  frame and result age per processed frame; percentiles are computed on the capture thread at the HUD tick
  and travel in the immutable `CaptureStats`. `summary.txt` gains `timingSamples`, twelve `…MsP50/P95` lines,
  `thermalStatus` (API 29+) and `thermalHeadroom` (API 30+, two decimals). The HUD text is unchanged.
- **Export:** `tools/export_model.py --sizes S|WxH --out DIR`, `--self-test` (no heavy imports).

## Decisions
- **M1 was split** (lead; told to the owner in chat): M1a is this PR; **M1b** = raw-output goldens and the
  golden decode test, the pure-Kotlin decoder, the benchmark screen with CSV and soak mode, a bundled landscape
  model with a per-geometry choice. M1b comes after M4.
- **A landscape model is for landscape.** In portrait it is worse than any square model (content scale 0.28);
  the checklist says so. No per-orientation switch yet (M1b).
- **`readFloat` allocates** (LiteRT Kotlin API, 68 KB per frame); it cannot be removed with the bundled runtime.
  Recorded in `third_party/ULTRALYTICS.md` and ARCHITECTURE §13.
- `ImportedModelMeta.decode` applies the aspect rule too (fail closed); the store decodes with the default
  config.

## Not verified yet (needs the phone)
- A rectangular model on the Mali GPU: it may compile only for the CPU (the red banner would show it).
- `ms` for the three sizes. Estimates from two measured points (21 ms at 416², 33.9 ms at 640²): about 22, 29
  and 35 ms.
- `thermalStatus` / `thermalHeadroom` readings and the pre/post timing readback (Android glue, no JVM test).

## Open items / proposals
- `export-model.yml` input text still says "e.g. 640"; it also takes `WxH`, without spaces (protected file,
  wording only: change it with the next export task).
- Whether 20.0 fps on the camera video is the app's limit or the stream's: the `frameMs` and `copyMs`
  percentiles of the next diagnostics zip answer it (frame p50 well under 50 ms means the stream).
- Proposal for AGENTS.md (not edited mid-session): say that sizes are written WIDTHxHEIGHT.

## Next
M3 (tracking) in two PRs, plan `docs/plans/M3.md`: M3a the tracker in `core`, M3b the tracker in the app.
