# M-legacy handoff — older models and the dot from the person box

**Lead: Opus 5.5 · 2026-11-22 · branch `claude/great-bell-m374lx` · @Test 2178 → 2307 · reviewed by android-reviewer
(0 critical, 4 warnings fixed). Plan: `docs/plans/M_legacy.md`. Phone steps: `docs/TEST_CHECKLIST.md`, "M-legacy".**

## Done
- T1: NHWC input `[1,H,W,3]` (older onnx2tf TFLite) accepted with the NCHW size rules; meta v4 stores the layout;
  the engine checks the loaded layout equals the recorded one.
- T2: detect-only outputs accepted: raw `[1,4+C,N]` / `[1,N,4+C]` (C 1–80, N must equal the anchor count of the input
  size for strides 8/16/32 — refuses YOLOv5 objectness heads) and end-to-end `[1,N,6]` (N ≤ 300). `RawDetectDecoder`
  (core, no per-frame allocation), NMS shared with `RawPoseDecoder` (`RawBoxCandidates`), meta v5 with `classes=`.
  The owner's `[1,3,320,320]` → `[1,6,2100]` model is accepted.
- T3: box-only flag from the loaded model: head = box centre x, box top + 0.10 h, not "estimated" (lead stays on),
  `headBoxOnlyNoiseRatio`; re-lock uses the existing box torso (no looser rule); HUD note
  `Box-only model: head from box (class N)`; `summary.txt` `modelKind`, `detectPersonClass`.
- Review fixes: anchor-count rule, refusal texts, `lock: cleared (model kind changed)`, model closed on any Throwable.

## Decisions
- Person class = `TrackerConfig.detectPersonClass` (default 0), set in code; the owner's class meaning is unverified.
- Switching pose ↔ box-only resets the tracker and ends the lock (fail closed).

## Open
- Not run on a phone yet (NHWC preprocessing and detect decode only reasoned from code + unit tests).
- If class 0 is not "person" in the owner's model: build with 1, or add a setting with a model reload.
- No unit test for the PoseEstimator close-on-Throwable path (no fake `InferenceModel` seam).
- No `values-ar` resources; refusal texts are English only.

## Next
Owner tests the M-legacy checklist with the `[1,6,2100]` model and sends the diagnostics zip.
