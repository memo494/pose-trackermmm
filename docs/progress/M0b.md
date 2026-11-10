# M0b — screen-capture overlay (handoff, IN PROGRESS — paused at usage limit)

**Plan:** `docs/plans/M0b.md` (approved 2026-11-10; D1–D3 accepted). **Branch:** `m0b-screen-overlay`,
draft PR #3. **Session env:** aarch64 proot on the phone; no local Android builds, so CI is the build.

## Done
- Owner decision: product = **screen-capture overlay** (MediaProjection → pose → floating overlay).
- C1 consult (Fable): design confirmed with changes A–G, logged in the plan.
- **T1** ARCHITECTURE v0.6. **T2** Ultralytics pose core vendored (pinned `c97bf34`), AGPL LICENSE,
  `third_party/ULTRALYTICS.md`. **T3** pure-Kotlin core (TrackerConfig, Letterbox, FitCenter, CaptureSize,
  RowCopy, FrameTimestamp). **CI green** on PR #3 (run 42834207569), `@Test` = 66.
- **Model export fixed + run.** PR **#4** (`model-export` → `main`) carries the script fix and
  `yolo26n-pose_{320,416}.tflite`, goldens and `model_info.json`. Input is `(1,3,S,S)` float32 NCHW;
  output is `(1,300,57)` float32. CI on #4 was running at pause (run 42835960608).

## In flight at pause
- **T6 overlay** implementer was mid-task on `m0b-screen-overlay` (first commit `dc1123f` RedrawPolicy; untracked
  `app/.../overlay/` files existed). Next session: check `git status` / `git log`, and whether its work was
  pushed. Then run CI and review against the T6 acceptance criteria.

## Next steps
1. Owner merges **PR #4** (models). Then rebase `m0b-screen-overlay` on `main`.
2. Finish/verify T6 → T4 (detector adapter + model packaging; needs #4) → T5 (capture service) → T7 → T8.
3. android-reviewer on the full diff → PR #3 ready → owner merges → APK on `latest`.

## Open items / proposals
- AGENTS.md rule 5 + ARCHITECTURE §11.5 still use `ResolutionSelector` (CameraX) as the example. Propose a new
  example in a session-end edit.
- Snapshots (`DetectionFrame`/`OverlayState`) are allocated per frame. Pool them in M1 together with D2.
- Optional repo setting: allow GitHub Actions to create PRs (Settings → Actions → General), so
  `export-model` can open its own PR.
- `fix-model-export` branch is superseded by `model-export`; delete it after #4 merges.
