package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.overlay.OverlayState

/**
 * Immutable snapshot of the capture for the diagnostics zip. Plain values (strings instead of the other
 * modules' enums) so the class and its report are testable on the JVM.
 *
 * @property accelerator name of the detector backend (`GPU` or `CPU`); `null` = not known yet.
 * @property captureFps frames per second delivered by the capture, hertz (about 0 on a static screen).
 * @property inferenceMs inference time per frame, milliseconds.
 * @property captureWidthImg width of the capture buffer, Img pixels; 0 = unknown.
 * @property captureHeightImg height of the capture buffer, Img pixels; 0 = unknown.
 * @property timestampSource name of the source of the newest frame's timestamp; `null` = no frame yet.
 * @property warning name of the fail-closed warning shown on the HUD; `null` = none.
 * @property dotsVisible `false` while the captured content is reported as not visible.
 * @property frames frames that went through detection, count.
 * @property droppedSizeMismatch frames dropped for a wrong size, count.
 * @property copyFailures frames dropped because the pixel copy failed, count.
 * @property arrivalFallbacks frames whose image timestamp failed the guard (arrival time used), count.
 * @property failures frames that ended with a contained failure, count.
 * @property displayWidthPx width of the display the capture was planned for (used by the overlay mapping), pixels; 0 = unknown.
 * @property displayHeightPx height of that display, pixels; 0 = unknown.
 * @property detections people in the newest detected frame after filtering, count; negative = no frame yet.
 * @property framesWithDetections frames whose detection returned at least one person, count.
 * @property maxRawScore highest raw person score of the newest frame over all model output rows, before any
 *   threshold, unitless; negative = not measured.
 * @property peakMaxRawScore highest [maxRawScore] of the session; negative = never measured.
 * @property rawRowsAboveThreshold model output rows of the newest frame whose raw score passed the threshold,
 *   count; negative = not measured.
 * @property nanRawRows model output rows of the newest frame with a NaN score, count; negative = not measured.
 * @property headXImg head point of the first person of the newest frame with people, Img pixels; NaN = none yet.
 * @property headYImg see [headXImg].
 */
internal data class CaptureStats(
    val accelerator: String?,
    val captureFps: Float,
    val inferenceMs: Float,
    val captureWidthImg: Int,
    val captureHeightImg: Int,
    val timestampSource: String?,
    val warning: String?,
    val dotsVisible: Boolean,
    val frames: Long,
    val droppedSizeMismatch: Long,
    val copyFailures: Long,
    val arrivalFallbacks: Long,
    val failures: Long,
    val displayWidthPx: Int = 0,
    val displayHeightPx: Int = 0,
    val detections: Int = OverlayState.NOT_MEASURED_COUNT,
    val framesWithDetections: Long = 0L,
    val maxRawScore: Float = OverlayState.NOT_MEASURED_SCORE,
    val peakMaxRawScore: Float = OverlayState.NOT_MEASURED_SCORE,
    val rawRowsAboveThreshold: Int = OverlayState.NOT_MEASURED_COUNT,
    val nanRawRows: Int = OverlayState.NOT_MEASURED_COUNT,
    val headXImg: Float = Float.NaN,
    val headYImg: Float = Float.NaN,
)
