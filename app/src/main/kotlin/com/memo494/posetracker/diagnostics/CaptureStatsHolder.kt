package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.capture.FramePipeline
import com.memo494.posetracker.overlay.OverlayState

/**
 * The newest [CaptureStats], written by the capture session at the HUD tick and read when the diagnostics
 * zip is built. The last value stays after the session ends: it is what a bug report needs.
 */
internal object CaptureStatsHolder {
    @Volatile
    var latest: CaptureStats? = null

    /** Capture thread, once per HUD tick: stores a snapshot of [state] and the [pipeline] counters. */
    fun publish(
        state: OverlayState,
        pipeline: FramePipeline,
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
            )
    }
}
