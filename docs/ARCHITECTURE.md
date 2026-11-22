# pose-tracker-android — Architecture (v1.6)

Real-time Android app: a screen-capture overlay that draws a stable, low-latency dot on each person's head
over any other app (video, stream, game), using YOLO26n-pose on-device. Built and modified by AI coding agents (Claude Code, Codex); built only
by GitHub Actions / cloud sessions; tested by installing the APK on a phone. Every decision optimises for:
**(1) a working demo early, (2) safe modification by agents, (3) debuggability without Android Studio.**

**Base:** the inference + pose-decoding layer is adapted from the Android core of the official
Ultralytics Flutter plugin (§2.1). Screen capture, overlay, tracking, smoothing, lock, diagnostics, benchmark
and tests are ours.

How the app gets built step by step is in `docs/BUILD_PLAN.md`. Changes per version are in §15.

**v1.5 (M4f):** fewer id switches: the association gate has a floor on its per-axis sigma
(`trackGateMinSigmaRatio` 0.2 × track height), and the lock moves to a returning person near where the old track was
lost while that track is still LOST (`early` re-lock) (§6.3, §6.6, §7.1, §15).

**v1.4 (M4e):** association-miss diagnostics (no behaviour change): when a track is born next to a LOST one (or next to
a live or just removed one), the tracker records the values the matcher uses for that pair, the app logs them (rate
limited) and `summary.txt` counts them (§6.3, §6.7, §7.1, §15).

**v1.3 (M4d):** re-lock tuning from the first phone test: `relockMaxDistance` 0.40, upper-body torsos from the
shoulders, a lock with an empty signature ends at once (`cleared (no signature)`), and `lock: pending id=N frames=K`
(§6.6, §6.7, §7.1, §15).

**v1.2 (M4c):** a lock survives the person leaving the picture: while the locked person is tracked the pipeline
keeps a colour histogram of their torso, and when they come back (a new track) the lock moves to them if the colours
match clearly and unambiguously (§6.6); new log lines and `summary.txt` counters (§6.7), `relock…` config (§7.1).

**v1.0 (M4a):** a tentative track survives one missed frame (§6.3) and a display smoother (OneEuro per shown track)
moves the head point and the box before they are drawn (§6.4); §7.1, §12, §15.

**v0.8 (M3a):** §6.2 and §6.3 describe the tracker as built in `core` (head point, three Kalman filters, one
time rule, two-stage association with cost limits, lifecycle); §9 the scenario tests (also §7.1, §12, §13).

**v0.7 (M1a):** model inputs may be rectangular (`[1, 3, H, W]`, sizes written `WIDTHxHEIGHT`), rectangular models are
downloads, and `summary.txt` carries timing percentiles and the thermal state (§6.1, §6.7, §8, §12).

---

## 1. Scope and target device

**Product (owner decision 2026-11-10):** the app captures the screen with `MediaProjection`, runs YOLO26n-pose on
the captured frames and draws the result in a `TYPE_APPLICATION_OVERLAY` window on top of whatever app is in the
foreground. Front/back camera features are gone. Portrait and landscape are both in scope (games and videos run
landscape).

### Target device (primary test phone)
**Poco X7 Pro** — MediaTek Dimensity 8400-Ultra, Mali-G720 MC7 GPU, 120 Hz AMOLED, Android 15 / HyperOS 2.
The screen is 1220×2712. Capture runs at ½ scale (610×1356, `TrackerConfig`). How many frames per second the
virtual display really delivers is measured in M1b (§6.8). The phone is known to run warm under sustained load →
thermal behaviour is first logged in M1a (`thermalStatus`, §6.7), measured in M1b and handled in M5.

### Performance targets on the target device
| Metric | Goal | Fallback (acceptable) |
|---|---|---|
| Capture/analysis stream (while the screen content changes) | 60 fps | 30 fps |
| Inference (yolo26n-pose, GPU) | ≤ 16 ms/frame → keeps up with 60 fps | ≤ 33 ms → 30 fps |
| Overlay drawing | only on a new result (§6.5), never a free-running loop | — |
| Sustained 10 min | no drop below 30 fps | thermal policy kicks in (§7.2) |

A static screen delivers no frames at all; that is correct, not a miss (§6.3).

### Permissions (all handled, none may dead-end the user)
- **Display over other apps** (`SYSTEM_ALERT_WINDOW`): granted in system settings; re-checked in `onResume`.
- **Screen-capture consent** (`MediaProjection`): asked on every start, the result Intent is never cached.
  API 34+: `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` (full display, no
  single-app mode in v1). A capture that is not display-sized fails closed: dots hidden, HUD warning.
- **Notifications** (`POST_NOTIFICATIONS`): needed for the foreground-service notification (with a Stop action);
  denial never blocks capture. Foreground service type `mediaProjection`.
- Offline: the app uses no network.

### v1 features
- Screen capture of the whole display, portrait + landscape (rotation handled, §4.2).
- Dot on each person's head, stable track IDs, tap to lock a person (ring) via a small touchable handle window (§6.6).
- Smooth: no jitter when still, no visible lag or overshoot when moving/stopping, survives 0.8 s occlusion.
- Debug HUD (corner strip): raw keypoints (green), tracked dot (red), IDs, capture fps, inference ms, accelerator (GPU/CPU), capture size, timestamp source, model size, version+SHA.
- **Red banner "Running on CPU"** whenever the GPU accelerator failed to load.
- Benchmark screen (§6.8) and debug sheet with tuning sliders (persisted).
- Diagnostics: in-app log viewer, crash capture, **Share diagnostics** zip.
- Taps always reach the app underneath (overlay is not touchable except during an armed lock tap, §6.5/§6.6).

### Known limits (by design of Android)
Apps that mark their window secure (`FLAG_SECURE`, DRM video, banking) are captured **black**: no detections there.
The overlay itself is part of the capture (§6.5).

### Deferred (v1.1+)
Single-app capture mode (`MediaProjectionConfig` for a single window), gallery-video input mode, Play Store release
(licence, §8). Gyro ego-motion compensation is dropped (nothing to compensate: the source is the screen).

---

## 2. Tech stack (versions pinned in `gradle/libs.versions.toml`; never auto-upgraded)

| Concern | Choice |
|---|---|
| Language / build | Kotlin 2.4.x (LiteRT 2.3 ships Kotlin 2.4 metadata; matches upstream), Gradle Kotlin DSL, version catalog, wrapper committed + validated |
| UI | Jetpack Compose for the control activity only (permissions, start/stop, log screen, debug sheet) |
| Overlay | **Plain `android.view.View`** in a `TYPE_APPLICATION_OVERLAY` window (not `ComposeView`: a window outside an activity has no lifecycle owner, and a plain `onDraw` is easy to keep allocation-free); invalidate rules in §6.5 |
| Capture | `MediaProjection` → `VirtualDisplay` → `ImageReader` (RGBA_8888, `maxImages = 2`, size = display × `TrackerConfig` capture scale, exactly one `VirtualDisplay` per session) in a foreground service of type `mediaProjection`. Details in §5 / §6.8 |
| Camera | **Dropped.** CameraX is no longer used in code. Its entries stay in `gradle/libs.versions.toml` (protected file) until a later, deliberate catalog PR removes them |
| Inference | **LiteRT 2.3.x `CompiledModel` API, bundled runtime**: `com.google.ai.edge.litert:litert` + `litert-gpu` (same version). `Accelerator.GPU`, CPU fallback. Not the old `Interpreter` API (CPU-only in 2.x) and not the Play-services runtime (deliberate: same runtime version on every device → reproducible benchmarks) |
| Model | `yolo26n-pose`, exported `format="litert", nms=False`, FP32 (GPU computes in FP16), **two sizes: 320 and 416** |
| DI | Manual `AppGraph` |
| Settings | DataStore Preferences, single `TrackerConfig` |
| Tests | JUnit5 + Truth in `core` (JVM); JUnit4 in Android modules |
| Checks | Spotless (ktlint) + Android Lint. No detekt in v1 |

### 2.1 Upstream base (Ultralytics)
- **Source:** `github.com/ultralytics/yolo-flutter-app`, Android native part (`android/src/main/kotlin/com/ultralytics/yolo/`),
  **pinned at commit `c97bf347ddbd94b48f18c7264c4856294aee4d01` (2026-11-08)**. Licence **AGPL-3.0**.
- **Why:** official, actively maintained, on LiteRT 2.3.0 (`litert`, `litert-gpu`, `litert-metadata`) with
  `CompiledModel` GPU→CPU fallback, and its `PoseEstimator` decodes both the YOLO26 end-to-end layout (300 rows)
  and the classic layout with NMS. Only 3 of its 24 Kotlin files depend on Flutter.
- **Keep (adapted):** `LiteRtModel`, `Predictor`, `PoseEstimator`, `GeometryUtils`, `Utils`, `YOLOResult`,
  `YOLOTask`, and `ImageUtils` **without its CameraX `ImageProxy` functions** (`toBitmap` is CameraX-only and
  allocates per frame, so it goes; `prepareBitmapForModel` stays and needs an exact-width source — our adapter
  row-copies the capture frame into a reused exact-width buffer, §6.1).
- **Drop:** `YOLOView` entirely (it is the CameraX binding/analyzer/lens-switching view; replaced by our capture
  service and overlay), Flutter glue (`YOLOPlugin`, `YOLOPlatformView`, `YOLOPlatformViewFactory`,
  `YOLOStreamConfig`, `YOLOInstanceManager`), other tasks (classify, detect-only, segment, semantic, depth, OBB),
  the QNN/NPU path (Qualcomm-only; the target phone is MediaTek), photo capture, zoom, and the upstream
  `OverlayView` (replaced by ours).
- **Layout:** vendored files stay in package `com.ultralytics.yolo` inside `detector/`, with the upstream file
  header kept, so future syncs are a readable diff. Every change to vendored code is logged
  in `third_party/ULTRALYTICS.md` (file · what · why). Our own code lives in `com.memo494.posetracker`.
- **Known issues to fix (M1):**
  - upstream creates the model on a throwaway executor and runs it on upstream's camera executor; the LiteRT rule is
    create/run/close on one thread (§5);
  - the vendored decoder **allocates per frame** (`LiteRtModel.kt:198-202`, `PoseEstimator.kt:105-150`), which
    breaks AGENTS rule 8. Our own code complies from M0b; the decoder is made allocation-free in M1b (plan
    decision D2; M1 was split in M1a and M1b, §12) and the issue is logged in `third_party/ULTRALYTICS.md` until then.
- **What upstream does not have (our work):** screen capture + overlay, tracking/IDs, smoothing, fading and lock,
  lock, diagnostics, benchmark, thermal policy, tests (upstream has no Android tests).
- **Sync policy:** no automatic updates. The lead may propose a sync to a newer upstream commit as its own PR.

---

## 3. Modules (3 only)

```
pose-tracker-android/
├─ core/        PURE KOTLIN JVM (kotlin("jvm") plugin → android.* cannot compile here)
│               model types, geometry (Letterbox Img↔Mdl, FitCenter Img↔View), capture math (CaptureSize,
│               RowCopy, FrameTimestamp), tracking, TrackerConfig, replay I/O, `json` (strict reader),
│               `modelpack` (ModelInfo, Sha256, ModelStager)
├─ detector/    Android library: PoseDetector interface + adapter over vendored Ultralytics code
│               (com/ultralytics/yolo: LiteRtModel, Predictor, PoseEstimator, utils), Fake impl
├─ app/         Capture service (MediaProjection + ImageReader, foreground), overlay window + plain-View
│               renderer + HUD, handle window (M4), control activity (Compose: permissions, start/stop),
│               benchmark, debug sheet, DataStore, diagnostics, AppGraph
├─ third_party/ ULTRALYTICS.md (pinned commit, kept/dropped files, modification log)
├─ LICENSE      AGPL-3.0 (inherited from the vendored Ultralytics code and the model)
├─ model/       yolo26n-pose_320.tflite, yolo26n-pose_416.tflite, model_info.json, golden fixtures (committed)
├─ signing/     debug.keystore (debug-only key, committed on purpose — see §10)
├─ tools/       export_model.py + requirements.lock (fully pinned)
├─ scripts/     verify.sh, ci-status.sh, cloud-setup.sh (see docs/AI_TEAM.md §6)
├─ testdata/    replays/*.json (real recordings)
├─ docs/        ARCHITECTURE.md, AI_TEAM.md, BUILD_PLAN.md, TEST_CHECKLIST.md, REPO_MAP.md,
│               plans/<milestone>.md (lead's plans), progress/<milestone>.md (handoffs)
├─ .claude/     skills/ (vendored, pinned), agents/ (implementer, android-reviewer, debugger, Explore),
│               settings.json — all defined in docs/AI_TEAM.md
├─ AGENTS.md    rules for all AI agents;  CLAUDE.md = "@AGENTS.md" + Claude-specific notes
└─ .github/     workflows/android.yml, workflows/export-model.yml
```
Dependencies: `app → detector → core`, `app → core`. Nothing else.

