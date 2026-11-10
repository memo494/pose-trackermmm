# pose-tracker-android — Architecture (v0.5)

Real-time Android app: live camera preview + a stable, low-latency dot on each person's head,
using YOLO26n-pose on-device. Built and modified by AI coding agents (Claude Code, Codex); built only
by GitHub Actions / cloud sessions; tested by installing the APK on a phone. Every decision optimises for:
**(1) a working demo early, (2) safe modification by agents, (3) debuggability without Android Studio.**

**Base:** the camera + inference + pose-decoding layer is adapted from the Android core of the official
Ultralytics Flutter plugin (§2.1). Tracking, smoothing, lock, diagnostics, benchmark and tests are ours.

How the app gets built step by step is in `docs/BUILD_PLAN.md`. Changes per version are in §15.

---

## 1. Scope and target device

### Target device (primary test phone)
**Poco X7 Pro** — MediaTek Dimensity 8400-Ultra, Mali-G720 MC7 GPU, 120 Hz AMOLED, Android 15 / HyperOS 2.
Stock camera records 1080p at 60 fps, so a 60 fps analysis stream is plausible but must be verified
through CameraX (§6.8). The phone is known to run warm under sustained load → thermal behaviour is measured
in M1 and handled in M5.

### Performance targets on the target device
| Metric | Goal | Fallback (acceptable) |
|---|---|---|
| Camera analysis stream | 60 fps | 30 fps |
| Inference (yolo26n-pose, GPU) | ≤ 16 ms/frame → keeps up with 60 fps | ≤ 33 ms → 30 fps |
| Overlay drawing | display refresh (120 Hz) | 60 Hz |
| Sustained 10 min | no drop below 30 fps | thermal policy kicks in (§7.2) |

### v1 features
- Back + front camera, portrait.
- Dot on each person's head, stable track IDs, tap to lock a person (ring).
- Smooth: no jitter when still, no visible lag or overshoot when moving/stopping, survives 0.8 s occlusion.
- Debug HUD: raw keypoints (green), tracked dot (red), IDs, camera fps, inference ms, accelerator (GPU/CPU), model size, version+SHA.
- **Red banner "Running on CPU"** whenever the GPU accelerator failed to load.
- Benchmark screen (§6.8) and debug sheet with tuning sliders (persisted).
- Diagnostics: in-app log viewer, crash capture, **Share diagnostics** zip.
- Offline. Only permission: CAMERA (handles denial + background/foreground).

### Deferred (v1.1+)
Landscape, gyro ego-motion compensation, gallery-video input mode, Play Store release (licence, §8).

---

## 2. Tech stack (versions pinned in `gradle/libs.versions.toml`; never auto-upgraded)

| Concern | Choice |
|---|---|
| Language / build | Kotlin 2.4.x (LiteRT 2.3 ships Kotlin 2.4 metadata; matches upstream), Gradle Kotlin DSL, version catalog, wrapper committed + validated |
| UI | Jetpack Compose; camera preview via `PreviewView` inside `AndroidView` |
| Overlay | Compose `Canvas`; per-frame reads in **draw phase only** (§6.5) |
| Camera | CameraX **1.6.x** stable: `Preview` + `ImageAnalysis` (RGBA_8888, KEEP_ONLY_LATEST, `ResolutionSelector`) bound together with one shared `ViewPort`; frame rate chosen with the `SessionConfig` frame-rate API (§6.8) |
| Inference | **LiteRT 2.3.x `CompiledModel` API, bundled runtime**: `com.google.ai.edge.litert:litert` + `litert-gpu` (same version). `Accelerator.GPU`, CPU fallback. Not the old `Interpreter` API (CPU-only in 2.x) and not the Play-services runtime (deliberate: same runtime version on every device → reproducible benchmarks) |
| Model | `yolo26n-pose`, exported `format="litert", nms=False`, FP32 (GPU computes in FP16), **two sizes: 320 and 416** |
| DI | Manual `AppGraph` |
| Settings | DataStore Preferences, single `TrackerConfig` |
| Tests | JUnit5 + Truth in `core` (JVM); JUnit4 in Android modules |
| Checks | Spotless (ktlint) + Android Lint. No detekt in v1 |

### 2.1 Upstream base (Ultralytics)
- **Source:** `github.com/ultralytics/yolo-flutter-app`, Android native part (`android/src/main/kotlin/com/ultralytics/yolo/`),
  **pinned at commit `c97bf347ddbd94b48f18c7264c4856294aee4d01` (2026-11-08)**. Licence **AGPL-3.0**.
