# M0c — import a model, a 640 model to download, steadier marks (handoff: READY FOR THE OWNER)

**Plan:** `docs/plans/M0c.md`. **Branch:** `m0c-model-import`, PR #6. **Builds:** no local Gradle in cloud
sessions, so CI is the build.

## Status (2026-11-15; lead on Opus 5.5 on 2026-11-14 and on Fable 5.2 on 2026-11-15, the owner's choice)
- **All tasks done and green:** T1–T7, the review fixes R1, T8, T9 (owner's option B) and T10 (review R2). Last
  green run: 43675096502 (#69) on `84e313e`. `@Test` = 792 (426 on `main`). Protected files: only
  `.github/workflows/export-model.yml` (plan decision D6 and R1-10). No new dependency.
- **The 640 model** is in the pre-release `models`: `yolo26n-pose_640.tflite` (about 12 MB) and its `.json`.
- **Reviews (android-reviewer, Opus):** two passes on the first diff, one per session, nothing critical (fixes
  R1-1…R1-10 and T8); one pass on everything after it (R2): one critical defect in T9, fixed in T10, and three
  warnings (the store part of W1 is fixed; the rest is deferred with reasons, plan → "Review R2").
- **Merge:** the owner approved it in chat on 2026-11-15 ("merge after the review"); the lead merges PR #6.
- **Next step is the owner's:** install the APK from the `latest` release, run `docs/TEST_CHECKLIST.md` section
  M0c, and send `ms`, `fps` and `max` for the built-in and the imported model on the same video, plus the
  diagnostics zip.
- **After the phone test:** fix what it finds (one branch and PR per problem), then the PR "import flow hardening"
  (below). The measured `ms` and `fps` at 640 decide the rectangular input size for M1. M0d (crop to the video
  area) is parked in `docs/plans/M0d.md`.

## Where things are
- `core/modelimport`: `ModelFileSniffer`, `ImportedModelRules` (one layout: input `[1,3,S,S]`, output
  `[1,N<=300,57]`), `ImportedModelMeta`, `ImportGuard` (crash marker `Idle` / `Pending(stage)` / `Tripped(stage)`).
  `core/overlay`: `HeadRing`, `CornerBrackets`.
- `detector`: `ModelProbe` (the real tensor shapes, CPU `CompiledModel`), `PoseDetectors.fromImported` (length and
  sha256 checked again before every load).
- `app/modelimport`: `ModelImporter` (one import per process, copy with hash and size cap, early stop, guarded
  probe), `ImportedModelStore` (one slot, file named by content, first-access step), `ModelChoice`,
  `SessionModelLoader`, `ModelStatusHolder`. `app/control`: the model block, `ControlModelMapper`, `ImportResults`.
- Marks: ring and corner brackets by default, switch **Debug marks (box and body points)**.
- `tools/export_model.py --sizes --out`; `export-model.yml` with `publish_tag` (must start with `models`).

## Decisions
- **Option B (owner, 2026-11-14):** a guard tripped at the probe of a new file keeps a stored model whose length
  and sha256 still match its meta and only shows a notice; the first Start of that model lifts the trip. A trip at
  the load always switches the imported model off, and a stored trip is never replaced by the probe marker (T10).
- R1-7 stays: `accept` stores `Idle` under its own lock. The older test's exact list of writes got one more entry.
- The store protects its own temporary file: `newTempFile()` runs the app-start step, and `clear()` refuses while
  an import runs.
- Two sessions worked on this branch on 2026-11-14 and collided; the owner kept this one. From the owner's review
  copy of two files (Gemini) only the `ControlScreen` change was taken (no visible change); the `MainActivity`
  copy only re-wrapped lines and was older than T9.

## Not verified yet (needs the phone)
- Nothing of M0c has run on a device. First: the real 640 file is accepted and loads (GPU or CPU), `ms` and `fps`,
  and whether `max` for a person at medium distance is clearly higher than with the built-in 416 model.
- Marks: less blinking than v0.1.37 with ring and brackets; the Debug marks switch after Stop and Start.
- Rotating during an import; picking the file from a cloud provider; how long the hash takes at Start after a
  probe trip. The crash paths need a file that kills LiteRT and cannot be tested on purpose.

## Open items / proposals
- **PR "import flow hardening" (after the phone test):** run the import in an application scope with one busy
  marker that also spans the result (R2 W1 screen part, W3); a no-progress timeout or a Cancel button (W2: until
  then, if the buttons stay disabled after an import that hangs, close the app from recents); result handling in
  the reducer with tests (S6); S1, S2, S5.
- From the plan: the stricter export script on `fix-export-model-deps` (M1); duplicate boxes on one person, seen
  once (M1 or M3).
- AGENTS.md proposals (never edited mid-session): one session per branch, and files from other tools come through
  the lead instead of a direct push; record the ktlint rules that cost CI runs here (once one `when` entry has
  braces all need them, with blank lines between; an expression body is joined onto the `=` line when it fits in
  140; the final newline); after a push compare the run's `head_sha` with `git rev-parse HEAD` (the remote-tracking
  ref of this shallow clone is not updated by a push).
- The app's texts and the checklist are English only; an Arabic version is an owner decision.
