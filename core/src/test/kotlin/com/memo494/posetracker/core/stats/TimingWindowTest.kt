package com.memo494.posetracker.core.stats

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TimingWindowTest {
    private fun windowOf(
        capacity: Int,
        vararg values: Float,
    ): TimingWindow {
        val window = TimingWindow(capacity)
        for (value in values) window.add(value)
        return window
    }

    @Test
    fun emptyWindow_hasNoCountAndEveryPercentileIsNaN() {
        val window = TimingWindow(TrackerConfig.MIN_TIMING_WINDOW_FRAMES)

        assertThat(window.count).isEqualTo(0)
        assertThat(window.percentile(0.5)).isNaN()
        assertThat(window.percentile(0.95)).isNaN()
    }

    @Test
    fun percentiles_ofOneToOneHundred_useTheNearestRank() {
        val window = TimingWindow(128)
        for (i in 1..100) window.add(i.toFloat())

        assertThat(window.count).isEqualTo(100)
        assertThat(window.percentile(0.5)).isEqualTo(50f)
        assertThat(window.percentile(0.95)).isEqualTo(95f)
        assertThat(window.percentile(0.0)).isEqualTo(1f)
        assertThat(window.percentile(1.0)).isEqualTo(100f)
    }

    @Test
    fun percentiles_ofUnsortedDataWithDuplicates_areComputedOnTheSortedValues() {
        val window = windowOf(16, 9f, 1f, 5f, 5f, 3f, 7f, 5f)

        // sorted: 1 3 5 5 5 7 9 -> rank ceil(0.5 * 7) = 4 -> 5; rank ceil(0.95 * 7) = 7 -> 9
        assertThat(window.percentile(0.5)).isEqualTo(5f)
        assertThat(window.percentile(0.95)).isEqualTo(9f)
    }

    @Test
    fun oneSample_isEveryPercentile() {
        val window = windowOf(16, 4.25f)

        assertThat(window.percentile(0.0)).isEqualTo(4.25f)
        assertThat(window.percentile(0.5)).isEqualTo(4.25f)
        assertThat(window.percentile(1.0)).isEqualTo(4.25f)
    }

    @Test
    fun wrapAround_keepsTheNewestCapacityValuesOnly() {
        val window = TimingWindow(16)
        for (i in 1..20) window.add(i.toFloat())

        // holds 5..20
        assertThat(window.count).isEqualTo(16)
        assertThat(window.percentile(0.0)).isEqualTo(5f)
        assertThat(window.percentile(0.5)).isEqualTo(12f)
        assertThat(window.percentile(1.0)).isEqualTo(20f)
    }

    @Test
    fun wrapAround_afterSeveralLaps_stillHoldsTheNewestValues() {
        val window = TimingWindow(16)
        for (i in 1..100) window.add(i.toFloat())

        // holds 85..100
        assertThat(window.count).isEqualTo(16)
        assertThat(window.percentile(0.0)).isEqualTo(85f)
        assertThat(window.percentile(1.0)).isEqualTo(100f)
    }

    @Test
    fun percentile_afterMoreSamples_seesThem() {
        val window = windowOf(16, 1f, 2f, 3f)
        assertThat(window.percentile(1.0)).isEqualTo(3f)

        window.add(10f)

        assertThat(window.percentile(1.0)).isEqualTo(10f)
        assertThat(window.count).isEqualTo(4)
    }

    @Test
    fun percentile_doesNotDisturbTheOrderInWhichSamplesAreReplaced() {
        val window = TimingWindow(16)
        for (i in 1..20) window.add((21 - i).toFloat())

        // holds 16..1 (newest = 1, oldest = 16)
        assertThat(window.percentile(0.5)).isEqualTo(8f)

        window.add(0.5f)

        // the oldest sample (16) was replaced; sorting must have happened on a copy
        assertThat(window.percentile(1.0)).isEqualTo(15f)
        assertThat(window.percentile(0.0)).isEqualTo(0.5f)
    }

    @Test
    fun badSamples_areDroppedAndNotCounted() {
        val window = TimingWindow(16)

        assertThat(window.add(Float.NaN)).isFalse()
        assertThat(window.add(Float.POSITIVE_INFINITY)).isFalse()
        assertThat(window.add(Float.NEGATIVE_INFINITY)).isFalse()
        assertThat(window.add(-0.001f)).isFalse()
        assertThat(window.add(-1f)).isFalse()

        assertThat(window.count).isEqualTo(0)
        assertThat(window.percentile(0.5)).isNaN()
    }

    @Test
    fun badSamples_betweenGoodOnes_doNotShiftTheResult() {
        val window = TimingWindow(16)
        window.add(2f)
        window.add(Float.NaN)
        window.add(-3f)
        window.add(4f)

        assertThat(window.count).isEqualTo(2)
        assertThat(window.percentile(0.0)).isEqualTo(2f)
        assertThat(window.percentile(1.0)).isEqualTo(4f)
    }

    @Test
    fun zero_isAValidSample() {
        val window = windowOf(16, 0f)

        assertThat(window.count).isEqualTo(1)
        assertThat(window.percentile(0.5)).isEqualTo(0f)
    }

    @Test
    fun add_ofAGoodSample_returnsTrue() {
        assertThat(TimingWindow(16).add(1f)).isTrue()
    }

    @Test
    fun percentile_outsideZeroToOne_isNaN() {
        val window = windowOf(16, 1f, 2f, 3f)

        assertThat(window.percentile(-0.01)).isNaN()
        assertThat(window.percentile(1.01)).isNaN()
        assertThat(window.percentile(Double.NaN)).isNaN()
    }

    @Test
    fun capacity_atItsBounds_isAccepted() {
        assertThat(TimingWindow(TrackerConfig.MIN_TIMING_WINDOW_FRAMES).capacity).isEqualTo(16)
        assertThat(TimingWindow(TrackerConfig.MAX_TIMING_WINDOW_FRAMES).capacity).isEqualTo(4096)
    }

    @Test
    fun capacity_outsideItsBounds_isRejected() {
        for (capacity in listOf(15, 0, -1, 4097)) {
            assertThrows(IllegalArgumentException::class.java) { TimingWindow(capacity) }
        }
    }
}
