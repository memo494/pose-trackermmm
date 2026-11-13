# M0b — screen-capture overlay: first green dots over other apps (plan)

**Lead:** main session, Opus 5.5 · **Branch:** `m0b-screen-overlay` · **Status:** approved by owner 2026-11-10 (D1–D3 accepted)

## Goal
Owner sees, on the Poco X7 Pro: start the app → grant overlay + screen-capture → open any app with people
(video, stream, game) → **green boxes + keypoints drawn over the people**, HUD (version+SHA, accelerator, fps,
inference ms), red "Running on CPU" banner on fallback, taps still reach the app underneath, Share diagnostics.

## Owner decisions (2026-11-10)
1. Product = **screen-capture overlay** (MediaProjection → pose → floating overlay over other apps), not the
   CameraX camera app of ARCHITECTURE v0.5. CameraX is dropped from the code (catalog entry left as-is; it is
   protected — remove in a later deliberate catalog PR).
2. GitHub CLI installed in this session; owner logs in (`gh auth login`).

## Prerequisite (lead, before T4)
Model export: `gh workflow run export-model.yml` on `main` → review its PR (shapes in `model/model_info.json`)
→ owner merges → rebase this branch. T1–T3 do not need the model and start first.

## Verification in this environment
This session runs in aarch64 proot on a phone (SDK 34 only, x86-only AAPT2, ~1.6 GB free RAM): **no local
`scripts/verify.sh`**. Implementers commit, push the branch and read `scripts/ci-status.sh` (CI = source of
truth). In a claude.ai/code cloud session, use `scripts/verify.sh` as normal.

## Consult log (budget 1/3 used)
**C1 — Is MediaProjection → VirtualDisplay → ImageReader → plain-View overlay right, and what must change?**
Answer (Fable 5.1, high confidence on items marked *): keep the architecture, with these changes:
- **A\*** Overlay `LayoutParams.alpha` ≤ `InputManager.getMaximumObscuringOpacityForTouch()` (0.8, API 31+),
  or Android 12+ blocks touches to the app below.
- **B** No unconditional `postInvalidateOnAnimation` loop. The overlay is captured, so redraw → frame → infer →
  redraw would loop. Invalidate only on a new snapshot (sub-pixel deadband); HUD text ≤ 2 Hz.
- **C** Timestamp: listener callbacks queue behind inference, so use `image.timestamp` (CLOCK_MONOTONIC = the
  `nanoTime` base), guarded by `0 ≤ now − ts ≤ 100 ms`. If the guard fails, fall back to `nanoTime` at acquire
  and flag it in the HUD.
- **D\*** Copy the frame, then `image.close()` **before** inference. `maxImages = 2`.
- **E\*** Vendored `ImageUtils.toBitmap` is CameraX-only and allocates per frame; `prepareBitmapForModel` needs
  an exact-width source. Our adapter row-copies into a reused exact-width buffer (stride padding, short last
  row). `LiteRtModel.kt:198-202` and `PoseEstimator.kt:105-150` allocate per frame → see decision D2.
- **F** API 34+: `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`. A capture
  that is not display-sized fails closed (dots hidden, HUD warning).
- **G** Rotation is M0b scope (games and videos run landscape): `onCapturedContentResize` (34+) /
  `DisplayListener` (26–33) → new reader + `virtualDisplay.resize()` + `setSurface()`, never a second VD.
- \*Overlay is in the capture; FLAG_SECURE would black out the whole capture → mitigate with hollow rings, thin
  strokes and a corner HUD, then measure (dot A/B in the checklist).
- Plain `View`, not ComposeView, for the overlay. M4 tap-to-lock = a small touchable handle window that arms
  the overlay for one tap.
- Teardown order (idempotent): remove overlay → `virtualDisplay.release()` → reader close on the capture thread
  → detector close → `projection.stop()` → quit thread → `stopSelf`. Every restart needs new consent.
**Decisions:** all adopted (D1). Docs follow-ups go into T1.

