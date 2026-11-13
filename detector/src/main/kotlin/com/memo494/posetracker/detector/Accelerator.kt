package com.memo494.posetracker.detector

/**
 * Which compute backend the pose detector is running on (ARCHITECTURE §6.1).
 *
 * Selection is GPU-first with a CPU fallback; when it falls back to [CPU] the HUD shows a red
 * "Running on CPU" banner. A [PoseDetector] reports the backend that actually runs through
 * [PoseDetector.accelerator].
 */
enum class Accelerator {
    GPU,
    CPU,
}
