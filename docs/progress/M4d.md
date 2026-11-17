# M4d handoff — re-lock tuning

**PR #15 · branch `m4d-relock-tuning` · head 5883769 · CI green (run 44150195532) · @Test 1498 → 1518.**

## Done
- `relockMaxDistance` 0.30 → 0.40 (margin 0.10), pinned by matcher tests at 0.35.
- Upper-body torso: both confident shoulders, box not whole body → from the lower shoulder down by
  `relockUpperTorsoRatio` (0.8) × horizontal shoulder width; never above either shoulder; fails closed on
  NaN/Infinity. Four confident keypoints that give no area now fail closed (no fallback).
- `cleared (no signature)` reason; `lock: pending id=N frames=K` log; config check min ≤ max/4; test rename;
  never-judged-again test (regression guard only, like the pending-log and two TorsoRegion tests).
- Docs: ARCHITECTURE v1.3 (§6.6, §6.7, §7.1, §13, §15), TEST_CHECKLIST.

## Open
- One phone lock rejected all candidates at d = 1.00 for 60 s. That needs a signature with data whose bins never
  overlap the candidates'; the cause is unknown. Read `frames=K` for such a lock in the next diagnostics.

## Next
Phone test (same as M4c); then the sweep-tool fix PR, the tuning sheet, M1b.
