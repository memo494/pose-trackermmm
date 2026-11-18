# M4e handoff — id-switch diagnostics

**PR #16 · branch `m4e-id-switch-diagnostics` · head 9c3137e · CI green (run 44309685657) · @Test 1518 → 1559.**

## Done
- Every birth next to another track gets one record: `reason=gate|cost|taken` (next to a LOST track, with the matcher's
  own gate χ², IoU, headRatio, hRatio, cost, stage, score, lostMs), `reason=live state=… detIou=…` (next to a track that
  is not LOST), `reason=removed cause=lostTime|exit|other` (next to a track removed this frame).
- Counters in summary.txt: `assocMisses`, `assocMissGate`, `assocMissCost`, `assocMissTaken`, `assocMissLowBirth`,
  `assocBirthNearLive`, `assocBirthAfterRemoval`. Log ≤ 30 lines/min plus `track: miss suppressed N`.
- Cost math shared with `AssociationCost` (exact equality test); identity test proves tracks are unchanged.
- Simulation `docs/plans/M4_sim/walk.py`: normal walking does not switch ids at 8–20 fps; the phone does.

## Notes
- live/removed values use the track state after the frame (not exactly what the matcher saw). `EXIT` cause untested.

## Next
Phone test (walk, turn, sit with a lock on) → read the reasons → M4f tunes the failing term, simulation updated to match.
