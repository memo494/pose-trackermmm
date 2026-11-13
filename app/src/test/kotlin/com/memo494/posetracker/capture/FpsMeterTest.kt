package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FpsMeterTest {
    private val frameGapNs = 16_666_667L
    private val windowNs = 1_000_000_000L

    @Test
    fun nothingRecorded_isZero() {
        assertThat(FpsMeter().fps(5L * windowNs)).isEqualTo(0f)
    }

    @Test
    fun steadySixtyFramesPerSecond_isAboutSixty() {
        val meter = FpsMeter()
        for (i in 0 until 120) meter.onFrame(i * frameGapNs)

        assertThat(meter.fps(119 * frameGapNs)).isWithin(1.5f).of(60f)
    }

    @Test
    fun afterAnIdleGapLongerThanTheWindow_isZero() {
        val meter = FpsMeter()
        for (i in 0 until 30) meter.onFrame(i * frameGapNs)

        assertThat(meter.fps(29 * frameGapNs + windowNs + 1L)).isEqualTo(0f)
    }

    @Test
    fun decaysAsFramesLeaveTheWindow() {
        val meter = FpsMeter()
        for (i in 0 until 10) meter.onFrame(i * 100_000_000L)

        assertThat(meter.fps(900_000_000L)).isWithin(0.01f).of(10f)
        assertThat(meter.fps(1_400_000_000L)).isWithin(0.01f).of(6f)
    }

    @Test
    fun aFrameInTheFutureIsNotCounted() {
        val meter = FpsMeter()
        meter.onFrame(2_000L)

        assertThat(meter.fps(1_000L)).isEqualTo(0f)
    }

    @Test
    fun moreFramesThanTheCapacity_countsOnlyTheNewestOnes() {
        val meter = FpsMeter(windowNs = windowNs, capacity = 4)
        for (i in 0 until 10) meter.onFrame(i * 1_000L)

        assertThat(meter.fps(9_000L)).isWithin(0.01f).of(4f)
    }

    @Test
    fun wrapAround_keepsTheNewestFramesAcrossTheSeam() {
        val meter = FpsMeter(windowNs = windowNs, capacity = 3)
        meter.onFrame(0L)
        meter.onFrame(100L)
        meter.onFrame(200L)
        meter.onFrame(300L)

        assertThat(meter.fps(300L)).isWithin(0.01f).of(3f)
        assertThat(meter.fps(300L + windowNs + 1L)).isEqualTo(0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveWindow_isRejected() {
        FpsMeter(windowNs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCapacity_isRejected() {
        FpsMeter(capacity = 0)
    }
}
