# M-legacy — older models run, and the dot comes from the person box when there are no keypoints

**Lead: Opus 5.5 · 2026-11-22 · branch `claude/great-bell-m374lx` (session branch) · status: APPROVED by the owner for T1, T2, T3 (2026-11-22)**

## Analysis — why older models do not run today
The import guard (`core/modelimport/ImportedModelRules.check`) refuses two families that most older YOLO `.tflite`
files belong to:

| Older model | Typical tensors | Today | Why |
|---|---|---|---|
| YOLOv8/v11 pose, older Ultralytics TFLite (onnx2tf) | in `[1,H,W,3]` NHWC, out `[1,56,8400]` | refused `input_nhwc` | guard and `UltralyticsPoseEngine.wrap` (`check(inputUsesNchw)`) demand NCHW — although the vendored `LiteRtModel` already preprocesses NHWC (`inputUsesNchw=false`, legacy onnx2tf path) |
| YOLOv8/v11 **detect** (no keypoints) | out `[1,84,8400]` (COCO) or `[1,5,N]` (person only) | refused `output_classic_head` / `output_detect_only` | the app only decodes pose heads (17 keypoints) |
| YOLO26 detect end-to-end | out `[1,300,6]` | refused `output_shape` | same |

Raw pose output (`[1,56,N]`), normalized vs pixel coordinates and NMS already work (F2, `core/decode/RawPoseDecoder`).
`HeadPointEstimator` already has a **box fallback** (`HeadPoint.estimated`) for a person with no usable head keypoint,
so a detect-only model can place the dot from the person box alone — "the app finds the person from the detection".

## Tasks (sequential, implementer, tests first)
| # | Goal | Files | Tests first | Acceptance | Size |
|---|---|---|---|---|---|
| T1 | Accept NHWC input `[1,H,W,3]` (float32) with the same size/step/aspect rules; meta records the layout (new meta version, v1/v2 still read as NCHW); `wrap` accepts both layouts; `fitsSlot` uses (W,H) whatever the layout | `ImportedModelRules`, `ImportedModelMeta`, `UltralyticsPoseEngine`, `third_party/ULTRALYTICS.md` if vendored code changes | rules: NHWC accepted with W/H read from axes 2/1; NHWC too-small / not-multiple still refused; meta round trip + old v2 text still decodes | `input_nhwc` key kept but no longer returned for a valid NHWC model; @Test count up | S |
| T2 | Detect-only decoder in `core`: `[1,4+C,N]`, `[1,N,4+C]` (C = 1 or 80, person = class 0) and end-to-end `[1,N,6]` (x1,y1,x2,y2,score,class); reuse the normalized-frame rule and class-agnostic NMS of `RawPoseDecoder`; no per-frame allocation; detections carry 17 keypoints with confidence 0. Other channel counts (e.g. YOLOv5 `85` with objectness, `[1,N,85]`) stay refused (fail closed) | `core/decode/` (new `RawDetectDecoder`), `PoseOutputLayout` (new DETECT_* layouts), `ImportedModelRules`, `ImportedModelMeta`, `PoseEstimator` (vendored, logged) | decode of synthetic tensors (both axes orders, normalized + pixels, non-person classes dropped, NMS, score threshold); rules accept 5/84 channels and `[1,N,6]`, refuse 85 | person boxes match the synthetic truth within 1 px; zero allocations per frame (existing alloc test pattern) | M |
| T3 | Head dot from the box for box-only models: the estimator's box fallback is used every frame, stable (no keypoint-offset learning), position from `TrackerConfig` fields (`headFromBoxYRatio` etc.); re-lock torso judge is skipped (box-only lock keeps the shirt-colour signature only, never looser than today's wrong-person rule); HUD note `Box-only model: head from box` and `modelKind=pose|box` in `summary.txt` | `core/tracking/HeadPointEstimator`, `TrackerConfig`, lock judge, `app/overlay` HUD, diagnostics | estimator: no-keypoint detections give a point at the configured ratio of the box, jitter ≤ box jitter; tracker scenario with keypoint-free detections keeps one track per person; HUD text mapping | existing tracker scenarios unchanged (protected thresholds untouched) | M |
| T4 | Control screen texts for the new accepted kinds and the remaining refusals (Arabic + English), ARCHITECTURE §8 update, TEST_CHECKLIST "M-legacy" phone steps | `app/control`, `docs/ARCHITECTURE.md` §8, `docs/TEST_CHECKLIST.md` | reducer/mapper tests for the new rejection/notice texts | — | S |

Then: `android-reviewer` on the whole diff → push → PR (owner merges).

## Open questions for the owner
1. Is this the problem you meant ("older models are refused at import")? If you have a specific model file or
   diagnostics zip that fails, its tensor shapes / the refusal text decide the order.
2. "يحدد مكان الشخص من الكشف" is read here as: **detect-only models (no keypoints) are accepted and the dot is
   placed from the person box.** Say if you meant something else (e.g. the dot should follow the box instead of the
   head keypoints even for pose models).
3. YOLOv5 (`[1,N,85]`, objectness column) is left refused in this plan; add it as T5?

## Owner answers (2026-11-22)
- Yes, the import refusal is the problem. Do T1, T2, T3.
- The owner's model: input `[1,3,320,320]` (NCHW, already accepted), output `[1,6,2100]` = raw channels-first
  detect head with **2 classes** (4 box + 2 class scores; 2100 = 40²+20²+10² anchors at 320). Today refused as
  `output_detect_only`. So T2 must accept **any class count C from 1 to 80** (not only 1 or 80); the person class is
  `TrackerConfig.detectPersonClass` (default 0). The meaning of the classes is unknown → class 0 = person is an
  assumption the owner checks on the phone (T3 adds the class index to the HUD/summary so a wrong guess is visible).
- Shape rule (fail closed): `[1,4+C,N]` with `4+C < N` = raw channels-first; `[1,N,6]` with `N <= 300` = end-to-end
  (x1,y1,x2,y2,score,class); `[1,N,4+C]` with `N > 300` = raw channels-last. `5+3k` channel counts (8, 11, …) stay
  `output_keypoint_count` (unchanged); YOLOv5 `85` (objectness) stays refused.
- T4 is folded in: each task ships its own control-screen / HUD texts; ARCHITECTURE §8 note in T3.

## Consults
None yet (budget 3).
