# Vendored Ultralytics code

- **Source:** https://github.com/ultralytics/yolo-flutter-app, Android native part
  (`android/src/main/kotlin/com/ultralytics/yolo/`).
- **Pinned commit:** `c97bf347ddbd94b48f18c7264c4856294aee4d01` (2026-11-08). No automatic updates; a sync to a newer
  commit is its own PR (ARCHITECTURE §2.1).
- **Licence:** AGPL-3.0. The upstream licence text is the root `LICENSE` of this repository; the upstream file headers
  (`// Ultralytics 🚀 AGPL-3.0 License - https://ultralytics.com/license`) are kept on every vendored file.
- **Where:** `detector/src/main/kotlin/com/ultralytics/yolo/`, package `com.ultralytics.yolo` (unchanged).
- **Formatting:** the directory is excluded from Spotless/ktlint (root `build.gradle.kts`) so the files stay a readable
  diff against upstream. Do not reformat them.
- **Dependencies it needs:** `com.google.ai.edge.litert:litert` and `litert-gpu` (same version, from the catalog).
  Nothing from `org.tensorflow:*`, CameraX, Flutter, ONNX Runtime or SnakeYAML.

## Kept files

`LiteRtModel`, `Predictor` (contains `InferenceModel`, `Predictor`, `BasePredictor`), `PoseEstimator`,
`GeometryUtils`, `Utils` (contains `YOLOUtils`, palette and `drawPose` helpers), `YOLOResult`, `YOLOTask`,
`ImageUtils`.

## Dropped files (not copied)

`YOLOView`, `YOLOPlugin`, `YOLOPlatformView`, `YOLOPlatformViewFactory`, `YOLOStreamConfig`, `YOLOInstanceManager`
(Flutter glue and the CameraX view), `YOLO` (high-level API), `YOLOFileUtils` (model metadata via SnakeYAML/litert-metadata),
`OrtQnnModel` (Qualcomm NPU path; the target phone is MediaTek), `ObjectDetector`, `Segmenter`, `SemanticSegmenter`,
`DepthEstimator`, `Classifier`, `ObbDetector`, `OBB` (other tasks).

## Change log (file · what · why)

| File | What | Why |
| --- | --- | --- |
| `YOLOTask.kt` | Enum reduced to `POSE`. | Other tasks are not vendored; `Predictor.create` must stay exhaustive. |
| `YOLOResult.kt` | Removed fields `masks`, `semanticMask`, `depthMap`, `probs`, `obb` and the classes `Masks`, `SemanticMask`, `DepthMap`, `Probs`, `OBBResult`. | They belong to dropped tasks; `OBBResult` needs the dropped `OBB`. Pose decoding never used them. |
| `GeometryUtils.kt` | Removed `obbIoU` and `nonMaxSuppressionOBB`. | They need the dropped `OBB` class. The rest (polygon clipping, `nonMaxSuppression`, `computeIoU`) is unchanged. |
| `Predictor.kt` | `InferenceModel.create` now returns `LiteRtModel` directly (no `.onnx`/`OrtQnnModel` branch). KDoc no longer mentions QNN/NPU. | `OrtQnnModel` is not vendored (QNN is Qualcomm-only). |
| `Predictor.kt` | `Predictor.create` keeps only the `POSE` branch; parameter `classifierOptions` removed. | Other predictors are not vendored. |
| `Predictor.kt` | Removed `BasePredictor.loadModelLabels`. | It calls the dropped `YOLOFileUtils.loadModelMetadata`. Labels are passed to the constructor; `colors` stays empty. |
| `Predictor.kt` | Added the read-only property `BasePredictor.inputUsesNchw` (returns `rtModel.inputUsesNchw`). | `rtModel` is `protected`; the adapter must refuse a model whose input layout `LiteRtModel` could not read by name (it then assumes NHWC and would feed our NCHW model wrong data). |
| `Predictor.kt` | Removed `BasePredictor.inputOBBFromModelOBB`, the private `outputCoordinatesAreNormalized(OBB)` and the unused `atan2`/`hypot` imports. | They need the dropped `OBB` class. |
| `PoseEstimator.kt` | `init` no longer calls `loadModelLabels`; it only warns when no labels were passed. | `loadModelLabels` was removed (above). |
| `LiteRtModel.kt` | One KDoc line no longer references `[OrtQnnModel]`. | The class is not vendored. |
| `ImageUtils.kt` | Removed `toBitmap`, `yuvImageToBitmap`, `yuv420888ToNv21`, `imageToByteBuffer` and the `ImageProxy`/`ByteArrayOutputStream` imports. | CameraX-only (`androidx.camera.core.ImageProxy`) and allocates per frame; our capture adapter row-copies frames itself (ARCHITECTURE §6.1). `letterboxTransform`, `prepareBitmapForModel` and the float-array copy stay. |
| `PoseEstimator.kt` | Added read-only diagnostics `lastMaxRawScore`, `lastRawRowsAboveThreshold`, `lastNanRawRows` and the private allocation-free `recordRawScoreStats`, called at the start of `postProcessPose` (both layouts). | Phone bug "nothing detected": the diagnostics zip could not tell an empty model output from detections lost later. The scan reads only the score column; it changes no decoded result. |
| `PoseEstimator.kt` | Output layout detection treats `[1, 56, N]` and `[1, N, 56]` (N > 56) as RAW pose output, for any N; a new constructor parameter `rawNmsIou`; new `postProcessRawPose` (called from `postProcessPose` for that layout) decodes through `RawPoseDecoder` (core: threshold, class-agnostic NMS, cap 300, allocation-free) and maps boxes and keypoints to the image with the existing letterbox helpers, in pixel mode. The old classic-head loop and `nmsPoseDetections` stay for other feature counts and are no longer reached by imported models. | Models exported by Ultralytics Platform (LiteRT default `nms=None`) and YOLOv8/YOLO11 pose models give raw output; the old loop allocated per candidate, had a hardcoded 0.25 second threshold, and misread raw output with N <= 300 as end-to-end. The raw path shares the import check's layout rule (`PoseOutputLayout.rawOf`, batch 1). |
| `PoseEstimator.kt` | Detect-only (no keypoint) outputs: new constructor parameter `personClass`; the pose layout setup moved unchanged into `initPose`, a new `initDetectOnly` handles `PoseOutputLayout.detectOf(shape)` (`[1, 4+C, N]`, `[1, N, 4+C]`, `[1, N, 6]`) with core's `RawDetectDecoder`; new `postProcessDetectOnly` (called from `postProcessPose` first) maps its boxes to the image like the raw pose path and returns 17 keypoints at (0, 0) with confidence 0 (one shared immutable list); new read-only `detectLayout` / `detectClasses` so the app can compare them with the import record; `recordRawScoreStats` reads the person-class score column (`scoreFeature`). A `personClass` the model does not have throws (model unusable) after closing the model. | Owner's real model is a 2-class detect head `[1, 6, 2100]` with no keypoints (M-legacy T2); these shapes made the constructor throw `Unexpected pose output feature size`. The pose paths are untouched. |
| `PoseEstimator.kt` | M-legacy review: `init` closes the model on any `Throwable` after creating it (before: only an `IllegalArgumentException` of the detect decoder; an Error such as OOM leaked it, and `initPose` and the later buffer allocations had no guard); `detectOf`/`detectClassesOf` now get the model input size (a raw detect head must have the anchor count of the input); for `DETECT_END_TO_END` the diagnostics scan (`recordRawScoreStats`) counts only rows of the person class. | Review findings: a leaked `CompiledModel` on failure, YOLOv5-style `[1, 6300, 9]` misread as detect, diagnostics counting other classes. |
| `Utils.kt` | Unchanged. | n/a |

