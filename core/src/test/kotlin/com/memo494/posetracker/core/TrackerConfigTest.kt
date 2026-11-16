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
    fun toString_listsTheTimingAndThermalFields_forConfigTxt() {
        val text = TrackerConfig().toString()

        assertThat(text).contains("timingWindowFrames=512")
        assertThat(text).contains("thermalSampleIntervalMs=2000")
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

    private fun assertWithin(
        value: Float,
        min: Float,
        max: Float,
    ) {
        assertThat(value).isAtLeast(min)
        assertThat(value).isAtMost(max)
    }
}
