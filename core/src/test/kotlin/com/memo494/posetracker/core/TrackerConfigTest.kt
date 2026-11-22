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
        assertThat(config.minHeadRingRadiusDp).isEqualTo(12f)
        assertThat(config.boxCornerRatio).isEqualTo(0.18f)
        assertThat(config.debugMarks).isFalse()
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
    fun hudIdleFps_isTheHudRatePlusTheMargin() {
        assertThat(TrackerConfig().hudIdleFps).isEqualTo(4f)
        assertThat(TrackerConfig(hudRateHz = 5f).hudIdleFps).isEqualTo(5f + TrackerConfig.HUD_IDLE_FPS_MARGIN)
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
    fun minHeadRingRadiusDp_negativeOrAboveTheMaximum_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(minHeadRingRadiusDp = -0.5f) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(minHeadRingRadiusDp = TrackerConfig.MAX_MIN_HEAD_RING_RADIUS_DP + 1f)
        }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(minHeadRingRadiusDp = Float.NaN) }
    }

    @Test
    fun minHeadRingRadiusDp_atItsBounds_isAccepted() {
        assertThat(TrackerConfig(minHeadRingRadiusDp = 0f).minHeadRingRadiusDp).isEqualTo(0f)
        val high = TrackerConfig(minHeadRingRadiusDp = TrackerConfig.MAX_MIN_HEAD_RING_RADIUS_DP)
        assertThat(high.minHeadRingRadiusDp).isEqualTo(TrackerConfig.MAX_MIN_HEAD_RING_RADIUS_DP)
    }

    @Test
    fun boxCornerRatio_zeroNegativeHalfOrMore_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(boxCornerRatio = 0f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(boxCornerRatio = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(boxCornerRatio = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(boxCornerRatio = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(boxCornerRatio = Float.NaN) }
    }

    @Test
    fun boxCornerRatio_justInsideTheOpenInterval_isAccepted() {
        assertThat(TrackerConfig(boxCornerRatio = 0.01f).boxCornerRatio).isEqualTo(0.01f)
        assertThat(TrackerConfig(boxCornerRatio = 0.49f).boxCornerRatio).isEqualTo(0.49f)
    }

    @Test
    fun debugMarks_canBeTurnedOn() {
        assertThat(TrackerConfig(debugMarks = true).debugMarks).isTrue()
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
    fun importLimits_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.importMinBytes).isEqualTo(100_000L)
        assertThat(config.importMaxBytes).isEqualTo(200L * 1024L * 1024L)
        assertThat(config.importMinInputSize).isEqualTo(160)
        assertThat(config.importMaxInputSize).isEqualTo(1280)
        assertThat(config.importMaxInputAspect).isEqualTo(4.0f)
        assertThat(TrackerConfig.IMPORT_INPUT_SIZE_STEP).isEqualTo(32)
    }

    @Test
    fun importMaxInputAspect_atItsBounds_isAccepted() {
        val low = TrackerConfig(importMaxInputAspect = TrackerConfig.MIN_IMPORT_MAX_INPUT_ASPECT_LIMIT)
        val high = TrackerConfig(importMaxInputAspect = TrackerConfig.MAX_IMPORT_MAX_INPUT_ASPECT_LIMIT)

        assertThat(low.importMaxInputAspect).isEqualTo(1.0f)
        assertThat(high.importMaxInputAspect).isEqualTo(16.0f)
    }

    @Test
    fun importMaxInputAspect_outsideItsBounds_isRejected() {
        for (value in listOf(0.99f, 0.0f, -4.0f, 16.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMaxInputAspect = value) }
        }
    }

    @Test
    fun timingWindowFrames_hasTheDocumentedDefaultAndBounds() {
        assertThat(TrackerConfig().timingWindowFrames).isEqualTo(512)
        assertThat(TrackerConfig.MIN_TIMING_WINDOW_FRAMES).isEqualTo(16)
        assertThat(TrackerConfig.MAX_TIMING_WINDOW_FRAMES).isEqualTo(4096)
        assertThat(TrackerConfig(timingWindowFrames = 16).timingWindowFrames).isEqualTo(16)
        assertThat(TrackerConfig(timingWindowFrames = 4096).timingWindowFrames).isEqualTo(4096)
    }

    @Test
    fun timingWindowFrames_outsideItsBounds_isRejected() {
        for (value in listOf(15, 0, -1, 4097)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(timingWindowFrames = value) }
        }
    }

    @Test
    fun thermalSampleIntervalMs_hasTheDocumentedDefaultAndBounds() {
        val config = TrackerConfig()

        assertThat(config.thermalSampleIntervalMs).isEqualTo(2000L)
        assertThat(config.thermalSampleIntervalNs).isEqualTo(2_000_000_000L)
        assertThat(TrackerConfig.MIN_THERMAL_SAMPLE_INTERVAL_MS).isEqualTo(1000L)
        assertThat(TrackerConfig(thermalSampleIntervalMs = 1000L).thermalSampleIntervalMs).isEqualTo(1000L)
        assertThat(TrackerConfig(thermalSampleIntervalMs = TrackerConfig.MAX_THERMAL_SAMPLE_INTERVAL_MS).thermalSampleIntervalMs)
            .isEqualTo(TrackerConfig.MAX_THERMAL_SAMPLE_INTERVAL_MS)
    }

    @Test
    fun thermalSampleIntervalMs_outsideItsBounds_isRejected() {
        for (value in listOf(999L, 0L, -1L, TrackerConfig.MAX_THERMAL_SAMPLE_INTERVAL_MS + 1L)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalSampleIntervalMs = value) }
        }
    }

    @Test
    fun thermalStepDown_hasTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.thermalHotHeadroom).isEqualTo(0.9f)
        assertThat(config.thermalCoolHeadroom).isEqualTo(0.75f)
        assertThat(config.thermalHotConfirmMs).isEqualTo(3_000L)
        assertThat(config.thermalHotConfirmNs).isEqualTo(3_000_000_000L)
        assertThat(config.thermalCoolDownMs).isEqualTo(60_000L)
        assertThat(config.thermalCoolDownNs).isEqualTo(60_000_000_000L)
        assertThat(TrackerConfig.THERMAL_STATUS_HOT_MIN).isEqualTo(2)
        assertThat(TrackerConfig.THERMAL_STATUS_COOL_MAX).isEqualTo(1)
    }

    @Test
    fun thermalStepDown_atItsBounds_isAccepted() {
        TrackerConfig(thermalHotHeadroom = TrackerConfig.MAX_THERMAL_HEADROOM, thermalCoolHeadroom = TrackerConfig.MIN_THERMAL_HEADROOM)
        TrackerConfig(thermalHotConfirmMs = TrackerConfig.MIN_THERMAL_HOT_CONFIRM_MS)
        TrackerConfig(thermalHotConfirmMs = TrackerConfig.MAX_THERMAL_HOT_CONFIRM_MS)
        TrackerConfig(thermalCoolDownMs = TrackerConfig.MIN_THERMAL_COOL_DOWN_MS)
        TrackerConfig(thermalCoolDownMs = TrackerConfig.MAX_THERMAL_COOL_DOWN_MS)
    }

    @Test
    fun thermalStepDown_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotHeadroom = 1.51f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalCoolHeadroom = 0.29f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotHeadroom = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotConfirmMs = 999L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotConfirmMs = 60_001L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalCoolDownMs = 4_999L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalCoolDownMs = 600_001L) }
    }

    @Test
    fun thermalCoolHeadroom_notBelowTheHotHeadroom_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotHeadroom = 0.8f, thermalCoolHeadroom = 0.8f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(thermalHotHeadroom = 0.8f, thermalCoolHeadroom = 0.9f) }
    }

    @Test
    fun toString_listsTheTimingAndThermalFields_forConfigTxt() {
        val text = TrackerConfig().toString()

        assertThat(text).contains("timingWindowFrames=512")
        assertThat(text).contains("thermalSampleIntervalMs=2000")
        assertThat(text).contains("thermalHotHeadroom=0.9")
        assertThat(text).contains("thermalCoolDownMs=60000")
    }

    @Test
    fun importLimits_doNotChangeTheBuiltInModelSizes() {
        assertThat(TrackerConfig().modelInputSize).isEqualTo(416)
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(modelInputSize = 640) }
    }

    @Test
    fun importLimits_atTheirBounds_areAccepted() {
        val low =
            TrackerConfig(
                importMinBytes = TrackerConfig.MIN_IMPORT_BYTES_LIMIT,
                importMaxBytes = TrackerConfig.MIN_IMPORT_BYTES_LIMIT,
                importMinInputSize = TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT,
                importMaxInputSize = TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT,
            )
        val high =
            TrackerConfig(
                importMinBytes = TrackerConfig.MAX_IMPORT_BYTES_LIMIT,
                importMaxBytes = TrackerConfig.MAX_IMPORT_BYTES_LIMIT,
                importMinInputSize = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
                importMaxInputSize = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
            )

        assertThat(low.importMaxInputSize).isEqualTo(TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT)
        assertThat(high.importMaxBytes).isEqualTo(TrackerConfig.MAX_IMPORT_BYTES_LIMIT)
    }

    @Test
    fun importBytes_outsideTheirBounds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinBytes = -1L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMaxBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(importMaxBytes = TrackerConfig.MAX_IMPORT_BYTES_LIMIT + 1L)
        }
    }

    @Test
    fun importBytes_minAboveMax_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinBytes = 200L, importMaxBytes = 199L) }
    }

    @Test
    fun importInputSize_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinInputSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinInputSize = -32) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(importMaxInputSize = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT + TrackerConfig.IMPORT_INPUT_SIZE_STEP)
        }
    }

    @Test
    fun importInputSize_notAMultipleOfTheStep_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinInputSize = 161) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMaxInputSize = 1281) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(importMinInputSize = 100, importMaxInputSize = 1280) }
    }

    @Test
    fun importInputSize_minAboveMax_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(importMinInputSize = 640, importMaxInputSize = 608)
        }
    }

    @Test
    fun poseOutputColumns_are4Plus1Plus1Plus17Times3() {
        assertThat(TrackerConfig.POSE_OUTPUT_COLUMNS).isEqualTo(4 + 1 + 1 + 17 * 3)
    }

    @Test
    fun jsonAndDetectionLimits_haveTheDocumentedValues() {
        assertThat(TrackerConfig.MAX_DETECTIONS_PER_FRAME).isEqualTo(30)
        assertThat(TrackerConfig.JSON_MAX_DEPTH).isEqualTo(32)
    }

    @Test
    fun headPointFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.headKeypointEnterScore).isEqualTo(0.5f)
        assertThat(config.headKeypointLeaveScore).isEqualTo(0.3f)
        assertThat(config.headOffsetAlpha).isEqualTo(0.2f)
        assertThat(config.headFallbackTopRatio).isEqualTo(0.10f)
        assertThat(config.headMeasurementNoiseRatio).isEqualTo(0.03f)
        assertThat(config.headFallbackNoiseRatio).isEqualTo(0.10f)
        assertThat(config.headMinMeanConfidence).isEqualTo(0.05f)
    }

    @Test
    fun headPointFields_defaultsAreWithinTheirBounds() {
        val config = TrackerConfig()

        assertWithin(config.headKeypointEnterScore, TrackerConfig.MIN_HEAD_KEYPOINT_SCORE, TrackerConfig.MAX_HEAD_KEYPOINT_SCORE)
        assertWithin(config.headKeypointLeaveScore, TrackerConfig.MIN_HEAD_KEYPOINT_SCORE, TrackerConfig.MAX_HEAD_KEYPOINT_SCORE)
        assertWithin(config.headOffsetAlpha, TrackerConfig.MIN_HEAD_OFFSET_ALPHA, TrackerConfig.MAX_HEAD_OFFSET_ALPHA)
        assertWithin(config.headFallbackTopRatio, TrackerConfig.MIN_HEAD_FALLBACK_TOP_RATIO, TrackerConfig.MAX_HEAD_FALLBACK_TOP_RATIO)
        assertWithin(
            config.headMeasurementNoiseRatio,
            TrackerConfig.MIN_HEAD_MEASUREMENT_NOISE_RATIO,
            TrackerConfig.MAX_HEAD_MEASUREMENT_NOISE_RATIO,
        )
        assertWithin(
            config.headFallbackNoiseRatio,
            TrackerConfig.MIN_HEAD_FALLBACK_NOISE_RATIO,
            TrackerConfig.MAX_HEAD_FALLBACK_NOISE_RATIO,
        )
        assertWithin(
            config.headMinMeanConfidence,
            TrackerConfig.MIN_HEAD_MIN_MEAN_CONFIDENCE,
            TrackerConfig.MAX_HEAD_MIN_MEAN_CONFIDENCE,
        )
    }

    @Test
    fun headPointFields_atTheirBounds_areAccepted() {
        val low =
            TrackerConfig(
                headKeypointEnterScore = TrackerConfig.MIN_HEAD_KEYPOINT_SCORE,
                headKeypointLeaveScore = TrackerConfig.MIN_HEAD_KEYPOINT_SCORE,
                headOffsetAlpha = TrackerConfig.MIN_HEAD_OFFSET_ALPHA,
                headFallbackTopRatio = TrackerConfig.MIN_HEAD_FALLBACK_TOP_RATIO,
                headMeasurementNoiseRatio = TrackerConfig.MIN_HEAD_MEASUREMENT_NOISE_RATIO,
                headFallbackNoiseRatio = TrackerConfig.MIN_HEAD_FALLBACK_NOISE_RATIO,
                headMinMeanConfidence = TrackerConfig.MIN_HEAD_MIN_MEAN_CONFIDENCE,
            )
        val high =
            TrackerConfig(
                headKeypointEnterScore = TrackerConfig.MAX_HEAD_KEYPOINT_SCORE,
                headKeypointLeaveScore = TrackerConfig.MAX_HEAD_KEYPOINT_SCORE,
                headOffsetAlpha = TrackerConfig.MAX_HEAD_OFFSET_ALPHA,
                headFallbackTopRatio = TrackerConfig.MAX_HEAD_FALLBACK_TOP_RATIO,
                headMeasurementNoiseRatio = TrackerConfig.MAX_HEAD_MEASUREMENT_NOISE_RATIO,
                headFallbackNoiseRatio = TrackerConfig.MAX_HEAD_FALLBACK_NOISE_RATIO,
                headMinMeanConfidence = TrackerConfig.MAX_HEAD_MIN_MEAN_CONFIDENCE,
            )

        assertThat(low.headOffsetAlpha).isEqualTo(TrackerConfig.MIN_HEAD_OFFSET_ALPHA)
        assertThat(high.headOffsetAlpha).isEqualTo(1.0f)
    }

    @Test
    fun headPointFields_outsideTheirBounds_areRejected() {
        val rejected =
            listOf<() -> TrackerConfig>(
                { TrackerConfig(headKeypointEnterScore = TrackerConfig.MAX_HEAD_KEYPOINT_SCORE + 0.01f) },
                { TrackerConfig(headKeypointEnterScore = TrackerConfig.MIN_HEAD_KEYPOINT_SCORE - 0.01f) },
                { TrackerConfig(headKeypointLeaveScore = TrackerConfig.MIN_HEAD_KEYPOINT_SCORE - 0.01f) },
                { TrackerConfig(headOffsetAlpha = 0.0f) },
                { TrackerConfig(headOffsetAlpha = 1.01f) },
                { TrackerConfig(headFallbackTopRatio = -0.01f) },
                { TrackerConfig(headFallbackTopRatio = TrackerConfig.MAX_HEAD_FALLBACK_TOP_RATIO + 0.01f) },
                { TrackerConfig(headMeasurementNoiseRatio = 0.0f) },
                { TrackerConfig(headMeasurementNoiseRatio = TrackerConfig.MAX_HEAD_MEASUREMENT_NOISE_RATIO + 0.01f) },
                { TrackerConfig(headFallbackNoiseRatio = 0.0f) },
                { TrackerConfig(headFallbackNoiseRatio = TrackerConfig.MAX_HEAD_FALLBACK_NOISE_RATIO + 0.01f) },
                { TrackerConfig(headMinMeanConfidence = 0.0f) },
                { TrackerConfig(headMinMeanConfidence = TrackerConfig.MAX_HEAD_MIN_MEAN_CONFIDENCE + 0.01f) },
            )

        for (build in rejected) {
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun headPointFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headKeypointEnterScore = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headKeypointLeaveScore = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headOffsetAlpha = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headFallbackTopRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headMeasurementNoiseRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headFallbackNoiseRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(headMinMeanConfidence = value) }
        }
    }

    @Test
    fun headKeypointLeaveScore_aboveTheEnterScore_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(headKeypointEnterScore = 0.4f, headKeypointLeaveScore = 0.41f)
        }
        assertThat(TrackerConfig(headKeypointEnterScore = 0.4f, headKeypointLeaveScore = 0.4f).headKeypointLeaveScore)
            .isEqualTo(0.4f)
    }

    @Test
    fun associationFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.maxTracks).isEqualTo(32)
        assertThat(config.trackGateChi2).isEqualTo(9.21f)
        assertThat(config.assocIouWeight).isEqualTo(0.5f)
        assertThat(config.assocHeadWeight).isEqualTo(0.5f)
        assertThat(config.assocHeadScaleRatio).isEqualTo(0.5f)
        assertThat(config.dedupeIou).isEqualTo(0.7f)
    }

    @Test
    fun associationFields_defaultsAreWithinTheirBounds() {
        val config = TrackerConfig()

        assertThat(config.maxTracks).isAtLeast(TrackerConfig.MIN_MAX_TRACKS)
        assertThat(config.maxTracks).isAtMost(TrackerConfig.MAX_MAX_TRACKS)
        assertWithin(config.trackGateChi2, TrackerConfig.MIN_TRACK_GATE_CHI2, TrackerConfig.MAX_TRACK_GATE_CHI2)
        assertWithin(config.assocIouWeight, TrackerConfig.MIN_ASSOC_WEIGHT, TrackerConfig.MAX_ASSOC_WEIGHT)
        assertWithin(config.assocHeadWeight, TrackerConfig.MIN_ASSOC_WEIGHT, TrackerConfig.MAX_ASSOC_WEIGHT)
        assertWithin(
            config.assocHeadScaleRatio,
            TrackerConfig.MIN_ASSOC_HEAD_SCALE_RATIO,
            TrackerConfig.MAX_ASSOC_HEAD_SCALE_RATIO,
        )
        assertWithin(config.dedupeIou, TrackerConfig.MIN_DEDUPE_IOU, TrackerConfig.MAX_DEDUPE_IOU)
    }

    @Test
    fun associationFields_atTheirBounds_areAccepted() {
        val low =
            TrackerConfig(
                maxTracks = TrackerConfig.MIN_MAX_TRACKS,
                trackGateChi2 = TrackerConfig.MIN_TRACK_GATE_CHI2,
                assocIouWeight = TrackerConfig.MIN_ASSOC_WEIGHT,
                assocHeadWeight = TrackerConfig.MIN_ASSOC_WEIGHT,
                assocHeadScaleRatio = TrackerConfig.MIN_ASSOC_HEAD_SCALE_RATIO,
                dedupeIou = TrackerConfig.MIN_DEDUPE_IOU,
            )
        val high =
            TrackerConfig(
                maxTracks = TrackerConfig.MAX_MAX_TRACKS,
                trackGateChi2 = TrackerConfig.MAX_TRACK_GATE_CHI2,
                assocIouWeight = TrackerConfig.MAX_ASSOC_WEIGHT,
                assocHeadWeight = TrackerConfig.MAX_ASSOC_WEIGHT,
                assocHeadScaleRatio = TrackerConfig.MAX_ASSOC_HEAD_SCALE_RATIO,
                dedupeIou = TrackerConfig.MAX_DEDUPE_IOU,
            )

        assertThat(low.maxTracks).isEqualTo(TrackerConfig.MIN_MAX_TRACKS)
        assertThat(high.maxTracks).isEqualTo(TrackerConfig.MAX_MAX_TRACKS)
        assertThat(high.dedupeIou).isEqualTo(TrackerConfig.MAX_DEDUPE_IOU)
    }

    @Test
    fun associationFields_outsideTheirBounds_areRejected() {
        val rejected =
            listOf<() -> TrackerConfig>(
                { TrackerConfig(maxTracks = TrackerConfig.MIN_MAX_TRACKS - 1) },
                { TrackerConfig(maxTracks = TrackerConfig.MAX_MAX_TRACKS + 1) },
                { TrackerConfig(trackGateChi2 = TrackerConfig.MIN_TRACK_GATE_CHI2 - 0.01f) },
                { TrackerConfig(trackGateChi2 = TrackerConfig.MAX_TRACK_GATE_CHI2 + 0.01f) },
                { TrackerConfig(assocIouWeight = 0.0f) },
                { TrackerConfig(assocIouWeight = TrackerConfig.MAX_ASSOC_WEIGHT + 0.01f) },
                { TrackerConfig(assocHeadWeight = 0.0f) },
                { TrackerConfig(assocHeadWeight = TrackerConfig.MAX_ASSOC_WEIGHT + 0.01f) },
                { TrackerConfig(assocHeadScaleRatio = 0.0f) },
                { TrackerConfig(assocHeadScaleRatio = TrackerConfig.MAX_ASSOC_HEAD_SCALE_RATIO + 0.01f) },
                { TrackerConfig(dedupeIou = TrackerConfig.MIN_DEDUPE_IOU - 0.01f) },
                { TrackerConfig(dedupeIou = TrackerConfig.MAX_DEDUPE_IOU + 0.01f) },
            )

        for (build in rejected) {
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun associationFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackGateChi2 = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(assocIouWeight = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(assocHeadWeight = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(assocHeadScaleRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(dedupeIou = value) }
        }
    }

    @Test
    fun trackGateMinSigmaRatio_defaultsToTwoTenths_andAcceptsItsBounds() {
        assertThat(TrackerConfig().trackGateMinSigmaRatio).isEqualTo(0.2f)
        assertThat(TrackerConfig.MIN_TRACK_GATE_MIN_SIGMA_RATIO).isEqualTo(0f)
        assertThat(TrackerConfig.MAX_TRACK_GATE_MIN_SIGMA_RATIO).isEqualTo(1f)
        assertThat(TrackerConfig(trackGateMinSigmaRatio = TrackerConfig.MIN_TRACK_GATE_MIN_SIGMA_RATIO).trackGateMinSigmaRatio)
            .isEqualTo(0f)
        assertThat(TrackerConfig(trackGateMinSigmaRatio = TrackerConfig.MAX_TRACK_GATE_MIN_SIGMA_RATIO).trackGateMinSigmaRatio)
            .isEqualTo(1f)
    }

    @Test
    fun trackGateMinSigmaRatio_outsideItsBoundsOrNotFinite_isRejected() {
        val bad =
            listOf(
                TrackerConfig.MIN_TRACK_GATE_MIN_SIGMA_RATIO - 0.01f,
                TrackerConfig.MAX_TRACK_GATE_MIN_SIGMA_RATIO + 0.01f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )
        for (value in bad) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackGateMinSigmaRatio = value) }
        }
    }

    @Test
    fun relockEarlyMaxHeadRatio_defaultsToSixTenths_andAcceptsItsBounds() {
        assertThat(TrackerConfig().relockEarlyMaxHeadRatio).isEqualTo(0.6f)
        assertWithin(
            TrackerConfig().relockEarlyMaxHeadRatio,
            TrackerConfig.MIN_RELOCK_EARLY_MAX_HEAD_RATIO,
            TrackerConfig.MAX_RELOCK_EARLY_MAX_HEAD_RATIO,
        )
        TrackerConfig(relockEarlyMaxHeadRatio = TrackerConfig.MIN_RELOCK_EARLY_MAX_HEAD_RATIO)
        TrackerConfig(relockEarlyMaxHeadRatio = TrackerConfig.MAX_RELOCK_EARLY_MAX_HEAD_RATIO)
    }

    @Test
    fun relockEarlyMaxHeadRatio_outsideItsBoundsOrNotFinite_isRejected() {
        val bad =
            listOf(
                TrackerConfig.MIN_RELOCK_EARLY_MAX_HEAD_RATIO - 0.01f,
                TrackerConfig.MAX_RELOCK_EARLY_MAX_HEAD_RATIO + 0.01f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )
        for (value in bad) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockEarlyMaxHeadRatio = value) }
        }
    }

    @Test
    fun trackTentativeMaxMisses_defaultsToOne_andAcceptsItsBounds() {
        assertThat(TrackerConfig().trackTentativeMaxMisses).isEqualTo(1)
        assertThat(TrackerConfig.DEFAULT_TRACK_TENTATIVE_MAX_MISSES).isAtLeast(TrackerConfig.MIN_TRACK_TENTATIVE_MAX_MISSES)
        assertThat(TrackerConfig.DEFAULT_TRACK_TENTATIVE_MAX_MISSES).isAtMost(TrackerConfig.MAX_TRACK_TENTATIVE_MAX_MISSES)
        assertThat(TrackerConfig.MIN_TRACK_TENTATIVE_MAX_MISSES).isEqualTo(0)
        assertThat(TrackerConfig.MAX_TRACK_TENTATIVE_MAX_MISSES).isEqualTo(5)
        assertThat(TrackerConfig(trackTentativeMaxMisses = 0).trackTentativeMaxMisses).isEqualTo(0)
        assertThat(TrackerConfig(trackTentativeMaxMisses = 5).trackTentativeMaxMisses).isEqualTo(5)
    }

    @Test
    fun trackTentativeMaxMisses_outsideItsBounds_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(trackTentativeMaxMisses = TrackerConfig.MIN_TRACK_TENTATIVE_MAX_MISSES - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(trackTentativeMaxMisses = TrackerConfig.MAX_TRACK_TENTATIVE_MAX_MISSES + 1)
        }
    }

    @Test
    fun assocMissFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.assocMissNearRatio).isEqualTo(1.0f)
        assertThat(config.assocMissLogPerMin).isEqualTo(30)
        assertWithin(
            config.assocMissNearRatio,
            TrackerConfig.MIN_ASSOC_MISS_NEAR_RATIO,
            TrackerConfig.MAX_ASSOC_MISS_NEAR_RATIO,
        )
        assertThat(config.assocMissLogPerMin).isAtLeast(TrackerConfig.MIN_ASSOC_MISS_LOG_PER_MIN)
        assertThat(config.assocMissLogPerMin).isAtMost(TrackerConfig.MAX_ASSOC_MISS_LOG_PER_MIN)
    }

    @Test
    fun assocMissFields_atTheirBounds_areAccepted() {
        TrackerConfig(
            assocMissNearRatio = TrackerConfig.MIN_ASSOC_MISS_NEAR_RATIO,
            assocMissLogPerMin = TrackerConfig.MIN_ASSOC_MISS_LOG_PER_MIN,
        )
        TrackerConfig(
            assocMissNearRatio = TrackerConfig.MAX_ASSOC_MISS_NEAR_RATIO,
            assocMissLogPerMin = TrackerConfig.MAX_ASSOC_MISS_LOG_PER_MIN,
        )
    }

    @Test
    fun assocMissFields_outsideTheirBounds_areRejected() {
        val rejected: List<() -> TrackerConfig> =
            listOf(
                { TrackerConfig(assocMissNearRatio = TrackerConfig.MIN_ASSOC_MISS_NEAR_RATIO / 2f) },
                { TrackerConfig(assocMissNearRatio = TrackerConfig.MAX_ASSOC_MISS_NEAR_RATIO * 2f) },
                { TrackerConfig(assocMissNearRatio = Float.NaN) },
                { TrackerConfig(assocMissLogPerMin = TrackerConfig.MIN_ASSOC_MISS_LOG_PER_MIN - 1) },
                { TrackerConfig(assocMissLogPerMin = TrackerConfig.MAX_ASSOC_MISS_LOG_PER_MIN + 1) },
            )
        for (build in rejected) assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun smoothFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.smoothEnabled).isTrue()
        assertThat(config.smoothMinCutoffHz).isEqualTo(1.0f)
        assertThat(config.smoothBeta).isEqualTo(3.0f)
        assertThat(config.smoothDerivativeCutoffHz).isEqualTo(1.0f)
    }

    @Test
    fun smoothFields_acceptTheirBounds_andTheDefaultsLieInside() {
        assertWithin(TrackerConfig().smoothMinCutoffHz, TrackerConfig.MIN_SMOOTH_MIN_CUTOFF_HZ, TrackerConfig.MAX_SMOOTH_MIN_CUTOFF_HZ)
        assertWithin(TrackerConfig().smoothBeta, TrackerConfig.MIN_SMOOTH_BETA, TrackerConfig.MAX_SMOOTH_BETA)
        assertWithin(
            TrackerConfig().smoothDerivativeCutoffHz,
            TrackerConfig.MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ,
            TrackerConfig.MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ,
        )
        assertThat(TrackerConfig.MIN_SMOOTH_MIN_CUTOFF_HZ).isEqualTo(0.05f)
        assertThat(TrackerConfig.MAX_SMOOTH_MIN_CUTOFF_HZ).isEqualTo(30f)
        assertThat(TrackerConfig.MIN_SMOOTH_BETA).isEqualTo(0f)
        assertThat(TrackerConfig.MAX_SMOOTH_BETA).isEqualTo(100f)
        assertThat(TrackerConfig.MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ).isEqualTo(0.05f)
        assertThat(TrackerConfig.MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ).isEqualTo(30f)
        TrackerConfig(smoothMinCutoffHz = TrackerConfig.MIN_SMOOTH_MIN_CUTOFF_HZ, smoothBeta = TrackerConfig.MIN_SMOOTH_BETA)
        TrackerConfig(smoothMinCutoffHz = TrackerConfig.MAX_SMOOTH_MIN_CUTOFF_HZ, smoothBeta = TrackerConfig.MAX_SMOOTH_BETA)
        TrackerConfig(smoothDerivativeCutoffHz = TrackerConfig.MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ)
        TrackerConfig(smoothDerivativeCutoffHz = TrackerConfig.MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ)
    }

    @Test
    fun smoothFields_outsideTheirBounds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(smoothMinCutoffHz = TrackerConfig.MIN_SMOOTH_MIN_CUTOFF_HZ / 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(smoothMinCutoffHz = TrackerConfig.MAX_SMOOTH_MIN_CUTOFF_HZ + 1)
        }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(smoothBeta = -0.5f) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(smoothBeta = TrackerConfig.MAX_SMOOTH_BETA + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(smoothDerivativeCutoffHz = TrackerConfig.MIN_SMOOTH_DERIVATIVE_CUTOFF_HZ / 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(smoothDerivativeCutoffHz = TrackerConfig.MAX_SMOOTH_DERIVATIVE_CUTOFF_HZ + 1)
        }
    }

    @Test
    fun smoothFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(smoothMinCutoffHz = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(smoothBeta = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(smoothDerivativeCutoffHz = value) }
        }
    }

    @Test
    fun fadeFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.fadeMinAlpha).isEqualTo(0.25f)
        assertThat(config.fadeStepAlpha).isEqualTo(0.1f)
    }

    @Test
    fun fadeFields_acceptTheirBounds_andTheDefaultsLieInside() {
        assertWithin(TrackerConfig().fadeMinAlpha, TrackerConfig.MIN_FADE_MIN_ALPHA, TrackerConfig.MAX_FADE_MIN_ALPHA)
        assertWithin(TrackerConfig().fadeStepAlpha, TrackerConfig.MIN_FADE_STEP_ALPHA, TrackerConfig.MAX_FADE_STEP_ALPHA)
        assertThat(TrackerConfig.MIN_FADE_MIN_ALPHA).isEqualTo(0f)
        assertThat(TrackerConfig.MAX_FADE_MIN_ALPHA).isEqualTo(1f)
        assertThat(TrackerConfig.MIN_FADE_STEP_ALPHA).isEqualTo(0.01f)
        assertThat(TrackerConfig.MAX_FADE_STEP_ALPHA).isEqualTo(1f)
        TrackerConfig(fadeMinAlpha = TrackerConfig.MIN_FADE_MIN_ALPHA, fadeStepAlpha = TrackerConfig.MIN_FADE_STEP_ALPHA)
        TrackerConfig(fadeMinAlpha = TrackerConfig.MAX_FADE_MIN_ALPHA, fadeStepAlpha = TrackerConfig.MAX_FADE_STEP_ALPHA)
    }

    @Test
    fun fadeFields_outsideTheirBounds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeMinAlpha = -0.01f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeMinAlpha = 1.01f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeStepAlpha = TrackerConfig.MIN_FADE_STEP_ALPHA / 2) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeStepAlpha = 1.01f) }
    }

    @Test
    fun fadeFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeMinAlpha = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(fadeStepAlpha = value) }
        }
    }

    @Test
    fun lockFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.lockHandleSizeDp).isEqualTo(40f)
        assertThat(config.lockMaxTapDistanceRatio).isEqualTo(1f)
        assertThat(config.lockArmTimeoutMs).isEqualTo(5000L)
        assertThat(config.lockRingStrokeDp).isEqualTo(3f)
        assertThat(config.lockOuterRingRatio).isEqualTo(1.3f)
    }

    @Test
    fun lockArmTimeoutNs_isTheTimeoutInNanoseconds() {
        assertThat(TrackerConfig().lockArmTimeoutNs).isEqualTo(5_000_000_000L)
        assertThat(TrackerConfig(lockArmTimeoutMs = 1500L).lockArmTimeoutNs).isEqualTo(1_500_000_000L)
    }

    @Test
    fun lockFields_defaultsLieInsideTheirBounds() {
        val config = TrackerConfig()

        assertWithin(config.lockHandleSizeDp, TrackerConfig.MIN_LOCK_HANDLE_SIZE_DP, TrackerConfig.MAX_LOCK_HANDLE_SIZE_DP)
        assertWithin(
            config.lockMaxTapDistanceRatio,
            TrackerConfig.MIN_LOCK_MAX_TAP_DISTANCE_RATIO,
            TrackerConfig.MAX_LOCK_MAX_TAP_DISTANCE_RATIO,
        )
        assertThat(config.lockArmTimeoutMs).isAtLeast(TrackerConfig.MIN_LOCK_ARM_TIMEOUT_MS)
        assertThat(config.lockArmTimeoutMs).isAtMost(TrackerConfig.MAX_LOCK_ARM_TIMEOUT_MS)
        assertWithin(config.lockRingStrokeDp, TrackerConfig.MIN_STROKE_DP, TrackerConfig.MAX_STROKE_DP)
        assertWithin(config.lockOuterRingRatio, TrackerConfig.MIN_LOCK_OUTER_RING_RATIO, TrackerConfig.MAX_LOCK_OUTER_RING_RATIO)
    }

    @Test
    fun lockFields_acceptTheirBounds() {
        TrackerConfig(
            lockHandleSizeDp = TrackerConfig.MIN_LOCK_HANDLE_SIZE_DP,
            lockMaxTapDistanceRatio = TrackerConfig.MIN_LOCK_MAX_TAP_DISTANCE_RATIO,
            lockArmTimeoutMs = TrackerConfig.MIN_LOCK_ARM_TIMEOUT_MS,
            lockRingStrokeDp = TrackerConfig.MIN_STROKE_DP,
            lockOuterRingRatio = TrackerConfig.MIN_LOCK_OUTER_RING_RATIO,
        )
        TrackerConfig(
            lockHandleSizeDp = TrackerConfig.MAX_LOCK_HANDLE_SIZE_DP,
            lockMaxTapDistanceRatio = TrackerConfig.MAX_LOCK_MAX_TAP_DISTANCE_RATIO,
            lockArmTimeoutMs = TrackerConfig.MAX_LOCK_ARM_TIMEOUT_MS,
            lockRingStrokeDp = TrackerConfig.MAX_STROKE_DP,
            lockOuterRingRatio = TrackerConfig.MAX_LOCK_OUTER_RING_RATIO,
        )
    }

    @Test
    fun lockFields_outsideTheirBounds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockHandleSizeDp = TrackerConfig.MIN_LOCK_HANDLE_SIZE_DP - 1f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockHandleSizeDp = TrackerConfig.MAX_LOCK_HANDLE_SIZE_DP + 1f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockMaxTapDistanceRatio = 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(lockMaxTapDistanceRatio = TrackerConfig.MAX_LOCK_MAX_TAP_DISTANCE_RATIO + 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockArmTimeoutMs = TrackerConfig.MIN_LOCK_ARM_TIMEOUT_MS - 1L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockArmTimeoutMs = TrackerConfig.MAX_LOCK_ARM_TIMEOUT_MS + 1L) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockRingStrokeDp = TrackerConfig.MIN_STROKE_DP / 2f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockRingStrokeDp = TrackerConfig.MAX_STROKE_DP + 1f) }
        assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockOuterRingRatio = 1f) }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(lockOuterRingRatio = TrackerConfig.MAX_LOCK_OUTER_RING_RATIO + 0.1f)
        }
    }

    @Test
    fun lockFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockHandleSizeDp = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockMaxTapDistanceRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockRingStrokeDp = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(lockOuterRingRatio = value) }
        }
    }

    @Test
    fun relockFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.relockHueBins).isEqualTo(16)
        assertThat(config.relockSatBins).isEqualTo(4)
        assertThat(config.relockGreyBins).isEqualTo(4)
        assertThat(config.relockMinValue).isEqualTo(0.15f)
        assertThat(config.relockMinSaturation).isEqualTo(0.15f)
        assertThat(config.relockMaxSamplePixels).isEqualTo(2000)
        assertThat(config.relockSignatureAlpha).isEqualTo(0.1f)
        assertThat(config.relockTorsoShrink).isEqualTo(0.1f)
        assertThat(config.relockBoxTopRatio).isEqualTo(0.25f)
        assertThat(config.relockBoxBottomRatio).isEqualTo(0.6f)
        assertThat(config.relockBoxWidthRatio).isEqualTo(0.6f)
        assertThat(config.relockKeypointMinScore).isEqualTo(0.5f)
        assertThat(config.relockMemoryMs).isEqualTo(60_000L)
        assertThat(config.relockMaxDistance).isEqualTo(0.40f)
        assertThat(config.relockMinMargin).isEqualTo(0.10f)
        assertThat(config.relockMinSamplePixels).isEqualTo(150)
        assertThat(config.relockFullBodyMinAspect).isEqualTo(1.8f)
        assertThat(config.relockJudgeFrames).isEqualTo(10)
        assertThat(config.relockUpperTorsoRatio).isEqualTo(0.8f)
        assertThat(config.relockJudgeMaxFrames).isEqualTo(30)
        assertThat(config.relockMinSourceSamples).isEqualTo(3)
        assertThat(config.relockOccluderMaxOverlap).isEqualTo(0.25f)
    }

    @Test
    fun relockMemoryNs_isTheMemoryInNanoseconds() {
        assertThat(TrackerConfig().relockMemoryNs).isEqualTo(60_000_000_000L)
        assertThat(TrackerConfig(relockMemoryMs = 2500L).relockMemoryNs).isEqualTo(2_500_000_000L)
    }

    @Test
    fun relockFields_acceptTheirBounds() {
        TrackerConfig(
            relockHueBins = TrackerConfig.MIN_RELOCK_HUE_BINS,
            relockSatBins = TrackerConfig.MIN_RELOCK_SAT_BINS,
            relockGreyBins = TrackerConfig.MIN_RELOCK_GREY_BINS,
            relockMinValue = TrackerConfig.MIN_RELOCK_COLOUR_LIMIT,
            relockMinSaturation = TrackerConfig.MIN_RELOCK_COLOUR_LIMIT,
            relockMaxSamplePixels = TrackerConfig.MIN_RELOCK_SAMPLE_PIXELS,
            relockSignatureAlpha = TrackerConfig.MIN_RELOCK_SIGNATURE_ALPHA,
            relockTorsoShrink = 0f,
            relockBoxTopRatio = 0f,
            relockBoxBottomRatio = TrackerConfig.MIN_RELOCK_BOX_BOTTOM_RATIO + 0.1f,
            relockBoxWidthRatio = TrackerConfig.MIN_RELOCK_BOX_WIDTH_RATIO,
            relockKeypointMinScore = TrackerConfig.MIN_KEYPOINT_MIN_SCORE,
            relockMemoryMs = TrackerConfig.MIN_RELOCK_MEMORY_MS,
            relockMaxDistance = TrackerConfig.MIN_RELOCK_MAX_DISTANCE,
            relockMinMargin = 0f,
            relockMinSamplePixels = TrackerConfig.MIN_RELOCK_MIN_SAMPLE_PIXELS,
            relockFullBodyMinAspect = TrackerConfig.MIN_RELOCK_FULL_BODY_ASPECT,
            relockJudgeFrames = TrackerConfig.MIN_RELOCK_JUDGE_FRAMES,
            relockUpperTorsoRatio = TrackerConfig.MIN_RELOCK_UPPER_TORSO_RATIO,
            relockJudgeMaxFrames = TrackerConfig.MIN_RELOCK_JUDGE_FRAMES,
            relockMinSourceSamples = TrackerConfig.MIN_RELOCK_MIN_SOURCE_SAMPLES,
            relockOccluderMaxOverlap = TrackerConfig.MIN_RELOCK_OCCLUDER_MAX_OVERLAP,
        )
        TrackerConfig(
            relockHueBins = TrackerConfig.MAX_RELOCK_HUE_BINS,
            relockSatBins = TrackerConfig.MAX_RELOCK_SAT_BINS,
            relockGreyBins = TrackerConfig.MAX_RELOCK_GREY_BINS,
            relockMinValue = TrackerConfig.MAX_RELOCK_COLOUR_LIMIT,
            relockMinSaturation = TrackerConfig.MAX_RELOCK_COLOUR_LIMIT,
            relockMaxSamplePixels = TrackerConfig.MAX_RELOCK_SAMPLE_PIXELS,
            relockSignatureAlpha = TrackerConfig.MAX_RELOCK_SIGNATURE_ALPHA,
            relockTorsoShrink = TrackerConfig.MAX_RELOCK_TORSO_SHRINK,
            relockBoxTopRatio = TrackerConfig.MAX_RELOCK_BOX_TOP_RATIO,
            relockBoxBottomRatio = 1f,
            relockBoxWidthRatio = 1f,
            relockKeypointMinScore = TrackerConfig.MAX_KEYPOINT_MIN_SCORE,
            relockMemoryMs = TrackerConfig.MAX_RELOCK_MEMORY_MS,
            relockMaxDistance = TrackerConfig.MAX_RELOCK_MAX_DISTANCE,
            relockMinMargin = TrackerConfig.MAX_RELOCK_MIN_MARGIN,
            relockMinSamplePixels = TrackerConfig.MAX_RELOCK_MIN_SAMPLE_PIXELS,
            relockFullBodyMinAspect = TrackerConfig.MAX_RELOCK_FULL_BODY_ASPECT,
            relockJudgeFrames = TrackerConfig.MAX_RELOCK_JUDGE_FRAMES,
            relockUpperTorsoRatio = TrackerConfig.MAX_RELOCK_UPPER_TORSO_RATIO,
            relockJudgeMaxFrames = TrackerConfig.MAX_RELOCK_JUDGE_MAX_FRAMES,
            relockMinSourceSamples = TrackerConfig.MAX_RELOCK_MIN_SOURCE_SAMPLES,
            relockOccluderMaxOverlap = 1f,
        )
    }

    @Test
    fun relockFields_outsideTheirBounds_areRejected() {
        val rejected: List<() -> TrackerConfig> =
            listOf(
                { TrackerConfig(relockHueBins = TrackerConfig.MIN_RELOCK_HUE_BINS - 1) },
                { TrackerConfig(relockHueBins = TrackerConfig.MAX_RELOCK_HUE_BINS + 1) },
                { TrackerConfig(relockSatBins = 0) },
                { TrackerConfig(relockSatBins = TrackerConfig.MAX_RELOCK_SAT_BINS + 1) },
                { TrackerConfig(relockGreyBins = 0) },
                { TrackerConfig(relockGreyBins = TrackerConfig.MAX_RELOCK_GREY_BINS + 1) },
                { TrackerConfig(relockMinValue = 0f) },
                { TrackerConfig(relockMinValue = TrackerConfig.MAX_RELOCK_COLOUR_LIMIT + 0.1f) },
                { TrackerConfig(relockMinSaturation = 0f) },
                { TrackerConfig(relockMinSaturation = TrackerConfig.MAX_RELOCK_COLOUR_LIMIT + 0.1f) },
                { TrackerConfig(relockMaxSamplePixels = TrackerConfig.MIN_RELOCK_SAMPLE_PIXELS - 1) },
                { TrackerConfig(relockMaxSamplePixels = TrackerConfig.MAX_RELOCK_SAMPLE_PIXELS + 1) },
                { TrackerConfig(relockSignatureAlpha = 0f) },
                { TrackerConfig(relockSignatureAlpha = 1.01f) },
                { TrackerConfig(relockTorsoShrink = -0.01f) },
                { TrackerConfig(relockTorsoShrink = TrackerConfig.MAX_RELOCK_TORSO_SHRINK + 0.01f) },
                { TrackerConfig(relockBoxTopRatio = -0.01f) },
                { TrackerConfig(relockBoxTopRatio = TrackerConfig.MAX_RELOCK_BOX_TOP_RATIO + 0.01f) },
                { TrackerConfig(relockBoxBottomRatio = 1.01f) },
                { TrackerConfig(relockBoxBottomRatio = 0.05f) },
                { TrackerConfig(relockBoxTopRatio = 0.5f, relockBoxBottomRatio = 0.5f) },
                { TrackerConfig(relockBoxTopRatio = 0.7f, relockBoxBottomRatio = 0.6f) },
                { TrackerConfig(relockBoxWidthRatio = 0.05f) },
                { TrackerConfig(relockBoxWidthRatio = 1.01f) },
                { TrackerConfig(relockKeypointMinScore = TrackerConfig.MIN_KEYPOINT_MIN_SCORE / 2f) },
                { TrackerConfig(relockKeypointMinScore = 1f) },
                { TrackerConfig(relockMemoryMs = TrackerConfig.MIN_RELOCK_MEMORY_MS - 1L) },
                { TrackerConfig(relockMemoryMs = TrackerConfig.MAX_RELOCK_MEMORY_MS + 1L) },
                { TrackerConfig(relockMaxDistance = 0f) },
                { TrackerConfig(relockMaxDistance = 1.01f) },
                { TrackerConfig(relockMinMargin = -0.01f) },
                { TrackerConfig(relockMinMargin = 1.01f) },
                { TrackerConfig(relockMinSamplePixels = TrackerConfig.MIN_RELOCK_MIN_SAMPLE_PIXELS - 1) },
                { TrackerConfig(relockMinSamplePixels = TrackerConfig.MAX_RELOCK_MIN_SAMPLE_PIXELS + 1) },
                { TrackerConfig(relockMinSamplePixels = 500, relockMaxSamplePixels = 400) },
                { TrackerConfig(relockFullBodyMinAspect = 0.9f) },
                { TrackerConfig(relockFullBodyMinAspect = TrackerConfig.MAX_RELOCK_FULL_BODY_ASPECT + 0.1f) },
                { TrackerConfig(relockJudgeFrames = 0) },
                { TrackerConfig(relockJudgeFrames = TrackerConfig.MAX_RELOCK_JUDGE_FRAMES + 1) },
                { TrackerConfig(relockJudgeMaxFrames = 0) },
                { TrackerConfig(relockJudgeFrames = 20, relockJudgeMaxFrames = 19) },
                { TrackerConfig(relockMinSourceSamples = 0) },
                { TrackerConfig(relockMinSourceSamples = TrackerConfig.MAX_RELOCK_MIN_SOURCE_SAMPLES + 1) },
                { TrackerConfig(relockJudgeMaxFrames = TrackerConfig.MAX_RELOCK_JUDGE_MAX_FRAMES + 1) },
                { TrackerConfig(relockOccluderMaxOverlap = TrackerConfig.MIN_RELOCK_OCCLUDER_MAX_OVERLAP / 2f) },
                { TrackerConfig(relockOccluderMaxOverlap = 1.01f) },
                { TrackerConfig(relockOccluderMaxOverlap = Float.NaN) },
                { TrackerConfig(relockUpperTorsoRatio = TrackerConfig.MIN_RELOCK_UPPER_TORSO_RATIO / 2f) },
                { TrackerConfig(relockUpperTorsoRatio = TrackerConfig.MAX_RELOCK_UPPER_TORSO_RATIO + 0.1f) },
            )

        for (build in rejected) assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun relockMinSamplePixels_mustBeAtMostAQuarterOfTheMaximum() {
        val limit = 2000 / TrackerConfig.RELOCK_SAMPLE_GRID_SLACK

        TrackerConfig(relockMaxSamplePixels = 2000, relockMinSamplePixels = limit)
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(relockMaxSamplePixels = 2000, relockMinSamplePixels = limit + 1)
        }
        assertThat(TrackerConfig.RELOCK_SAMPLE_GRID_SLACK).isEqualTo(4)
    }

    @Test
    fun relockFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockMinValue = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockMinSaturation = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockSignatureAlpha = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockTorsoShrink = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockBoxTopRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockBoxBottomRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockBoxWidthRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockKeypointMinScore = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockMaxDistance = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockMinMargin = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockFullBodyMinAspect = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(relockUpperTorsoRatio = value) }
        }
    }

    @Test
    fun trackLifecycleFields_haveTheDocumentedDefaults() {
        val config = TrackerConfig()

        assertThat(config.trackHighScore).isEqualTo(0.5f)
        assertThat(config.trackBirthScore).isEqualTo(0.25f)
        assertThat(config.trackBirthSuppressIou).isEqualTo(0.5f)
        assertThat(config.trackBirthSuppressHeadRatio).isEqualTo(0.15f)
        assertThat(config.trackConfirmHits).isEqualTo(3)
        assertThat(config.trackIdlePromoteMs).isEqualTo(400L)
        assertThat(config.trackIdlePromoteNs).isEqualTo(400_000_000L)
        assertThat(config.trackMaxLostMs).isEqualTo(800f)
        assertThat(config.trackMaxDtMs).isEqualTo(250f)
        assertThat(config.trackGapNoiseInflate).isEqualTo(10f)
        assertThat(config.trackMissVelocityTauMs).isEqualTo(300f)
        assertThat(config.trackAccelSigmaRatio).isEqualTo(8f)
        assertThat(config.trackHeightAccelSigmaRatio).isEqualTo(2f)
        assertThat(config.trackHeightNoiseRatio).isEqualTo(0.05f)
        assertThat(config.trackInitVelocitySigmaRatio).isEqualTo(2f)
        assertThat(config.trackMatchMaxCost).isEqualTo(0.7f)
        assertThat(config.trackLowMatchMaxCost).isEqualTo(0.6f)
        assertThat(config.trackExitMarginRatio).isEqualTo(0.1f)
    }

    @Test
    fun trackLifecycleFields_defaultsAreWithinTheirBounds() {
        val config = TrackerConfig()

        assertWithin(config.trackHighScore, TrackerConfig.MIN_TRACK_SCORE, TrackerConfig.MAX_TRACK_SCORE)
        assertWithin(config.trackBirthScore, TrackerConfig.MIN_TRACK_SCORE, TrackerConfig.MAX_TRACK_SCORE)
        assertWithin(
            config.trackBirthSuppressIou,
            TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_IOU,
            TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_IOU,
        )
        assertWithin(
            config.trackBirthSuppressHeadRatio,
            TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO,
            TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO,
        )
        assertThat(config.trackConfirmHits).isAtLeast(TrackerConfig.MIN_TRACK_CONFIRM_HITS)
        assertThat(config.trackConfirmHits).isAtMost(TrackerConfig.MAX_TRACK_CONFIRM_HITS)
        assertThat(config.trackIdlePromoteMs).isAtLeast(TrackerConfig.MIN_TRACK_DURATION_MS_LONG)
        assertThat(config.trackIdlePromoteMs).isAtMost(TrackerConfig.MAX_TRACK_DURATION_MS_LONG)
        assertWithin(config.trackMaxLostMs, TrackerConfig.MIN_TRACK_DURATION_MS, TrackerConfig.MAX_TRACK_DURATION_MS)
        assertWithin(config.trackMaxDtMs, TrackerConfig.MIN_TRACK_MAX_DT_MS, TrackerConfig.MAX_TRACK_MAX_DT_MS)
        assertWithin(
            config.trackGapNoiseInflate,
            TrackerConfig.MIN_TRACK_GAP_NOISE_INFLATE,
            TrackerConfig.MAX_TRACK_GAP_NOISE_INFLATE,
        )
        assertWithin(config.trackMissVelocityTauMs, TrackerConfig.MIN_TRACK_DURATION_MS, TrackerConfig.MAX_TRACK_DURATION_MS)
        assertWithin(
            config.trackAccelSigmaRatio,
            TrackerConfig.MIN_TRACK_ACCEL_SIGMA_RATIO,
            TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO,
        )
        assertWithin(
            config.trackHeightAccelSigmaRatio,
            TrackerConfig.MIN_TRACK_ACCEL_SIGMA_RATIO,
            TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO,
        )
        assertWithin(
            config.trackHeightNoiseRatio,
            TrackerConfig.MIN_TRACK_HEIGHT_NOISE_RATIO,
            TrackerConfig.MAX_TRACK_HEIGHT_NOISE_RATIO,
        )
        assertWithin(
            config.trackInitVelocitySigmaRatio,
            TrackerConfig.MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO,
            TrackerConfig.MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO,
        )
        assertWithin(config.trackMatchMaxCost, TrackerConfig.MIN_TRACK_MATCH_MAX_COST, TrackerConfig.MAX_TRACK_MATCH_MAX_COST)
        assertWithin(config.trackLowMatchMaxCost, TrackerConfig.MIN_TRACK_MATCH_MAX_COST, TrackerConfig.MAX_TRACK_MATCH_MAX_COST)
        assertWithin(
            config.trackExitMarginRatio,
            TrackerConfig.MIN_TRACK_EXIT_MARGIN_RATIO,
            TrackerConfig.MAX_TRACK_EXIT_MARGIN_RATIO,
        )
    }

    @Test
    fun trackLifecycleFields_atTheirBounds_areAccepted() {
        val low =
            TrackerConfig(
                trackHighScore = TrackerConfig.MIN_TRACK_SCORE,
                trackBirthScore = TrackerConfig.MIN_TRACK_SCORE,
                trackBirthSuppressIou = TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_IOU,
                trackBirthSuppressHeadRatio = TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO,
                trackConfirmHits = TrackerConfig.MIN_TRACK_CONFIRM_HITS,
                trackIdlePromoteMs = TrackerConfig.MIN_TRACK_DURATION_MS_LONG,
                trackMaxLostMs = TrackerConfig.MIN_TRACK_DURATION_MS,
                trackMaxDtMs = TrackerConfig.MIN_TRACK_MAX_DT_MS,
                trackGapNoiseInflate = TrackerConfig.MIN_TRACK_GAP_NOISE_INFLATE,
                trackMissVelocityTauMs = TrackerConfig.MIN_TRACK_DURATION_MS,
                trackAccelSigmaRatio = TrackerConfig.MIN_TRACK_ACCEL_SIGMA_RATIO,
                trackHeightAccelSigmaRatio = TrackerConfig.MIN_TRACK_ACCEL_SIGMA_RATIO,
                trackHeightNoiseRatio = TrackerConfig.MIN_TRACK_HEIGHT_NOISE_RATIO,
                trackInitVelocitySigmaRatio = TrackerConfig.MIN_TRACK_INIT_VELOCITY_SIGMA_RATIO,
                trackMatchMaxCost = TrackerConfig.MIN_TRACK_MATCH_MAX_COST,
                trackLowMatchMaxCost = TrackerConfig.MIN_TRACK_MATCH_MAX_COST,
                trackExitMarginRatio = TrackerConfig.MIN_TRACK_EXIT_MARGIN_RATIO,
            )
        val high =
            TrackerConfig(
                trackHighScore = TrackerConfig.MAX_TRACK_SCORE,
                trackBirthScore = TrackerConfig.MAX_TRACK_SCORE,
                trackBirthSuppressIou = TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_IOU,
                trackBirthSuppressHeadRatio = TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO,
                trackConfirmHits = TrackerConfig.MAX_TRACK_CONFIRM_HITS,
                trackIdlePromoteMs = TrackerConfig.MAX_TRACK_DURATION_MS_LONG,
                trackMaxLostMs = TrackerConfig.MAX_TRACK_DURATION_MS,
                trackMaxDtMs = TrackerConfig.MAX_TRACK_MAX_DT_MS,
                trackGapNoiseInflate = TrackerConfig.MAX_TRACK_GAP_NOISE_INFLATE,
                trackMissVelocityTauMs = TrackerConfig.MAX_TRACK_DURATION_MS,
                trackAccelSigmaRatio = TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO,
                trackHeightAccelSigmaRatio = TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO,
                trackHeightNoiseRatio = TrackerConfig.MAX_TRACK_HEIGHT_NOISE_RATIO,
                trackInitVelocitySigmaRatio = TrackerConfig.MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO,
                trackMatchMaxCost = TrackerConfig.MAX_TRACK_MATCH_MAX_COST,
                trackLowMatchMaxCost = TrackerConfig.MAX_TRACK_MATCH_MAX_COST,
                trackExitMarginRatio = TrackerConfig.MAX_TRACK_EXIT_MARGIN_RATIO,
            )

        assertThat(low.trackConfirmHits).isEqualTo(TrackerConfig.MIN_TRACK_CONFIRM_HITS)
        assertThat(high.trackConfirmHits).isEqualTo(TrackerConfig.MAX_TRACK_CONFIRM_HITS)
        assertThat(low.trackMaxDtMs).isEqualTo(TrackerConfig.MIN_TRACK_MAX_DT_MS)
        assertThat(high.trackMatchMaxCost).isEqualTo(TrackerConfig.MAX_TRACK_MATCH_MAX_COST)
        assertThat(high.trackIdlePromoteMs).isEqualTo(TrackerConfig.MAX_TRACK_DURATION_MS_LONG)
    }

    @Test
    fun trackLifecycleFields_outsideTheirBounds_areRejected() {
        val rejected =
            listOf<() -> TrackerConfig>(
                { TrackerConfig(trackHighScore = TrackerConfig.MIN_TRACK_SCORE - 0.01f) },
                { TrackerConfig(trackHighScore = TrackerConfig.MAX_TRACK_SCORE + 0.01f) },
                { TrackerConfig(trackBirthScore = TrackerConfig.MIN_TRACK_SCORE - 0.01f) },
                { TrackerConfig(trackBirthScore = TrackerConfig.MAX_TRACK_SCORE + 0.01f) },
                { TrackerConfig(trackBirthSuppressIou = TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_IOU - 0.01f) },
                { TrackerConfig(trackBirthSuppressIou = TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_IOU + 0.01f) },
                { TrackerConfig(trackBirthSuppressHeadRatio = TrackerConfig.MIN_TRACK_BIRTH_SUPPRESS_HEAD_RATIO - 0.01f) },
                { TrackerConfig(trackBirthSuppressHeadRatio = TrackerConfig.MAX_TRACK_BIRTH_SUPPRESS_HEAD_RATIO + 0.01f) },
                { TrackerConfig(trackConfirmHits = TrackerConfig.MIN_TRACK_CONFIRM_HITS - 1) },
                { TrackerConfig(trackConfirmHits = TrackerConfig.MAX_TRACK_CONFIRM_HITS + 1) },
                { TrackerConfig(trackIdlePromoteMs = TrackerConfig.MIN_TRACK_DURATION_MS_LONG - 1L) },
                { TrackerConfig(trackIdlePromoteMs = TrackerConfig.MAX_TRACK_DURATION_MS_LONG + 1L) },
                { TrackerConfig(trackMaxLostMs = 0f) },
                { TrackerConfig(trackMaxLostMs = TrackerConfig.MAX_TRACK_DURATION_MS + 1f) },
                { TrackerConfig(trackMaxDtMs = TrackerConfig.MIN_TRACK_MAX_DT_MS - 1f) },
                { TrackerConfig(trackMaxDtMs = TrackerConfig.MAX_TRACK_MAX_DT_MS + 1f) },
                { TrackerConfig(trackGapNoiseInflate = TrackerConfig.MIN_TRACK_GAP_NOISE_INFLATE - 0.01f) },
                { TrackerConfig(trackGapNoiseInflate = TrackerConfig.MAX_TRACK_GAP_NOISE_INFLATE + 1f) },
                { TrackerConfig(trackMissVelocityTauMs = 0f) },
                { TrackerConfig(trackMissVelocityTauMs = TrackerConfig.MAX_TRACK_DURATION_MS + 1f) },
                { TrackerConfig(trackAccelSigmaRatio = 0f) },
                { TrackerConfig(trackAccelSigmaRatio = TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO + 1f) },
                { TrackerConfig(trackHeightAccelSigmaRatio = 0f) },
                { TrackerConfig(trackHeightAccelSigmaRatio = TrackerConfig.MAX_TRACK_ACCEL_SIGMA_RATIO + 1f) },
                { TrackerConfig(trackHeightNoiseRatio = 0f) },
                { TrackerConfig(trackHeightNoiseRatio = TrackerConfig.MAX_TRACK_HEIGHT_NOISE_RATIO + 0.01f) },
                { TrackerConfig(trackInitVelocitySigmaRatio = 0f) },
                { TrackerConfig(trackInitVelocitySigmaRatio = TrackerConfig.MAX_TRACK_INIT_VELOCITY_SIGMA_RATIO + 1f) },
                { TrackerConfig(trackMatchMaxCost = 0f) },
                { TrackerConfig(trackMatchMaxCost = TrackerConfig.MAX_TRACK_MATCH_MAX_COST + 0.01f) },
                { TrackerConfig(trackLowMatchMaxCost = 0f) },
                { TrackerConfig(trackLowMatchMaxCost = TrackerConfig.MAX_TRACK_MATCH_MAX_COST + 0.01f) },
                { TrackerConfig(trackExitMarginRatio = -0.01f) },
                { TrackerConfig(trackExitMarginRatio = TrackerConfig.MAX_TRACK_EXIT_MARGIN_RATIO + 0.01f) },
            )

        for (build in rejected) {
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun trackLifecycleFields_nanAndInfinity_areRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackHighScore = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackBirthScore = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackMaxLostMs = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackMaxDtMs = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackGapNoiseInflate = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackMissVelocityTauMs = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackAccelSigmaRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackHeightAccelSigmaRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackHeightNoiseRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackInitVelocitySigmaRatio = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackMatchMaxCost = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackLowMatchMaxCost = value) }
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(trackExitMarginRatio = value) }
        }
    }

    @Test
    fun minBoxHeightImg_hasTheDocumentedDefault_withinItsBounds() {
        val config = TrackerConfig()

        assertThat(config.minBoxHeightImg).isEqualTo(2.0f)
        assertWithin(config.minBoxHeightImg, TrackerConfig.MIN_MIN_BOX_HEIGHT_IMG, TrackerConfig.MAX_MIN_BOX_HEIGHT_IMG)
    }

    @Test
    fun minBoxHeightImg_atItsBounds_isAccepted() {
        val low = TrackerConfig(minBoxHeightImg = TrackerConfig.MIN_MIN_BOX_HEIGHT_IMG)
        val high = TrackerConfig(minBoxHeightImg = TrackerConfig.MAX_MIN_BOX_HEIGHT_IMG)

        assertThat(low.minBoxHeightImg).isEqualTo(TrackerConfig.MIN_MIN_BOX_HEIGHT_IMG)
        assertThat(high.minBoxHeightImg).isEqualTo(TrackerConfig.MAX_MIN_BOX_HEIGHT_IMG)
    }

    @Test
    fun minBoxHeightImg_outsideItsBounds_orNotFinite_isRejected() {
        val rejected =
            listOf(
                TrackerConfig.MIN_MIN_BOX_HEIGHT_IMG - 0.01f,
                TrackerConfig.MAX_MIN_BOX_HEIGHT_IMG + 0.01f,
                0f,
                -1f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )

        for (value in rejected) {
            assertThrows(IllegalArgumentException::class.java) { TrackerConfig(minBoxHeightImg = value) }
        }
    }

    @Test
    fun modelLibraryMax_defaultsToFour() {
        assertThat(TrackerConfig().modelLibraryMax).isEqualTo(4)
    }

    @Test
    fun modelLibraryMax_atItsBounds_isAccepted() {
        assertThat(TrackerConfig(modelLibraryMax = TrackerConfig.MIN_MODEL_LIBRARY_MAX).modelLibraryMax).isEqualTo(1)
        assertThat(TrackerConfig(modelLibraryMax = TrackerConfig.MAX_MODEL_LIBRARY_MAX).modelLibraryMax).isEqualTo(16)
    }

    @Test
    fun modelLibraryMax_outsideItsBounds_isRejected() {
        val rejected: List<() -> TrackerConfig> =
            listOf(
                { TrackerConfig(modelLibraryMax = TrackerConfig.MIN_MODEL_LIBRARY_MAX - 1) },
                { TrackerConfig(modelLibraryMax = TrackerConfig.MAX_MODEL_LIBRARY_MAX + 1) },
            )
        for (build in rejected) assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun removeAllConfirmMs_defaultsToFourSeconds_andRejectsOutsideItsBounds() {
        assertThat(TrackerConfig().removeAllConfirmMs).isEqualTo(4000L)
        TrackerConfig(removeAllConfirmMs = TrackerConfig.MIN_REMOVE_ALL_CONFIRM_MS)
        TrackerConfig(removeAllConfirmMs = TrackerConfig.MAX_REMOVE_ALL_CONFIRM_MS)
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(removeAllConfirmMs = TrackerConfig.MIN_REMOVE_ALL_CONFIRM_MS - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrackerConfig(removeAllConfirmMs = TrackerConfig.MAX_REMOVE_ALL_CONFIRM_MS + 1)
        }
    }

    private fun assertWithin(
        value: Float,
        min: Float,
        max: Float,
    ) {
        assertThat(value).isAtLeast(min)
        assertThat(value).isAtMost(max)
    }

    @Test
    fun rawNmsIou_defaultsToPointSeven_andRejectsOutOfRangeAndNonFinite() {
        assertThat(TrackerConfig().rawNmsIou).isEqualTo(0.7f)
        assertThat(TrackerConfig(rawNmsIou = TrackerConfig.MIN_RAW_NMS_IOU).rawNmsIou).isEqualTo(TrackerConfig.MIN_RAW_NMS_IOU)
        assertThat(TrackerConfig(rawNmsIou = TrackerConfig.MAX_RAW_NMS_IOU).rawNmsIou).isEqualTo(TrackerConfig.MAX_RAW_NMS_IOU)
        val bad =
            listOf(
                TrackerConfig.MIN_RAW_NMS_IOU - 0.01f,
                TrackerConfig.MAX_RAW_NMS_IOU + 0.01f,
                Float.NaN,
                Float.POSITIVE_INFINITY,
            )
        for (value in bad) assertThrows(IllegalArgumentException::class.java) { TrackerConfig(rawNmsIou = value) }
    }
}
