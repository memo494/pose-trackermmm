package com.memo494.posetracker.core.stats

import com.memo494.posetracker.core.TrackerConfig

/**
 * Median and 95th percentile of one timing, in milliseconds; NaN = no sample, so unknown.
 *
 * @property p50 the median ([TrackerConfig.TIMING_MEDIAN_FRACTION]).
 * @property p95 the 95th percentile ([TrackerConfig.TIMING_P95_FRACTION]).
 */
data class Percentiles(
    val p50: Float,
    val p95: Float,
) {
    companion object {
        /** No sample: both values unknown. */
        val NONE = Percentiles(Float.NaN, Float.NaN)

        /** The two percentiles of [window]; [NONE] when it is empty. */
        fun of(window: TimingWindow): Percentiles =
            Percentiles(
                window.percentile(TrackerConfig.TIMING_MEDIAN_FRACTION),
                window.percentile(TrackerConfig.TIMING_P95_FRACTION),
            )
    }
}

/**
 * Immutable snapshot of the per-frame timings of the capture thread, built at the HUD tick and read by the
 * diagnostics thread. All values are milliseconds.
 *
 * @property samples processed frames in the frame window (at most `TrackerConfig.timingWindowFrames`); 0 = none yet.
 * @property copyMs the row copy of the frame into the reused buffer.
 * @property preMs the detector's pre-processing (image to model input).
 * @property inferMs model run plus output readback.
 * @property postMs the detector's post-processing (model output to detections).
 * @property frameMs the whole frame, from acquiring the image to publishing the result.
 * @property resultAgeMs publish time minus the frame's image timestamp; only frames whose image timestamp was
 *   accepted count.
 */
data class TimingSummary(
    val samples: Int,
    val copyMs: Percentiles,
    val preMs: Percentiles,
    val inferMs: Percentiles,
    val postMs: Percentiles,
    val frameMs: Percentiles,
    val resultAgeMs: Percentiles,
) {
    companion object {
        /** No frame yet. */
        val EMPTY =
            TimingSummary(0, Percentiles.NONE, Percentiles.NONE, Percentiles.NONE, Percentiles.NONE, Percentiles.NONE, Percentiles.NONE)
    }
}