- **Why:** official, actively maintained, already on CameraX 1.6.0 + LiteRT 2.3.0 (`litert`, `litert-gpu`,
  `litert-metadata`) with `CompiledModel` GPU→CPU fallback, and its `PoseEstimator` decodes both the YOLO26
  end-to-end layout (300 rows) and the classic layout with NMS. Only 3 of its 24 Kotlin files depend on Flutter.
- **Keep (adapted):** `LiteRtModel`, `Predictor`, `PoseEstimator`, `ImageUtils`, `GeometryUtils`, `Utils`,
  `YOLOResult`, `YOLOTask`, and the CameraX part of `YOLOView` (binding, analyzer, lens switching).
- **Drop:** Flutter glue (`YOLOPlugin`, `YOLOPlatformView`, `YOLOPlatformViewFactory`, `YOLOStreamConfig`,
  `YOLOInstanceManager`), other tasks (classify, detect-only, segment, semantic, depth, OBB), the QNN/NPU path
  (Qualcomm-only; the target phone is MediaTek), photo capture, zoom, and the upstream `OverlayView` (replaced by ours).
- **Layout:** vendored files stay in package `com.ultralytics.yolo` inside `detector/` (and the camera view in `app/`),
  with the upstream file header kept, so future syncs are a readable diff. Every change to vendored code is logged
  in `third_party/ULTRALYTICS.md` (file · what · why). Our own code lives in `com.memo494.posetracker`.
- **Known issue to fix (M1):** upstream creates the model on a throwaway executor and runs it on the camera
  executor; the LiteRT rule is create/run/close on one thread (§5).
- **What upstream does not have (our work):** tracking/IDs, smoothing and render-time prediction, lock,
  diagnostics, benchmark + 60 fps selection, thermal policy, tests (upstream has no Android tests).
- **Sync policy:** no automatic updates. The lead may propose a sync to a newer upstream commit as its own PR.

---

## 3. Modules (3 only)

```
pose-tracker-android/
├─ core/        PURE KOTLIN JVM (kotlin("jvm") plugin → android.* cannot compile here)
│               model types, geometry (Matrix3), tracking, TrackerConfig, replay I/O
├─ detector/    Android library: PoseDetector interface + adapter over vendored Ultralytics code
│               (com/ultralytics/yolo: LiteRtModel, Predictor, PoseEstimator, utils), Fake impl
├─ app/         Camera (trimmed Ultralytics camera view), Compose screens, overlay, benchmark, debug sheet,
│               DataStore, diagnostics, AppGraph
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

### 4.1 One clock: `System.nanoTime()`
- `FrameSource` stamps `arrivalNs = System.nanoTime()` as the first line of the analyzer.
  (`imageInfo.timestamp` may be uptime- or elapsedRealtime-based depending on device — not used for logic.)
- The renderer calls `System.nanoTime()` inside the draw callback (the `withFrameNanos` value is only a tick).
- Every `*Ns` field documents "System.nanoTime() timebase".

### 4.2 Named spaces; suffixes mandatory in code
| Space | Suffix | Definition |
|---|---|---|
| model | `Mdl` | letterboxed model input (320×320 or 416×416) |
| image | `Img` | analysis buffer **cropped to `imageProxy.cropRect` and rotated upright by `rotationDegrees`**, un-mirrored. Tracking lives here |
| view | `View` | overlay pixels (same size and position as `PreviewView`) |

- Detector: crop → rotate → letterbox → infer → un-letterbox back to **Img**.
- **Img → View**: because Preview and ImageAnalysis share one `ViewPort`, the crop rect has the view's aspect
  ratio, so Img → View is a uniform scale (+ horizontal mirror for the front camera). It is computed from the
  official CameraX recipe ("Transform output": `cropRect` + `rotationDegrees` → `PreviewView`, `setPolyToPoly`)
  composed with the detector's crop/rotate, and passed to `core` as a pure-Kotlin `Matrix3`.
  A unit test asserts our matrix equals the official recipe for all rotations.
- Velocities are transformed with the linear part only (no translation).

---

## 5. Data flow & threads

```
[analysis executor — ONE dedicated thread, owns CompiledModel + tracker]
  FrameSource(arrivalNs) → PoseDetector.detect → DetectionFrame(Img)
     → Tracker.update → publish immutable TrackSnapshot (StateFlow)
[main thread — draw phase each display frame]
  read snapshot → Predictor(t = now − previewLatency) → OneEuro → Img→View → draw
