package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig

/**
 * The factor of the head's process noise at low frame rates (F4): `max((ref / interval)^2, trackAccelMinScale)` with
 * `ref = trackAccelRefDtMs` and `interval` a moving average (time constant `trackAccelIntervalTauMs`) of the frame
 * interval, so that one slow frame does not change the scale. 1 at and above the reference rate. Gap steps (longer
 * than `trackMaxDtMs`) are neither scaled nor averaged. No allocation; one thread.
 */
internal class AccelScale(
    private val config: TrackerConfig,
) {
    private val refS = config.trackAccelRefDtMs / MS_PER_S
    private val tauS = config.trackAccelIntervalTauMs / MS_PER_S
    private var averageS = 0f
    private var primed = false

    /** Feeds the step of [dtS] seconds (a [gap] step is not averaged) and returns the factor for it. */
    fun update(
        dtS: Float,
        gap: Boolean,
    ): Float {
        if (gap || !(dtS > 0f && dtS.isFinite())) return 1f
        if (primed) {
            averageS += averageS / (tauS + averageS) * (dtS - averageS)
        } else {
            averageS = dtS
            primed = true
        }
        if (averageS <= refS) return 1f
        val ratio = refS / averageS
        return maxOf(ratio * ratio, config.trackAccelMinScale)
    }

    /** Forgets the average: the next step starts it again. */
    fun reset() {
        primed = false
        averageS = 0f
    }

    private companion object {
        const val MS_PER_S = 1000f
    }
}
