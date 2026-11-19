# M4f handoff — gate floor and earlier re-lock

**PR #17 · branch `m4f-gate-floor-early-relock` · @Test 1559 → 1589 · reviewed twice (one critical, one major, fixed).**

## Done
- `trackGateMinSigmaRatio` 0.2: gate-only variance floor; M3 suite identical in the reference; swaps unchanged.
- Early re-lock while the held track is LOST, only with spatial evidence: candidate within `relockEarlyMaxHeadRatio`
  (0.6) × held height of its predicted head and no other track (tentative included) in that radius; otherwise pending
  decides as before. Log `... early`.
- Rejected: a recovery stage by last observation (switches ÷ 2–5 but more swaps).

## Accepted risk (owner, option 1)
A lone same-coloured look-alike within 0.6 h of a hidden person's coasting head can take the lock early.

## Next
Phone test: `assocMissGate` should fall by ≥ half, births/min fall, any `early` re-lock must be the same person.
