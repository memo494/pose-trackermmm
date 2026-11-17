package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.tracking.TrackSnapshot
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
 * @property warning why the HUD shows a warning line; `null` = none. Most warnings hide the detections (fail
 *   closed), see `OverlayWarning.hidesDetections`.
 * @property displayWidthPx width of the display the capture was planned for, pixels; 0 = unknown. The
 *   Img -> View mapping uses this, never a size the view reads itself, so the mapping always belongs to
 *   the same rotation as [captureWidthImg] x [captureHeightImg].
 * @property displayHeightPx height of the display the capture was planned for, pixels; 0 = unknown.
 * @property maxRawScore highest raw person score of the newest frame over all model output rows, before
 *   any threshold, unitless; negative = not measured.
 * @property rawRowsAboveThreshold model output rows of the newest frame whose raw score passed the score
 *   threshold, count; negative = not measured.
 * @property modelInputWidth width of the running model's input, in model pixels (Mdl); 0 = no model.
 * @property modelInputHeight height of the running model's input, in model pixels (Mdl); 0 = no model.
 * @property modelImported whether the running model is the imported one, not the built-in one.
 * @property tracks the shown tracks of the tracker for [frame] (or for the idle time since it), Img space, an
 *   immutable copy made on the capture thread; [TrackSnapshot.EMPTY] when there is no tracked frame.
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
    val displayWidthPx: Int = 0,
    val displayHeightPx: Int = 0,
    val maxRawScore: Float = NOT_MEASURED_SCORE,
    val rawRowsAboveThreshold: Int = NOT_MEASURED_COUNT,
    val modelInputWidth: Int = 0,
    val modelInputHeight: Int = 0,
    val modelImported: Boolean = false,
    val tracks: TrackSnapshot = TrackSnapshot.EMPTY,
) {
    /** Whether [frame] may be drawn: content visible and no warning that hides the detections. */
    val detectionsShown: Boolean
        get() = dotsVisible && warning?.hidesDetections != true

    /**
     * Whether [tracks] were made for the current capture size ([captureWidthImg] x [captureHeightImg]); the view
     * draws tracks only then (fail closed, like the raw detections). False for the empty snapshot (size 0 x 0)
     * unless the capture size is unknown too; it has nothing to draw anyway.
     */
    val tracksMatchCapture: Boolean
        get() = tracks.widthImg == captureWidthImg && tracks.heightImg == captureHeightImg

    companion object {
        /** [maxRawScore] when the detector did not measure it. */
        const val NOT_MEASURED_SCORE = -1f

        /** [rawRowsAboveThreshold] when the detector did not measure it. */
        const val NOT_MEASURED_COUNT = -1

        /** Nothing known yet: draws no detections and a mostly empty HUD. */
        val EMPTY = OverlayState()
    }
}