---

## 4. Clocks and coordinate spaces (the two biggest bug sources)

### 4.1 One clock: `System.nanoTime()` timebase
- A frame's time is **`image.timestamp`**: for a `VirtualDisplay` it is `CLOCK_MONOTONIC`, the same base as
  `System.nanoTime()`. `ImageReader` listener callbacks queue behind inference on the capture thread, so the
  callback time (`nanoTime()` at arrival) would be late by up to one inference; the image timestamp is not.
- **Guard (`FrameTimestamp`, pure, in `core`):** accept `image.timestamp` only if `0 ≤ now − ts ≤ 100 ms`
  (`TrackerConfig`). If the guard fails, fall back to `nanoTime()` taken right at `acquireLatestImage`, and **flag
  it in the HUD** (timestamp source = `image` | `arrival`). Source statistics go into the diagnostics zip (§6.7).
- The overlay's draw callback uses `System.nanoTime()` as "now".
- Every `*Ns` field documents "System.nanoTime() timebase".

### 4.2 Named spaces; suffixes mandatory in code
| Space | Suffix | Definition |
|---|---|---|
| model | `Mdl` | letterboxed model input, `width x height` (320×320 or 416×416 for the built-in models; any `WxH` for an imported one) |
| image | `Img` | the **capture content rect**: the pixels of the captured frame (display × capture scale, e.g. 610×1356) that show the screen content. No rotation, no mirror. Tracking lives here |
| view | `View` | overlay pixels (the overlay window; origin from `getLocationOnScreen`, size from `onSizeChanged`) |

- Detector: letterbox → infer → un-letterbox back to **Img** (`Letterbox`, Img↔Mdl, pure, round-trip tested).
- **Img → View** is `FitCenter`: a uniform fit-centre scale plus an offset (`View` = `Img` · s + (ox, oy)),
  invertible (View → Img is used for taps, §6.6). When capture size and window size have the display's aspect it
  is a pure scale; the offset absorbs display cutouts and a window that does not start at (0, 0). It is passed to
  `core` as plain numbers (scale, offset), never as an Android `Matrix`.
- Velocities are transformed with the linear part only (the scale, no offset).
- **Fail closed:** a frame whose size is not the expected capture size (rotation in flight, resize, a capture
  that is not display-sized) is dropped and never mapped; dots are hidden and the HUD says why.
- **Rotation (M0b scope):** on `onCapturedContentResize` (API 34+) or a `DisplayListener` change (API 26–33)
  the session creates a new `ImageReader` and calls `virtualDisplay.resize()` + `setSurface()` on the
  **same** `VirtualDisplay` — never a second one. Frames of the old size are dropped. The overlay recomputes
  `FitCenter` from its new size.
- `onCapturedContentVisibilityChanged(false)` (API 34+) hides the dots.

---

## 5. Data flow & threads

```
[capture HandlerThread — ONE dedicated thread = the analysis thread, owns CompiledModel + tracker]
  ImageReader listener → acquireLatestImage → FrameTimestamp → RowCopy into a reused exact-width buffer
     → image.close()  → PoseDetector.detect → DetectionFrame(Img)
     → Tracker.update → TrackSnapshot.ofSmoothed (HeadSmoother, §6.4; immutable copy of the shown tracks)
     → LockKeeper.onFrame (§6.6: torso signature of the locked person, or the re-lock judging; reads the frame's
        pixels, so it runs here, before the frame array is reused)
     → OverlayState(detections, tracks, …) → OverlayStateHolder (AtomicReference)
  HUD tick (2 Hz) → Tracker.onIdle(now − arrival of the newest tracked frame) → TrackSnapshot.ofIdle (the
     smoother is not advanced) → new snapshot if the shown tracks changed → same state path
  geometry change (capture or display size) → Tracker.reset() + HeadSmoother.reset() → state with
     TrackSnapshot.EMPTY
[main thread — overlay View]
  RedrawPolicy.shouldRedrawMarks says changed → invalidate → onDraw: Img→View (FitCenter) → brackets and
     ring per track (the head points are already smoothed; a LOST track is drawn faded, §6.5; the locked
     track gets a double ring, §6.6)
[main thread — lock handle window]
  tap on the handle → LockController (§6.6) → FramePipeline.requestLock / clearLock (LockKeeper, AtomicInteger)
```

| Object | Owner thread | Rule |
|---|---|---|
| LiteRT `Environment` | process | created once, shared |
| `CompiledModel` + its `TensorBuffer`s | capture (= analysis) | create, warm up, run, close on this thread only; never on main; buffers allocated **once** and reused; buffers closed before the model |
| `MediaProjection` callbacks, `ImageReader` listener | capture | `registerCallback(…, captureHandler)` |
| `Tracker` and its reused `TrackList` | capture | one per `FramePipeline` (so per Start); sole writer and reader; the overlay never sees the `TrackList`: it is only valid until the next tracker call |
| `HeadSmoother` (M4a) | capture | one per `FramePipeline`, next to the tracker; pool of `maxTracks` slots, allocation-free; reset wherever the tracker is reset |
| `TrackSnapshot` (in `OverlayState.tracks`) | made on capture, read on main | immutable copy of the shown tracks (head points smoothed by `HeadSmoother`), taken right after each `update` / `onIdle`; `TrackSnapshot.EMPTY` is shared, so nothing is allocated while nobody is tracked |
| Probe `CompiledModel` (`ModelProbe`, M0c import) | IO worker, CPU only | created and closed inside one call; never on main, never GPU, never kept |
| `ImportedModelStore` | any worker thread | one process-wide lock serialises every call; refuses the main thread (`IllegalStateException`) |
| Import single-flight flag (`ModelImporter`) | any thread | process-wide `AtomicBoolean`, set before `import.tflite` is created and cleared in `finally`; a second import answers `Failed(BUSY)`; a recreated screen seeds `importing` from it |
| `ModelStatusHolder` | any thread | one `AtomicReference`, immutable values; written by the capture thread, read by the screen |
| Imported model file (hash check) | capture | the capture thread hashes `model-<sha16>.tflite` against its meta before loading it |
| Imported model file (hash check after a probe trip) | IO worker / capture | `ImportedModelStore.storedFileMatches` reads the file once, only for a probe trip with a meta and a file: on `Dispatchers.IO` for the control screen and on the capture thread at Start; refuses the main thread |
| `OverlayState` | made on capture, read on main | immutable; one new per processed frame, per HUD tick and per geometry change |
| Overlay `View`, lock handle window, `LockController` / `LockArming`, `WindowManager` calls (M4b) | main | plain classes, preallocated; no work except drawing and the lock flow; the arming timeout is a main-looper `Handler` runnable |
| `LockKeeper` (M4b, M4c) | UI writes the wish, capture does the rest | the wish (`requestedId`) is an `AtomicInteger`, -1 = none, written by `requestLock` / `clearLock` from the UI thread; everything else (held id, signatures, pending state, candidates, counters) is plain fields on the capture thread. The capture thread puts the lock in a published state only while the held id is among the state's shown tracks (a pending lock puts none); it clears the wish with `compareAndSet(id, -1)`, so a request made meanwhile is not lost. `hasLock` (any thread) is true while a lock is held, pending or requested |
| Torso signatures (M4c) | capture | two `AppearanceSignature`s (the locked person's and a scratch one) inside `LockKeeper`, allocated once; a colour histogram, no image; dropped when the lock ends |
| `CaptureSession.pipeline` (M4b) | written on capture, read on main | `@Volatile`; main reads it only to call the thread-safe lock methods of `FramePipeline` through `LockTarget` |
| TrackerConfig | DataStore → StateFlow | applied at next frame boundary |

- **Copy, then close, then infer:** `maxImages = 2`; copy the frame out (`RowCopy`: handles `rowStride` padding
  and a short last row) into the reused buffer and call `image.close()` **before** inference, so the reader never
  starves. The close is in a `finally`. Per-frame work is wrapped so that any `Throwable` (including
  `OutOfMemoryError`) is logged and never crashes the service.
- Teardown is idempotent and runs in three phases that never block the main thread (documented in
  `CaptureSession`, resources released in the reverse order of creation): **main** (remove the overlay) →
  **capture** (stop the HUD tick and the listeners, release the virtual display, close the reader, close the
  model: the capture thread owns them) → **main** (unregister the callback, stop the projection, quit the capture
  thread, stop the service). Each hop is a `Handler.post`; nothing joins or waits. The projection's `onStop` (lock
  screen, system "stop sharing") triggers it. A restart always asks for new consent.
- **HUD tick:** a static screen delivers no frames, so the capture handler republishes the last state at the HUD
  rate (`hudRateHz`) and the shown capture fps decays to 0. The same tick tells the tracker how long ago the newest
  tracked frame arrived (`Tracker.onIdle`), so a still picture gets its marks (§6.5).
- **Tracker and gaps in the data:** only a frame the detector saw goes to `Tracker.update`. A frame without a
  detector, while the capture is not display-sized, with a size mismatch or a failed copy is not given to it (no
  information is not "nobody there"). A new capture or display size resets the tracker and publishes a state
  without tracks, so positions of the old geometry are never mapped with the new one; ids go on (§6.3).
- A detector that cannot be created does not stop the session: frames are still drained and closed and the HUD
  shows `DETECTOR_UNAVAILABLE`.

---

## 6. Subsystems

### 6.1 Detector (`detector/`)
```kotlin
interface PoseDetector : Closeable {
    val inputWidth: Int; val inputHeight: Int          // model input size (Mdl px, read from the model; W x H)
    val accelerator: Accelerator                       // what runs
    val lastInferenceMs: Float                         // model run + output readback of the last detect; 0 before
    val lastPreMs: Float; val lastPostMs: Float        // pre-processing / post-processing of the last detect (M1a)
    fun detect(frame: CaptureFrame): DetectionFrame    // RGBA pixels in the reused exact-width buffer, size, timestampNs
}
```
Thread-confined: create, `detect` and `close` on the capture thread (a call from another thread throws).
- Implemented as an adapter over the vendored Ultralytics `LiteRtModel` + `PoseEstimator` (§2.1). Where upstream
  already does the right thing (output-layout detection, end-to-end vs NMS decoding, GPU→CPU fallback) it is
  reused; the rules below are what we add or enforce on top.
- **Input:** `CaptureFrame` wraps the reused exact-width RGBA buffer filled by `RowCopy` (§5). Upstream's
  `prepareBitmapForModel` needs an exact-width source, which a raw `ImageReader` plane is not (row stride
  padding) — hence the copy. Upstream's `ImageUtils.toBitmap` (CameraX `ImageProxy`) is not used.
- **Per-frame allocations:** our adapter allocates nothing per frame; the vendored decoder still does until M1b
  (§2.1 known issues).
- **Accelerator selection:** create with `Options(Accelerator.GPU)` (GPU only). On `LiteRtException`,
  log it and recreate with `Options(Accelerator.CPU)`; HUD shows `CPU` and the red banner appears.
  (A mixed `Options(GPU, CPU)` can silently run on CPU, which would hide problems.)
- One warm-up inference right after creation (GPU shader compilation must not stall the first frames). It is the
  vendored one: `LiteRtModel` runs it inside its GPU→CPU ladder, so the adapter adds no second warm-up.
- **Model file:** packaged as an app asset (the `stage<Variant>ModelAssets` build task, §8), staged at start by
  `ModelStager` into `noBackupFilesDir/models` (versioned name, temp file + rename) with a sha256 check, and only
  then opened by LiteRT **by path**; a file that does not match its hash is never opened.
- Input tensor layout **read from the model** (expected NCHW `(1,3,H,W)`, RGB, 0–1; **not necessarily square** since
  M1a). Never hard-coded. If the
  vendored `LiteRtModel` could not read the layout by name it silently assumes NHWC; the adapter checks
  `BasePredictor.inputUsesNchw` and refuses to run (fail closed). It also requires the input width and height
  it was created for: `PoseDetectors.fromAssets` expects the square `TrackerConfig.modelInputSize`,
  `fromImported` the width and height of the stored meta; `LiteRtPoseDetector.create` closes the engine and throws
  when either side differs.
- Output expected `(1,300,57)`: per row box xyxy, score, cls, 17×(x,y,v). Exact column order and
  pixel-vs-normalised scale are **verified by a golden test** against Python `model.predict()` on the same
  image (fixture committed with the model). Rows with score < 0.15 dropped.
- Timing measured as `run` **plus output readback** (`run` can return before the GPU finishes). `lastPreMs` and
  `lastPostMs` come from the vendored result of the same detect; a negative value (`NOT_MEASURED`: the fake detector,
  or before the first detect) is not a sample (§6.7).
- Sizes are written **WIDTHxHEIGHT** wherever a person or a log reads them (`832x384`, like `capture 1356x610`); a
  square size is the single number (`640`). One rule, `core/model/ModelInputText` (a `String` form and an
  allocation-free `CharArray` form for the HUD); a side that is not positive gives `0`.
