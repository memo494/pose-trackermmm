# M0d — video-area crop: bigger people for the model (plan)

**Lead:** main session (Fable 5.2 this session, the owner's choice) · **Branch:** `m0d-roi-crop` (to be cut from `main` when it starts)
· **Status:** parked on 2026-11-13, after M0c (model import). Not approved yet.

## Why (phone evidence, 2026-11-13)
First phone run of M0b (Poco X7 Pro, GPU, 21 ms at 416²): a person is detected only once he is close to the
camera. The whole capture (610x1356) is letterboxed to 416x416 (gain 0.307), so a 16:9 video band in portrait is
only about 187x105 model pixels and a person 15 % of the video height is about 16 Mdl px tall. Cropping the
capture to the video rectangle before the model gives 2.2x in portrait and 1.25x in landscape full screen.
The landscape bug of the same run is PR #5 (merged); its HUD line `det N/R max S` is the measuring tool here.

## Parked: measured evidence that changed the order (2026-11-13, v0.1.37)
Landscape works after PR #5. The HUD line `det N/R max S` and an offline sweep on the owner's screenshots (the
bundled 416 model run with a numpy interpreter on 416 windows cut from the rescaled video) agree with the phone
within 0.02 on the clean frames and show that resolution is the lever:

| Person (share of video height) | today | area -> 416 (this plan) | whole screen at 640 | area -> 640 | area -> 832 |
|---|---|---|---|---|---|
| alley walker (27 %) | 0.00 | 0.06 | 0.42 | 0.54 | 0.72 |
| bending person (32 %) | 0.09 | 0.32 | 0.71 | 0.22 to 0.80 | 0.85 |
| far workers (12 to 15 %) | 0.00 | 0.00 | 0.00 | 0.04 | 0.07 |

So in landscape this plan alone moves little; a 640-wide model input moves a lot; people of 12 to 15 % need about
three times today's resolution and stay out of reach. Dependable boxes need roughly 80 to 100 Mdl px of person
height on this content (C1 estimated 45 to 50). Rewrite "Honest limit" and acceptance point 2 with these numbers
before asking for approval. Seen in passing: one person drawn with two boxes (duplicate rows of the end-to-end
head); dedupe belongs to M1 or M3.

## Goal (what the owner sees)
Notification action **Video area** → a rectangle to drag and resize over the video → **Save**. From then on the
model sees only that rectangle. The HUD shows `roi WxH`, thin corner marks show the area, boxes and rings stay
on the people. With no saved area for the current display size and rotation: whole screen as today, HUD `roi off`.

**Honest limit (C1):** this should fix people of about a fifth of the video height, and make 15 % intermittent.
People of about a tenth will mostly still be missed; that needs step 2, a wider rectangular model (M1, with the
benchmark). The acceptance criteria below are written that way.

## Consult log (budget 1/3 used)
**C1 — Is "ROI crop, then more model pixels" the right design and order, and what must step 1 include?**
Answer (Fable 5.2; high confidence on mechanics and manual selection, medium on timings): keep the design and
the order, with these amendments, all adopted:
- **A** Key the saved area by display size + `Display.rotation` and store it in display px (not normalized):
  rotation 90 and 270 put the cutout on opposite sides; normalized values would silently rescale.
- **B** Crop-local pixels get their own suffix **`Roi`** (`Img = Roi + roiOrigin`, integer translation, no scale).
  Only `CaptureFrame`, `UltralyticsPoseEngine` and `YoloResultMapper` may see `Roi`.
- **C** Crop at copy time (`RowCopy` region), add the origin in `YoloResultMapper` only. A stale or unusable
  area costs recall, never a wrong position: the fail-closed fallback is the whole frame + HUD `roi off`.
- **D** Manual selection only. Automatic detection (temporal difference, black bars) fails on still scenes,
  animations and dark themes; later at most a suggestion the owner confirms.
- **E** The editor is a second, temporary, touchable full-screen overlay with Save / Cancel buttons (Back is not
  delivered to a non-focusable window). The area outline is drawn by `PoseOverlayView`, never by a second
  pass-through window (same-UID opacities combine: 0.8 + 0.8 → 0.96 > 0.8 blocks touches below).
