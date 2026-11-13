# pose-tracker-android — Architecture (v0.6)

Real-time Android app: a screen-capture overlay that draws a stable, low-latency dot on each person's head
over any other app (video, stream, game), using YOLO26n-pose on-device. Built and modified by AI coding agents (Claude Code, Codex); built only
by GitHub Actions / cloud sessions; tested by installing the APK on a phone. Every decision optimises for:
**(1) a working demo early, (2) safe modification by agents, (3) debuggability without Android Studio.**

**Base:** the inference + pose-decoding layer is adapted from the Android core of the official
Ultralytics Flutter plugin (§2.1). Screen capture, overlay, tracking, smoothing, lock, diagnostics, benchmark
and tests are ours.

How the app gets built step by step is in `docs/BUILD_PLAN.md`. Changes per version are in §15.

---

## 1. Scope and target device

**Product (owner decision 2026-11-10):** the app captures the screen with `MediaProjection`, runs YOLO26n-pose on
the captured frames and draws the result in a `TYPE_APPLICATION_OVERLAY` window on top of whatever app is in the
foreground. Front/back camera features are gone. Portrait and landscape are both in scope (games and videos run
landscape).

### Target device (primary test phone)
**Poco X7 Pro** — MediaTek Dimensity 8400-Ultra, Mali-G720 MC7 GPU, 120 Hz AMOLED, Android 15 / HyperOS 2.
The screen is 1220×2712. Capture runs at ½ scale (610×1356, `TrackerConfig`). How many frames per second the
virtual display really delivers is measured in M1 (§6.8). The phone is known to run warm under sustained load →
thermal behaviour is measured in M1 and handled in M5.

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
    breaks AGENTS rule 8. Our own code complies from M0b; the decoder is made allocation-free in M1 (plan
    decision D2) and the issue is logged in `third_party/ULTRALYTICS.md` until then.
- **What upstream does not have (our work):** screen capture + overlay, tracking/IDs, smoothing and prediction,
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
| model | `Mdl` | letterboxed model input (320×320 or 416×416) |
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
     → Tracker.update → publish immutable snapshot (AtomicReference / StateFlow)
[main thread — overlay View]
  on a new snapshot → invalidate → onDraw: Img→View (FitCenter) → draw (OneEuro/Predictor from M4)
