package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.tracking.HeadTestDetections.allHead
import com.memo494.posetracker.core.tracking.HeadTestDetections.boxOnlyPerson
import com.memo494.posetracker.core.tracking.HeadTestDetections.person
import com.memo494.posetracker.core.tracking.HeadTestDetections.scoresOf
import org.junit.jupiter.api.Test

class HeadMeasurementTest {
    private val config = TrackerConfig()
    private val out = HeadPoint()

    @Test
    fun allFiveVisible_isTheirMean() {
        val detection = person(centreXImg = 300f, centreYImg = 200f, heightImg = 100f)

        HeadMeasurement.of(detection, config, out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
    }

    @Test
    fun onlyKeypointsAtOrAboveTheEnterScore_areUsed() {
        // Nose (0.5, used) at (300, 202) and left eye (0.49, not used); the other three are below.
        val detection = person(300f, 200f, 100f, scoresOf(0.5f, 0.49f, 0.1f, 0f, 0f))

        HeadMeasurement.of(detection, config, out)

        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(202f)
    }

    @Test
    fun noKeypointVisible_usesTheBoxFallbackAndIsEstimated() {
        val detection = person(300f, 200f, 100f, scoresOf(0.49f, 0.2f, 0f, 0f, 0f))

        HeadMeasurement.of(detection, config, out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isTrue()
        // Box centre x is 300; the box top is 190, so the fallback is at 190 + 0.10 * 100.
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
    }

    @Test
    fun fallbackTopRatio_comesFromTheConfig() {
        val detection = person(300f, 200f, 100f, scoresOf())

        HeadMeasurement.of(detection, TrackerConfig(headFallbackTopRatio = 0.2f), out)

        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(210f)
    }

    @Test
    fun variance_isTheMeasurementNoiseOverTheMeanConfidenceOfTheUsedKeypoints() {
        val detection = person(300f, 200f, 100f, scoresOf(0.9f, 0.5f, 0.2f, 0f, 0f))

        HeadMeasurement.of(detection, config, out)

        // Used: 0.9 and 0.5, mean 0.7; (0.03 * 100)^2 / 0.7.
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 9f / 0.7f).of(9f / 0.7f)
    }

    @Test
    fun variance_ofTheFallback_isTheFallbackNoiseSquared() {
        val detection = person(300f, 200f, 200f, scoresOf())

        HeadMeasurement.of(detection, config, out)

        // (0.10 * 200)^2
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 400f).of(400f)
    }

