package com.memo494.posetracker.core.overlay

/**
 * Why the overlay hides the detections and shows a HUD warning instead (fail closed, ARCHITECTURE §4.2).
 * `null` in the overlay state means "no warning".
 */
enum class OverlayWarning {
    /** The capture is not display-sized (rotation in flight, resize, or a partial capture); never mapped. */
    CAPTURE_NOT_DISPLAY_SIZED,
}
