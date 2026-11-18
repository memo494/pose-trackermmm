package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.capture.FramePipeline
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.stats.ThermalSampler
import com.memo494.posetracker.core.stats.ThermalStatusText
import com.memo494.posetracker.modelimport.ModelStatusHolder
import com.memo494.posetracker.overlay.OverlayState

/**
 * The newest [CaptureStats], written by the capture session at the HUD tick and read when the diagnostics
 * zip is built. The last value stays after the session ends: it is what a bug report needs.
 */
internal object CaptureStatsHolder {
    @Volatile
    var latest: CaptureStats? = null

    /** The `debugMarks` of the newest session's config (set when it starts); `config.txt` shows it. */
    @Volatile
    var debugMarks: Boolean = TrackerConfig.DEFAULT_DEBUG_MARKS

    /**
     * Capture thread, once per HUD tick: stores a snapshot of [state], the [pipeline] counters and its timing
     * percentiles (computed here, on the capture thread, so the diagnostics thread only reads the snapshot) and
     * the last reading of [thermal] (`null` = no thermal source: not available).
     */
    fun publish(
        state: OverlayState,
        pipeline: FramePipeline,
        thermal: ThermalSampler?,
    ) {
        latest =
            CaptureStats(
                accelerator = state.accelerator?.name,
                captureFps = state.captureFps,
                inferenceMs = state.inferenceMs,
                captureWidthImg = state.captureWidthImg,
                captureHeightImg = state.captureHeightImg,
                timestampSource = state.timestampSource?.name,
                warning = state.warning?.name,
                dotsVisible = state.dotsVisible,
                frames = pipeline.frames,
                droppedSizeMismatch = pipeline.droppedSizeMismatch,
                copyFailures = pipeline.copyFailures,
                arrivalFallbacks = pipeline.arrivalFallbacks,
                failures = pipeline.failures,
                displayWidthPx = state.displayWidthPx,
                displayHeightPx = state.displayHeightPx,
                detections = pipeline.lastDetections,
                framesWithDetections = pipeline.framesWithDetections,
                maxRawScore = state.maxRawScore,
                peakMaxRawScore = pipeline.peakMaxRawScore,
                rawRowsAboveThreshold = state.rawRowsAboveThreshold,
                nanRawRows = pipeline.lastNanRawRows,
                headXImg = pipeline.lastHeadXImg,
                headYImg = pipeline.lastHeadYImg,
                tracksShown = state.tracks.count,
                tracksMostAtOnce = pipeline.tracksMostAtOnce,
                trackBirths = pipeline.trackBirths,
                trackConfirmations = pipeline.trackConfirmations,
                trackIdlePromotions = pipeline.trackIdlePromotions,
                trackRecoveries = pipeline.trackRecoveries,
                trackRemovals = pipeline.trackRemovals,
                locks = pipeline.lockCount,
                lockRelocks = pipeline.lockRelockCount,
                lockRelockRejects = pipeline.lockRelockRejectCount,
                lockExpired = pipeline.lockExpiredCount,
                assocMisses = pipeline.assocMisses,
                assocMissGate = pipeline.assocMissGate,
                assocMissCost = pipeline.assocMissCost,
                assocMissTaken = pipeline.assocMissTaken,
                assocMissLowBirth = pipeline.assocMissLowBirth,
                assocBirthNearLive = pipeline.assocBirthNearLive,
                assocBirthAfterRemoval = pipeline.assocBirthAfterRemoval,
                model = ModelStatusHolder.latest(),
                timing = pipeline.timingSummary(),
                thermalStatus = thermal?.status ?: ThermalStatusText.NOT_AVAILABLE,
                thermalHeadroom = thermal?.headroom ?: Float.NaN,
            )
    }
}