Build-side changes outside the vendored directory: root `LICENSE` (AGPL-3.0, from upstream), `detector/build.gradle.kts`
(`litert`, `litert-gpu`, `unitTests.isReturnDefaultValues` so pure helpers behind `android.graphics` statics can be
unit-tested), root `build.gradle.kts` (Spotless exclusion for `**/com/ultralytics/**`).

Left in place although unused by pose (harmless, would only add diff noise): `ImageUtils.processGrayscaleImage` and
`copyRgbBitmapToFloatBuffer`, `BasePredictor.modelMaskCropRect`, `depthColors` and other palette helpers in `Utils.kt`.

## Known issues (fix in M1b; plan decision D2)

- **Threading.** Upstream creates the model on a throwaway executor and runs it on its camera executor. LiteRT's rule is
  create/run/close on one thread (ARCHITECTURE §5). Our adapter owns a single inference thread (true since M0b).
- **Raw-output readback allocates.** LiteRT 2.3.2's Kotlin `TensorBuffer` only offers `readFloat(): FloatArray` (a new array per
  call; there is no read-into-a-given-array or `FloatBuffer` variant, checked in the `litert-api` 2.3.2 class files), so
  `LiteRtModel.run` allocates the output array every frame: 56 x 8400 floats = about 1.9 MB for a raw 640 model (end-to-end
  outputs are far smaller). This cannot be fixed without a LiteRT version change or new API; it stays until then.
- **The vendored wrapper allocates per detection** on the raw path as on the end-to-end path (`RectF`, `Pair`s, `Keypoints`,
  lists in `postProcessRawPose`); only the core `RawPoseDecoder` (threshold, NMS, cap) is allocation-free.
- **Per-frame allocations** (AGENTS rule 8). The decoder allocates every frame: `LiteRtModel.run` (`readAsFloats`, a new
  `FloatArray` per output) and `PoseEstimator.postProcessPose`/`postProcessEndToEndPose` (lists, `RectF`, `Pair`s,
  `Keypoints`). `PoseEstimator.predict` also builds `YOLOResult`. Our own code complies with rule 8 from M0b; the vendored
  decoder is rewritten allocation-free in M1b ("buffers allocated once").
- **`nmsPoseDetections` second-pass threshold** is hard-coded to 0.25 (above our 0.15 score threshold), so detections
  between 0.15 and 0.25 are dropped in the classic layout. Revisit with the tuning in M1b.
- **Input source.** `prepareBitmapForModel` needs a `Bitmap` of exact width (no row stride padding); the adapter provides it.
- **`readFloat()` allocates (found in M1a).** LiteRT Kotlin `TensorBuffer.readFloat()` returns a new `FloatArray` on
  every call (JNI `NewFloatArray` in `litert_tensor_buffer_jni.cc`; `TensorBuffer.kt` has no read-into overload).
  The readback of the 300x57 output (17 100 floats, 68 KB per frame) in `LiteRtModel.run` therefore cannot be made
  allocation-free with the bundled runtime. The decoder's own temporaries (lists, `RectF`, `Pair`s, `Keypoints`) are
  removed in M1b; this one array stays and is known, not a bug of ours.
