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
        assertThat(config.redrawDeadbandPx).isEqualTo(0.5f)
        assertThat(config.keypointMinScore).isEqualTo(0.5f)
        assertThat(config.hudTextSizeSp).isEqualTo(12f)
        assertThat(config.headRingRadiusRatio).isEqualTo(0.12f)
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
        assertWithin(config.redrawDeadbandPx, TrackerConfig.MIN_REDRAW_DEADBAND_PX, TrackerConfig.MAX_REDRAW_DEADBAND_PX)
        assertWithin(config.keypointMinScore, TrackerConfig.MIN_KEYPOINT_MIN_SCORE, TrackerConfig.MAX_KEYPOINT_MIN_SCORE)
        assertWithin(config.hudTextSizeSp, TrackerConfig.MIN_HUD_TEXT_SIZE_SP, TrackerConfig.MAX_HUD_TEXT_SIZE_SP)
        assertWithin(
            config.headRingRadiusRatio,
            TrackerConfig.MIN_HEAD_RING_RADIUS_RATIO,
            TrackerConfig.MAX_HEAD_RING_RADIUS_RATIO,
        )
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

    @Test
    fun redrawDeadbandPx_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(redrawDeadbandPx = TrackerConfig.MIN_REDRAW_DEADBAND_PX - 0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(redrawDeadbandPx = TrackerConfig.MAX_REDRAW_DEADBAND_PX + 0.01f)
        }
    }

    @Test
    fun keypointMinScore_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(keypointMinScore = TrackerConfig.MIN_KEYPOINT_MIN_SCORE - 0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(keypointMinScore = TrackerConfig.MAX_KEYPOINT_MIN_SCORE + 0.01f)
        }
    }

    @Test
    fun hudTextSizeSp_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(hudTextSizeSp = TrackerConfig.MIN_HUD_TEXT_SIZE_SP - 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(hudTextSizeSp = TrackerConfig.MAX_HUD_TEXT_SIZE_SP + 0.5f)
        }
    }

    @Test
    fun headRingRadiusRatio_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(headRingRadiusRatio = TrackerConfig.MIN_HEAD_RING_RADIUS_RATIO - 0.01f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(headRingRadiusRatio = TrackerConfig.MAX_HEAD_RING_RADIUS_RATIO + 0.01f)
        }
    }

    @Test
    fun overlayAdditions_atTheirBounds_areAccepted() {
        val low =
            TrackerConfig(
                redrawDeadbandPx = TrackerConfig.MIN_REDRAW_DEADBAND_PX,
                keypointMinScore = TrackerConfig.MIN_KEYPOINT_MIN_SCORE,
                hudTextSizeSp = TrackerConfig.MIN_HUD_TEXT_SIZE_SP,
                headRingRadiusRatio = TrackerConfig.MIN_HEAD_RING_RADIUS_RATIO,
            )
        val high =
            TrackerConfig(
                redrawDeadbandPx = TrackerConfig.MAX_REDRAW_DEADBAND_PX,
                keypointMinScore = TrackerConfig.MAX_KEYPOINT_MIN_SCORE,
                hudTextSizeSp = TrackerConfig.MAX_HUD_TEXT_SIZE_SP,
                headRingRadiusRatio = TrackerConfig.MAX_HEAD_RING_RADIUS_RATIO,
            )

        assertThat(low.redrawDeadbandPx).isEqualTo(TrackerConfig.MIN_REDRAW_DEADBAND_PX)
        assertThat(high.headRingRadiusRatio).isEqualTo(TrackerConfig.MAX_HEAD_RING_RADIUS_RATIO)
    }

    @Test
    fun modelInputSize_defaultsToTheLargeSize() {
        assertThat(TrackerConfig().modelInputSize).isEqualTo(416)
        assertThat(TrackerConfig.DEFAULT_MODEL_INPUT_SIZE).isEqualTo(TrackerConfig.MODEL_INPUT_SIZE_LARGE)
    }

    @Test
    fun modelInputSize_smallAndLarge_areAccepted() {
        assertThat(TrackerConfig(modelInputSize = TrackerConfig.MODEL_INPUT_SIZE_SMALL).modelInputSize)
            .isEqualTo(320)
        assertThat(TrackerConfig(modelInputSize = TrackerConfig.MODEL_INPUT_SIZE_LARGE).modelInputSize)
            .isEqualTo(416)
    }

    @Test
    fun modelInputSize_anyOtherValue_isRejected() {
        for (size in intArrayOf(-416, 0, 319, 321, 352, 415, 417, 640)) {
            assertThrows(IllegalArgumentException::class.java) {
                TrackerConfig(modelInputSize = size)
            }
        }
    }

    @Test
    fun jsonAndDetectionLimits_haveTheDocumentedValues() {
        assertThat(TrackerConfig.MAX_DETECTIONS_PER_FRAME).isEqualTo(30)
        assertThat(TrackerConfig.JSON_MAX_DEPTH).isEqualTo(32)
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