```

| Object | Owner thread | Rule |
|---|---|---|
| LiteRT `Environment` | process | created once, shared |
| `CompiledModel` + its `TensorBuffer`s | analysis | create, warm up, run, close on this thread only; never on main; buffers allocated **once** and reused; buffers closed before the model |
| Tracker | analysis | sole writer |
| TrackSnapshot | any | immutable |
| Predictor / OneEuro state | main | plain `remember`ed class, never Compose `State` |
| TrackerConfig | DataStore → StateFlow | applied at next frame boundary |

`image.close()` always in `finally`.

---

## 6. Subsystems

### 6.1 Detector (`detector/`)
```kotlin
interface PoseDetector : Closeable {
    val inputSize: Int; val accelerator: Accelerator
    fun detect(frame: CameraFrame): DetectionFrame
}
```
- Implemented as an adapter over the vendored Ultralytics `LiteRtModel` + `PoseEstimator` (§2.1). Where upstream
  already does the right thing (output-layout detection, end-to-end vs NMS decoding, GPU→CPU fallback) it is
  reused; the rules below are what we add or enforce on top.
- **Accelerator selection:** create with `Options(Accelerator.GPU)` (GPU only). On `LiteRtException`,
  log it and recreate with `Options(Accelerator.CPU)`; HUD shows `CPU` and the red banner appears.
  (A mixed `Options(GPU, CPU)` can silently run on CPU, which would hide problems.)
- One warm-up inference right after creation (GPU shader compilation must not stall the first frames).
- Input tensor layout **read from the model** (expected NCHW `(1,3,S,S)`, RGB, 0–1). Never hard-coded.
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
- On a missed frame: decay velocity by `exp(−dt / 100 ms)`.
- **Association** (ByteTrack two-stage: high ≥ 0.5, then low 0.15–0.5 vs remaining tracks):
  cost = 0.5·(1 − IoU) + 0.5·min(1, d_head / (0.5·h)); Mahalanobis gate < 9.21;
  **Hungarian** behind an `Assigner` interface (deterministic tie-break).
- Lifecycle (time-based): new track needs score ≥ 0.6; confirmed after 3 hits within 300 ms; tentative deleted
  on first miss; confirmed deleted after `maxLostMs = 800`; **locked track 2000 ms**.
- Works identically at 30 and 60 fps (everything uses real dt; tests run at both rates).

### 6.4 Prediction & smoothing (render side)
- Render target time `t = now − previewLatencyMs` (default 50, tunable): the dot must match the *displayed* frame.
- Extrapolate `pos + v·clamp(t − lastUpdate, 0, 80 ms)`, scaled by velocity confidence (from covariance).
- OneEuro in Img space normalised by `h`: minCutoff 1.5 Hz, beta 0.5, dCutoff 1 Hz; reset on birth/re-lock.
- Lost tracks drawn faded; removed tracks fade out over 150 ms.

### 6.5 Overlay rules (Compose)
Frame tick read only inside `drawWithContent` / `drawBehind`. No per-frame values in composition-read `State`
(would recompose 60–120×/s).

### 6.6 Target lock
Tap → nearest confirmed track. Prefer keeping the locked track alive (2 s) over re-locking.
Re-lock candidates: tracks born after the loss, within `R = min(0.5h + |v|·t, 1.5h)` of last prediction,
height ratio 0.7–1.4; else unlock.

### 6.7 Diagnostics (`app/diagnostics`) — built in M0
- Ring-buffer logger (also to logcat) + in-app log screen.
- Uncaught-exception handler saves the stack trace; offered on next launch.
- **Share diagnostics** (share sheet) → zip: logs, device/OS, accelerator, camera fps ranges, FPS + thermal
  stats, `model_info.json`, current `TrackerConfig`, last 10 s replay JSON, latest benchmark CSV, screenshot.
  This zip is what the owner pastes to an agent.

### 6.8 Camera frame rate & benchmark screen — built in M1
- **Frame-rate selection:** build the session config for Preview + ImageAnalysis, query
  `CameraInfo.getSupportedFrameRateRanges(sessionConfig)`, choose the first available of
  `[60,60] → [30,60] → [30,30]`, apply it with the `SessionConfig` frame-rate API (CameraX ≥ 1.5), and
  **measure** the real analyzer fps. The exact API shape is checked against the official CameraX skill /
  docs in M1; fallback if unavailable: `setTargetFrameRate` (hint only).
- **Benchmark screen** shows and exports (CSV, included in diagnostics):
  supported fps ranges; measured camera fps; inference ms (p50/p95, with readback) for 320 and 416, GPU and CPU;
  end-to-end fps; thermal status (`PowerManager.currentThermalStatus`) and thermal headroom; a 10-minute soak
  mode logging all of the above every second.
- Results decide the defaults (fps, model size) — recorded in `TrackerConfig` defaults and in this doc.

---

## 7. Config & policies

### 7.1 Config
`TrackerConfig` (in `core`): every tunable number with unit, default, min, max, KDoc. No magic numbers
elsewhere. Debug sheet exposes the important ones as sliders + "reset to defaults", plus: model size
(320/416), target fps (60/30), accelerator override (auto/CPU).

### 7.2 Thermal policy (M5)
If thermal status ≥ `MODERATE` or headroom forecast > 0.9: step down 60 → 30 fps; then 416 → 320;
step back up after 60 s below the threshold. Every step is logged and shown in the HUD.

---

## 8. Model pipeline & licence
- `export-model.yml` — **manual** (`workflow_dispatch`) GitHub Actions job on ubuntu x86_64. Installs
  `tools/requirements.lock` (pinned ultralytics ≥ 8.4.83 + deps), runs
  `export(format="litert", nms=False, imgsz=S)` for S = 320 and 416, runs Python `predict()` on the fixture
  image to produce golden outputs, writes `model_info.json` (I/O shapes, versions, sha256), opens a PR.
- Normal builds never run Python. Build fails with a clear message if a model or its sha256 mismatches.
- No INT8 / w8a16 (loses the end-to-end head; w8a16 falls off the GPU).
- **Licence: AGPL-3.0 for the whole app** (vendored Ultralytics code + model weights). Fine for private personal use. Any distribution (Play Store, sharing the APK
  publicly) requires open-sourcing app + weights, or an Ultralytics Enterprise licence.

---

## 9. Testing
| What | How |
|---|---|
| Geometry | table tests: 4 rotations × mirror × aspect ratios × sensorOrientation 90/270; equality with official CameraX recipe |
| Tracking (synthetic, known truth) | crossing, stop/start, 0.5 s dropout, keypoint dropout, variable dt; each at 30 and 60 fps |
| Tracking metrics (asserted) | ID switches = 0 on crossing; still jitter RMS(2nd diff) < 1 px; stop overshoot < 0.1h; lag < 1 frame |
| Real replays | from Share diagnostics, added as regression tests |
| Decoding | golden test vs Python output, for both model sizes |
| On phone | `docs/TEST_CHECKLIST.md` per milestone with pass criteria (walk across, two people cross, occlusion, sudden stop, front camera, rotate, background/resume, permission denied, 10-min soak) |

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
   Fable 5.1 for hard questions; Explore on Haiku 5.5).
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
| M0b | Import the Ultralytics core (§2.1) → camera + raw keypoints in green with our model; diagnostics + log screen; CPU banner | **green dots on people** |
| M1 | One-thread LiteRT fix, golden decode test (both sizes), **frame-rate selection + benchmark screen** | real numbers for fps / ms / heat |
| M2 | Img↔View mapping extracted into `core` + tests (checked against the official CameraX recipe) | green dots exactly on heads, back + front camera |
| M3 | Tracker + Kalman + synthetic tests (30 & 60 fps); IDs | red dots with IDs, stable when crossing |
| M4 | Render prediction + OneEuro + lock + debug sheet | smooth dot, tap-to-lock, tuning sliders |
| M5 | Thermal policy, perf pass, lifecycle/permission polish, replay mode | stable over 30 min |

One session per milestone; M4 may need a second session for tuning on the phone.

## 13. Open items
- Exact output column order / scale — settled by golden test in M1.
- Whether CameraX grants 60 fps for Preview + ImageAnalysis on the Poco X7 Pro — measured in M1.
- `previewLatencyMs` default — tuned on the phone in M4.
- Gyro ego-motion compensation — candidate for v1.1 if camera pans are the main failure.

## 14. Build environment (summary; details in BUILD_PLAN.md)
Code is written in Claude Code cloud sessions (claude.ai/code) on this repo by the AI team in
`docs/AI_TEAM.md`: the main session is the lead (Opus 5.5, effort `xhigh` by default, `max` on request),
which plans and supervises while Sonnet 5.5 implementer subagents write the code and a Fable 5.1 consultant
answers hard questions. Each change is verified in the session with `scripts/verify.sh`
and always by GitHub Actions, which is the source of truth. The owner's laptop is not used for builds.

## 15. Changes
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
