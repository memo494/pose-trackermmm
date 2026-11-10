package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.detector.Accelerator

/**
 * Immutable snapshot the capture service publishes to the overlay (ARCHITECTURE §5). The overlay reads
 * the newest one only inside `onDraw`; it never mutates it.
 *
 * @property frame newest detections, Img space; `null` = none yet.
 * @property accelerator backend of the detector; `null` = not known yet.
 * @property captureFps frames per second delivered by the capture, hertz (about 0 on a static screen).
 * @property inferenceMs inference time per frame, milliseconds.
 * @property captureWidthImg width of the capture buffer, Img pixels; 0 = unknown.
 * @property captureHeightImg height of the capture buffer, Img pixels; 0 = unknown.
 * @property timestampSource where the newest frame's timestamp came from; `null` = no frame yet.
 * @property dotsVisible `false` while the captured content is not visible
 *   (`onCapturedContentVisibilityChanged(false)`); detections are then not drawn.
 * @property warning why detections are hidden and the HUD shows a warning (fail closed); `null` = none.
 */
data class OverlayState(
    val frame: DetectionFrame? = null,
    val accelerator: Accelerator? = null,
    val captureFps: Float = 0f,
    val inferenceMs: Float = 0f,
    val captureWidthImg: Int = 0,
    val captureHeightImg: Int = 0,
    val timestampSource: TimestampSource? = null,
    val dotsVisible: Boolean = true,
    val warning: OverlayWarning? = null,
) {
    /** Whether [frame] may be drawn: content visible and no fail-closed warning. */
    val detectionsShown: Boolean
        get() = dotsVisible && warning == null

    companion object {
        /** Nothing known yet: draws no detections and a mostly empty HUD. */
        val EMPTY = OverlayState()
    }
}
