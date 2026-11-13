package com.memo494.posetracker.diagnostics

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
)
