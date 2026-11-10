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
| `Predictor.kt` | Removed `BasePredictor.inputOBBFromModelOBB`, the private `outputCoordinatesAreNormalized(OBB)` and the unused `atan2`/`hypot` imports. | They need the dropped `OBB` class. |
| `PoseEstimator.kt` | `init` no longer calls `loadModelLabels`; it only warns when no labels were passed. | `loadModelLabels` was removed (above). |
| `LiteRtModel.kt` | One KDoc line no longer references `[OrtQnnModel]`. | The class is not vendored. |
| `ImageUtils.kt` | Removed `toBitmap`, `yuvImageToBitmap`, `yuv420888ToNv21`, `imageToByteBuffer` and the `ImageProxy`/`ByteArrayOutputStream` imports. | CameraX-only (`androidx.camera.core.ImageProxy`) and allocates per frame; our capture adapter row-copies frames itself (ARCHITECTURE §6.1). `letterboxTransform`, `prepareBitmapForModel` and the float-array copy stay. |
| `Utils.kt` | Unchanged. | n/a |

Build-side changes outside the vendored directory: root `LICENSE` (AGPL-3.0, from upstream), `detector/build.gradle.kts`
(`litert`, `litert-gpu`, `unitTests.isReturnDefaultValues` so pure helpers behind `android.graphics` statics can be
unit-tested), root `build.gradle.kts` (Spotless exclusion for `**/com/ultralytics/**`).

Left in place although unused by pose (harmless, would only add diff noise): `ImageUtils.processGrayscaleImage` and
`copyRgbBitmapToFloatBuffer`, `BasePredictor.modelMaskCropRect`, `depthColors` and other palette helpers in `Utils.kt`.

## Known issues (fix in M1; plan decision D2)

- **Threading.** Upstream creates the model on a throwaway executor and runs it on its camera executor. LiteRT's rule is
  create/run/close on one thread (ARCHITECTURE §5). Our adapter must own a single inference thread; M1 verifies it.
- **Per-frame allocations** (AGENTS rule 8). The decoder allocates every frame: `LiteRtModel.run` (`readAsFloats`, a new
  `FloatArray` per output) and `PoseEstimator.postProcessPose`/`postProcessEndToEndPose` (lists, `RectF`, `Pair`s,
  `Keypoints`). `PoseEstimator.predict` also builds `YOLOResult`. Our own code complies with rule 8 from M0b; the vendored
  decoder is rewritten allocation-free in M1 ("buffers allocated once").
- **`nmsPoseDetections` second-pass threshold** is hard-coded to 0.25 (above our 0.15 score threshold), so detections
  between 0.15 and 0.25 are dropped in the classic layout. Revisit with the tuning in M1.
- **Input source.** `prepareBitmapForModel` needs a `Bitmap` of exact width (no row stride padding); the adapter provides it.
