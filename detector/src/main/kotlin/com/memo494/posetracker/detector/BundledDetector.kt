package com.memo494.posetracker.detector

/**
 * A detector built from a model bundled in the assets, with the file it came from.
 *
 * @property detector the detector; the caller owns it and must close it.
 * @property fileName bare name of the bundled `.tflite` file, e.g. `yolo26n-pose_448x960.tflite`.
 * @property sha256 SHA-256 of that file (64 lowercase hex characters), as listed in `model_info.json`.
 */
class BundledDetector(
    val detector: PoseDetector,
    val fileName: String,
    val sha256: String,
)
