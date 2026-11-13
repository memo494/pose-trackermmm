# M0c — import a model file, and a 640 model to download (plan)

**Lead:** main session (Fable 5.2 this session, the owner's choice) · **Branch:** `m0c-model-import` (from `main` @ `32c6fba`)
· **Status:** approved by the owner in chat on 2026-11-13 (he asked for the import button, has no model file
yet, and accepted "I build the button and prepare a 640 file to download"; he also approved running the model
export on GitHub). Merging stays his decision.

## Why (phone evidence, v0.1.37, 2026-11-13)
Landscape works after PR #5. Detection depends on the person's size in the model input: `max 0.72` to `0.84`
for near people, `0.02` to `0.08` for far ones, flicker in between. An offline sweep on the owner's screenshots
(table in `docs/plans/M0d.md`) says a 640-wide input turns the medium cases into detections (0.00 → about 0.4
to 0.5, 0.09 → about 0.7) while the area crop alone does not. The owner then asked for a way to import a
ready-made model, which also lets him try other models without a new app build.

## Goal (what the owner sees)
On the control screen, while stopped: the active model (`Built-in 416` or the imported file with its input
size), a button **Import model** (system file picker) and a button **Use built-in model**. A file that fits is
used from the next Start; a file that does not fit is refused with the reason. The HUD shows the input size and
marks an imported model. A ready `yolo26n-pose_640.tflite` is on the repository's release page under the tag
`models`, to download on the phone and import.

**Honest limits:** a 640 model is slower (estimate 45 to 55 ms, 17 to 20 fps on this phone; to be measured).
People of 12 to 15 % of the video height stay undetected. Version 1 accepts one layout only (below).

## Lead decisions
- **D1 One imported slot.** The picked file is copied into app-private storage (`noBackupFilesDir`); importing
  again replaces it; **Use built-in model** switches back and deletes the copy. Import and reset are possible only
  while the capture is stopped.
- **D2 Accepted layout, version 1 (fail closed).** Exactly one float32 input `[1, 3, S, S]` (NCHW, square, `S` a
  multiple of 32 inside the limits in `TrackerConfig`) and exactly one float32 output `[1, N, 57]` (end-to-end
  pose head, 17 keypoints, `N` > 0). This is the layout of the bundled models and of default `yolo26*-pose`
  LiteRT exports. Anything else (detect models, the classic `[1, 56, N]` head, another keypoint count, NHWC,
  non-square) is refused with a message that names the shapes found. Rectangular inputs come with M1.
- **D3 The real tensors decide.** Validation reads the tensor shapes from the model itself through LiteRT
  (`CompiledModel`, CPU, on a worker thread, closed afterwards). Ultralytics metadata inside the file may only
  make a refusal message clearer.
- **D4 Never worse than the built-in model.** If the imported model cannot be loaded at Start, the session
  falls back to the built-in model and says so (HUD warning and control-screen notice). A native crash cannot
  be caught, so a pending marker is written before LiteRT first opens the file (probe) and before the first load
  in a session, and cleared afterwards; a marker found at the next start disables the imported model and tells
  the owner.
- **D5 The 640 file is a download, not part of the app.** `model/**`, the APK and `model_info.json` do not
  change. The export workflow builds the file with the pinned toolchain (`tools/requirements.lock`, the versions
  that produced the bundled models), checks it and uploads it to the release tag `models`.
- **D6 Protected files named explicitly:** `.github/workflows/export-model.yml` (T1 only). Nothing else that is
  protected changes. No new dependency (`ActivityResultContracts.OpenDocument` is in the activity library
  already used; `SharedPreferences` and files are the platform's).
- **D7 Not in M0c:** rectangular models, other output layouts, several imported models, a benchmark screen
  (M1), the area crop (M0d), Arabic texts.

## Verification in this environment
No Android SDK in cloud sessions: **no local `scripts/verify.sh`**. CI runs on pull requests, so the lead opens
a **draft PR** after T1; implementers commit, push the branch (plain push, never force), wait about 90 s and read
`scripts/ci-status.sh`, checking that the run's `head_sha` is the pushed commit.

## Tasks (sequential on this branch; agent = implementer)

### T1 — Export a model file for download · S/M · tools + the export workflow
**Files:** `tools/export_model.py`, `.github/workflows/export-model.yml` (protected, in scope for this task only).
- Script: remove the unused `import tensorflow as tf`. New options `--sizes 640[,…]` (default: today's
  `320,416`) and `--out DIR`. With `--out` the script writes only `DIR/yolo26n-pose_<size>.tflite` and
  `DIR/yolo26n-pose_<size>.json` (tensor shapes and dtypes, sha256, bytes, package versions) and does not touch
  `model/`. It fails (non-zero exit, nothing published) unless the exported model has one float32 input
  `[1,3,S,S]` and one float32 output `[1,300,57]`, and `predict()` on the Ultralytics sample image finds at
  least two people with a score of 0.5 or more. Without `--out` the behaviour stays as it is today.
- Workflow: `workflow_dispatch` inputs `sizes` and `publish_tag` (both empty by default = today's behaviour,
  untouched). With `sizes`: install from `tools/requirements.lock`, run the script with `--out`, upload the
  files as a workflow artifact, and with `publish_tag` create the release if it is missing (pre-release, never
  marked latest, so the rolling `latest` APK release is not disturbed) and upload the files to it, replacing
  older ones of the same name. The job summary lists the files and their sha256.

**Check:** the script cannot run here (Ultralytics is not installed), so run `python3 -m py_compile
tools/export_model.py`, keep every import of Ultralytics, torch and LiteRT inside the functions that need them so
`--help` works without them, and read the diff carefully. The lead dispatches the workflow on this branch after
the push (`sizes=640`, `publish_tag=models`) and reads the result.
**Stop and report** if the pinned install or the export fails twice; do not loosen the checks.

### T2 — Core: rules for an imported model · M · pure Kotlin, new package `core/modelimport`
**Files:** `core/modelimport/{ModelFileSniffer,ImportedModelRules,ImportedModelMeta,ImportGuard}.kt`,
`TrackerConfig.kt` (import limits); tests for each.
- `ModelFileSniffer.looksLikeTflite(header: ByteArray, fileBytes: Long, config)`: the FlatBuffers identifier
  `TFL3` at bytes 4 to 7 and a size inside `importMinBytes..importMaxBytes`.
- `ImportedModelRules.check(inputs, outputs, config): ImportCheck` where a tensor is (shape, element type name):
  `Accepted(inputSize, rows)` or `Rejected(reason: ImportRejection, details: String)`; one reason per rule of D2,
  and the details always carry the shapes found.
- `ImportedModelMeta` (display name, sha256, bytes, input size, output rows) with a strict text codec
  (`encode` / `decode` returning `null` on anything unexpected); display names are sanitised (no path, length cap).
- `ImportGuard`: the pure state machine of D4 (`Idle`, `Pending(stage)`, `Tripped`) with `begin`, `succeed`,
  `onAppStart`; text form for one preference value.
- `TrackerConfig`: `importMinBytes`, `importMaxBytes`, `importMinInputSize`, `importMaxInputSize`,
  `IMPORT_INPUT_SIZE_STEP` = 32, with bounds like the other fields.

**Tests first:** every accept and every rejection of D2 (wrong rank, batch not 1, channels not 3, NHWC,
non-square, size not a multiple of 32, below or above the limits, two inputs, two outputs, 56-column and
`[1,56,N]` outputs, non-float types); sniffer (short header, wrong identifier, too small, too large); codec round
trip and every malformed text; guard transitions including a marker found at start.

### T3 — Detector: probe a file and build a detector from it · M
**Files:** `detector/{PoseDetectors,ModelProbe,UltralyticsPoseEngine,LiteRtPoseDetector}.kt` (KDoc where only
wording changes); tests `LiteRtPoseDetectorTest`, new `ModelProbeTest` for the pure part.
- `ModelProbe.probe(context, file): ProbedModel` (worker thread only): opens the file with LiteRT
  (`CompiledModel`, CPU), reads the input and output tensor shapes and element types, closes it. Any exception
  or `Error` becomes a result the caller can show; nothing is rethrown to the UI thread.
- `PoseDetectors.fromImported(context, file, meta, config)`: GPU first with the CPU fallback like `fromAssets`,
  expects `meta.inputSize`, no asset staging. `fromAssets` is unchanged.
- The engine keeps requiring NCHW and a square input (D2).
- **Stop and report** if a vendored file must change beyond reading shapes (log any change in
  `third_party/ULTRALYTICS.md`).

**Tests first:** the detector built from a fake engine reports the imported size and refuses a size that differs
from the meta; the probe's mapping from raw tensor descriptions to `ImportedModelRules` inputs.

### T4 — App: import use case, storage, and the model choice at Start · M
**Files:** new `app/modelimport/{ImportedModelStore,ModelImporter,ModelChoice}.kt`;
`app/capture/{CaptureService,CaptureSession}.kt`; `app/overlay/{OverlayState,HudFormatter}.kt`;
`app/diagnostics/{CaptureStats,CaptureStatsHolder,DiagnosticsReport,DiagnosticsZip}.kt`; tests
`HudFormatterTest`, `DiagnosticsReportTest`, new `ModelChoiceTest`.
- `ModelImporter.import(uri)` on a worker thread (never the main thread): sniff → copy to a temporary file
  inside `noBackupFilesDir/imported` while hashing (size cap enforced during the copy) → guard `begin(PROBE)` →
  `ModelProbe` → `ImportedModelRules` → on accept: atomic rename, save the meta, guard `succeed`; on reject or
  failure: delete the temporary file and return the reason. The previous imported model stays until a new one
  is accepted.
- `ModelChoice.resolve(meta, fileExists, guard)`: pure; `Imported(meta)` or `BuiltIn(reason?)`.
- `CaptureSession.startCapture`: resolves the choice on the capture thread; for an imported model guard
  `begin(LOAD)` → `PoseDetectors.fromImported` → `succeed`; on any failure log it, fall back to `fromAssets`
  and publish a warning that the built-in model is in use.
- HUD: the input size, with a mark when the model is imported (within `HUD_LINE_CAPACITY_CHARS`).
  `summary.txt` and `config.txt`: model source, file name, short sha256, input size, output rows, fallback reason.

**Tests first:** `ModelChoice` for every combination (no meta, file missing, guard tripped, all good); HUD text
for built-in and imported; the report fields.

### T5 — App: control screen · M
**Files:** `app/control/{ControlState,ControlScreen}.kt` (the reducer lives in `ControlState.kt`), `MainActivity.kt`,
`res/values/strings.xml`; test `ControlReducerTest`.
- State: the active model description, `importing`, the last import result (accepted, or a rejection with its
  details), guard notice. Events: `ImportClicked`, `ImportPicked(uri?)`, `ImportFinished(result)`,
  `UseBuiltInClicked`, `ModelStateLoaded`. Effects: `OPEN_MODEL_PICKER`, `RUN_IMPORT`, `CLEAR_IMPORTED`.
  Import and reset are ignored while running or importing; Start is disabled while importing.
- Screen: a "Model" block with the active model, **Import model**, **Use built-in model** (only when one is
  imported), a progress line while importing, and the result text. One short line: only import files you trust.
- `MainActivity`: `OpenDocument` launcher (`*/*`), runs the importer off the main thread, reads the stored model
  state off the main thread when the screen starts and after each import.

**Tests first:** the reducer for every new event in the stopped, running and importing states; a rejection keeps
the previous model; `UseBuiltInClicked` clears the notice.

### T6 — Docs · S
**Files:** `docs/ARCHITECTURE.md` (§8 model pack: imported models, D1 to D5; §6.5 the stale `onSizeChanged`
sentence left by PR #5; §6.7 new diagnostics fields; §12 rows for M0c and M0d; §15 change entry),
`docs/TEST_CHECKLIST.md` (section "M0c — import a model" from the acceptance list below).

## Then (lead)
`android-reviewer` on the whole diff → fixes through the implementer → PR ready, CI green → handoff
`docs/progress/M0c.md`, `docs/REPO_MAP.md`, PR text ending with "How to test on the phone" → ask the owner
before merging.

## Acceptance on the phone (becomes the checklist section)
1. Download `yolo26n-pose_640.tflite` from the `models` release. In the app, stopped: **Import model** → pick the
   file. The screen shows the file name and `640`.
2. Start: the corner text shows the input size 640 and the imported mark. Note `ms` and `fps`.
3. The same video as before: the `max` number for a medium-distance person is clearly higher than with the
   built-in model, and the box flickers less.
4. **Use built-in model** → Start: back to 416 and the old speed.
5. Import a file that is not a model (a photo): refused with a reason, the previous model stays.
6. Share diagnostics: `summary.txt` names the model source, file and input size.

## Open items
- Measured `ms` and `fps` at 640 on the phone decide the rectangular size for M1 (352x608 or 384x640).
- The unmerged branch `fix-export-model-deps` holds a stricter export script (self-check against `predict()`,
  raw-output goldens, public-domain fixture). Decide in M1 whether to bring it to `main`.
- Duplicate boxes on one person were seen once (v0.1.37): dedupe in M1 or M3.

## Progress
| Task | Status | Commit | Notes |
|---|---|---|---|
| T1 | done, not yet run in CI | a8f943a, 0efa029 | script and workflow written; not run here (no Ultralytics); lead dispatches sizes=640 publish_tag=models |
| T2 | — | | |
| T3 | — | | |
| T4 | — | | |
| T5 | — | | |
| T6 | — | | |
