package com.memo494.posetracker.core.model

/** Where a [DetectionFrame.timestampNs] came from (ARCHITECTURE §4.1); shown in the HUD. */
enum class TimestampSource {
    /** `Image.timestamp`, accepted by the guard in `FrameTimestamp`. */
    IMAGE,

    /** `System.nanoTime()` taken at `acquireLatestImage`, used when the image stamp failed the guard. */
    ARRIVAL,
}
