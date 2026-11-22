# M1b-fix-import handoff — a new model lands where it fits

**Branch `m1b-fix-import-ux` · @Test 1744 (main after PR #21) → 1783 · reviewed by android-reviewer (no Critical; 2 warnings + 2 suggestions fixed).**

## Finding
"The second import erases the first model" was not lost data (phone v0.1.207 · 44ab6d2: both `448x960` and
`960x448` in the library). An accepted import assigned no slot, the screen briefly called the new model active,
successful imports were not logged. PR #20's guard tests were strengthened (files exist, bytes match) and merged
as PR #21 (tests only; #20 was closed because REST cannot take a PR out of draft).

## Done
- `autoSlotFor` (core, pure): a NEW, non-square model goes to the slot of its tensor shape (W>H Landscape, H>W
  Portrait) only when that slot is built-in or holds a model of the opposite shape. Fitting or square slot models,
  duplicates, blocked models, unreadable slots/blocked list → nothing assigned (fail closed).
- `ModelLibraryStore`: add + assign in one step under `LOCK`; a failed slot write keeps the model, reports no slot.
- One log line per accepted import (name · sha12 · WxH · new|duplicate · slot · library count).
- Control screen: "Added. Now used for Portrait/Landscape." / "Added to the library. Pick it…" /
  "Already in the library. Pick it…"; the last import result clears on a slot pick or a delete; the active-model
  line is decided by the reload only.

## Open
- Owner's current phone state still has both slots on `448x960`: pick `960x448` for Landscape by hand once
  (auto-assign only acts on new imports).
- A notice hidden by `modelNotice = null` on accept may flash until the reload (pre-existing, left as is).

## Next
M1b-2 benchmark screen (ARCHITECTURE §6.8), then M1b-3 (raw goldens + allocation-free pure-Kotlin decoder).