```

| Object | Owner thread | Rule |
|---|---|---|
| LiteRT `Environment` | process | created once, shared |
| `CompiledModel` + its `TensorBuffer`s | capture (= analysis) | create, warm up, run, close on this thread only; never on main; buffers allocated **once** and reused; buffers closed before the model |
| `MediaProjection` callbacks, `ImageReader` listener | capture | `registerCallback(…, captureHandler)` |
| Tracker | capture | sole writer |
| Snapshot | any | immutable |
| Overlay `View`, `WindowManager` calls, Predictor / OneEuro state | main | plain classes, preallocated; no work except drawing |
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
  rate (`hudRateHz`) and the shown capture fps decays to 0.
- A detector that cannot be created does not stop the session: frames are still drained and closed and the HUD
  shows `DETECTOR_UNAVAILABLE`.

---

## 6. Subsystems

### 6.1 Detector (`detector/`)
```kotlin
interface PoseDetector : Closeable {
    val inputSize: Int; val accelerator: Accelerator   // model input side (Mdl px, read from the model); what runs
    val lastInferenceMs: Float                         // model run + output readback of the last detect; 0 before
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
- **Per-frame allocations:** our adapter allocates nothing per frame; the vendored decoder still does until M1
  (§2.1 known issues).
- **Accelerator selection:** create with `Options(Accelerator.GPU)` (GPU only). On `LiteRtException`,
  log it and recreate with `Options(Accelerator.CPU)`; HUD shows `CPU` and the red banner appears.
  (A mixed `Options(GPU, CPU)` can silently run on CPU, which would hide problems.)
- One warm-up inference right after creation (GPU shader compilation must not stall the first frames). It is the
  vendored one: `LiteRtModel` runs it inside its GPU→CPU ladder, so the adapter adds no second warm-up.
- **Model file:** packaged as an app asset (the `stage<Variant>ModelAssets` build task, §8), staged at start by
  `ModelStager` into `noBackupFilesDir/models` (versioned name, temp file + rename) with a sha256 check, and only
  then opened by LiteRT **by path**; a file that does not match its hash is never opened.
- Input tensor layout **read from the model** (expected NCHW `(1,3,S,S)`, RGB, 0–1). Never hard-coded. If the
  vendored `LiteRtModel` could not read the layout by name it silently assumes NHWC; the adapter checks
  `BasePredictor.inputUsesNchw` and refuses to run (fail closed). It also requires a square input equal to
  `TrackerConfig.modelInputSize`.
- Output expected `(1,300,57)`: per row box xyxy, score, cls, 17×(x,y,v). Exact column order and
  pixel-vs-normalised scale are **verified by a golden test** against Python `model.predict()` on the same
  image (fixture committed with the model). Rows with score < 0.15 dropped.
- Timing measured as `run` **plus output readback** (`run` can return before the GPU finishes).
- `FakePoseDetector` replays JSON → tests and a "replay mode" in the debug sheet.

### 6.2 Head point (`core/tracking/HeadPointEstimator`)
- Keypoints 0–4 (nose, eyes, ears). Visibility hysteresis: enter ≥ 0.5, leave < 0.3.
- Per track, EMA (α 0.1) of each keypoint's offset from the head centre; missing keypoints filled from these
  offsets → no jumps when an ear disappears.
- Fewer than 2 ever visible: fallback `(boxCx, top + 0.10·h)`, flagged estimated.
- Measurement noise `R ∝ (0.03·h)² / meanKeypointConf`. Configurable vertical offset (face centre ↔ top of head).

### 6.3 Tracker (`core/tracking`)
- **Kalman CV** per track, state `[hx, hy, vx, vy, h, vh]`, real dt from timestamps.
  Noise scaled by box height `h`: σ_accel ≈ 8·h px/s², σ_meas ≈ 0.03·h.
- Adaptive: if NIS > 9.21 (χ² 99 %, 2 dof) inflate Q ×10 for **one step only**.
- On a frame where the person was not detected (a miss): decay velocity by `exp(−dt / 100 ms)`.
- **Idle screen = no information, not a miss.** The virtual display only produces a frame when the screen content
  changes, so a paused video or a static page yields no frames. A time gap without frames never counts as a miss,
  never advances lifecycle counters (`maxLostMs`, lock timeout) and never decays velocity; tracks are held as they
  are and the HUD shows capture fps ≈ 0. Only an actual frame that lacks a person is a miss. The first frame after
  a gap does its update with `dt` clamped to a `TrackerConfig` maximum (so the Kalman step stays sane).
- **Association** (ByteTrack two-stage: high ≥ 0.5, then low 0.15–0.5 vs remaining tracks):
  cost = 0.5·(1 − IoU) + 0.5·min(1, d_head / (0.5·h)); Mahalanobis gate < 9.21;
  **Hungarian** behind an `Assigner` interface (deterministic tie-break).
- Lifecycle (time-based, measured over frames that exist — see above): new track needs score ≥ 0.6; confirmed
  after 3 hits within 300 ms; tentative deleted on first miss; confirmed deleted after `maxLostMs = 800`;
  **locked track 2000 ms**.
- Works identically at 30 and 60 fps and with irregular frame spacing (everything uses real dt from frame
  timestamps; tests run at both rates and with idle gaps).

### 6.4 Prediction & smoothing (render side; from M4)
- **Prediction looks forward.** The detection describes the screen at `image.timestamp`; the content is already
  on screen and has moved on by the time the result is drawn (capture → copy → inference → handoff → next
  vsync). Render target time `t = now + displayLeadMs` (default tuned in M4, `TrackerConfig`); there is no
  camera-preview latency to subtract any more.
- Extrapolate `pos + v·clamp(t − lastUpdate, 0, 80 ms)`, scaled by velocity confidence (from covariance).
  After an idle gap (§6.3) nothing is extrapolated.
- OneEuro in Img space normalised by `h`: minCutoff 1.5 Hz, beta 0.5, dCutoff 1 Hz; reset on birth/re-lock.
- Lost tracks drawn faded; removed tracks fade out over 150 ms.
- Any redraw this needs between frames obeys the invalidate rules in §6.5 (it may only run while a dot actually
  moves by more than the deadband).

### 6.5 Overlay rules (plain `View`)
The overlay is a plain `View` (not `ComposeView`) in a `TYPE_APPLICATION_OVERLAY` window.
- **Window:** `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`;
  display cutout mode `ALWAYS` (API 30+) / `SHORT_EDGES` (28–29); `setFitInsetsTypes(0)`. Taps reach the app below.
- **Alpha cap (C1-A):** `LayoutParams.alpha` ≤ `InputManager.getMaximumObscuringOpacityForTouch()` (0.8, API 31+;
  on older versions the `TrackerConfig` fallback cap, also 0.8). Above it Android 12+ **blocks touches to the app
  below**. The on-phone check is an empty logcat for "Untrusted touch due to occlusion".
- **Invalidate rules (C1-B):** the overlay is part of the capture, so an unconditional redraw loop
  (`postInvalidateOnAnimation`) would create redraw → new frame → inference → redraw. Therefore: **no
  free-running loop**; call `invalidate()` only when a new snapshot differs from the drawn one by more than a
  sub-pixel deadband (a pure function, unit tested); HUD text updates at most 2 Hz (`TrackerConfig`).
  A paused video must show capture fps ≈ 0.
  **HUD idle rule:** the HUD's own refresh causes up to `hudRateHz + 1` frames per second, so a capture rate below
  `TrackerConfig.hudIdleFps` (`hudRateHz` + 2) prints as 0 fps and its fps and inference-time changes do not
  refresh the HUD text; only a leave from or entry into idle, or any other field, does.
- **Draw phase:** `onDraw` allocates nothing: preallocated `Paint`s, a reused `CharArray` per HUD line
  (`drawText(char[], …)`), no lambdas or temporaries. Origin and size come from `getLocationOnScreen` /
  `onSizeChanged` → `FitCenter` (§4.2).
- **The overlay is in the capture** — and `FLAG_SECURE` on it would black out the **whole** capture, so it is
  not an option. Mitigation: hollow rings instead of filled dots, thin box strokes, a corner HUD strip. The
  remaining effect on detection is **measured** (dot A/B in `docs/TEST_CHECKLIST.md`: head shift < 0.03·h,
  keypoint 0–4 confidence drop < 0.05) and tracked in §13.

### 6.6 Target lock (M4)
- The overlay window stays not touchable. Locking uses a **small touchable handle window** (separate
  `TYPE_APPLICATION_OVERLAY`, tiny, at a screen edge). Tapping the handle **arms** the overlay for exactly one
  tap: the overlay becomes touchable (and shows the armed state), the next tap goes to the nearest confirmed
  track (tap View → Img via inverse `FitCenter`), then the overlay is not touchable again. Arming also times
  out (`TrackerConfig`). While armed, the alpha cap (§6.5) still applies. No confirmed track near the tap →
  nothing is locked (fail closed).
- Prefer keeping the locked track alive (2 s) over re-locking.
  Re-lock candidates: tracks born after the loss, within `R = min(0.5h + |v|·t, 1.5h)` of last prediction,
  height ratio 0.7–1.4; else unlock. The 2 s lock timeout does not run during an idle gap (§6.3).

### 6.7 Diagnostics (`app/diagnostics`) — built in M0
- Ring-buffer logger (also to logcat) + in-app log screen.
- Uncaught-exception handler saves the stack trace; offered on next launch.
- **Share diagnostics** (share sheet) → zip: logs, device/OS, accelerator, **capture info** (display size,
  capture size and scale, size-change events, capture fps, dropped size-mismatched frames, timestamp-source
  statistics, `now − image.timestamp` range), FPS + thermal stats, `model_info.json`, current `TrackerConfig`,
  last 10 s replay JSON, latest benchmark CSV. This zip is what the owner pastes to an agent.

### 6.8 Capture frame rate & benchmark screen — built in M1
- **Capture fps** is *measured*, not selected: frames delivered per second while the screen content changes
  (a static screen delivers none, §6.3). The HUD shows it (smoothed, ≤ 2 Hz); the capture scale is a
  `TrackerConfig` value (default ½). The Poco X7 Pro's real rate (60 vs 120 Hz source, what the virtual display
  delivers) is a question the benchmark answers in M1.
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
overlay alpha cap fallback 0.8, dot/box stroke widths, lock arm timeout, max `dt` after an idle gap.