- **F** Known vendored behaviour: keypoints are clamped to the crop, boxes are not; sizes must be valid before
  `predict`. `applyGeometry` returns early on an unchanged plan, so the area must be re-resolved before that
  return (a 90↔270 flip keeps the plan).
- **G** Step 2 = ONE landscape-shaped rectangular model (HxW 352x608 or 384x640; about 26–30 ms), not 640² and
  not tiles; `captureScale` stays 0.5. Decided in M1 from measurements.
- **H** Add a minimum ring radius: on a small person the ring (0.12 × box width) is a blob on the head.

## Lead decisions for the owner to approve
- **D1** Adopt C1 A–H as written.
- **D2** The area is chosen by hand, once per screen rotation, from the notification. No automatic detection.
- **D3** Not in M0d: the rectangular model and the benchmark (M1), tracking, a floating button, moving the HUD
  out of the video, Arabic texts in the app.
- **D4** No protected file changes. New code goes to `core/roi` (not `core/**/geometry/**`). No new dependency
  (`SharedPreferences` is the platform's).

## Verification in this environment
No Android SDK in cloud sessions (dl.google.com is refused): **no local `scripts/verify.sh`**. CI runs on pull
requests, so the lead opens a **draft PR** after T1; implementers commit, push the branch (plain push, never
force), wait about 90 s and read `scripts/ci-status.sh`, checking that the run's `head_sha` is the pushed commit.

## Tasks (sequential on this branch; agent = implementer)

### T1 — Docs: ARCHITECTURE v0.7 and the checklist · S · docs only
**Files:** `docs/ARCHITECTURE.md`, `docs/TEST_CHECKLIST.md`.
- §4.2: new row `Roi`; the model row reads "letterboxed model input of the Roi"; the area rules of C1 A–C, F
  (key, display px, outward snap, limits from `TrackerConfig`, decline → whole frame + `roi off`, swap on the
  capture thread at a frame boundary, a change drops the shown detections, inactive while not display-sized).
- §5: the flow line (region copy) and a thread-table row (area store and active area: capture thread).
- §6.1: the detector input is the crop; the origin is added in `YoloResultMapper` only.
- §6.5: correct the stale sentence left by PR #5 (the mapping uses the display size carried by `OverlayState`,
  not `onSizeChanged`); add the outline rule, the editor window (C1 E) and the minimum ring radius.
- §6.7 new diagnostics fields; §12 a row for M0d and "rectangular model candidates" in M1; §13 open items (Tuya
  stream resolution, step 2); §15 change entry.
- `TEST_CHECKLIST.md`: section "M0d — video area" from "Acceptance on the phone" below.

### T2 — Core: area types and resolver · M · new package `core/roi`
**Files:** `core/roi/{DisplayRectPx,RoiKey,RoiRectImg,RoiResolver}.kt`, `TrackerConfig.kt` (`roiMinSideImg` = 96,
`roiMaxAspect` = 4.0, with bounds like the other fields); tests `core/roi/RoiResolverTest`, `TrackerConfigTest`.
- `DisplayRectPx(leftPx, topPx, rightPx, bottomPx)`: Int display pixels of one rotation, right/bottom exclusive.
- `RoiKey(displayWidthPx, displayHeightPx, rotationDegrees)`; `RoiKey.of(...)` is `null` unless the sizes are
  positive and the rotation is 0, 90, 180 or 270.
- `RoiRectImg(leftImg, topImg, widthImg, heightImg)`: Int Img pixels.
- `RoiResolver.resolve(rect, displayWidthPx, displayHeightPx, capture: CaptureSize, config): RoiRectImg?`
  maps display px → Img per axis with exact Long arithmetic, left/top floored and right/bottom ceiled, so the
  crop contains the marked rectangle and stays inside the frame. Declines (`null`, never repairs): a
  non-positive size, an empty or inverted rect, a rect not fully inside the display, a side below
  `roiMinSideImg`, long side / short side above `roiMaxAspect`.

**Tests first (table):** 1220x2712 → 610x1356 with the band (0,600,1220,1286) → (0,300,610,343); odd edges snap
outward; whole display → whole capture; landscape 2712x1220; a capture that is not exactly half; every decline.