- `FakePoseDetector` replays JSON → tests and a "replay mode" in the debug sheet.

### 6.2 Head point (`core/tracking/HeadPointEstimator`)
- Keypoints 0–4 (nose, eyes, ears). Two classes, both pure and allocation-free:
  - `HeadMeasurement` (stateless, used for association before a detection has a track): plain mean of the head
    keypoints with score ≥ `headKeypointEnterScore` (0.5); none → the box fallback below.
  - `HeadPointEstimator` (one per track, the point the track is updated with). Visibility hysteresis per
    keypoint: enter ≥ 0.5, leave < 0.3.
- Offsets: per head keypoint, its offset from the head centre in units of `h`, EMA `headOffsetAlpha` (0.2).
  A keypoint's centre estimate is `keypoint − offset·h`; the centre is the confidence-weighted mean of these
  estimates → no jump when an ear appears or disappears.
  - All five visible: the offsets are learned against the plain mean of the five (their targets sum to zero, so
    the reference cannot drift; a track that started with a partial view converges to the plain mean).
  - Partial view: the centre is computed first; a keypoint that has no offset yet gets one, existing offsets
    are never changed.
- Fallback when no keypoint can anchor the centre: `(box centre x, top + headFallbackTopRatio·h)` (0.10), flagged
  `estimated`; learned offsets are kept.
- Measurement variance `(headMeasurementNoiseRatio·h)² / mean keypoint confidence` (0.03; the mean is floored
  at `headMinMeanConfidence` 0.05); fallback `(headFallbackNoiseRatio·h)²` (0.10). A box with `h ≤ 0` gives an
  invalid point and changes nothing.

### 6.3 Tracker (`core/tracking`)
Reference model: `docs/plans/M3_sim/` (Python); the code in `core/tracking` (`Tracker`) is its port. Every number
below is a `TrackerConfig` field. Capture thread only; allocation-free after construction (a pool of `maxTracks`
tracks, index loops); deterministic.
- **Kalman:** three independent position/velocity filters per track (`KalmanAxis`): head x, head y, box height
  `h`. Discrete white-noise acceleration, noise scaled by `h`: σ_accel `trackAccelSigmaRatio`·h (8 h/s²) for the
  head, `trackHeightAccelSigmaRatio`·h (2 h/s²) for the height; height noise `trackHeightNoiseRatio` 0.05; the
  head's measurement noise comes from §6.2. A new track starts with velocity σ `trackInitVelocitySigmaRatio`·h
  (2 h/s) on all three.
- **One time rule:** `dtEff = min(dt, trackMaxDtMs)` (250 ms) for every filter step and every lifecycle clock. A
  step with `dt > trackMaxDtMs` is a gap step and inflates the process noise ×`trackGapNoiseInflate` (10) once.
  `dt ≤ 0` (repeated or backward timestamp) ignores the frame; the first frame does not predict.
- **No "NIS > 9.21 inflates Q" rule:** the same gate is in the association, so a matched pair cannot exceed it.
  No noise inflation on a miss either (it made slow crossings worse).
- **Miss:** the three velocities decay by `exp(−dtEff / trackMissVelocityTauMs)`, τ **300 ms** (with 100 ms slow
  crossings swapped ids).
- **Idle screen = no information, not a miss.** The virtual display only produces a frame when the screen content
  changes, so a paused video or a static page yields no frames. A time gap without frames never counts as a miss
  and never decays velocity; tracks are held as they are and the HUD shows capture fps ≈ 0. Only an actual frame
  that lacks a person is a miss. The first frame after a gap steps with `dtEff` (a gap adds at most
  `trackMaxDtMs` once, to the Kalman step and to the lifecycle clocks); nothing ages during the gap itself.
- **Detections:** at most `MAX_DETECTIONS_PER_FRAME` (30); dedupe first (IoU ≥ `dedupeIou` 0.7 drops the lower
  score); scores below `scoreThreshold` and unusable boxes are ignored.
- **Association** (ByteTrack two-stage): stage 1 = tracks × detections with score ≥ `trackHighScore` (0.5);
  stage 2 = the still unmatched tracks × the low ones (down to `scoreThreshold`). **Who goes first (F3/A2):** a
  tentative track is a *shadow* when, against some CONFIRMED or LOST track's predicted state, its predicted box has
  IoU ≥ `trackBirthSuppressIou` (0.5) AND its head is within `trackBirthSuppressHeadRatio` (0.15) × that track's
  height. Shadows are matched last, on the detections the other tracks leave over; every other track (confirmed,
  LOST, and tentative tracks that are another person) is matched together in one Hungarian as before. Why: a
  near-duplicate newborn, whose box equals the latest detection, won the person's detection after 3 hits and the old,
  often locked, track went LOST and was removed. A tentative track that is a second person (head farther away) is not
  affected, so an occluded locked track cannot take the detection of a new neighbour. Cost
  `assocIouWeight·(1 − IoU) + assocHeadWeight·min(1, d_head / (assocHeadScaleRatio·h))` (0.5, 0.5, 0.5);
  Mahalanobis gate `trackGateChi2` (9.21) on the head innovation, where (M4f) each axis variance is `max(P + R,
  (trackGateMinSigmaRatio × h)²)` with `trackGateMinSigmaRatio` 0.2 (0..1, 0 = off, the M3 gate): the filter's
  variance is far below the real jitter of the detector (phone data: a gate sigma of 0.066 h against heads that
  move 0.3 h between matches). The floor is in the gate only; the cost, the cost limits and the filter are
  unchanged. **Hungarian** (`HungarianAssigner`) behind an
  `Assigner` interface, deterministic tie-break, with a **cost limit** per stage: `trackMatchMaxCost` 0.7,
  `trackLowMatchMaxCost` 0.6. It maximises `sum(limit − cost)` over pairs below the limit; it does not maximise
  the number of pairs (a missing person must not shift the ids of the others).
