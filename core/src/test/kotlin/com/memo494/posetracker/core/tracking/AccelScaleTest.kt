package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

/** The low-rate scale of the head process noise (F4, W2): from a smoothed frame interval, not from each step. */
class AccelScaleTest {
    private val config = TrackerConfig()

    private fun scale() = AccelScale(config)

    @Test
    fun steadyFiftyMilliseconds_isScaleOne() {
        val scale = scale()
        repeat(STEPS) { assertThat(scale.update(0.050f, gap = false)).isEqualTo(1f) }
    }

    @Test
    fun steadyEightyThreeMilliseconds_isTheMinimumScale() {
        val scale = scale()
        repeat(STEPS) { assertThat(scale.update(0.0833f, gap = false)).isEqualTo(config.trackAccelMinScale) }
    }

    @Test
    fun anIntervalBetweenTheTwo_isTheSquareOfTheRatio() {
        val scale = scale()
        var last = 0f
        repeat(STEPS) { last = scale.update(0.0625f, gap = false) }

        assertThat(last).isWithin(EPS).of(0.64f)
    }

    @Test
    fun aGapStep_isNotScaled_andDoesNotMoveTheInterval() {
        val scale = scale()
        repeat(STEPS) { scale.update(0.0833f, gap = false) }

        assertThat(scale.update(1.0f, gap = true)).isEqualTo(1f)
        assertThat(scale.update(0.0833f, gap = false)).isEqualTo(config.trackAccelMinScale)
    }

    @Test
    fun oneLongStepAmongFiftyMillisecondOnes_barelyMovesTheScale() {
        val scale = scale()
        repeat(STEPS) { scale.update(0.050f, gap = false) }

        assertThat(scale.update(0.100f, gap = false)).isGreaterThan(SMALL_SHIFT_MIN)
    }

    @Test
    fun aJitteringTwentyFps_staysCloseToOne() {
        val scale = scale()
        var worst = 1f
        for (i in 0 until STEPS) {
            val dt = if (i % 2 == 0) 0.035f else 0.065f
            worst = minOf(worst, scale.update(dt, gap = false))
        }

        // The average leans a little to the longer frames (weight dt / (tau + dt)); alternating 35 and 65 ms stays above 0.85.
        assertThat(worst).isGreaterThan(JITTER_MIN)
    }

    @Test
    fun reset_forgetsTheInterval() {
        val scale = scale()
        repeat(STEPS) { scale.update(0.0833f, gap = false) }
        scale.reset()

        assertThat(scale.update(0.050f, gap = false)).isEqualTo(1f)
    }

    private companion object {
        const val STEPS = 60
        const val EPS = 0.02f
        const val SMALL_SHIFT_MIN = 0.8f
        const val JITTER_MIN = 0.85f
    }
}
