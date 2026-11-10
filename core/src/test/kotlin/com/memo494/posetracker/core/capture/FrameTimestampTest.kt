package com.memo494.posetracker.core.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

class FrameTimestampTest {
    private val guardNs = TrackerConfig.DEFAULT_TIMESTAMP_GUARD_NS
    private val nowNs = 5_000_000_000L
    private val arrivalNs = 4_999_000_000L

    @Test
    fun imageStampJustBeforeNow_isAccepted() {
        val imageNs = nowNs - 30_000_000L

        assertThat(FrameTimestamp.isImageStampValid(imageNs, nowNs, guardNs)).isTrue()
        assertThat(FrameTimestamp.resolveNs(imageNs, nowNs, arrivalNs, guardNs)).isEqualTo(imageNs)
        assertThat(FrameTimestamp.sourceOf(imageNs, nowNs, guardNs)).isEqualTo(TimestampSource.IMAGE)
    }

    @Test
    fun imageStampEqualToNow_isAccepted() {
        assertThat(FrameTimestamp.isImageStampValid(nowNs, nowNs, guardNs)).isTrue()
    }

    @Test
    fun imageStampExactlyAtTheGuard_isAccepted() {
        assertThat(FrameTimestamp.isImageStampValid(nowNs - guardNs, nowNs, guardNs)).isTrue()
    }

    @Test
    fun imageStampOlderThanTheGuard_fallsBackToArrival() {
        val imageNs = nowNs - guardNs - 1L

        assertThat(FrameTimestamp.isImageStampValid(imageNs, nowNs, guardNs)).isFalse()
        assertThat(FrameTimestamp.resolveNs(imageNs, nowNs, arrivalNs, guardNs)).isEqualTo(arrivalNs)
        assertThat(FrameTimestamp.sourceOf(imageNs, nowNs, guardNs)).isEqualTo(TimestampSource.ARRIVAL)
    }

    @Test
    fun imageStampInTheFuture_isNegativeAge_andFallsBackToArrival() {
        val imageNs = nowNs + 1L

        assertThat(FrameTimestamp.isImageStampValid(imageNs, nowNs, guardNs)).isFalse()
        assertThat(FrameTimestamp.resolveNs(imageNs, nowNs, arrivalNs, guardNs)).isEqualTo(arrivalNs)
        assertThat(FrameTimestamp.sourceOf(imageNs, nowNs, guardNs)).isEqualTo(TimestampSource.ARRIVAL)
    }

    @Test
    fun negativeImageStamp_isRejectedEvenWhenTheAgeLooksSmall() {
        assertThat(FrameTimestamp.isImageStampValid(-1L, 50_000_000L, guardNs)).isFalse()
        assertThat(FrameTimestamp.isImageStampValid(Long.MIN_VALUE, nowNs, guardNs)).isFalse()
    }

    @Test
    fun zeroImageStamp_isRejectedWhenNowIsFarAway() {
        assertThat(FrameTimestamp.isImageStampValid(0L, nowNs, guardNs)).isFalse()
    }
}
