package com.memo494.posetracker.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TrackerConfigTest {
    @Test
    fun defaults_matchTheDocumentedValues() {
        val config = TrackerConfig()

        assertThat(config.scoreThreshold).isEqualTo(0.15f)
        assertThat(config.captureScale).isEqualTo(0.5f)
        assertThat(config.hudRateHz).isEqualTo(2f)
        assertThat(config.timestampGuardNs).isEqualTo(100_000_000L)
        assertThat(config.overlayAlphaCapFallback).isEqualTo(0.8f)
    }

    @Test
    fun defaults_areWithinTheirMinAndMax() {
        val config = TrackerConfig()

        assertWithin(config.scoreThreshold, TrackerConfig.MIN_SCORE_THRESHOLD, TrackerConfig.MAX_SCORE_THRESHOLD)
        assertWithin(config.captureScale, TrackerConfig.MIN_CAPTURE_SCALE, TrackerConfig.MAX_CAPTURE_SCALE)
        assertWithin(config.hudRateHz, TrackerConfig.MIN_HUD_RATE_HZ, TrackerConfig.MAX_HUD_RATE_HZ)
        assertWithin(
            config.overlayAlphaCapFallback,
            TrackerConfig.MIN_OVERLAY_ALPHA_CAP_FALLBACK,
            TrackerConfig.MAX_OVERLAY_ALPHA_CAP_FALLBACK,
        )
        assertWithin(config.dotStrokeDp, TrackerConfig.MIN_STROKE_DP, TrackerConfig.MAX_STROKE_DP)
        assertWithin(config.boxStrokeDp, TrackerConfig.MIN_STROKE_DP, TrackerConfig.MAX_STROKE_DP)
        assertThat(config.timestampGuardNs).isAtLeast(TrackerConfig.MIN_TIMESTAMP_GUARD_NS)
        assertThat(config.timestampGuardNs).isAtMost(TrackerConfig.MAX_TIMESTAMP_GUARD_NS)
    }

    @Test
    fun overlayAlphaCapFallback_defaultDoesNotExceedTheAndroidObscuringLimit() {
        assertThat(TrackerConfig.MAX_OVERLAY_ALPHA_CAP_FALLBACK).isAtMost(0.8f)
    }

    @Test
    fun hudIntervalNs_isTheInverseOfTheRate() {
        assertThat(TrackerConfig().hudIntervalNs).isEqualTo(500_000_000L)
    }

    @Test
    fun construction_belowMin_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(scoreThreshold = TrackerConfig.MIN_SCORE_THRESHOLD - 0.001f)
        }
    }

    @Test
    fun construction_aboveMax_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(timestampGuardNs = TrackerConfig.MAX_TIMESTAMP_GUARD_NS + 1L)
        }
    }

    @Test
    fun construction_nan_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(captureScale = Float.NaN)
        }
    }

    @Test
    fun construction_atTheBounds_isAccepted() {
        val low = TrackerConfig(captureScale = TrackerConfig.MIN_CAPTURE_SCALE)
        val high = TrackerConfig(captureScale = TrackerConfig.MAX_CAPTURE_SCALE)

        assertThat(low.captureScale).isEqualTo(TrackerConfig.MIN_CAPTURE_SCALE)
        assertThat(high.captureScale).isEqualTo(TrackerConfig.MAX_CAPTURE_SCALE)
    }

    private fun assertWithin(
        value: Float,
        min: Float,
        max: Float,
    ) {
        assertThat(value).isAtLeast(min)
        assertThat(value).isAtMost(max)
    }
}
