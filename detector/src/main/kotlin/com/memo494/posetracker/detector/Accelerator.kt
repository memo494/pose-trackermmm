package com.memo494.posetracker.detector

/**
 * Which compute backend the pose detector is running on (ARCHITECTURE §6.1).
 *
 * Selection is GPU-first with a CPU fallback; when it falls back to [CPU] the HUD shows a red
 * "Running on CPU" banner. M0a placeholder: the real [PoseDetector] adapter arrives in M0b.
 */
enum class Accelerator {
    GPU,
    CPU,
}