### 7.2 Thermal policy (M5)
If thermal status ≥ `MODERATE` or headroom forecast > 0.9: step down the analysis fps cap 60 → 30; then 416 → 320;
step back up after 60 s below the threshold. Every step is logged and shown in the HUD.

---

## 8. Model pipeline & licence
- `export-model.yml` — **manual** (`workflow_dispatch`) GitHub Actions job on ubuntu x86_64. Installs
  `tools/requirements.lock` (pinned ultralytics ≥ 8.4.83 + deps), runs
  `export(format="litert", nms=False, imgsz=S)` for S = 320 and 416, runs Python `predict()` on the fixture
  image to produce golden outputs, writes `model_info.json` (I/O shapes, versions, sha256), opens a PR.
- Normal builds never run Python. Build fails with a clear message if a model or its sha256 mismatches: the check
  is the `stage<Variant>ModelAssets` Gradle task of `app`, which verifies every file listed in `model_info.json`
  and copies only the `.tflite` files and `model_info.json` into the APK assets.
- No INT8 / w8a16 (loses the end-to-end head; w8a16 falls off the GPU).
- **Licence: AGPL-3.0 for the whole app** (vendored Ultralytics code + model weights). Fine for private personal use. Any distribution (Play Store, sharing the APK
  publicly) requires open-sourcing app + weights, or an Ultralytics Enterprise licence.

