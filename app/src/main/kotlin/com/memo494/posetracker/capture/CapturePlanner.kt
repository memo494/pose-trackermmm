package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureSize
import kotlin.math.abs

/**
 * What to capture and whether the result can be mapped to the screen.
 *
 * @property size size of the `ImageReader` / `VirtualDisplay` buffer, Img pixels.
 * @property displaySized `true` only when the captured content has the display's width and height; a
 *   partial capture or a rotation in flight is not display-sized and its detections are never drawn.
 */
internal data class CapturePlan(
    val size: CaptureSize,
    val displaySized: Boolean,
)

/** Pure decision for the capture size (no `android.*`). */
internal object CapturePlanner {
    /**
     * Plans a capture of a display of [displayWidthPx] x [displayHeightPx] pixels whose captured content
     * (`onCapturedContentResize`) is [contentWidthPx] x [contentHeightPx] pixels, at [scale]
     * ([TrackerConfig.captureScale]).
     *
     * [CapturePlan.displaySized] needs each content side within
     * [TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX] of the display side; the other orientation is
     * therefore not display-sized. Returns `null` (fail closed) when any size is not positive or the
     * capture size cannot be derived.
     */
    fun plan(
        displayWidthPx: Int,
        displayHeightPx: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
        scale: Float,
    ): CapturePlan? {
        if (displayWidthPx <= 0 || displayHeightPx <= 0 || contentWidthPx <= 0 || contentHeightPx <= 0) return null
        val size = CaptureSize.of(displayWidthPx, displayHeightPx, scale) ?: return null
        val widthOk = abs(contentWidthPx - displayWidthPx) <= TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX
        val heightOk = abs(contentHeightPx - displayHeightPx) <= TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX
        return CapturePlan(size, widthOk && heightOk)
    }
}
