# M1b-fix-import — a new model lands where it fits, and the screen says so

Lead: Opus 5.5 · branch `m1b-fix-import-ux` from `main` · owner approved 2026-11-22 ("best decision").

## Finding (2026-11-22)
Report "the second import erases the first model" is **not a code bug**. Phone v0.1.207 · 44ab6d2: the library
holds both `448x960` and `960x448` (control-screen screenshot); both slots point to `448x960`, `modelSwitches=0`.
Cause: an accepted import only adds to the library and never touches a slot, the screen briefly calls the new
model "In this orientation", a successful import is not logged, and a later rejected import's red text stays under
the list. PR #20 (debugger's guard tests, no fix) is merged as tests only.

## Decision — auto-assign, narrowly
After an import is **accepted as a new library entry** (not a duplicate), set slot S to it only when ALL hold:
- the model's input tensor is not square; W > H → Landscape, H > W → Portrait (from the tensor shape, never the
  file name);
- slot S is the built-in model, **or** S holds a library model whose own shape does not fit S (e.g. a tall model
  in Landscape, the migration state where both slots share one model).
A slot that already holds a model of the right shape is never overwritten; square models and duplicates assign
nothing. Fail closed: if the slot record cannot be read or written, the import still counts as stored, nothing is
assigned, and the message says to pick it by hand.

## T1 — pure rule (core) · S · implementer
Files: `core/.../modelimport/` (next to `ModelOrientation`/`ModelLibrary`). A pure function
`autoSlotFor(newModel, slots, library): ModelOrientation?` per the decision above.
Tests first (JUnit 5): tall→Portrait when Portrait built-in; wide→Landscape when Landscape holds a tall model
(owner's exact case); no assign when the slot already fits; square → null; duplicate → null; both slots built-in →
only the matching one.
Accept: tests fail without the function; verify.sh PASS; @Test count up.

## T2 — apply it, log it, show it (app) · M · implementer
Files: `ModelLibraryStore` (apply T1 inside the same `LOCK` as `store`, so add+assign is one step),
`ModelImporter`/`ImportOutcome`/`ImportSummary` (carry the assigned slot or null), `MainActivity.importOrFail`
(one `AppLog` line per accepted import: name · sha256 first 12 · input WxH · new|duplicate · slot assigned or none ·
library count), `ControlState.onImportFinished` (do not set `activeModel` to the new model unless the slot of the
current orientation got it — the reload decides), `ControlScreen.ImportResultText` + strings
(accepted: "Added. Now used for Landscape." / "Added to the library — pick it under Portrait or Landscape.").
Also: the last import's result text is cleared when the owner changes a slot or deletes a model.
Tests first (JUnit 4, app): store assigns per T1 and leaves a fitting slot alone; a slot write failure keeps the
model stored and reports no slot; `onImportFinished` keeps `activeModel` unchanged when no slot of this orientation
changed; result text cleared on slot change and delete; the log line is written once per accepted import.
Accept: verify.sh PASS; @Test not lower; no protected file; no main-thread file work.

## Review and ship
android-reviewer on the full diff → fix Criticals → PR ending with "How to test on the phone" → `ci-status.sh`
green → lead merges (owner's standing OK). Then M1b-2.

## Consults
None.
