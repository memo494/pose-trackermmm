package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.core.stats.ThermalStatusText
import com.memo494.posetracker.core.stats.ThermalStepDown
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.modelimport.ModelStatus
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
 * @property tracksShown tracks shown in the newest published state, count.
 * @property tracksMostAtOnce most tracks shown at once since Start, count.
 * @property trackBirths tracks started since Start, count.
 * @property trackConfirmations tentative tracks that became shown after enough hits since Start, count.
 * @property trackIdlePromotions tentative tracks promoted on a still picture since Start, count.
 * @property trackRecoveries lost tracks that were matched again since Start, count.
 * @property trackRemovals shown tracks that were removed since Start, count.
 * @property locks locks set (a tap on a person) since Start, count.
 * @property lockRelocks times a pending lock moved to a returning person (re-lock by clothing colours) since Start, count.
 * @property lockRelockRejects new tracks compared with a pending lock and not accepted (too different, or not clearly
 *   the closest) since Start, count.
 * @property lockExpired pending locks whose memory ran out since Start, count.
 * @property assocMisses tracks born next to a LOST track (association misses) since Start, count.
 * @property assocMissGate misses whose pair was outside the gate since Start, count.
 * @property assocMissCost misses whose pair passed the gate but cost too much since Start, count.
 * @property assocMissTaken misses whose pair the matcher would have accepted since Start, count.
 * @property assocMissLowBirth unmatched detections below the birth score next to a LOST track since Start, count.
 * @property assocBirthNearLive tracks born next to a track that was not LOST since Start, count.
 * @property assocBirthAfterRemoval tracks born next to a track removed in the same frame since Start, count.
 * @property model the model of the session (source, file, fallback, input size); `null` = not known.
 * @property timing percentiles of the per-frame timings over the newest frames; [TimingSummary.EMPTY] = none yet.
 * @property thermalStatus name of the device's thermal status (`NONE` to `SHUTDOWN`); `n/a` = not known.
 * @property thermalHeadroom the device's thermal headroom, unitless (1.0 = throttling); NaN = not known.
 * @property thermalState the thermal step-down's state, `NORMAL` or `HOT` (the small model is wanted).
 * @property thermalStepDowns how many times the step-down went from `NORMAL` to `HOT` since Start.
 * @property thermalHotMs how long the step-down was `HOT` since Start, in milliseconds.
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
    val tracksShown: Int = 0,
    val tracksMostAtOnce: Int = 0,
    val trackBirths: Int = 0,
    val trackConfirmations: Int = 0,
    val trackIdlePromotions: Int = 0,
    val trackRecoveries: Int = 0,
    val trackRemovals: Int = 0,
    val locks: Int = 0,
    val lockRelocks: Int = 0,
    val lockRelockRejects: Int = 0,
    val lockExpired: Int = 0,
    val assocMisses: Int = 0,
    val assocMissGate: Int = 0,
    val assocMissCost: Int = 0,
    val assocMissTaken: Int = 0,
    val assocMissLowBirth: Int = 0,
    val assocBirthNearLive: Int = 0,
    val assocBirthAfterRemoval: Int = 0,
    val model: ModelStatus? = null,
    val timing: TimingSummary = TimingSummary.EMPTY,
    val thermalStatus: String = ThermalStatusText.NOT_AVAILABLE,
    val thermalHeadroom: Float = Float.NaN,
    val thermalState: String = ThermalStepDown.State.NORMAL.name,
    val thermalStepDowns: Int = 0,
    val thermalHotMs: Long = 0L,
)
