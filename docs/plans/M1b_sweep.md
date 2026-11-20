# M1b-4 — sweep tool fixes (plan)

**Approved by the owner (2026-11-20: "كمل كل اشي").** Leftovers of the PR #9 review (`tools/model_sweep.py`,
`.github/workflows/export-model.yml`; the owner allowed changes to the export workflow on 2026-11-15).

## Decisions (lead)
1. **Duplicate reference boxes stop the run:** two reference rows (score ≥ `REF_SCORE`) with IoU ≥ `MATCH_IOU` mean
   the reference is not clean → `SystemExit` with a clear message (fail closed; today they count twice).
2. **Plain-scene check per model:** before the scaled cases, every model runs on the plain image; a model that finds
   fewer than half the reference people there is marked `broken on the plain image` in sweep.md/json and its rows are
   not ranked (they are still printed).
3. **Reference note:** sweep.md says which model made the reference and that the reference favours that model's own
   misses (a person it missed is not expected from anyone).
4. **`num_threads`:** the interpreter uses a fixed thread count (`SWEEP_THREADS` = 4) for every model and sweep.md
   prints it, so CPU times are comparable between runs.
5. **`publish_tag` guard:** the workflow refuses `publish_tag` = `models-sweep` (and anything not starting with
   `models`, as today); sweep output is only ever written by the sweep job.
6. Self-tests in the tool (`--self-test`, as the existing `ok(...)` checks) for 1, 2 and 5's tag check; no Android
   code changes, no @Test count change.

## T1 — all of the above · S–M
## Progress
| Task | Status | Commit | Notes |
|---|---|---|---|
| T1 | — | | |