### T3 — Core: region copy and area-aware `CaptureFrame` · M
**Files:** `core/capture/{RowCopy,CaptureFrame}.kt`; tests `RowCopyTest`, `CaptureFrameTest`.
- `RowCopy.copyRgbaRegion(source, widthImg, heightImg, rowStride, pixelStride, leftImg, topImg, regionWidth,
  regionHeight, destination): Boolean`: Long arithmetic; fails closed (returns `false`, writes nothing) unless
  the region is inside the frame, the destination is exactly `regionWidth * regionHeight * 4` bytes and the
  source holds the last byte it needs; the position is restored. `copyRgba` keeps its behaviour.
- `CaptureFrame`: new `widthRoi`, `heightRoi`, `roiLeftImg`, `roiTopImg`; `widthImg` / `heightImg` stay the FULL
  capture size; `rgba` is exactly `widthRoi * heightRoi * 4` bytes. New `setRegion(...)` with the same
  fail-closed and overflow rules; `set(...)` stays and means "the whole frame" (origin 0,0).

**Tests first:** row padding, a short last row, a region at every edge and corner, 1x1, out of bounds by one on
each side, wrong destination size, position restored; `setRegion` accepts and declines per rule; `set` unchanged.

### T4 — Detector: run on the crop, translate back to Img · M
**Files:** `detector/{UltralyticsPoseEngine,YoloResultMapper}.kt`, `LiteRtPoseDetector.kt` (KDoc), `FakePoseDetector.kt`
only if needed; tests `YoloResultMapperTest`, `LiteRtPoseDetectorTest`.
- Engine: the bitmap and `estimator.predict(bitmap, widthRoi, heightRoi)` use the Roi size; the bitmap is
  re-created only when that size changes.
- `YoloResultMapper.toDetections(result, roiLeftImg, roiTopImg)` adds the origin to the box edges and to every
  keypoint: the single place where `Roi` becomes `Img`. No clamping.
- `LiteRtPoseDetector.detect` keeps building `DetectionFrame` from the full `widthImg` / `heightImg`.

**Tests first:** origin added to boxes and keypoints; origin (0,0) gives today's values; a box partly outside the
crop is not clamped; `DetectionFrame` keeps the full capture size for a frame with a region.
**Stop and report** if a vendored file would have to change.

### T5 — App: pipeline and session wiring, HUD, diagnostics · M
**Files:** `app/capture/{FramePipeline,CaptureSession}.kt`, `app/overlay/{OverlayState,HudFormatter}.kt`,
`app/diagnostics/{CaptureStats,CaptureStatsHolder,DiagnosticsReport}.kt`; tests `FramePipelineTest`,
`HudFormatterTest`, `DiagnosticsReportTest`.
- `FramePipeline.setRoi(roi: RoiRectImg?)` (capture thread): used from the next frame; a change republishes at
  once without the old detections, like `setExpectedSize`. With an area: `copyRgbaRegion` + `setRegion`; the copy
  buffer is replaced only when the region size changes. The area is ignored (whole frame) while `displaySized`
  is false or when it does not fit the frame (counter `roiDeclined`).
- `OverlayState` carries the active area (Img; 0 = off) in every state.
- `CaptureSession.applyGeometry` resolves the area for the current key on every pass, **before** the
  unchanged-plan early return, and calls `setRoi`. The stored rect comes through a seam
  `RoiSource { fun rectFor(key: RoiKey): DisplayRectPx? }`; in T5 it returns `null` (T7 plugs the store in).
  One log line per change: `roi: 1220x2712 rot0 -> Img l,t,w,h` or `roi off (<reason>)`.
- HUD stats line: `roi WxH` or `roi off`. `summary.txt`: the area and `roiDeclined`.

**Tests first:** the detector receives the crop size and origin while the published frame keeps the full size; a
changed area drops the frame and republishes; ignored while not display-sized; an area that does not fit → whole
frame + counter; the same buffer instance across frames of one area; HUD text on / off within the line capacity.
**Acceptance:** with no stored area nothing changes on screen except `roi off`.

### T6 — Core: editor model and stored form · M · pure Kotlin
**Files:** `core/roi/{RoiEditorModel,RoiCodec}.kt`, `TrackerConfig.kt` (handle touch size in dp); tests for both.
- `RoiCodec`: key `roi_<w>x<h>_r<rot>`, value `l,t,r,b`; `decode` is strict (exactly four plain decimal Ints,
  left < right, top < bottom) and returns `null` otherwise.
