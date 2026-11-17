# M4c — re-lock by clothing colours (handoff: MERGED)

**Plan:** `docs/plans/M4.md` (M4c). **PR:** #14. CI green, `@Test` 1498. Review: one critical defect found and
fixed (candidates by id); re-review OK.

## What the owner sees
A locked person who leaves and comes back within 60 s (frame time) gets the double ring back by itself, if
their torso colours match the remembered signature clearly better than anyone else's. Doubt = no re-lock.
Lock events are in the log (`lock: …` with distances) and `summary.txt` (`locks`, `lockRelocks`,
`lockRelockRejects`, `lockExpired`).

## Not verified
Real distances: `relockMaxDistance` 0.30 and `relockMinMargin` 0.10 are guesses; tune from the owner's logs.

## Open
Config cross-check `relockMinSamplePixels` ≤ max / 4; one test rename; one window test. Tuning sheet, M1b,
sweep-tool fixes.