---

## 9. Testing
| What | How |
|---|---|
| Geometry | table tests in `core`: `Letterbox` Img↔Mdl round-trip; `FitCenter` corners → view corners and back, portrait + landscape, with offset (cutout) and different window/capture sizes; `CaptureSize` (1220×2712 → 610×1356, exact aspect, even dimensions) |
| Capture math | `RowCopy` (padded row stride, short last row, tight output); `FrameTimestamp` guard (in range, negative, > 100 ms → fallback); teardown idempotent and size-mismatch frames dropped (fake session); per-frame `catch` handles `OutOfMemoryError` |
| Overlay | `HudFormatter` writes the expected text into a reused `CharArray`; the invalidate decision (new snapshot / deadband) as a pure function |
| Tracking (synthetic, known truth) | crossing, stop/start, 0.5 s dropout, keypoint dropout, variable dt, idle gap (no frames ≠ miss); each at 30 and 60 fps |
| Tracking metrics (asserted) | ID switches = 0 on crossing; still jitter RMS(2nd diff) < 1 px; stop overshoot < 0.1h; lag < 1 frame |
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
| M1 | One-thread LiteRT fix, allocation-free vendored decoder (D2), golden decode test (both sizes), **capture fps measurement + benchmark screen** | real numbers for fps / ms / heat |
| M2 | Img→View hardening: `FitCenter` extended and checked on the phone across rotation, cutout and resize events (no camera) | green dots exactly on heads in portrait and landscape |
| M3 | Tracker + Kalman + synthetic tests (30 & 60 fps, idle gaps); IDs | red dots with IDs, stable when crossing |
| M4 | Forward prediction + OneEuro + handle-window lock + debug sheet | smooth dot, tap-to-lock, tuning sliders |
| M5 | Thermal policy, perf pass, lifecycle/permission polish, replay mode | stable over 30 min |

One session per milestone; M4 may need a second session for tuning on the phone.

## 13. Open items
- Exact output column order / scale — settled by golden test in M1.
- Real capture fps of the virtual display on the Poco X7 Pro (and `image.timestamp` reliability) — measured in M0b/M1.
- **HyperOS restrictions:** battery/auto-start limits killing the foreground service, extra "display pop-up"
  toggles, overlay or notification quirks — checked on the phone in M0b; findings go in `docs/TEST_CHECKLIST.md`.
- **Dot A/B (overlay in the capture):** does our own overlay change detection (head shift < 0.03·h, keypoint 0–4
  confidence drop < 0.05)? Measured in M0b; if it fails, thinner/hollower marks or capture-side masking in a
  later milestone.
- **DRM / secure apps capture black** (`FLAG_SECURE`): expected and unfixable by us; no dots there. Only
  documented, plus the HUD may say "no frames".
- `displayLeadMs` default — tuned on the phone in M4.

## 14. Build environment (summary; details in BUILD_PLAN.md)
Code is written in Claude Code cloud sessions (claude.ai/code) on this repo by the AI team in
`docs/AI_TEAM.md`: the main session is the lead (Opus 5.5, effort `xhigh` by default, `max` on request),
which plans and supervises while Sonnet 5.5 implementer subagents write the code and a Fable 5.2 consultant
answers hard questions. Each change is verified in the session with `scripts/verify.sh`
and always by GitHub Actions, which is the source of truth. The owner's laptop is not used for builds.

## 15. Changes
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