- `RoiEditorModel(displayWidthPx, displayHeightPx, minSidePx, handleSlopPx, initial)`: starts from `initial` or
  the largest centred 16:9 rectangle; `onDown` picks the nearest corner within the slop, else "move" inside the
  rectangle, else nothing; `onMove` resizes or moves, always inside the display and never below `minSidePx`;
  `onUp`; `rect`. No allocation per move.

**Tests first:** default rectangle in portrait and landscape; each corner; move; clamps at every edge; minimum
size from every corner; a touch outside does nothing; codec round trip; every malformed text declines.

### T7 — App: area editor, notification action, store · M · Android glue, kept small
**Files:** new `app/roi/{RoiStore,RoiEditorWindow,RoiEditorView}.kt`; `app/capture/{CaptureNotification,CaptureService,
CaptureSession,FramePipeline}.kt`; `res/values/strings.xml`; tests `FramePipelineTest`, `CaptureTeardownTest` if
the phases change.
- `RoiStore`: `SharedPreferences` read and written on the capture thread only; implements `RoiSource`; `save`,
  `clear`; anything `RoiCodec` cannot decode counts as absent.
- Notification action **Video area** → `CaptureService` → the session opens `RoiEditorWindow` (main thread): a
  second full-screen `TYPE_APPLICATION_OVERLAY` with the layout flags and cutout mode of `OverlayWindow`, without
  `FLAG_NOT_TOUCHABLE`, with `FLAG_NOT_FOCUSABLE`.
- `RoiEditorView` (plain `View`, nothing allocated in `onDraw`): dimmed screen, the rectangle with four corner
  handles, buttons **Save**, **Cancel**, **Whole screen** (clears the saved area), kept away from the screen edges.
- While the editor is open the pipeline skips detection (`FramePipeline.setPaused`, frames still drained and
  closed, state without detections). The editor closes on Save, Cancel, any geometry change and teardown.
  Save → store on the capture thread → `applyGeometry()` → `setRoi`.
- Control screen: one line of help text. No new control logic.

**Tests first (JVM):** `setPaused` (no detect calls, state without detections, every image closed once). The
window and view have no JVM test, like `OverlayWindow`: the lead and the reviewer read them.

### T8 — App: area outline and minimum ring radius · S
**Files:** `app/overlay/PoseOverlayView.kt`, `core/overlay/RedrawPolicy.kt`, `TrackerConfig.kt` (`minHeadRingRadiusDp`,
outline stroke); tests `RedrawPolicyTest`, `TrackerConfigTest`.
- Thin corner marks just outside the active area, drawn by `PoseOverlayView` itself; a changed area redraws.
- Ring radius = max(`headRingRadiusRatio` × box width, the minimum), so a small person gets a hollow ring.

## Then (lead)
`android-reviewer` on the whole diff → fixes through the implementer → PR ready, CI green → handoff
`docs/progress/M0d.md`, `docs/REPO_MAP.md`, PR text ending with "How to test on the phone".

## Acceptance on the phone (becomes the checklist section)
1. Camera app open, upright: notification → **Video area** → drag the rectangle onto the video → **Save**. The
   corner text shows `roi` with a size; thin corner marks surround the video.
2. A person about a fifth of the video height or taller gets a box and a ring. Compare with **Whole screen**:
   the `max` number in the third line is clearly higher with the area on.
3. Taps and scrolling still reach the camera app.
4. Turn the phone: `roi off` until an area is saved for that rotation; turning back restores the first area.
5. A paused video still brings the fps to 0. With the area on the fps stays at 25 or more.
6. Stop and start again: the area is remembered. **Whole screen** → `roi off`.

## Open items
- Step 2 (M1): rectangular model 352x608 or 384x640 with a golden test and the benchmark; needs `model/**`, the
  export script and the workflow (protected, to be named explicitly in the M1 plan).
- Still unknown: the Tuya stream resolution (SD or HD) and whether a box-top dot is acceptable for small people.
- The HUD strip can cover a corner of the video; moving it out of the area is not in M0d.

## Progress
| Task | Status | Commit | Notes |
|---|---|---|---|
| T1 | — | | |
| T2 | — | | |
| T3 | — | | |
| T4 | — | | |
| T5 | — | | |
| T6 | — | | |
| T7 | — | | |
| T8 | — | | |
