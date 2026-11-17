package com.memo494.posetracker.core.overlay

/**
 * Opacity of the marks of a `LOST` track (docs/plans/M4.md, M4b T1): full while the track was just lost, falling
 * linearly to a floor as the lost time approaches the time the tracker keeps it. Pure and allocation-free.
 */
object MarkFade {
    /**
     * `max(minAlpha, 1 - lostMs / maxLostMs)`, at most 1. A [lostMs] of 0 or less is 1 (opaque); one beyond
     * [maxLostMs] is [minAlpha]. Fails closed: a [lostMs] that is not a number, or a [maxLostMs] that is not a
     * positive number, gives [minAlpha] (the faintest, never a guessed full opacity).
     *
     * @param lostMs time in milliseconds the track has been without a matching detection.
     * @param maxLostMs time in milliseconds after which the tracker drops the track (`trackMaxLostMs`).
     * @param minAlpha floor of the opacity, unitless in [0, 1] (`fadeMinAlpha`).
     */
    fun alpha(
        lostMs: Float,
        maxLostMs: Float,
        minAlpha: Float,
    ): Float {
        if (lostMs.isNaN() || !(maxLostMs > 0f)) return minAlpha
        if (lostMs <= 0f) return 1f
        val line = 1f - lostMs / maxLostMs
        return if (line > minAlpha) line else minAlpha
    }
}
