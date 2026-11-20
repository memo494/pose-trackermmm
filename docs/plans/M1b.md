# M1b — models per orientation, benchmark, decoder (plan)

**Approved by the owner (2026-11-20: "كمل تطوير m1b").** Rules of 2026-11-15 hold: lead plans and merges after green
CI and an independent review; `model/**` and the export workflow may change for the decoder part.

## Order (one PR each, what the owner sees first)
1. **M1b-1 — a model per orientation** (this PR): portrait and landscape each use their own model.
2. **M1b-2 — benchmark screen** (ARCHITECTURE §6.8): fixture inference p50/p95 per model and accelerator,
   capture fps and latency on moving content, thermal, CSV in the diagnostics, 10-minute soak.
3. **M1b-3 — raw-output goldens + allocation-free pure-Kotlin decoder** (golden decode test; export workflow
   writes the raw `[1,300,57]` output and the exact input tensor for each size).
4. **M1b-4 — sweep tool fixes** (review of PR #9).

## Why M1b-1 first
The owner watches both orientations. Today one imported model serves both: a square 640 is good for portrait but
wastes half the input on the letterbox in landscape (content scale 0.47); a landscape model (e.g. 832x384, scale
0.61, about the same time) is good for landscape and bad for portrait (0.28). Only a per-orientation choice gets
both.

## M1b-1 decisions (lead)
1. **Model library:** up to `modelLibraryMax` (4) imported models, each the same meta as today (sha256 is the key),
   files stored as today. Import adds to the library (a duplicate sha is not added twice); a model can be deleted.
2. **Two slots:** `PORTRAIT` and `LANDSCAPE`, each "built-in" or one library sha. Defaults: both built-in.
   **Migration:** the single model imported before this version enters the library and both slots point to it, so
   nothing changes for the owner until he chooses.
3. **Orientation** = the capture geometry: width > height is landscape. The model for the session start is the
   slot of the start geometry.
4. **Switch on rotation:** when the capture geometry changes orientation and the slot's model differs from the
   loaded one, the capture thread closes the old detector and loads the new one (never the main thread). The
   crash guard (LOAD stage) is per sha as today: a model that tripped it falls back to the built-in for that slot
   with the existing banner reasons. A load failure falls back to the built-in, never stops the session.
   The tracker is reset on a switch (geometry changed anyway); the lock follows today's reset rule.
   Log `model: <name> for landscape|portrait (switch Nms)`.
5. **Control screen:** the library list (name, size WxH, delete), and two pickers "عمودي / Portrait" and
   "عرضي / Landscape" with the built-in and the library models; the existing labels stay in English like the rest
   of the screen unless they already have Arabic.
6. **Diagnostics:** summary.txt gets `modelPortrait=` and `modelLandscape=` (name and sha prefix or built-in),
   `modelSwitches=`, `modelSwitchMsMax=`; the existing `modelSource/modelFile/...` describe the model loaded now.
7. Fail closed everywhere: an unreadable library or slot record = built-in for that slot, logged.

## Tasks (implementer, one branch)
- **T1 core** · M: pure library/slot logic (`ModelLibrary`, `OrientationSlots`, resolve per slot with the guard,
  migration from the single-model state, orientation from geometry, config values) + tests.
- **T2 app store + control screen** · M: store several models and the slot record (atomic writes as today),
  import adds to the library, delete, pickers; tests.
- **T3 capture switch + diagnostics + docs** · M: switch on orientation change on the capture thread, guard,
  fallback, logs, summary counters; ARCHITECTURE (version bump), TEST_CHECKLIST "M1b-1"; tests.

## Acceptance on the phone
Import a landscape model (e.g. 832x384 from the release `models`) next to the 640; choose 640 for portrait and
832x384 for landscape; rotate during a capture: the HUD shows the right model after each rotation, no crash; the
diagnostics show both slots and the switches.

## Progress (M1b-1)
| Task | Status | Commit | Notes |
|---|---|---|---|
| T1 | — | | |
| T2 | — | | |
| T3 | — | | |
