package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig

/**
 * The three parts of the display-only forward lead (F4, docs/ARCHITECTURE.md §6.5): a head that moves fast is drawn
 * `lead = gSpeed * gStop * v * L` ahead of its smoothed point, with `v` the tracker's velocity, so that the age of
 * the result (capture to screen) does not show as lag. Pure functions; `TrackSnapshot.ofSmoothed` composes them
 * after the `HeadSmoother`, so the tracker, the association and the lock never see the lead.
 *
 * - [speedGain] `gSpeed`: 0 up to `displayLeadSpeedLowHps`, 1 from `displayLeadSpeedHighHps`, linear between (on the
 *   smoother's filtered speed). A still or trembling head has speed far below the low limit, so it gets no lead.
 * - [stopGain] `gStop`: the share of the tracker's speed that the raw measured head still has along the tracker's
 *   direction (full from `displayLeadStopFullRatio`), 0 to 1. A head that stops or turns shows a measured step of 0 or
 *   backwards one frame before the filters know, so the lead is dropped at once and cannot overshoot.
 * - [leadTimeMs] `L = min(result age + displayLeadExtraMs + half the frame interval, displayLeadMaxMs)`.
 */
internal object DisplayLead {
    /** `gSpeed` for the filtered [speedHps] (track heights per second); 0 for a value that is not a number. */
    fun speedGain(
        speedHps: Float,
        config: TrackerConfig,
    ): Float {
        if (!speedHps.isFinite()) return 0f
        val low = config.displayLeadSpeedLowHps
        val high = config.displayLeadSpeedHighHps
        return when {
            speedHps <= low -> 0f
            speedHps >= high -> 1f
            else -> (speedHps - low) / (high - low)
        }
    }

    /**
     * `gStop`: the measured velocity ([measuredVxImgPerS], [measuredVyImgPerS]) projected on the tracker velocity
     * ([vxImgPerS], [vyImgPerS]), divided by the tracker's speed, clamped to [0, 1]. 0 when the tracker has no
     * velocity or a value is not a number.
     */
    fun stopGain(
        vxImgPerS: Float,
        vyImgPerS: Float,
        measuredVxImgPerS: Float,
        measuredVyImgPerS: Float,
        config: TrackerConfig,
    ): Float {
        val speed2 = vxImgPerS * vxImgPerS + vyImgPerS * vyImgPerS
        if (!(speed2 > 0f && speed2.isFinite())) return 0f
        val gain = (measuredVxImgPerS * vxImgPerS + measuredVyImgPerS * vyImgPerS) / speed2
        return if (gain.isNaN()) 0f else (gain / config.displayLeadStopFullRatio).coerceIn(0f, 1f)
    }

    /** `L` in milliseconds; an age or an interval that is not a positive number counts as 0. */
    fun leadTimeMs(
        resultAgeMs: Float,
        frameIntervalMs: Float,
        config: TrackerConfig,
    ): Float {
        val age = if (resultAgeMs.isFinite() && resultAgeMs > 0f) resultAgeMs else 0f
        val interval = if (frameIntervalMs.isFinite() && frameIntervalMs > 0f) frameIntervalMs else 0f
        val total = age + config.displayLeadExtraMs + HALF * interval
        return if (total < config.displayLeadMaxMs) total else config.displayLeadMaxMs
    }

    /**
     * The factor, at most 1, that brings a lead vector of ([dxImg], [dyImg]) down to [maxLengthImg]
     * (`displayLeadMaxRatio` times the track height); 1 when it is not longer, 0 when it is not a finite vector or the
     * limit is not positive.
     */
    fun capScale(
        dxImg: Float,
        dyImg: Float,
        maxLengthImg: Float,
    ): Float {
        val length = kotlin.math.hypot(dxImg, dyImg)
        if (!length.isFinite() || !(maxLengthImg > 0f && maxLengthImg.isFinite())) return 0f
        return if (length > maxLengthImg) maxLengthImg / length else 1f
    }

    private const val HALF = 0.5f
}