    @Test
    fun variance_usesTheMinimumMeanConfidenceAsAFloor() {
        val lenient = TrackerConfig(headKeypointEnterScore = 0.05f, headKeypointLeaveScore = 0.05f, headMinMeanConfidence = 0.2f)
        val detection = person(300f, 200f, 100f, allHead(0.06f))

        HeadMeasurement.of(detection, lenient, out)

        assertThat(out.estimated).isFalse()
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 9f / 0.2f).of(9f / 0.2f)
    }

    @Test
    fun nonPositiveOrNonFiniteHeight_isInvalid_andNeverDividesByZero() {
        for (height in listOf(0f, -50f, Float.NaN)) {
            val detection = person(300f, 200f, height)

            HeadMeasurement.of(detection, config, out)

            assertThat(out.valid).isFalse()
            assertThat(out.headXImg.isNaN()).isTrue()
            assertThat(out.headYImg.isNaN()).isTrue()
            assertThat(out.varianceImg2.isNaN()).isTrue()
        }
    }

    @Test
    fun aBoxLowerThanTheMinimumHeight_isInvalid_andOneAtTheMinimumIsValid() {
        val minImg = config.minBoxHeightImg
        for (height in listOf(1e-25f, 0.5f, minImg - 0.01f)) {
            HeadMeasurement.of(person(300f, 200f, height), config, out)

            assertThat(out.valid).isFalse()
            assertThat(out.varianceImg2.isNaN()).isTrue()
        }

        HeadMeasurement.of(person(300f, 200f, minImg), config, out)

        assertThat(out.valid).isTrue()
        assertThat(out.varianceImg2 > 0f).isTrue()
    }

    @Test
    fun theMinimumBoxHeight_comesFromTheConfig() {
        val strict = TrackerConfig(minBoxHeightImg = 10f)

        HeadMeasurement.of(person(300f, 200f, 9.9f), strict, out)
        assertThat(out.valid).isFalse()

        HeadMeasurement.of(person(300f, 200f, 9.9f), config, out)
        assertThat(out.valid).isTrue()
    }

    @Test
    fun nonFiniteBoxEdge_isInvalid() {
        val box = person(300f, 200f, 100f)
        val detection =
            PoseDetection(Float.NaN, box.boxTopImg, box.boxRightImg, box.boxBottomImg, 0.9f, box.keypointsXyImg, box.keypointScores)

        HeadMeasurement.of(detection, config, out)

        assertThat(out.valid).isFalse()
    }

    @Test
    fun nanKeypointCoordinatesOrScores_areNotUsed() {
        val nan = Float.NaN
        val detection =
            person(
                centreXImg = 300f,
                centreYImg = 200f,
                heightImg = 100f,
                scores = scoresOf(nan, 0.9f, 0.9f, 0.9f, 0.9f),
                moved = mapOf(1 to (nan to 200f), 2 to (300f to Float.POSITIVE_INFINITY)),
            )

        HeadMeasurement.of(detection, config, out)

        // Only the two ears are usable: x = 300 - 5 and 300 + 5, y = 200.
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 10f).of(9f / 0.9f)
    }

    @Test
    fun aDetectionWithoutKeypoints_usesTheFallback() {
        val detection = PoseDetection(100f, 50f, 140f, 150f, 0.9f, FloatArray(0), FloatArray(0))

        HeadMeasurement.of(detection, config, out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isTrue()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(120f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(60f)
    }

    @Test
    fun theResultObject_isFullyOverwrittenByEveryCall() {
        HeadMeasurement.of(person(300f, 200f, 0f), config, out)
        assertThat(out.valid).isFalse()

        HeadMeasurement.of(person(300f, 200f, 100f), config, out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
    }

    // ---- box-only models (M-legacy T3) ----

    @Test
    fun boxOnly_putsThePointAtTheTopRatioOfTheBox_andIsNotEstimated() {
        val detection = boxOnlyPerson(centreXImg = 300f, centreYImg = 200f, heightImg = 100f)

        HeadMeasurement.of(detection, config, out, boxOnly = true)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        // Box top 190 + 0.10 * 100.
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
    }

    @Test
    fun boxOnly_ignoresKeypointsEvenWhenTheyAreConfident() {
        val detection = person(300f, 200f, 100f, moved = mapOf(0 to (500f to 400f)))

        HeadMeasurement.of(detection, config, out, boxOnly = true)

        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
    }

    @Test
    fun boxOnly_theTopRatioComesFromTheConfig() {
        val detection = boxOnlyPerson(300f, 200f, 100f)

        HeadMeasurement.of(detection, TrackerConfig(headFallbackTopRatio = 0.2f), out, boxOnly = true)

        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(210f)
    }

    @Test
    fun boxOnly_varianceIsTheBoxOnlyNoiseOverThePersonScore_notTheLargeFallbackNoise() {
        val detection = boxOnlyPerson(300f, 200f, 100f, score = 0.5f)

        HeadMeasurement.of(detection, config, out, boxOnly = true)

        // (0.05 * 100)^2 / 0.5 = 50; the pose fallback would be (0.10 * 100)^2 = 100.
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 50f).of(50f)
    }

    @Test
    fun boxOnly_theNoiseRatioComesFromTheConfig() {
        val detection = boxOnlyPerson(300f, 200f, 100f, score = 1f)

        HeadMeasurement.of(detection, TrackerConfig(headBoxOnlyNoiseRatio = 0.1f), out, boxOnly = true)

        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 100f).of(100f)
    }

    @Test
    fun boxOnly_varianceUsesTheMinimumMeanConfidenceAsAFloor() {
        val detection = boxOnlyPerson(300f, 200f, 100f, score = 0.001f)

        HeadMeasurement.of(detection, config, out, boxOnly = true)

        // (0.05 * 100)^2 / 0.05 (the minimum mean confidence).
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 500f).of(500f)
    }

    @Test
    fun boxOnly_anUnusableBoxIsInvalid() {
        HeadMeasurement.of(boxOnlyPerson(300f, 200f, 0f), config, out, boxOnly = true)

        assertThat(out.valid).isFalse()
        assertThat(out.headYImg.isNaN()).isTrue()
    }

    @Test
    fun theDefaultIsThePoseRule_aKeypointFreeDetectionIsEstimated() {
        HeadMeasurement.of(boxOnlyPerson(300f, 200f, 100f), config, out)

        assertThat(out.estimated).isTrue()
    }

    private companion object {
        const val TOLERANCE_PX = 0.01f
        const val RELATIVE_TOLERANCE = 1e-4f
    }
}