## Lead decisions for the owner to approve
- **D1** Adopt C1 A–G as written.
- **D2** AGENTS rule 8 (no per-frame allocations in the analyzer): our own code complies from M0b. The
  **vendored decoder's** per-frame allocations (E) are logged as a known issue in `third_party/ULTRALYTICS.md`
  and removed in **M1** (whose scope already says "buffers allocated once"). Reason: M0b goal is first dots;
  rewriting the vendored decoder now doubles M0b's size.
- **D3** Not in M0b: tracking, smoothing, lock, benchmark, thermal, replay mode, single-app capture mode.

## Tasks (sequential on this branch; agent = implementer unless noted)

### T1 — ARCHITECTURE v0.6 for screen capture · S/M · docs only
**Goal:** the design doc matches the new product so every later task reads the truth.
**Files:** `docs/ARCHITECTURE.md` only.
**Change:**
- §1: scope, permissions (overlay, capture consent, notifications), portrait + landscape.
- §2 and §2.1:
  - stack becomes MediaProjection + ImageReader + plain-View overlay;
  - drop the CameraX part of `YOLOView` and `ImageUtils.toBitmap`.
- §3: app = capture service + overlay + control activity.
- §4.1: timestamp rule (C1-C).
- §4.2: spaces.
  - Img = capture content rect.
  - View = overlay window.
  - Img→View = fit-centre scale + offset.
  - Geometry table tests change accordingly.
- §5: thread table.
  - capture `HandlerThread` = analysis thread and owns the `CompiledModel`;
  - main = overlay.
- §6.3: no frame on a static screen = no information, not a miss.
- §6.4: prediction now looks forward (content is already on screen).
- §6.5: plain View with the invalidate rules (C1-B) and the alpha cap (C1-A).
- §6.6: handle-window lock.
- §6.7: capture info instead of camera fps.
- §6.8: capture fps + benchmark.
- §9: test table.
- §12: milestones; M2 = Img→View + rotation hardening, no camera.
- §13: open items (HyperOS restrictions, dot A/B, DRM/secure apps capture black).
- §15: v0.6 entry.

Keep the doc's structure and section numbers.
**Tests:** none (docs). **Accept:** every C1 point appears in the right section; no CameraX instructions remain
except a "dropped" note; `@Test` count unchanged.

### T2 — Vendor the Ultralytics pose core · M
**Goal:** upstream pose inference + decoding compiles inside `detector/`.
**Files:**
- `detector/src/main/kotlin/com/ultralytics/yolo/{LiteRtModel,Predictor,PoseEstimator,GeometryUtils,Utils,YOLOResult,YOLOTask,ImageUtils}.kt`
  (ImageUtils without the CameraX `ImageProxy` functions);