- **Lifecycle** (time in `dtEff`, measured over frames that exist):
  - an unmatched detection with score ≥ `trackBirthScore` (0.25) starts a TENTATIVE track (while fewer than
    `maxTracks` = 32 exist), **unless** its box overlaps, with IoU ≥ `trackBirthSuppressIou` (0.5), a detection that a
    CONFIRMED or LOST track took in this frame AND its head is within `trackBirthSuppressHeadRatio` (0.15) × that
    track's height of that detection's head (F3/A1: a near duplicate of a followed person, IoU 0.5–0.69, below
    `dedupeIou`, used to be born next to the live track; counted in `assocBirthSuppressed` only when a track could
    have been given). Owner's log: 14 of 19 such births had a head distance ≤ 0.12 h; the other 5 (0.29–0.54 h) may be
    another person and are born. A neighbour with a smaller overlap or a farther head is still born; no cost or gate
    changed. **Known limit (pinned by a test, no height condition on purpose: the owner's duplicates have height
    ratios 0.59–2.03):** a person directly behind another, head within 0.15 h and box IoU ≥ 0.5, is not born while
    the front track is matched; if the front person leaves, the front track takes the back person's detection;
  - CONFIRMED, and shown, from the `trackConfirmHits`-th (3rd) consecutive hit; a tentative track survives
    `trackTentativeMaxMisses` (1, bounds 0..5) consecutive missed frames and is removed on the next one. While it
    survives it coasts like a lost track (velocity decay as for a miss, no `lostMs`), is not shown and not LOST;
    a miss does not reset its hit counter, a hit resets its miss counter; with 0 a tentative track is removed on
    its first miss. Why (M4a, owner's phone run: 278 births, 103 confirmations): a person whose detection flickers
    at score 0.4 waited long for the ring; on the reference model (`docs/plans/M4_sim/tentative.py`, 20 fps,
    40 seeds) the p90 time until the ring is shown fell from 1050 to 600 ms when a frame is missing 40 % of the
    time and from 2650 to 1450 ms at 60 %; the one- and two-frame false positives (S1, S9) stay unshown;
  - `onIdle(idleNs)` (`idleNs` = time since the newest frame, measured by the caller) promotes the tentative
    tracks once `idleNs ≥ trackIdlePromoteMs` (400 ms): a still picture;
  - a confirmed track that misses is LOST: it stays shown and coasts, and is removed after `trackMaxLostMs`
    (800 ms of `dtEff` time) or when its head point is more than `trackExitMarginRatio` (0.1) × its height
    outside the image; a hit makes it CONFIRMED again;
  - a track whose state is not finite or whose height is not positive is removed;
  - **locked track 2000 ms** (M4).
- **Ids** start at 1 and are never reused in a session; `reset()` (geometry change) drops the tracks and keeps
  the id counter. Counters for the diagnostics, cumulative, kept by `reset()`: `birthCount`, `confirmCount`,
  `idlePromoteCount`, `recoverCount`, `removeCount` (confirmed tracks only), `mostShownAtOnce`.
- **Association-miss diagnostics (M4e, no effect on any track).** Why: on the phone one person got 30+ ids in 3
  minutes with the new id born in the same frame the old track was coasting, so the person was detected but not
  associated, and the offline walk simulation did not reproduce it. When step "birth" starts a track while a
  CONFIRMED track is LOST and the new head is within `assocMissNearRatio` (1.0) × the lost track's height of the lost
  track's predicted head (the nearest such track), the tracker fills one fixed `AssociationMiss` record (`missCount`
  records after an `update`, `miss(i)`; cleared by the next `update` or `reset`; at most one per birth, so at most
  30): `lostId`, `newId`, `lostMs` (including this frame), `score`, `highStage`, `gateChi2` (the Mahalanobis value, compare with
  `trackGateChi2`), `iou`, `headRatio` (head distance / (`assocHeadScaleRatio` × h), not capped), `hRatio` (detection
  height / track height), `cost` (the §6.3 cost, also for a gated pair) and `reason`: `GATE` (value above
  `trackGateChi2`), `COST` (cost at or above the limit of the detection's stage) or `TAKEN` (the matcher would have
  accepted the pair and did not make it; by the Hungarian optimum this only happens when a match was refused after
  assignment, so a non-zero count is a bug signal). With no LOST track near, the nearest track that is not LOST (and
  was not born in this frame) gives `reason=LIVE` (the new box overlapped a track that took another detection, or a
  tentative track; `trackState`, and `detIou` = IoU of the new box with that track's matched detection box), and with
  none, the nearest track removed in this frame (copied into scratch arrays in `updateTracks`) gives `reason=REMOVED`
  with the removal cause (`LOST_TIME`, `EXIT`, `OTHER`); for these two the numbers use the track's state after the
  frame. The gate value and the cost come from `AssociationCost.mahalanobis2` (with the same floor argument as the
  matcher) and `ungatedCost`, the same functions the matcher uses (a test asserts exact equality). Cumulative counters (kept by `reset()`): `assocMisses` (= gate +
  cost + taken), `assocMissGate`, `assocMissCost`, `assocMissTaken`, `assocBirthNearLive`, `assocBirthAfterRemoval`, and `assocMissLowBirth` (an unmatched detection
  below `trackBirthScore` next to a LOST track, counted once per frame it appears, no record). The values come from
  scratch fields and the tracks' own state; the loop over the tracks runs only for such a detection, nothing is
  allocated and no track, id or counter other than these changes (test: three trackers with different
  `assocMissNearRatio`, one read and two not, give identical tracks on a scripted run).
- Works identically at 20, 30 and 60 fps and with irregular frame spacing (everything uses `dtEff` from frame
  timestamps; the scenarios of §9 run at several rates and with idle gaps).
- **Known limits (not asserted):** slow crossings in one row can swap ids (no appearance model); a person
  missing for 0.5 s while moving at 2 h/s gets a new id; a person faster than about 1 h/s whose picture freezes
  without a frozen frame gets a new id afterwards.

### 6.4 Smoothing (built in M4a); no forward prediction (decided in M4b)
- **The display smoother** (`core/tracking/HeadSmoother`, plan `docs/plans/M4.md`) runs on the capture thread
  between `Tracker.update` and the snapshot (`TrackSnapshot.ofSmoothed`, with the frame's timestamp, the one the
  tracker was given). The tracker's own state is never touched: association and lifecycle see the raw head point.
- **OneEuro per shown track id** on the head point (x, y), in Img px. Per axis `alpha = r / (r + 1)`,
  `r = 2π·fc·dt`; the speed is the low-passed derivative (cutoff `smoothDerivativeCutoffHz`, x and y combined),
  `fc = smoothMinCutoffHz + smoothBeta · speed / h` (`h` = the track's box height). Defaults: `smoothMinCutoffHz`
  1.0 Hz, `smoothBeta` 3.0, `smoothDerivativeCutoffHz` 1.0 Hz; `smoothEnabled` (default true) off = identity.
  The first sample of an id passes through; `dt ≤ 0`, a non-finite point or a height that is not positive leave
  the state alone.
- **The box moves with the head point** (same delta; its offsets to the head are kept), so ring and brackets stay
  together.
- **Slots:** a pool of `maxTracks` slots keyed by track id, no allocation after construction. An id that is not
  shown in a frame frees its slot, so an id that comes back starts again as a first sample. The **idle republish
  does not advance the smoother** (`TrackSnapshot.ofIdle`): a track the smoother knows keeps its last smoothed
  point, a track newly promoted by the HUD tick passes through unsmoothed. `reset()` is called wherever the
  tracker is reset (geometry change).
- **Measured on the reference model** (`docs/plans/M4_sim/euro.py`, 8 seeds): jitter ratio of a still person
  0.51 → 0.10 at 20 fps and 0.37 → 0.05 at 30 fps; walking lag +0.37 / +0.50 frame; overshoot after an abrupt
  stop ≤ 0.04 h. In the code tests: jitter ratio ≤ 0.2 (measured 0.19), lag 0.35 frame, overshoot 0.
- **No forward prediction (M4b decision, measured).** The detection describes the screen at `image.timestamp`, and the
  content has moved on by the time the result is drawn, so a lead (`pos + v · lead`) was considered. The reference
  model (`docs/plans/M4_sim/pred.py`: smoothed point plus the tracker's velocity times the lead; a walk then an
  abrupt stop and a still person, 6 seeds) shows no gain: at 20 fps a lead of 30 / 50 / 70 ms leaves the mean error
  of a walker unchanged (0.021 h → 0.021 / 0.023 / 0.023 h) because the smoother lags only a third of a frame, but
  raises the jitter ratio of a still person from 0.10 to 0.29 / 0.43 / 0.58 and the overshoot after a stop from 0.026
  to 0.056 / 0.081 / 0.112 h (30 fps alike). So the dot is drawn at the smoothed point of the newest frame; there is
  no `displayLeadMs`.
- Lost tracks are drawn faded (§6.5). Removed tracks do not fade out separately: a track is removed only after the
  fade has ended.
- Any redraw this needs between frames obeys the invalidate rules in §6.5 (it may only run while a dot actually
  moves by more than the deadband).

### 6.5 Overlay rules (plain `View`)
The overlay is a plain `View` (not `ComposeView`) in a `TYPE_APPLICATION_OVERLAY` window.
- **Window:** `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`;
  display cutout mode `ALWAYS` (API 30+) / `SHORT_EDGES` (28–29); `setFitInsetsTypes(0)`. Taps reach the app below, except for the one tap while the lock is armed (§6.6).
- **Alpha cap (C1-A):** `LayoutParams.alpha` ≤ `InputManager.getMaximumObscuringOpacityForTouch()` (0.8, API 31+;
  on older versions the `TrackerConfig` fallback cap, also 0.8). Above it Android 12+ **blocks touches to the app
  below**. The on-phone check is an empty logcat for "Untrusted touch due to occlusion".
- **Invalidate rules (C1-B):** the overlay is part of the capture, so an unconditional redraw loop
  (`postInvalidateOnAnimation`) would create redraw → new frame → inference → redraw. Therefore: **no
  free-running loop**; call `invalidate()` only when a new snapshot differs from the drawn one by more than a
  sub-pixel deadband (a pure function, unit tested); HUD text updates at most 2 Hz (`TrackerConfig`).
  Since M3b the decision is `RedrawPolicy.shouldRedrawMarks` (`OverlayStateHolder.publish`; M4b: the holder also
  redraws whenever `OverlayState.lockedId` differs from the drawn state), in this order: HUD
  due → warning changed → tracks (`RedrawPolicy.tracksChanged`: the count or an id differs at an index, or a head
  coordinate or a box edge moved by more than the deadband; `isLost` and `isHeadEstimated` change no pixel and
  are not compared; an unshown side counts as empty) → only with `debugMarks` also the detection comparison of
  `shouldRedraw` (rectangles and keypoints). Detections that move while the tracks do not never redraw without
  `debugMarks`.
  A paused video must show capture fps ≈ 0.
  **HUD idle rule:** the HUD's own refresh causes up to `hudRateHz + 1` frames per second, so a capture rate below
  `TrackerConfig.hudIdleFps` (`hudRateHz` + 2) prints as 0 fps and its fps and inference-time changes do not
  refresh the HUD text; only a leave from or entry into idle, or any other field, does.
- **Draw phase:** `onDraw` allocates nothing: preallocated `Paint`s, a reused `CharArray` per HUD line
  (`drawText(char[], …)`), no lambdas or temporaries. The view's origin comes from `getLocationOnScreen`; the
  display size is **not** read from the view (`onSizeChanged` can still hold the previous rotation): since PR #5
  the Img→View mapping uses the display size that the capture plan published in `OverlayState`
  (`OverlayMapping.forState` → `FitCenter`, §4.2). While that size or the capture size is unknown, nothing is drawn.
  The debug track id is written from a reused `CharArray` (`LabelPlacement` keeps it inside the view).
- **What the owner sees (M3b, M4a):** a track is shown from its third hit (since M4a one missed frame in
  between is tolerated, §6.3), so on a playing video the marks
  appear from the third frame (about 100 to 150 ms). A still picture sends no further frames, so the HUD tick
  (`Tracker.onIdle`, §5) promotes a person after `trackIdlePromoteMs` (400 ms) of silence: the marks come
  **0.4 to 0.9 s after the last frame** (400 ms plus up to one tick of 0.5 s at 2 Hz); before M3b they came at
  once. A person the model misses for a moment keeps the marks (LOST) for up to `trackMaxLostMs` (800 ms). Below
  about 2.5 processed frames per second a still picture and a slow stream cannot be told apart, so tentative
  tracks are then shown.
- **The overlay is in the capture** — and `FLAG_SECURE` on it would black out the **whole** capture, so it is
  not an option. Mitigation: hollow marks, thin strokes, a corner HUD strip, and the marks rule below.
- **Marks rule (M0c T7, from M3b on the tracker's tracks):** the marks come from the **shown tracks**
  (`OverlayState.tracks`, a `TrackSnapshot`), not from the raw detections. Per shown track the overlay draws short
  corner brackets at the track's box (`core/overlay/CornerBrackets`, `boxCornerRatio` of each box side, instead
  of the full box) and a head ring at the tracked head point with radius
  `max(headRingRadiusRatio × box width, minHeadRingRadiusDp)` (`core/overlay/HeadRing`). A LOST track is drawn
  faded (M4b): the brackets and the ring have the opacity `MarkFade.alpha = max(fadeMinAlpha, 1 − lostMs / trackMaxLostMs)`
  (`fadeMinAlpha` 0.25), from the track's `lostMs` in the snapshot (0 unless LOST), so the marks fade over about
  0.8 s instead of staying full and then vanishing. The fade is drawn in steps: `RedrawPolicy.tracksChanged` also
  returns true when a track's opacity changed by `fadeStepAlpha` (0.1) or more. On a paused screen no frames come,
  so the fade advances at the 2 Hz HUD tick (`TrackSnapshot.ofIdle` adds the idle time to `lostMs`, capped at
  `trackMaxLostMs`): about two steps. The raw detections The raw detections (full box, keypoint dots, no ring) and the
  track ids (a number above the top-left corner of each track's box) are drawn only with `debugMarks`
  (`TrackerConfig`, switch **Debug marks (box and body points)** on the control screen, read when a session
  starts). Why: the marks are inside the capture, and offline the box and
  the dots lowered the model's score of the same person on the next frame (so the marks vanished and the person
  was detected again: blinking); the measured table is in `docs/plans/M0c.md` ("Measured after T1"). The check on
  the phone is in `docs/TEST_CHECKLIST.md` (M0c) and the open item in §13.

### 6.6 Target lock (M4b, as built)
Display-only: the lock changes how the overlay draws one person. The app never injects input into another app and
uses no accessibility service.
- **The overlay window stays not touchable.** Locking uses a **small touchable handle window** (`LockHandleView`,
  its own `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `lockHandleSizeDp` 40, at the right screen edge in the
  vertical middle, content description in `strings.xml`). It is added after the overlay window and removed with it,
  on the main thread; if the system refuses it the overlay still works, without locking.
- **Flow** (`LockController`, pure Kotlin, tested; `LockArming` is the clocked state machine). A tap on the handle:
  when a person is locked it **unlocks**; else when armed it **cancels** the arming (a toggle); else it **arms**:
  the overlay window loses `FLAG_NOT_TOUCHABLE` (`updateViewLayout`), the HUD warning line shows the hint
  `tap a person` (a view-local flag drawn with the lowest priority, so a real warning wins; not part of
  `OverlayState`), and a main-looper timeout of `lockArmTimeoutMs` (5000) starts. The next finger-up on the overlay
  is mapped View → Img with the view's current `FitCenter` (`viewToImgX/Y`) against the state drawn last, and
  `LockPicker` returns the id of the **nearest shown track** (a LOST track counts) whose head point is within
  `lockMaxTapDistanceRatio` (1.0) × **that track's** box height of the tap; none → nothing is locked (fail
  closed). After that tap, after the timeout, when the handle cancels, and when the window is removed with the
  session, the overlay gets `FLAG_NOT_TOUCHABLE` back (a failed `updateViewLayout` then removes the overlay and
  the handle rather than leave a touchable window). While armed the alpha cap (§6.5) still applies.
- **Threads:** the wish to lock travels UI → capture in `LockKeeper.requestedId` (`AtomicInteger`, -1 = none, §5).
  Every state the pipeline publishes carries the lock only while the held id is among the state's shown tracks. An
  id that was never shown is dropped; a reset (rotation, new display or capture size) ends the lock; a track that
  is removed ends it, or makes it pending (next bullet). An id is never given to another track.
- **Re-lock by clothing colours (M4c, as built).** *Signature:* while the locked track is matched to a detection
  (not lost), the pipeline reads the pixels of that person's **torso** right after `detect` (on the capture thread,
  before the frame array is reused) and averages them into an `AppearanceSignature`: a normalised HSV histogram,
  `relockHueBins` 16 × `relockSatBins` 4 bins for pixels that have a colour, plus `relockGreyBins` 4 value bins for
  pixels darker than `relockMinValue` or less saturated than `relockMinSaturation` (both 0.15); an exponential
  average of weight `relockSignatureAlpha` (0.1) per frame. *Torso* (`TorsoRegion`): the rectangle through the
  shoulder and hip keypoints (COCO 5, 6, 11, 12, each score at least `relockKeypointMinScore` 0.5), else, only when
  the box is a standing body (height at least `relockFullBodyMinAspect` 1.8 × width), rows 0.25 to 0.6 of the box
  and its middle 60 % of the width; else (M4d), when the box is not a standing body but both shoulders are confident
  (a person at a desk, hips out of the picture), the **upper-body torso**: between the shoulders, from the lower
  shoulder down by `relockUpperTorsoRatio` (0.8) × the horizontal distance between the shoulders (never above a
  shoulder); when all four keypoints are confident but span no rectangle, nothing else is tried (fail closed); cut by
  10 % on every side and clipped to the image; never the head (the ring is drawn there and is part of the capture). At most `relockMaxSamplePixels` (2000) pixels are read on a grid, and
  fewer than `relockMinSamplePixels` (150) mean no torso. A torso that clipping cut (`TorsoRect.clipped`: the person
  is partly out of the picture) is not added to the signature.
  *Pending:* when the held track is no longer shown and a signature exists, the lock **waits** for
  `relockMemoryMs` (60 s) of **frame-timestamp time** (HUD ticks and idle time do not count; a video paused for
  longer than that expires the lock at its first frame). It draws nothing (no ring), `hasLock` stays true, so a tap
  on the handle ends it. Without a signature (no unclipped torso was ever read) the lock ends at once as `cleared (no
  signature)` instead of judging candidates against nothing. The signature keeps filling on every matched, unclipped
  frame while the lock is held; `lock: pending id=N frames=K` says how many frames filled it.
  *Candidates:* while the held track is matched the pipeline notes the highest track id of the tracker's list
  (`maxIdAtLastMatch`). While pending, a candidate is any **shown track matched in the frame whose id is higher**, so
  also a person who came back while the old track was still lost. Each candidate is judged on each of its first
  `relockJudgeFrames` (10) matched frames: every shown, matched track is sampled **once per frame** (a clipped or too
  small torso is "unsampled"), the candidate's Bhattacharyya distance to the signature `d` and the smallest distance
  `next` of the others go to `RelockMatcher.judge`: accepted when `d ≤ relockMaxDistance` (0.40, M4d; 0.30 in M4c) and `next − d ≥
  relockMinMargin` (0.10). The lock moves at the first acceptance (the new track gets the double ring in that very
  state). A candidate that is not accepted in its window is counted (`lockRelockRejects`) and logged once, when the
  window ends. **Fail closed:** another shown, matched track that cannot be sampled makes the candidate ambiguous
  (`next=unsampled`); a tie or doubt is no re-lock.
  *Early re-lock (M4f):* the same judging already **while the held track is LOST** (coasting, not yet removed) and a
  signature exists: the candidates (id above `maxIdAtLastMatch`), the window, distance, margin and sample rules are
  the ones above, **plus spatial evidence** of an id switch (appearance alone cannot tell a look-alike from the
  person while the person may still come back): the candidate's head must be within `relockEarlyMaxHeadRatio` (0.6)
  × the held track's height of the held track's **predicted head**, and **no other track at all, tentative ones included** (not the held one, not
  the candidate) may be within that radius (a person about to be shown counts). A candidate without it is not judged at all (no window used, no reject, no
  log), so it is still judged in full when the held track is removed (*pending*, unchanged). At the first acceptance
  the lock moves at once and the old track is no longer held (`lock: relocked id=A -> B d=… next=… early`;
  `lockRelocks` and `lockRelockRejects` count early moves and early rejects like the others). If the held track is
  matched again first, judging stops and the candidates are forgotten (the lock stays). What was judged before the
  removal carries over into pending. Limit: a person who comes back far from where they were lost is only found after
  the removal, as before.
  *Ends:* reset, Stop, a handle tap (also one that replaces a pending lock with a new one) or the memory running
  out. The signature is a colour histogram in memory on the capture thread only (no face, no image), dropped at each
  of these.
  *Limits (by design, numbers are guesses until phone data exists):* two people in similar clothes may never be
  told apart, and then the ring does not come back; a returning person is judged only on their first 10 matched
  frames (a long entry half out of the picture can use them up); the colour of the light and of the video's
  compression change the histogram; the distance and margin numbers are tuned from the `lock:` log lines (§6.7).
- **Handle while pending:** the handle behaves as locked (a tap ends the wait, the next tap arms), though the ring
  is not drawn.
- **Drawing:** the locked track's head ring uses `lockRingStrokeDp` (3) and a second ring at radius ×
  `lockOuterRingRatio` (1.3); its brackets are unchanged. It fades like any LOST track.

### 6.7 Diagnostics (`app/diagnostics`) — built in M0
- Ring-buffer logger (also to logcat) + in-app log screen.
- Uncaught-exception handler saves the stack trace; offered on next launch.
- **Share diagnostics** (share sheet) → zip: logs, device/OS, accelerator, **capture info** (display size,
  capture size and scale, size-change events, capture fps, dropped size-mismatched frames, timestamp-source
  statistics, `now − image.timestamp` range), FPS + thermal stats, `model_info.json`, current `TrackerConfig`,
  last 10 s replay JSON, latest benchmark CSV. This zip is what the owner pastes to an agent.
  **Save diagnostics** writes the same zip to a place the owner picks (system "save as" dialog, no storage
  permission; `DiagnosticsSave`), so it can be kept and sent later.
- **Model in the HUD (M0c):** the detections line ends with the model's input size and, for an imported model,
  the mark `imp`: `det 2/3 trk 2 max 0.89 in 416` (built-in) or `det 2/3 trk 2 max 0.89 in 640 imp`; with no
  model loaded the ` in …` part is left out (`HudFormatter.formatDetections`). When an imported model was expected
  but the built-in one runs (`OverlayWarning.IMPORTED_MODEL_NOT_USED`), the warning line says
  `Imported model not used: built-in model`; it has the lowest priority and does not hide the dots.
- **Model in `summary.txt` (M0c, `DiagnosticsReport`):** `modelSource` (`builtin` / `imported`; `unknown` before any
  session), `modelFile` (`builtin` for the built-in model), `modelSha256` (first `SHA256_SHORT_CHARS` = 12 hex
  characters), `modelInputSize`, `modelOutputRows`, `modelFallback` (a `ModelFallback` name or `none`),
  `modelFallbackMessage`, and `modelFallbackFile` (name and short sha256 of an imported model that was refused;
  only then).
- **Model size text (M1a):** `modelInputSize=` and the HUD `in …` use one text (`ModelInputText`): `640` for a square
  input, `832x384` for a rectangular one (width x height), `0` when there is no model. Example HUD line:
  `det 2/3 trk 2 max 0.89 in 832x384 imp`.
- **Tracker in the HUD and in `summary.txt` (M3b).** The detections line carries `trk N` between the row count and
  `max`: the number of shown tracks in the published state (`TrackSnapshot.count`; always a number, 0 when nobody
  is tracked); a changed track count refreshes the HUD text like a changed detection count (`hudChanged`). In
  `summary.txt` seven lines follow `headYImg=` and come before the model fields: `tracksShown` (now),
  `tracksMostAtOnce`, `trackBirths`, `trackConfirmations`, `trackIdlePromotions`, `trackRecoveries`,
  `trackRemovals`. The last six are the tracker's cumulative counters since Start (§6.3; `reset()` on a geometry
  change keeps them); all are 0 before the first frame. They are read on the capture thread at the HUD tick.
- **Lock in the log and in `summary.txt` (M4c).** One log line per lock event (never per frame): `lock: set id=N`,
  `lock: pending id=N frames=K` (K = frames blended into the signature), `lock: relocked id=N -> M d=0.18 next=0.41` (`next=none` when nobody else was shown),
  `lock: candidate id=M d=0.41 rejected (too far)` / `rejected (ambiguous, next=0.38)` (`next=unsampled` when
  another track could not be sampled) / `rejected (no torso)`, `lock: expired`, and `lock: cleared (handle | reset |
  stop | replaced | no signature)`. The numbers `d` and `next` are what to read when tuning `relockMaxDistance` and
  `relockMinMargin`. Four counters follow `trackRemovals` in `summary.txt`: `locks` (locks set), `lockRelocks`,
  `lockRelockRejects` (candidates whose window ended without acceptance), `lockExpired`; since Start, read on the
  capture thread at the HUD tick, 0 before the first lock.
- **Association misses in the log and in `summary.txt` (M4e).** One line per miss (§6.3), on the capture thread, at
  most `assocMissLogPerMin` (30) per minute (fixed one-minute windows; the counters count every miss):
  `track: miss id=L -> new id=N lostMs=250 score=0.88 stage=high|low gateChi2=123.46 iou=0.00 headRatio=1.50
  hRatio=1.05 cost=1.00 reason=gate|cost|taken` (two decimals, `lostMs` whole milliseconds, `n/a` for a number that
  cannot be computed); a birth next to a live track ends `reason=live state=confirmed|tentative detIou=0.23`, one
  after a removal `reason=removed cause=lostTime|exit|other`. When a window ends with dropped lines, the next line
  written is `track: miss suppressed N`. Read it with the limits: `gateChi2` above 9.21 = gated, `cost` at or above 0.7 (high) / 0.6
  (low) = too expensive. Eight counters follow `lockExpired` in `summary.txt`: `assocMisses`, `assocMissGate`,
  `assocMissCost`, `assocMissTaken`, `assocMissLowBirth`, `assocBirthNearLive`, `assocBirthAfterRemoval`, `assocBirthSuppressed` (F3/A1, §6.3); since Start, read at the HUD tick, 0 before the first miss.
- **Model per orientation in the log and in `summary.txt` (M1b-1).** After the model fields: `modelPortrait` and
  `modelLandscape` (`builtin` or `<name> <first 12 hex of sha256>`), `modelSwitches` (changes of the model caused by a
  change of orientation since Start; the load at Start is not counted) and `modelSwitchMsMax` (the longest, ms, from
  closing the old detector to the new one ready). One log line per load: `model: <name> for portrait|landscape
  (start Nms)` at Start, `(switch Nms)` after a change (`built-in` for the built-in model). `ModelStatus` is
  published again after each switch, so the `modelSource/modelFile/...` fields describe the model loaded now.
- **Timing in `summary.txt` (M1a).** After the model fields, in this order: `timingSamples`, then `copyMsP50`,
  `copyMsP95`, `preMsP50`, `preMsP95`, `inferMsP50`, `inferMsP95`, `postMsP50`, `postMsP95`, `frameMsP50`,
  `frameMsP95`, `resultAgeMsP50`, `resultAgeMsP95` (twelve keys, milliseconds with one decimal), then
  `thermalStatus` and `thermalHeadroom`.
  - *What they are:* `copy` = the row copy into the reused buffer; `pre` = image to model input; `infer` = model run
    plus output readback; `post` = model output to detections; `frame` = acquire → publish; `resultAge` = publish
    time minus the frame's image timestamp (only frames whose image timestamp was accepted count, §4.1).
  - *Samples:* one per processed frame (detected and published); a frame that was drained, dropped for its size or
    not copied adds none. `timingSamples` is how many frames the windows hold, at most `timingWindowFrames` (512,
    about 17 s of 30 fps): the numbers describe the newest frames, not the whole session. `TimingWindow` is a ring
    with a scratch array allocated once, so `add` allocates nothing; NaN and negative values (`NOT_MEASURED`) are
    dropped; an empty window is NaN, written `n/a`.
  - *Where:* the windows are written and the percentiles computed on the capture thread, at the HUD tick
    (`FramePipeline.timingSummary`, called from `CaptureStatsHolder.publish`); the diagnostics thread only reads the
    immutable `CaptureStats`. The clock is `NanoClock`, no new one.
  - *Thermal:* `thermalStatus` is the name of `PowerManager.currentThermalStatus` (`NONE` … `SHUTDOWN`; API 29+) and
    `thermalHeadroom` is `getThermalHeadroom(0)` (API 30+, a ratio where 1.0 = throttling). `ThermalSampler` reads
    at most every `thermalSampleIntervalMs` (2 s; the platform allows about one headroom read per second) and
    keeps the last answer; whatever cannot be read is `n/a`, never a guessed number (AGENTS rule 1).
  - *The HUD text does not change:* a changing HUD is captured and feeds back (§6.5).

### 6.8 Capture frame rate & benchmark screen — planned for M1b
- **Capture fps** is *measured*, not selected: frames delivered per second while the screen content changes
  (a static screen delivers none, §6.3). The HUD shows it (smoothed, ≤ 2 Hz); the capture scale is a
  `TrackerConfig` value (default ½). The Poco X7 Pro's real rate (60 vs 120 Hz source, what the virtual display
  delivers) is a question the benchmark answers in M1b.
- **Benchmark screen** shows and exports (CSV, included in diagnostics):
  measured capture fps on moving content (e.g. a looping video the owner plays) and the `now − image.timestamp`
  latency; inference ms (p50/p95, with readback) for 320 and 416, GPU and CPU, run on a fixture image so it does
  not depend on the capture; end-to-end fps; thermal status (`PowerManager.currentThermalStatus`) and thermal
  headroom; a 10-minute soak mode logging all of the above every second.
- Results decide the defaults (capture scale, model size, fps cap) — recorded in `TrackerConfig` defaults and in
  this doc.

---

## 7. Config & policies

### 7.1 Config
`TrackerConfig` (in `core`): every tunable number with unit, default, min, max, KDoc. No magic numbers
elsewhere. Debug sheet exposes the important ones as sliders + "reset to defaults", plus: model size
(`modelInputSize`, 320 or 416, default 416), capture scale, analysis fps cap (60/30), accelerator override (auto/CPU).
Capture-related entries (M0b): score threshold 0.15, capture scale ½, HUD rate 2 Hz, timestamp guard 100 ms,
overlay alpha cap fallback 0.8, dot/box stroke widths, lock arm timeout.
Tracker entries (M3a), all in `TrackerConfig` with unit and bounds: head point (keypoint enter/leave scores, offset
EMA, fallback and noise ratios), Kalman (acceleration and noise ratios, initial velocity, longest time step
`trackMaxDtMs`, gap inflation, miss velocity decay), association (weights, head scale, gate, dedupe IoU, cost
limits per stage) and lifecycle (high and birth scores, confirm hits, idle promotion, lost time, exit margin,
`maxTracks`). M4a adds `trackTentativeMaxMisses` (frames, 0..5, default 1) to the lifecycle group and the
smoother group (`smoothEnabled`, `smoothMinCutoffHz` 0.05..30, `smoothBeta` 0..100, `smoothDerivativeCutoffHz`
0.05..30); defaults in §6.4. M4b adds the fade group (`fadeMinAlpha` 0..1, default 0.25; `fadeStepAlpha` 0.01..1,
default 0.1) and the lock group (`lockHandleSizeDp` 16..96, 40; `lockMaxTapDistanceRatio` 0.1..3, 1.0; `lockArmTimeoutMs`
1000..30000, 5000; `lockRingStrokeDp` 0.5..6, 3; `lockOuterRingRatio` 1.05..2, 1.3). M4c adds the re-lock group (all `relock…`, unit and bounds in KDoc): signature (`relockHueBins` 16, `relockSatBins`
4, `relockGreyBins` 4, `relockMinValue` and `relockMinSaturation` 0.15, `relockSignatureAlpha` 0.1), torso
(`relockTorsoShrink` 0.1, `relockBoxTopRatio` 0.25, `relockBoxBottomRatio` 0.6, `relockBoxWidthRatio` 0.6,
`relockKeypointMinScore` 0.5, `relockFullBodyMinAspect` 1.8), sampling (`relockMaxSamplePixels` 2000,
`relockMinSamplePixels` 150) and decision (`relockMemoryMs` 60000, `relockMaxDistance` 0.40, `relockMinMargin` 0.10,
`relockJudgeFrames` 10); M4d adds `relockUpperTorsoRatio` 0.8 (0.2..2) and the check `relockMinSamplePixels` ≤
`relockMaxSamplePixels` / `RELOCK_SAMPLE_GRID_SLACK` (4); M4e adds the diagnostics `assocMissNearRatio` (0.1..5,
default 1.0) and `assocMissLogPerMin` (0..600, default 30), which change no track; M4f adds `trackGateMinSigmaRatio`
(0..1, default 0.2; 0 = no floor on the gate's sigma) and `relockEarlyMaxHeadRatio` (0.1..3, default 0.6). There is no `displayLeadMs`
(no prediction, §6.4).

### 7.2 Thermal policy (M5)
If thermal status ≥ `MODERATE` or headroom forecast > 0.9: step down the analysis fps cap 60 → 30; then 416 → 320;
step back up after 60 s below the threshold. Every step is logged and shown in the HUD.

---

## 8. Model pipeline & licence
- `export-model.yml` — **manual** (`workflow_dispatch`) GitHub Actions job on ubuntu x86_64. Installs
  `tools/requirements.lock` (pinned ultralytics ≥ 8.4.83 + deps), runs
  `export(format="litert", nms=False, imgsz=S)` for S = 320 and 416 (the bundled models; downloads: see below), runs
  Python `predict()` on the fixture image to produce golden outputs, writes `model_info.json` (I/O shapes, versions, sha256), opens a PR.
- Normal builds never run Python. Build fails with a clear message if a model or its sha256 mismatches: the check
  is the `stage<Variant>ModelAssets` Gradle task of `app`, which verifies every file listed in `model_info.json`
  and copies only the `.tflite` files and `model_info.json` into the APK assets.
- No INT8 / w8a16 (loses the end-to-end head; w8a16 falls off the GPU).
- **Imported models (M0c).** The app keeps imported models besides the bundled ones (one slot until M1b-1, now a library, see "Model library" below).
  - *Storage:* the picked file is copied into `noBackupFilesDir/imported/` while its sha256 is computed and stored
    as `model-<first 16 hex of sha256>.tflite` (named by content, so two imports of equal size never share the
    name the GPU program cache is keyed by); importing again replaces it, **Use built-in model** deletes it. Import
    and reset work only while the capture is stopped; one import runs at a time per process.
  - *Accepted layout, version 2 (M1a):* exactly one float32 input `[1, 3, H, W]` (channels first; height and width
    each a multiple of 32 between `importMinInputSize` and `importMaxInputSize`, 160 to 1280 by default; the long
    side at most `importMaxInputAspect` (4.0) times the short side) and exactly one float32 output `[1, N, 57]`
    (end-to-end pose head, 17 keypoints, `0 < N <= 300`: the vendored decoder treats 57 columns as end-to-end only
    up to 300 rows). Anything else is refused with a reason that names the shapes found. Version 1 accepted only
    square inputs; the rejection `input_not_square` is gone, a too elongated input is refused as `input_aspect`
    (a key is never reused for another meaning). Square models stay accepted.
  - *The real tensors decide:* the shapes are read from the model itself through LiteRT (`ModelProbe`, CPU
    `CompiledModel`, worker thread); metadata inside the file is never trusted.
  - *Never worse than the built-in model:* if the imported model cannot be loaded at Start, the session runs
    the built-in model and shows the warning of §6.7. A native crash cannot be caught, so a marker is stored
    before LiteRT first opens the file and before each session's load, and cleared after; a marker found at the
    next app start (`ImportGuard`: `Idle`, `Pending(stage)`, `Tripped(stage)`) is handled by its stage. A trip at
    the **load** switches the imported model off (built-in model, and the control screen says so). A trip at the
    **probe** suspects only the new file that was being checked (it was never stored): a stored model whose
    length and sha256 still match its meta stays in use and the screen only shows a notice that the new file was
    not imported; if nothing is stored, or the stored file does not match (or the check cannot run), the
    built-in model is used and the screen says so. The first Start of a kept model stores `Idle` after its load,
    which lifts the probe trip; an accepted import or **Use built-in model** lift any trip. While a trip is stored
    the probe of a new file does not replace it (no marker is written before or after that probe), so a model
    switched off after a load crash stays off even if the process dies in the next probe.
  - *Model library and a model per orientation (M1b-1).* The single imported-model slot became a library of up to
    `modelLibraryMax` (4) models keyed by sha256 (a model imported twice is stored once; a full library refuses the
    import until a model is deleted) and two slots, **portrait** and **landscape**, each the built-in model or one
    library model (default built-in). The orientation is the capture geometry (`ModelOrientation.of(widthImg,
    heightImg)`: width > height is landscape; square or unknown is portrait). *Storage* under
    `noBackupFilesDir/imported/`: one directory per model (`<first 16 hex of sha256>/`, managed by an
    `ImportedModelStore` over it: `model-<16 hex>.tflite` and `meta.txt`, written in the crash-safe order above),
    `slots.txt` (`OrientationSlots`, header `posetracker-model-slots v1`) and `blocked.txt` (`ModelBlocklist`), the
    last two written to a scratch file, synced and renamed over the old one. The per-model stores have no
    crash marker of their own (only the library owns it). *Migration:* the model of the earlier version (in the root
    directory) is **copied** (never moved) into its own directory on the first read, checked by length and sha256, both
    slots point to it, and only then the old record goes; each step can be repeated after a process death and a
    half-finished entry is finished from whichever copy exists. An old record whose file is gone becomes an entry
    that the screen reports as "file missing". *Fail closed:* a model record that does not read is
    skipped, a slot record that does not read (or names a model that is not there) is the built-in model for that
    slot, both logged; an unreadable blocked list blocks every model.
  - *Switch on rotation (M1b-1).* `ModelSwitcher` (capture thread; never the main thread) loads the model of the
    start geometry's slot at Start and, whenever the capture geometry changes and the slot asks for another model
    than the loaded one, closes the old detector and loads the new one through `SessionModelLoader`; the pipeline then
    drops the old detections and resets the tracker, the smoother and the lock (`FramePipeline.onModelChanged`). A
    model that is blocked, missing or fails to load (an `Error` such as `OutOfMemoryError` included) gives the built-in
    model with the existing fallback reasons; the session never stops, and a failed load is tried again when its
    orientation returns. The LOAD crash marker now names the model (`pending:load:<sha256>`); a trip moves only that
    model to `blocked.txt` and the marker goes idle, so the other models keep working. If the marker cannot be set back to idle after
    a load (a failed write), the `Pending` stays stored: for the rest of the process loads fall back to the built-in model
    and imports answer BUSY (the library refuses a second `Pending`), and at the next start that model is blocked (fail
    closed: a possible crash is never ignored). A model file is copied into the library only if it still has its length
    and sha256; an old file that does not is migrated as a "file missing" entry, once. To try a blocked model again,
    delete it and import it again (importing the same file again also unblocks it). A marker without a model (an
    older version's) blocks the migrated model. `ModelImporter` stores through `ImportTarget` (the library; the
    single-model `ImportedModelStore` implements it too); a full library is `FailureKind.LIBRARY_FULL`.
  - *Control screen (M1b-1).* The library list (name, size, **Delete**, "switched off after a crash"), the choices
    **عمودي / Portrait** and **عرضي / Landscape**, the line "In this orientation: …" (the slot of the screen's
    orientation, with its notice) and **Remove all imported models** (replaces **Use built-in model**; it needs a second
    tap within `TrackerConfig.removeAllConfirmMs`, the label says "Tap again to remove all").
  - *Classes:* `ModelFileSniffer` (`TFL3` identifier and size limits), `ImportedModelRules` (accept or refuse
    the tensors), `ImportedModelMeta` (name, sha256, bytes, input width and height, output rows; strict text codec, **version 2**:
    header `posetracker-imported-model v2` with `inputWidth=` and `inputHeight=`; a stored **v1** text with one
    `inputSize=` line is still read and becomes width = height, so an installed app keeps its model),
    `ImportGuard` (crash-marker state machine) — all in `core/modelimport`; `ModelProbe` and
    `PoseDetectors.fromImported` in `detector`; `ModelImporter` (copy, probe, check, store), `ImportedModelStore`
    (files and preferences), `ModelChoice` (pure choice at Start), `SessionModelLoader` (load with the
    fallback), `ModelStatusHolder` (what the session runs, read by the screen and the diagnostics) — in
    `app/modelimport`.
  - *The 640 model is a download, not part of the app:* `export-model.yml` (manual, inputs `sizes` and
    `publish_tag`) builds `yolo26n-pose_640.tflite` with the pinned toolchain (`tools/requirements.lock`), checks
    it and uploads it to the pre-release tagged `models`. It is not in the APK, `model/**` or `model_info.json`,
    and the rolling `latest` APK release is not touched.
  - *Rectangular downloads (M1a):* the same release also holds `yolo26n-pose_640x288.tflite`,
    `yolo26n-pose_832x384.tflite` and `yolo26n-pose_960x448.tflite` (input `[1, 3, H, W]`, output `[1, 300, 57]`,
    each with a `.json`). Names and sizes are WIDTHxHEIGHT. They are for **landscape**: the screen is about 2.2
    times wider than high, so a landscape input keeps people larger; in portrait a landscape model makes people
    smaller than the square 640 model does, so use 640 or the built-in model there (a per-geometry choice is M1b).
    `tools/export_model.py --sizes` takes `S` or `WxH` entries (each side a positive multiple of 32, no
    duplicates; `640x640` means `640`); `WxH` works only with `--out` (the download path), the default 320/416
    path under `model/` is unchanged. The export uses `imgsz=(H, W)`, checks the tensors, and runs `predict()` on a
    landscape sample image for `W > H` (a portrait one otherwise) with the same pass rule (at least two people at
    score 0.5). `--self-test` checks the parsing and naming without the export toolchain.
- **Licence: AGPL-3.0 for the whole app** (vendored Ultralytics code + model weights). Fine for private personal use. Any distribution (Play Store, sharing the APK
  publicly) requires open-sourcing app + weights, or an Ultralytics Enterprise licence.

---

## 9. Testing
| What | How |
|---|---|
| Geometry | table tests in `core`: `Letterbox` Img↔Mdl round-trip; `FitCenter` corners → view corners and back, portrait + landscape, with offset (cutout) and different window/capture sizes; `CaptureSize` (1220×2712 → 610×1356, exact aspect, even dimensions) |
| Capture math | `RowCopy` (padded row stride, short last row, tight output); `FrameTimestamp` guard (in range, negative, > 100 ms → fallback); teardown idempotent and size-mismatch frames dropped (fake session); per-frame `catch` handles `OutOfMemoryError` |
| Overlay | `HudFormatter` writes the expected text into a reused `CharArray`; the invalidate decision (new snapshot / deadband) as a pure function |
| Tracking (synthetic, known truth) | scenarios S1 to S15 on `SyntheticScene` (crossing, stop, 0.5 s dropout, keypoint dropout, idle gap = no frames ≠ miss, still picture, score ramps, false positives, walking out, duplicate box, random dropouts, growing person, S15 a flickering person at score 0.4 shown within 1000 ms), each at 20, 30 and 60 fps and at 30 fps ± 30 %; the Python functions in `docs/plans/M3_sim/scenarios.py` define them; measured first on the reference model (§6.3) |
| Tracking metrics (asserted) | numbers in `core/src/test/**/MetricsThresholds.kt` (protected): ID switches 0 on crossings; M3: still-jitter **ratio** track / raw (RMS of the 2nd difference) ≤ 0.60 at 20 fps, 0.45 at 30, 0.25 at 60; stop overshoot < 0.10 h; \|lag\| < 0.5 frame. The absolute "still jitter < 1 px" belongs to M4 (OneEuro on top; the Kalman alone gives 1.37 px at 20 fps, h = 200 px, in the model) |
| Real replays | from Share diagnostics, added as regression tests |
| Decoding | golden test vs Python output, for both model sizes |
| On phone | `docs/TEST_CHECKLIST.md` per milestone with pass criteria: taps pass through (no "Untrusted touch" in logcat), paused video → capture fps ≈ 0, `now − image.timestamp` 0–30 ms, dot A/B, rotate to landscape, lock screen → clean stop and new consent on restart, CPU banner, DRM app captures black (expected), permission-denied paths, two people cross, occlusion, sudden stop, 10-min soak |

Agents may not loosen asserted thresholds (protected files).

---

## 10. CI/CD
`android.yml` on PRs and `main`:
- `concurrency: cancel-in-progress`, path filters (skip docs-only changes).
- `actions/setup-java` 17, `gradle/actions/setup-gradle` (cache), `gradle/actions/wrapper-validation`.
  The Android SDK is preinstalled on `ubuntu-latest`.
- `./gradlew spotlessCheck test lint assembleDebug`.
- **Signing:** the debug build uses `signing/debug.keystore`, committed on purpose. It is a debug-only key
  in a private repo, never used for release; it makes every APK install over the previous one and needs no
  secrets setup. A real release key (later) goes in GitHub Secrets and is never committed.
- `versionCode` = run number; HUD shows version + git SHA.
- On `main`: publish the APK to a rolling GitHub Release **`latest`** → one stable download link for the phone.
- Test summary posted as PR comment. Builds confirmed working on the phone are tagged `ok-YYYYMMDD-n` for rollback.
- Budget: private-repo free tier ≈ 2,000 min/month ≈ 200 builds; the rules above keep usage low.

---

## 11. AGENTS.md (content outline)
0. Roles, models and the milestone workflow are defined in `docs/AI_TEAM.md` (lead plans and supervises on
   Opus 5.5; implementer builds on Sonnet 5.5; android-reviewer and debugger on Opus 5.5; consultant on
   Fable 5.2 for hard questions; Explore on Haiku 5.5).
1. Read the ARCHITECTURE sections named in the task (not the whole file). Verify with `scripts/verify.sh`.
2. **Protected — change only if the task explicitly says so:** `gradle/libs.versions.toml`, Gradle wrapper,
   `.github/**`, `core/**/geometry/**`, test thresholds in `core/src/test/**/Metrics*`, `model/**`, `signing/**`,
   `.claude/skills/**`.
3. Never delete, skip, or weaken tests to make CI pass. CI fails if the `@Test` count drops.
4. Coordinate suffixes `Img/View/Mdl` and the thread table (§5) are mandatory.
5. No magic numbers outside `TrackerConfig`. No deprecated APIs (e.g. use `ResolutionSelector`).
6. LiteRT: `CompiledModel` only; never on the main thread; never allocate `TensorBuffer`s per frame;
   never use `Accelerator.GPU` without the `litert-gpu` dependency; never mix `org.tensorflow:*` with
   `com.google.ai.edge.litert:*`; bundled runtime is a deliberate choice (overrides the official skill's
   Play-services default).
7. No new dependencies without stating why. No Dependabot/Renovate.
8. One concern per PR. Each PR description ends with **"How to test on the phone"** steps.
9. No per-frame allocations in the analyzer or draw phase.
10. Context hygiene (docs/AI_TEAM.md §3): builds only via `scripts/verify.sh`, CI only via
    `scripts/ci-status.sh`, never paste full logs, read files by line range, wide searches via `Explore`.
11. `AGENTS.md` and `CLAUDE.md` are never edited mid-session; rule changes are proposed in the handoff.
12. `AGENTS.md` stays under ~150 lines.
13. Vendored Ultralytics code (`com/ultralytics/yolo`): change it only when the task needs it, keep the upstream
    file headers, and log every change in `third_party/ULTRALYTICS.md`. Upstream's own AGENTS.md/CLAUDE.md
    rules are not imported (its "tests are out of scope" rule does not apply here).

---

## 12. Milestones (demo first)
| # | Deliverable | Owner sees on phone |
|---|---|---|
| M0a | Tooling: 3 modules, CI green, signed APK on `latest`, AI team files + scripts, skills vendored, AGENTS/CLAUDE.md; model-export workflow run (FP32 320/416 + golden outputs) | app opens (placeholder), version in HUD |
| M0b | Import the Ultralytics core (§2.1) → screen capture + overlay + raw keypoints in green with our model, portrait + landscape; permissions flow; diagnostics + log screen; CPU banner | **green boxes/keypoints over people in any app**, taps still reach the app below |
| M0c | Import one model file (layout checked from its real tensors, crash guard, fallback to the built-in model); `yolo26n-pose_640.tflite` as a download under the release tag `models`; steadier marks (head ring + corner brackets, **Debug marks** switch); plan `docs/plans/M0c.md` | import the 640 file, HUD shows `in 640 imp`; a medium-distance person detected and blinking less |
| M0d | Video-area crop: bigger people for the model (parked, not approved; plan `docs/plans/M0d.md`) | — |
| M1a | Rectangular model inputs end to end (import layout v2, meta v2, engine, HUD, diagnostics), rectangular models to download (`640x288`, `832x384`, `960x448`), timing statistics (p50/p95 per stage, result age, thermal state) in `summary.txt`; plan `docs/plans/M1.md`. Done in this PR; the one-thread LiteRT item is already true since M0b | import `832x384`, HUD `in 832x384 imp`; real ms / fps per model on one video; timing lines in the diagnostics |
| M1b | Later: raw-output goldens and the golden decode test, allocation-free pure-Kotlin decoder (D2), benchmark screen with CSV and soak mode, a bundled landscape model with a per-geometry choice | real numbers for fps / ms / heat; no per-frame allocation in the decoder |
| M2 | Img→View hardening: `FitCenter` extended and checked on the phone across rotation, cutout and resize events (no camera) | green dots exactly on heads in portrait and landscape |
| M3 | **M3a** (done): tracker + Kalman in `core`, scenario tests S1-S14 (20/30/60 fps, idle gaps); **M3b** (done): the tracker in the app (`FramePipeline` → `TrackSnapshot` → overlay; rings and brackets from tracks, `trk N` in the HUD, track ids with `debugMarks`, tracker counters in `summary.txt`); plan `docs/plans/M3.md` | rings and brackets from tracks that bridge short misses, a ring on a still picture after 0.4 to 0.9 s, `trk N` in the HUD, `track…` lines in the diagnostics |
| M4 | **M4a** (done): tentative tracks survive one missed frame (rings come sooner), display smoother (OneEuro per shown track, box moves with the head), scenario S15; plan `docs/plans/M4.md`; **M4b** (done except the phone check): no forward prediction (measured, §6.4), fading LOST tracks, handle-window tap-to-lock; the tuning sheet is not built; **M4c** (built, phone check open): the lock comes back to a person who left the picture and returned, by the colours of their torso (§6.6) | M4a: rings that come sooner and do not tremble on a still person; M4b: LOST rings fade, tap-to-lock with a double ring; M4c: the double ring comes back on the same person within a minute, `lock:` lines in the log |
| M5 | Thermal policy, perf pass, lifecycle/permission polish, replay mode | stable over 30 min |

One session per milestone; M4 may need a second session for tuning on the phone.

## 13. Open items
- Exact output column order / scale — settled by the golden decode test in M1b.
- Real capture fps of the virtual display on the Poco X7 Pro (and `image.timestamp` reliability) — measured in M0b;
  the timing lines of M1a (`resultAgeMsP50/P95`, `captureFps`) add numbers, the benchmark screen is M1b.
- **HyperOS restrictions:** battery/auto-start limits killing the foreground service, extra "display pop-up"
  toggles, overlay or notification quirks — checked on the phone in M0b; findings go in `docs/TEST_CHECKLIST.md`.
- **Dot A/B (overlay in the capture):** answered offline in M0c: the keypoint dots and the full box lower the
  model's score of the same person on the next frame (`docs/plans/M0c.md`, "Measured after T1"; two people
  only), so the default marks are now a ring and corner brackets (§6.5). Still to check on the phone, with the
  **Debug marks** switch off and on (`docs/TEST_CHECKLIST.md`, M0c).
- **People of 12–15 % of the video height stay undetected** at 640 (and far workers and the snow scene still
  score 0.00); the area crop is M0d (parked), rectangular inputs M1a (the phone numbers decide which size to use).
- **Duplicate boxes on one person** were seen once (v0.1.37): dedupe before association is in the tracker (IoU ≥
  0.7). Not handled: duplicate boxes with IoU below 0.7 give two tracks on one person.
- **Tracker limits (M3a, not asserted):** slow crossings in one row can swap ids (no appearance model); a person
  missing for 0.5 s while moving at 2 h/s gets a new id; a person faster than about 1 h/s whose picture freezes
  without a frozen frame gets a new id afterwards. Score numbers (`trackBirthScore` 0.25, `trackHighScore` 0.5) and
  the other tracker defaults are tuned on the phone in M4.
- **Per-frame objects of the pipeline (M3b):** `FramePipeline` still allocates one `OverlayState` and the
  detections of the detector per processed frame, and a `TrackSnapshot` (arrays inside) whenever somebody is
  shown; `TrackSnapshot.EMPTY` is shared, so nothing is allocated for that part while nobody is tracked. The
  `Tracker` itself allocates nothing after construction (§6.3). Making the state objects reusable is not done;
  decide it with the allocation numbers of the M1b benchmark.
- **Unmerged branch `fix-export-model-deps`** holds a stricter export script (self-check against `predict()`,
  raw-output goldens, public-domain fixture); its raw-output goldens are needed for M1b.
- **M1b:** raw-output goldens (script on `fix-export-model-deps`), pure decoder + golden test, benchmark screen, a
  bundled landscape model with a per-geometry choice once the phone numbers of M1a are in.
- **Rectangular model on the Mali GPU:** if one compiles only for the CPU, the red banner shows it; report the size.
- **`readFloat()` allocates:** the LiteRT Kotlin `TensorBuffer.readFloat()` returns a new `FloatArray` per call, so
  the 300x57 output readback (68 KB per frame) cannot be allocation-free with the bundled runtime
  (`third_party/ULTRALYTICS.md`).
- **DRM / secure apps capture black** (`FLAG_SECURE`): expected and unfixable by us; no dots there. Only
  documented, plus the HUD may say "no frames".
- Tuning sheet (sliders for the important `TrackerConfig` numbers): not built in M4b; later.
- **Re-lock limits (M4c):** the colour signature cannot tell two people in similar clothes apart (then the ring does not
  come back, by design); it is judged on the first 10 matched frames of a new track; `relockMaxDistance` 0.40 (M4d, from the first
  phone test) and `relockMinMargin` 0.10 are to be tuned from the `d=` and `next=` values of the `lock:` log lines; the
  phone shows how often the torso rows land on clothes and how often a returning person is half out of the picture
  when first shown. A video paused for more than `relockMemoryMs` expires a pending lock at its first frame.
- Lock keep-alive by position (re-lock without colours): not built; the colour signature replaces it (§6.6).

## 14. Build environment (summary; details in BUILD_PLAN.md)
Code is written in Claude Code cloud sessions (claude.ai/code) on this repo by the AI team in
`docs/AI_TEAM.md`: the main session is the lead (Opus 5.5, effort `xhigh` by default, `max` on request),
which plans and supervises while Sonnet 5.5 implementer subagents write the code and a Fable 5.2 consultant
answers hard questions. Each change is verified in the session with `scripts/verify.sh`
and always by GitHub Actions, which is the source of truth. The owner's laptop is not used for builds.

## 15. Changes
- **v1.6 (M1b-1, 2026-11-20):** a model per orientation (plan `docs/plans/M1b.md`). §8: the model library (up to
  `modelLibraryMax` models by sha256), the portrait and landscape slots, migration, the per-model LOAD crash marker and
  blocked list, the switch on rotation on the capture thread, the control screen; §6.7: `modelPortrait`,
  `modelLandscape`, `modelSwitches`, `modelSwitchMsMax` and the `model: … for landscape (switch Nms)` line; §7.1:
  `modelLibraryMax` 4 (1..16).
- **v1.5 (M4f, 2026-11-19):** fewer id switches from the third phone test (plan `docs/plans/M4.md`). §6.3: the gate's
  per-axis variance is at least `(trackGateMinSigmaRatio × h)²`; §6.6: early re-lock while the held track is LOST,
  only with spatial evidence (log suffix `early`); §7.1: `trackGateMinSigmaRatio` 0.2 (0..1), `relockEarlyMaxHeadRatio` 0.6 (0.1..3).
- **v1.4 (M4e, 2026-11-18):** association-miss diagnostics from the second phone test (plan `docs/plans/M4.md`), no
  behaviour change. §6.3: `AssociationMiss` records and the counters `assocMisses`, `assocMissGate`, `assocMissCost`,
  `assocMissTaken`, `assocMissLowBirth`, `assocBirthNearLive`, `assocBirthAfterRemoval`. §6.7: the `track: miss …` log
  line (at most `assocMissLogPerMin` a minute, then `track: miss suppressed N`) and the seven `summary.txt` counters. §7.1: `assocMissNearRatio`, `assocMissLogPerMin`.
- **v1.3 (M4d, 2026-11-17):** re-lock tuning from the first phone test (plan `docs/plans/M4.md`). §6.6: `relockMaxDistance`
  0.30 → 0.40; upper-body torso from the shoulders (`relockUpperTorsoRatio` 0.8) when the hips are not confident and the
  box is not a standing body; four confident keypoints with no area fail closed; a lock with an empty signature ends
  at once as `cleared (no signature)`. §6.7: `lock: pending id=N frames=K`. §7.1: the new field and the check
  `relockMinSamplePixels` ≤ `relockMaxSamplePixels` / 4. §13: limits updated.
- **v1.2 (M4c, 2026-11-17):** re-lock by clothing colours, documented as built (plan `docs/plans/M4.md`). (A) §6.6: the
  "no re-lock" bullet is replaced by the as-built description: torso signature (HSV histogram with grey bins, EMA),
  `TorsoRegion` (keypoints or a full-body box, clipped torsos and small samples are not used), pending for
  `relockMemoryMs` of frame time with no ring, candidates by id (`maxIdAtLastMatch`), judging on each of the first
  `relockJudgeFrames` matched frames with every track sampled once per frame, accept at distance 0.30 (0.40 since v1.3) and margin 0.10,
  fail closed on unsampled neighbours, ends (handle, replaced, reset, Stop, expiry), limits. (B) §5: `LockKeeper` in
  the flow and thread table (replaces the `lockedId` row; signatures are capture-only). (C) §6.7: the `lock:` log
  lines and the counters `locks`, `lockRelocks`, `lockRelockRejects`, `lockExpired`. (D) §7.1: the 18 `relock…`
  fields; §12: M4c in the M4 row; §13: re-lock limits. `TrackList.matchedDetectionIndex` (core) tells which detection
  matched a track in the newest `update`. Review fixes after T2: candidates by id instead of "newly shown", a
  judging window, clipped torsos, the full-body box rule, one sample per track and frame, `cleared (replaced)`.
- **v1.1 (M4b, 2026-11-17):** fading and tap-to-lock, documented as built (plan `docs/plans/M4.md`). (A) §6.4: no forward
  prediction, with the measured numbers (the lead raises jitter and overshoot and leaves the walker's error
  unchanged); `displayLeadMs` is gone. (B) §6.5: LOST tracks fade with `MarkFade` (`fadeMinAlpha`, `fadeStepAlpha`),
  `TrackSnapshot.lostMs` (idle adds, capped), redraw on a fade step, the paused-screen fade at the HUD tick; the
  redraw also follows `lockedId`. (C) §6.6 rewritten to the code: handle window, `LockController` / `LockArming` /
  `LockPicker`, arm / tap / unlock / cancel flow, no keep-alive or re-lock, double ring. (D) §5: the lock in the data
  flow and thread table (`FramePipeline.lockedId`, `CaptureSession.pipeline` `@Volatile`, lock flow on main). (E) §7.1:
  fade and lock fields; §12: M4b row.
- **v1.0 (M4a, 2026-11-17):** faster confirmation and a display smoother, documented as built (plan
  `docs/plans/M4.md`). (A) §6.3: a tentative track survives `trackTentativeMaxMisses` (1) missed frames (coasts,
  not shown, hit counter kept) instead of dying on the first; scenario S15 (score 0.4, frames dropped at random,
  shown within 1000 ms). (B) §6.4 rewritten: `HeadSmoother`, a OneEuro filter per shown track id on the head point
  in Img px (defaults 1.0 Hz, beta 3.0, derivative 1.0 Hz; the old 1.5 Hz / 0.5 / 1 Hz and "normalised by h"
  design is replaced by a cutoff that grows with speed / h), the box moved by the same delta, an id that is not
  shown frees its slot, the idle republish does not advance it, reset with the tracker; forward prediction, fading
  and the lead time stay M4b. (C) §5: `TrackSnapshot.ofSmoothed` / `ofIdle` and the smoother in the flow and the
  thread table (capture thread, not main). (D) §7.1: config entries `trackTentativeMaxMisses` and the four
  `smooth…` fields; §12: M4 split into M4a (done) and M4b.
- **v0.9 (M3b, 2026-11-17):** the tracker in the app, documented as built (plan `docs/plans/M3.md`). (A) §5: data
  flow and thread table: `Tracker` per `FramePipeline` on the capture thread, `TrackSnapshot` (immutable copy of
  the shown tracks, shared `EMPTY`) inside `OverlayState`, HUD tick → `Tracker.onIdle`, a geometry change →
  `Tracker.reset()`; a frame the detector did not see is not given to the tracker. (B) §6.5: the marks come from
  the shown tracks (brackets at the track box, ring at the tracked head, LOST drawn like CONFIRMED until M4);
  raw detections and track ids only with `debugMarks`; redraw decided by `RedrawPolicy.shouldRedrawMarks`
  (`tracksChanged`); a still picture gets its marks 0.4 to 0.9 s after the last frame (was: at once), a playing
  video from the third frame. (C) §6.7: HUD `det 2/3 trk 2 max 0.89 in 640 imp` and the seven tracker lines of
  `summary.txt` (`tracksShown`, `tracksMostAtOnce`, `trackBirths`, `trackConfirmations`, `trackIdlePromotions`,
  `trackRecoveries`, `trackRemovals`). (D) §12: M3b done; §13: per-frame objects of the pipeline.
- **v0.8 (M3a, 2026-11-16):** the tracker in `core`, documented as built (plan `docs/plans/M3.md`, Consults C1 to
  C3). (A) §6.2: `HeadMeasurement` (stateless) and `HeadPointEstimator` (per track); offset EMA α 0.2 (was 0.1);
  offsets learned against the plain mean of the five keypoints when all are visible; the fallback box point is
  used whenever no keypoint can anchor the centre (was "fewer than 2 ever visible"); variance floor
  `headMinMeanConfidence`. (B) §6.3 rewritten: three independent position/velocity filters (head x, head y,
  height); one time rule `dtEff = min(dt, trackMaxDtMs)` with a gap step inflating the noise ×10 once; the
  "NIS > 9.21 inflates Q" rule and any inflation on a miss are gone; dedupe before association; association with
  a cost limit per stage (`trackMatchMaxCost` 0.7, `trackLowMatchMaxCost` 0.6) instead of "most pairs";
  lifecycle (tentative, confirmed, lost, idle promotion, exit margin); ids never reused, `reset()` keeps the
  counter; counters for the diagnostics; known limits. Numbers that differ from v0.7: velocity decay on a miss
  τ **300 ms** (was 100 ms); longest time step `trackMaxDtMs` **250 ms** (was 100 ms); birth at score **0.25**
  (was 0.6) and shown from the **third** consecutive hit (was 3 hits within 300 ms). (C) §9: scenarios S1 to S14
  at 20/30/60 fps and 30 fps ± 30 %, asserted ratios (jitter track / raw ≤ 0.60 / 0.45 / 0.25), the absolute
  "still jitter < 1 px" moved to M4. (D) §7.1: tracker config groups; §12: M3 split into M3a (done) and M3b;
  §13: tracker limits, duplicate boxes with IoU below 0.7 not handled.
- **v0.7 (M1a, 2026-11-15):** (A) rectangular model inputs end to end: accepted layout version 2 (`[1, 3, H, W]`, each
  side a multiple of 32, long/short at most `importMaxInputAspect` 4.0; rejection `input_aspect` replaces
  `input_not_square`), imported-model meta version 2 (`inputWidth`, `inputHeight`; v1 still read), `PoseEngine` /
  `PoseDetector` carry width and height, sizes are written WIDTHxHEIGHT (`640` when square) in the HUD, the log and
  `modelInputSize=` (§6.1, §6.7, §8); (B) rectangular models to download from the `models` release
  (`yolo26n-pose_640x288`, `_832x384`, `_960x448`), `export_model.py --sizes S|WxH` with `--out` and `--self-test`
  (§8); (C) timing statistics in `summary.txt`: `timingSamples`, p50/p95 of copy, pre, inference, post, whole
  frame and result age, `thermalStatus`, `thermalHeadroom`; `PoseDetector.lastPreMs` / `lastPostMs`; computed on
  the capture thread at the HUD tick, HUD text unchanged (§6.7); (D) §12: M1 split into M1a (this change) and M1b
  (golden decode test, pure decoder, benchmark screen, bundled landscape model); the one-thread LiteRT item
  was already true since M0b; §13 open items updated; known issue logged: `TensorBuffer.readFloat()` allocates
  (`third_party/ULTRALYTICS.md`).
- **v0.6.4 (M0c, 2026-11-14):** (A) one imported model file (§8): layout of version 1 checked from the real
  tensors, crash guard, fallback to the built-in model, control-screen block **Model**; (B) the 640 model is a
  download under the release tag `models`, built by `export-model.yml`, not part of the APK; (C) marks: head ring
  (radius not below `minHeadRingRadiusDp`) + corner brackets by default, full box and keypoint dots only with
  `debugMarks` (§6.5), because the overlay is in the capture; (D) HUD `in <size>` / `imp`, the warning
  `Imported model not used: built-in model` and the model fields of `summary.txt` (§6.7); (E) §6.5 corrected: the
  Img→View mapping uses the display size of the capture plan, not `onSizeChanged`; M0c and M0d rows in §12,
  new open items in §13.
- **v0.6.3 (M0b review R1):** (A) HUD idle rule in §6.5 (fps below `hudIdleFps` shows 0 and no longer feeds back
  into the capture); (B) every capture-thread entry point is wrapped so no `Error` reaches the `HandlerThread`;
  (C) a changed expected capture size republishes the state without its old detections; (E) a failed start
  (`startForeground` refused, no consent result or projection, failed session) is counted in
  `CaptureService.startFailures` and shown on the control screen as `START_FAILED`.
- **v0.6.2 (M0b T5):** capture glue as implemented: `CaptureService` (foreground service, `START_NOT_STICKY`, Stop
  action in its notification) owns one `CaptureSession` (capture `HandlerThread`, one `VirtualDisplay` per session,
  `ImageReader` recreated on rotation); the tested decisions live in `FramePipeline`, `CapturePlanner` and
  `CaptureTeardown`; teardown is three phases (main → capture → main) and never blocks main; a missing detector
  shows `DETECTOR_UNAVAILABLE` instead of ending the session.
- **v0.6.1 (M0b T4):** detector as implemented: `PoseDetector` gained `lastInferenceMs` (source of the HUD's
  inference time); the model ships as an asset, `ModelStager` stages it into `noBackupFilesDir/models` after a
  sha256 check and LiteRT opens it by path; the adapter refuses a model whose input is not NCHW (new read-only
  `BasePredictor.inputUsesNchw`, logged in `third_party/ULTRALYTICS.md`) or not square; the warm-up is the vendored
  one; `modelInputSize` (default 416) added to §7.1; `core` also holds `json` and `modelpack`.
- **v0.6:** product changed (owner, 2026-11-10) from a CameraX camera app to a **screen-capture overlay**:
  MediaProjection → ImageReader → YOLO26n-pose → plain-View `TYPE_APPLICATION_OVERLAY` dots over other apps,
  portrait + landscape. CameraX dropped from code (catalog entry stays until a deliberate catalog PR);
  `YOLOView` and `ImageUtils.toBitmap` not vendored. Consult C1 adopted: alpha cap ≤ max obscuring opacity (A),
  no free-running invalidate loop (B), `image.timestamp` with a 100 ms guard (C), copy + `close()` before
  inference with `maxImages = 2` (D), exact-width row-copy adapter (E), `createConfigForDefaultDisplay()` and
  fail-closed on non-display-sized captures (F), rotation via reader recreate + `VirtualDisplay.resize()` (G),
  the overlay-in-capture / `FLAG_SECURE` finding, plain `View`, and a handle window for tap-to-lock (M4).
  Img = capture content rect, View = overlay window, Img→View = fit-centre scale + offset. Idle screen = no
  information (§6.3); prediction looks forward (§6.4). Known issue logged: vendored decoder allocates per frame (M1).
- **v0.5:** camera + inference + pose decoding adapted from the Ultralytics Flutter plugin's Android core
  (pinned commit, AGPL-3.0, §2.1) instead of written from scratch; milestones reordered (M0a tooling + model
  export, M0b import → first dots on the phone, M1 benchmark/threading); Fable 5.1 consultant added to the team.
- **v0.4:** AI team (`docs/AI_TEAM.md`): lead = main session on Opus 5.5 (xhigh, max on request) plans and supervises, implementer on
  Sonnet 5.5 builds, reviewer + debugger on Opus 5.5, Explore on Haiku 5.5; plans and handoffs in
  `docs/plans` / `docs/progress`; `scripts/verify.sh` + `scripts/ci-status.sh`; context-hygiene rules.
- **v0.3:** target device + fps targets; LiteRT `CompiledModel` (bundled 2.3.x) instead of Interpreter/GPU
  delegate; GPU-only-then-CPU selection with red banner; warm-up and readback timing; two model sizes;
  CameraX 1.6 frame-rate selection; benchmark screen in M1; thermal policy in M5; Img→View via the official
  CameraX recipe; committed debug keystore; skills vendored in `.claude/skills`.
- **v0.2:** 3 modules; one clock; render at `now − previewLatency`; draw-phase-only overlay; manual model
  export with `litert`/`nms=False`; fixed signing + `latest` release; diagnostics in M0; keypoint-offset head
  estimator; h-scaled Kalman with velocity decay; distance+IoU cost with Mahalanobis gate; Hungarian;
  time-based lifecycle; synthetic truth tests; protected files.