- `detector/build.gradle.kts` (add `libs.litert`, `libs.litert.gpu` — already in the catalog);
- root `LICENSE` (AGPL-3.0 text copied from upstream's LICENSE);
- `third_party/ULTRALYTICS.md`: pinned commit, kept/dropped files, change log (file · what · why), known issues
  (threading → M1; per-frame allocations → M1, D2).

**Source:** pinned commit `c97bf347ddbd94b48f18c7264c4856294aee4d01`, already cloned read-only at
`/tmp/claude-0/-root-pose-trackermmm/be277423-7f0a-48eb-9a06-8d91895e7ece/scratchpad/upstream/yolo-flutter-app/android/src/main/kotlin/com/ultralytics/yolo/`.

**Rules:**
- keep the upstream headers and the package;
- strip only what is needed to compile without Flutter, other tasks or QNN, and log each strip;
- no `org.tensorflow:*`.

**Tests first:** a JUnit4 test of a pure decode helper, e.g. output-layout detection for `(1,300,57)` vs the
classic layout. If no helper is pure without refactoring vendored code, report it instead.
**Accept:** CI green; `@Test` count not lower; ULTRALYTICS.md complete.

### T3 — Core foundations (pure Kotlin, JUnit5) · M · **may create `core/**/geometry/**` (explicitly allowed)**
**Goal:** all pure math and types that M0b needs, tested on the JVM.
**Files:** `core/src/main/kotlin/com/memo494/posetracker/core/`:
- `model/` (`PoseDetection` with Img-suffixed box/keypoints, `DetectionFrame` with `timestampNs` +
  `timestampSource`);
- `TrackerConfig` (score threshold 0.15, capture scale ½, HUD rate 2 Hz, timestamp guard 100 ms, overlay alpha
  cap 0.8 fallback, dot stroke), each with unit/default/min/max/KDoc;
- `geometry/Letterbox` (Img↔Mdl);
- `geometry/FitCenter` (capture content rect inside the VD; Img→View scale + offset, invertible);
- `capture/CaptureSize` (display size × scale, exact aspect, even dimensions);
- `capture/RowCopy` (ByteBuffer with rowStride/pixelStride → tight reused buffer; short last row);
- `capture/FrameTimestamp` (guard + fallback).

Delete the `CoreMath` placeholder only if nothing uses it; its tests then move, never drop.

**Tests first:**
- letterbox round-trip;
- FitCenter corners → view corners, portrait + landscape, with offset;
- RowCopy padded stride + short last row;
- CaptureSize 1220×2712 → 610×1356;
- timestamp guard: in range, negative, > 100 ms;
- config defaults within min/max.

**Accept:** CI green; no `android.*`; no magic numbers outside TrackerConfig.

### T4 — Detector adapter + model packaging · M · needs the model PR merged
**Goal:** `PoseDetector` (ARCHITECTURE §6.1) over the vendored code, fed from a reused exact-width buffer.
**Files:** `detector/.../PoseDetector.kt`, `LiteRtPoseDetector.kt`, `FakePoseDetector.kt`; `app/build.gradle.kts`
(assets from `model/` and a build-time sha256 check against `model_info.json` that fails with a clear message).

**Rules:**
- `Options(Accelerator.GPU)` only; on `LiteRtException` log and recreate with CPU, then report CPU;
- one warm-up;
- input shape read from the model;
- timing includes readback;
- score < `TrackerConfig` threshold dropped;
- create/run/close called only by the owning thread; the class is thread-confined and documented so.

**Tests first (JUnit4):**
- GPU-factory throws → detector reports CPU (via a factory seam);
- FakePoseDetector replays a JSON fixture;
- sha256 check rejects a tampered file (Gradle task test or a pure function).

**Accept:** CI green; `model/` untouched.

#### T4 split (lead, 2026-11-13): three implementer runs, CI after each
T4 as written is larger than M, so it runs as T4a → T4b → T4c. Findings that shape it:
- Vendored `LiteRtModel` loads from a **file path** (`CompiledModel.create(modelPath, …)`), and the
  litert-runtime skill says to stage the asset into `noBackupFilesDir` (versioned name, temp file + rename)
  instead of `create(assets, …)`. So the model is packaged as an asset and staged to a file at start.
- `core` is pure JVM with no JSON library and the catalog is protected → a small strict JSON reader in `core`
  (ARCHITECTURE §3 "replay I/O"). No new dependency.
- No local Gradle in this cloud session either (no network for the wrapper): the implementer pushes
  `m0b-screen-overlay` (plain push, never force) and reads `scripts/ci-status.sh`.
- `OverlayState.inferenceMs` needs a source → `PoseDetector.lastInferenceMs` (addition to §6.1).
- Default model size for M0b = **416** (thermal policy steps 416 → 320; small people need the pixels).

**T4a — core: JSON reader, replay fixture, CaptureFrame, config · M · JUnit5**
- `core/.../json/Json.kt`: strict RFC 8259 reader → sealed `JsonValue` (object, array, string, number kept as
  its lexeme with exact `toLong()` / `toDouble()`, boolean, null). Fails closed with `JsonParseException`
  (carries the offset) on: trailing data, duplicate keys, bad escapes, raw control characters, numbers outside
  the grammar (`+1`, `01`, `1.`, `.5`, `NaN`), nesting deeper than `TrackerConfig.JSON_MAX_DEPTH`. Load-time
  only, never per frame.
- `core/.../replay/ReplayFixture.kt`: `parse(text): List<DetectionFrame>`. Format v1, documented in KDoc:
  `{"version":1,"frames":[{"timestampNs":…,"timestampSource":"IMAGE"|"ARRIVAL","widthImg":…,"heightImg":…,`
  `"detections":[{"box":[left,top,right,bottom],"score":…,"keypointsXy":[x0,y0,…],"keypointScores":[…]}]}]}`.
  Unknown version, missing field, wrong array length, non-positive size or non-finite number → exception
  (never a guessed value).
- `core/.../capture/CaptureFrame.kt`: reusable holder that the capture thread refills each frame (no per-frame
  allocation): tight RGBA `ByteArray` (from `RowCopy`), `widthImg`, `heightImg`, `timestampNs`,
  `timestampSource`. `set(…)` returns `false` and changes nothing unless the size is positive and the array is
  exactly `widthImg * heightImg * 4` bytes. A detector must not keep the frame or its array after `detect`.
- `TrackerConfig`: `modelInputSize` (default 416; only 320 or 416 accepted, validated like the other fields),
  `MAX_DETECTIONS_PER_FRAME = 30`, `JSON_MAX_DEPTH`.
- **Tests first:** JSON table tests (valid documents, every rejection above, a Long above 2^53 read exactly);
  replay parse of a small fixture and every rejection; CaptureFrame accept/reject; TrackerConfig validation.
- **Accept:** CI green; `@Test` count up; only `core/**` touched; nothing under `core/**/geometry/**`.

**T4b — model packaging: ModelInfo, Sha256, ModelStager (core) + Gradle check and assets (app) · M**
- `core/.../modelpack/ModelInfo.kt`: `parse(text)` of `model_info.json` → one entry per size: `inputSize`,
  `fileName`, `sha256`, `inputShape`, `outputShape`; `entryFor(inputSize)` throws for an unknown size. Fails
  closed: `fileName` must be a bare name (no `/`, `\`, `..`), `sha256` is 64 lowercase hex characters, the size
  key equals both spatial dimensions of the input shape `[1,3,S,S]`.
- `core/.../modelpack/Sha256.kt`: `hex(InputStream)`, streaming.
- `core/.../modelpack/ModelStager.kt`: `stage(fileName, expectedSha256, open: () -> InputStream): File` into a
  directory given to the constructor. Staged name = `<base>-<first 16 hex of the sha>.<ext>`. An existing
  staged file is re-hashed and reused only if it matches. Otherwise: copy to a `.tmp` file while hashing;
  mismatch → delete the tmp and throw `ModelIntegrityException` (names the file, expected and actual sha);
  match → atomic move over the target; then delete older versions of the same base name and stale tmp files.
  Not thread-safe (capture thread only), documented.
- `core/build.gradle.kts`: declare the root `model/` directory as an input of the test task (otherwise a cached
  test result would survive a model change).
- `app/build.gradle.kts`: typed task `stageModelAssets`: reads `model/model_info.json`, verifies the sha256 of
  every listed file (fails with a clear message: file, expected, actual, and the fix — restore from git or
  re-run the `export-model` workflow), copies only the listed `.tflite` files and `model_info.json` into its
  output directory, registered with `androidComponents.onVariants { variant.sources.assets
  ?.addGeneratedSourceDirectory(task, …::outputDir) }`. `model/**` stays read-only.
- **Tests first (JUnit5, `@TempDir`):** fresh stage; a valid existing file is reused without opening the
  source; a tampered existing file is replaced; **a tampered source is rejected and leaves no file**; older
  versions and tmp leftovers are deleted; `ModelInfo` parses the committed `model/model_info.json` (320 and
  416) and rejects a bad sha, a path-like name and inconsistent shapes; the committed `.tflite` files match
  their sha256.
- **Accept:** CI green; the APK holds `assets/yolo26n-pose_320.tflite`, `assets/yolo26n-pose_416.tflite` and
  `assets/model_info.json` and nothing else from `model/` (lead checks the CI artifact); `model/` untouched.

**T4c — detector: PoseDetector, FakePoseDetector, LiteRtPoseDetector · M · JUnit4**
- `PoseDetector` (ARCHITECTURE §6.1): `inputSize`, `accelerator`, `lastInferenceMs` (model run + output
  readback of the last `detect`), `detect(frame: CaptureFrame): DetectionFrame`, `close()`.
- `FakePoseDetector(frames, inputSize, accelerator)`: returns the recorded frames in order and loops; the
  timestamp and its source come from the incoming `CaptureFrame`, the recorded Img size and detections are
  kept. An empty list gives frames without detections.
- `LiteRtPoseDetector`: thread-confined (owner = the creating thread; `detect` / `close` from another thread →
  `IllegalStateException`; documented). Built through a seam: `PoseEngineFactory.create(accelerator):
  PoseEngine`. Ladder: GPU first; on any `Exception` log it and retry with CPU; `accelerator` is what the
  engine reports, never what was asked. Drops detections with score < the threshold and keeps at most
  `MAX_DETECTIONS_PER_FRAME`. `close()` is idempotent; `detect` after `close` throws.
- `UltralyticsPoseEngine` (Android glue, not JVM-tested): vendored `PoseEstimator(context, modelFile.path,
  listOf("person"), useGpu, scoreThreshold, MAX_DETECTIONS_PER_FRAME)`. One reused source `Bitmap`
  (re-created only when the frame size changes; the old one recycled), filled with `copyPixelsFromBuffer` from
  a cached `ByteBuffer.wrap` (re-wrapped only when the array changes), then `predict(bitmap, widthImg,
  heightImg)`. `inputSize` is read from the model (square, else fail). **No second warm-up:** vendored
  `LiteRtModel` already runs one inside its accelerator ladder (say so in KDoc). `lastInferenceMs` =
  `YOLOResult.inferenceMs` (covers run + readback). An accelerator string other than `GPU` / `CPU` → fail.
- `YoloResultMapper` (JVM-tested): `YOLOResult` → `List<PoseDetection>`; box left/top/right/bottom from
  `Box.xywh` (upstream fills it with LTRB in image pixels despite the name), keypoints from `Keypoints.xy` and
  `conf`.
- `PoseDetectors.fromAssets(context, config)`: `model_info.json` asset → `ModelInfo` → `ModelStager` in
  `noBackupFilesDir/models` → engine factory → `LiteRtPoseDetector`; fails if the model's reported input size
  differs from `config.modelInputSize`.
- **Tests first:** GPU factory throws → detector reports CPU; GPU works → GPU; asked GPU but the engine reports
  CPU → CPU; both fail → the CPU error propagates and every created engine is closed; threshold boundary; the
  cap; `close` twice; `detect` after `close`; wrong-thread call rejected; FakePoseDetector replays a JSON
  fixture (through `ReplayFixture`) in order and loops; the mapper maps boxes and keypoints.
- Vendored code: change only if needed, and log it in `third_party/ULTRALYTICS.md`.
- **Accept:** CI green; `model/` untouched; our own code allocates nothing per frame except the returned
  `DetectionFrame` / `PoseDetection` objects (pooled in M1 with D2).

### T5 — Screen-capture service · M
**Goal:** frames from the screen reach the detector on one thread, safely, across rotation and stop.
**Files:**
- `app/src/main/AndroidManifest.xml`: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION,
  SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS; service `foregroundServiceType="mediaProjection"`, not exported;
- `app/.../capture/CaptureService.kt`, `CaptureSession.kt`.

**Behaviour (C1-§1):**
- null intent → `stopSelf`, `START_NOT_STICKY`;
- `ServiceCompat.startForeground(...MEDIA_PROJECTION)` in try/catch, then `getMediaProjection`, then
  `registerCallback` on the capture handler;
- ImageReader RGBA_8888, `maxImages=2`, size from `CaptureSize`;
- `createVirtualDisplay(... AUTO_MIRROR ...)` exactly once;
- `acquireLatestImage` → `FrameTimestamp` → `RowCopy` into a reused buffer → `image.close()` → detect → publish
  an immutable snapshot (AtomicReference);
- drop size-mismatched frames;
- resize on `onCapturedContentResize` / `DisplayListener`;
- `onCapturedContentVisibilityChanged(false)` → hide dots;
- `onStop` → idempotent teardown in C1 order;
- per-frame work wrapped so any `Throwable` (including `OutOfMemoryError`) is logged and never crashes the
  service.

**Tests first (JUnit4, pure seams):**
- teardown is idempotent (fake session);
- size-mismatch frames are dropped;
- the per-frame catch handles `OutOfMemoryError`.

**Accept:**
- CI green;
- no work on the main thread;
- no per-frame allocations in our code;
- the thread table is respected.

### T6 — Overlay window + HUD · M
**Goal:** green boxes + keypoints drawn over other apps without blocking their touches.
**Files:** `app/.../overlay/OverlayWindow.kt`, `PoseOverlayView.kt`, `HudFormatter.kt`.

**Rules (C1-A, B, §5):**
- TYPE_APPLICATION_OVERLAY with NOT_TOUCHABLE | NOT_FOCUSABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS;
- cutout ALWAYS (30+) / SHORT_EDGES (28–29); `setFitInsetsTypes(0)`;
- `alpha = getMaximumObscuringOpacityForTouch()` (31+, else the TrackerConfig cap);
- view origin and size from `getLocationOnScreen` / `onSizeChanged` → `FitCenter`;
- draw hollow rings, thin boxes and keypoints from the snapshot;
- invalidate only on a new snapshot (deadband);
- HUD ≤ 2 Hz in a corner strip: version+SHA, accelerator, capture fps, inference ms, capture size, timestamp
  source;
- red "Running on CPU" banner;
- preallocated Paints, `drawText(char[])`, no allocations in `onDraw`.

**Tests first (JUnit4):**
- `HudFormatter` writes into a reused `CharArray` with the expected text;
- the invalidate decision (new snapshot or deadband) as a pure function.

**Accept:** CI green; no Compose in the overlay.

### T7 — Control activity, permissions, notification · S/M
**Goal:** a clear start/stop flow that never dead-ends.
**Files:** `MainActivity.kt` (Compose), `app/.../control/ControlState.kt`, notification with a Stop action.

**Flow:**
- overlay permission check, re-checked in `onResume`, with a button to settings;
- POST_NOTIFICATIONS requested, but denial never blocks;
- consent intent with `createConfigForDefaultDisplay()` on 34+;
- RESULT_OK → `startForegroundService` with the result extras immediately (never cache the Intent);
- denied or cancelled → a message plus retry;
- running → Stop;
- every restart asks for new consent.

**Tests first (JUnit4):** the `ControlState` reducer covers every permission/consent transition.
**Accept:** CI green; edge-to-edge-safe layout (edge-to-edge skill).

### T8 — Diagnostics + on-phone checklist · M
**Goal:** the owner can send a useful bug report from the phone.
**Files:** `app/.../diagnostics/` (ring-buffer logger that also writes to logcat, uncaught-exception handler
that saves the trace and offers it on next launch, log screen, Share diagnostics zip via FileProvider: logs,
device/OS, accelerator, capture size/fps, timestamp-source stats, `model_info.json`, TrackerConfig);
`docs/TEST_CHECKLIST.md` (M0b section).

**Checklist covers:**
- taps pass through to the app below (logcat "Untrusted touch due to occlusion" empty);
- paused video → capture fps ≈ 0 while idle (C1-B);
- `now − image.timestamp` within 0–30 ms;
- dot A/B (head shift < 0.03·h, keypoint 0–4 confidence drop < 0.05);
- rotate to landscape: dots stay on people;
- lock screen → clean stop; restart asks for new consent;
- CPU banner;
- DRM apps capture black (expected);
- permissions denied paths.

**Tests first (JUnit4):** ring-buffer wrap; zip entry list builder (pure).
**Accept:** CI green.

## Progress
- **T1 done** — `2581d57`, ARCHITECTURE v0.6; docs only. Accepted extras: §7.1/§7.2/§3 camera wording removed;
  idle gaps don't advance lifecycle/lock timers and dt is capped after a gap (**re-check in M3 consult**).
  Handoff item: AGENTS.md rule 5 + ARCHITECTURE §11.5 still cite `ResolutionSelector` (CameraX) — propose new
  example wording at session end (AGENTS.md is not edited mid-session).
- **T2 done** — `bc6b304`, 8 vendored files (pose-only strips logged), AGPL LICENSE, ULTRALYTICS.md, `@Test` 5 → 16.
  Spotless excludes `**/com/ultralytics/**` (readable upstream diff). `unitTests.isReturnDefaultValues = true` in
  `detector` only so vendored class-init (`Paint`) doesn't throw on the JVM — reviewer to re-check. Layout detection
  `(1,300,57)` not pure → golden test in M1 instead. Finding for M1: classic-layout NMS hard-codes conf 0.25
  (> our 0.15). **Not compiled yet** (no local build; CI after push).
- **T3 done** — `5fbd896..5872676` (8 commits), `@Test` 16 → 66. API: `Letterbox.create`, `FitCenter.contentRectImg`
  / `fitInto` / `forDisplay` (window not at 0,0), `CaptureSize.of`, `RowCopy.ensureBuffer` / `copyRgba` (fails
  closed), `FrameTimestamp.resolveNs` / `sourceOf`. Lead hand-checked FitCenter corner mapping.
- **CI green for T1–T3** (run 42834207569) after one ktlint fix (`0f4b85e`). Draft PR #3 open.
- **Model export** (separate branch `fix-model-export`): script imported TensorFlow (not used by Ultralytics'
  `litert` export, which traces PyTorch via `litert_torch`) → fixed (`3ea57c6`); next run failed because
  AutoUpdate installed `litert-torch` mid-process and swapped torch → preinstall it in `tools/requirements.txt`.
  That run confirmed shapes: input `(1,3,320,320)` NCHW float, output `(1,300,57)`.
- **Order change:** T6 (overlay) runs before T4/T5 — it needs only T3 types; T5 wires it in. T4 waits for the model.
- **T6 done** (2026-11-12) — `dc1123f..b86e5f7`: RedrawPolicy, OverlayState(Holder), HudFormatter, PoseOverlayView,
  OverlayWindow. Fixes: no redraw on a shown-flag change alone; `setFitInsetsTypes(0)` for lint. CI green.
- **T4a done** (2026-11-13) — `3590635..7ea8968`: `core` JSON reader, ReplayFixture, CaptureFrame, config.
  `@Test` 123 → 205. CI green on round 2 (one ktlint fix).
- **T4b done** — `7a75469..1b98060`: ModelInfo, Sha256, ModelStager, `stage<Variant>ModelAssets`. `@Test` → 242.
  CI green on round 1. CI log: `Staged model assets: yolo26n-pose_320.tflite, yolo26n-pose_416.tflite,
  model_info.json`; APK artifact 26.1 MB → 47.4 MB. The APK listing itself could not be read (artifact download
  blocked in the cloud session).
- **T4c done** — `6ef02bd..dad3ea9`: PoseDetector, LiteRtPoseDetector, FakePoseDetector, YoloResultMapper,
  UltralyticsPoseEngine, `PoseDetectors.fromAssets`; vendored `BasePredictor.inputUsesNchw`; ARCHITECTURE v0.6.1.
  `@Test` → 271. CI green on round 2 (one ktlint fix), run 43380160634. Lead read the Android glue
  (`UltralyticsPoseEngine`, `PoseDetectors`): it is not JVM-tested, so its first real run is the phone test.

## Then (lead)
`android-reviewer` on the full diff → fixes via implementer → PR "M0b: screen-capture overlay" with
"How to test on the phone" → `scripts/ci-status.sh` green → `docs/progress/M0b.md` + REPO_MAP → owner merges
→ APK on `latest`.
