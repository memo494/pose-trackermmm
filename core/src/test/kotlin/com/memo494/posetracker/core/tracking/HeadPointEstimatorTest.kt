package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.tracking.HeadTestDetections.OFFSET_X_IN_H
import com.memo494.posetracker.core.tracking.HeadTestDetections.OFFSET_Y_IN_H
import com.memo494.posetracker.core.tracking.HeadTestDetections.allHead
import com.memo494.posetracker.core.tracking.HeadTestDetections.person
import com.memo494.posetracker.core.tracking.HeadTestDetections.scoresOf
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class HeadPointEstimatorTest {
    private val config = TrackerConfig()
    private val estimator = HeadPointEstimator(config)
    private val out = HeadPoint()

    @Test
    fun allFiveVisible_isTheirMean_andEqualsTheStatelessMeasurement() {
        val detection = person(centreXImg = 300f, centreYImg = 200f, heightImg = 100f)
        val stateless = HeadPoint()

        estimator.update(detection, out)
        HeadMeasurement.of(detection, config, stateless)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(stateless.headXImg)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(stateless.headYImg)
        assertThat(out.varianceImg2).isWithin(TOLERANCE_PX).of(stateless.varianceImg2)
    }

    @Test
    fun threeOfFiveVanishAfterLearning_theCentreMovesLessThanTwoPercentOfTheHeight() {
        val height = 200f
        learn(frames = 20, height = height)
        val centreX = 300f + 2f * 20f
        val centreY = 400f - 1.5f * 20f
        // Eyes and the right ear lose their score and sit at nonsense positions; the nose and left ear stay.
        val nowhere = 0f to 0f
        val detection =
            person(
                centreXImg = centreX,
                centreYImg = centreY,
                heightImg = height,
                scores = scoresOf(0.9f, 0.1f, 0.1f, 0.9f, 0.1f),
                moved = mapOf(1 to nowhere, 2 to nowhere, 4 to nowhere),
            )

        estimator.update(detection, out)

        val errorInH = hypot(out.headXImg - centreX, out.headYImg - centreY) / height
        assertThat(errorInH).isLessThan(0.02f)
        // Without the learned offsets the mean of the two remaining keypoints would be clearly off.
        val plainX = (detection.keypointXImg(0) + detection.keypointXImg(3)) / 2f
        val plainY = (detection.keypointYImg(0) + detection.keypointYImg(3)) / 2f
        val plainErrorInH = hypot(plainX - centreX, plainY - centreY) / height
        assertThat(plainErrorInH).isGreaterThan(0.02f)
    }

    @Test
    fun hysteresis_enterAtHalf_staysDownToTheLeaveScore_leavesBelowIt() {
        update(scoresOf(0.45f))
        assertThat(estimator.isKeypointVisible(0)).isFalse()
        assertThat(out.estimated).isTrue()

        update(scoresOf(0.6f))
        assertThat(estimator.isKeypointVisible(0)).isTrue()
        assertThat(out.estimated).isFalse()

        update(scoresOf(0.35f))
        assertThat(estimator.isKeypointVisible(0)).isTrue()
        assertThat(out.estimated).isFalse()

        update(scoresOf(0.25f))
        assertThat(estimator.isKeypointVisible(0)).isFalse()
        assertThat(out.estimated).isTrue()
    }

    @Test
    fun hysteresis_boundaries_enterIsInclusive_leaveIsExclusive() {
        update(scoresOf(0.5f))
        assertThat(estimator.isKeypointVisible(0)).isTrue()

        update(scoresOf(0.3f))
        assertThat(estimator.isKeypointVisible(0)).isTrue()

        update(scoresOf(0.29f))
        assertThat(estimator.isKeypointVisible(0)).isFalse()

        // Having left, it must reach the enter score again, not just the leave score.
        update(scoresOf(0.4f))
        assertThat(estimator.isKeypointVisible(0)).isFalse()
    }

    @Test
    fun noVisibleKeypoint_afterLearning_usesTheFallbackAndKeepsTheOffsets() {
        learn(frames = 5, height = 100f)
        val offsetsBefore = offsets()

        estimator.update(person(300f, 200f, 100f, scoresOf()), out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isTrue()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(200f)
        assertThat(estimator.learnedOffsetCount).isEqualTo(5)
        assertOffsetsWithin(offsetsBefore, 0f)
    }

    @Test
    fun fallbackVariance_isTheFallbackNoiseSquared() {
        estimator.update(person(300f, 200f, 200f, scoresOf()), out)

        assertThat(out.estimated).isTrue()
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 400f).of(400f)
    }

    @Test
    fun variance_isTheMeasurementNoiseOverTheMeanConfidenceOfTheUsedKeypoints() {
        estimator.update(person(300f, 200f, 100f, scoresOf(0.9f, 0f, 0f, 0.5f, 0f)), out)

        // Used: nose 0.9 and left ear 0.5, mean 0.7; (0.03 * 100)^2 / 0.7.
        assertThat(out.estimated).isFalse()
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 9f / 0.7f).of(9f / 0.7f)
    }

    @Test
    fun variance_usesTheMinimumMeanConfidenceAsAFloor() {
        val lenient = TrackerConfig(headKeypointEnterScore = 0.05f, headKeypointLeaveScore = 0.05f, headMinMeanConfidence = 0.2f)
        val lenientEstimator = HeadPointEstimator(lenient)

        lenientEstimator.update(person(300f, 200f, 100f, allHead(0.06f)), out)

        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 9f / 0.2f).of(9f / 0.2f)
    }

    @Test
    fun firstFrame_withUnlearnedKeypoints_isTheConfidenceWeightedMeanOfTheRawKeypoints() {
        val detection =
            person(
                centreXImg = 300f,
                centreYImg = 200f,
                heightImg = 100f,
                scores = scoresOf(0.9f, 0f, 0f, 0.5f, 0f),
                moved = mapOf(0 to (100f to 100f), 3 to (120f to 100f)),
            )

        estimator.update(detection, out)

        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of((0.9f * 100f + 0.5f * 120f) / 1.4f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(100f)
    }

    @Test
    fun offsets_areUpdatedByAnEmaOfTheKeypointPositionRelativeToTheCentre() {
        val first =
            person(
                300f,
                200f,
                100f,
                scoresOf(0.9f, 0.9f),
                moved = mapOf(0 to (100f to 100f), 1 to (120f to 100f)),
            )
        val second =
            person(
                300f,
                200f,
                100f,
                scoresOf(0.9f, 0.9f),
                moved = mapOf(0 to (100f to 100f), 1 to (130f to 100f)),
            )

        estimator.update(first, out)
        // The first observation sets the offsets directly: -0.1 h and +0.1 h around the raw mean (110, 100).
        assertThat(estimator.offsetXRatio(0)).isWithin(TOLERANCE_RATIO).of(-0.1f)
        assertThat(estimator.offsetXRatio(1)).isWithin(TOLERANCE_RATIO).of(0.1f)
        assertThat(estimator.offsetYRatio(0)).isWithin(TOLERANCE_RATIO).of(0f)

        estimator.update(second, out)

        // Centre estimates 110 and 120, mean 115; offset <- offset + 0.2 * ((kp - 115) / 100 - offset).
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(115f)
        assertThat(estimator.offsetXRatio(0)).isWithin(TOLERANCE_RATIO).of(-0.11f)
        assertThat(estimator.offsetXRatio(1)).isWithin(TOLERANCE_RATIO).of(0.11f)
    }

    @Test
    fun aVisibleKeypointWithoutAnOffset_isNotUsedRaw_onceAnotherKeypointHasOne() {
        val first = person(300f, 200f, 100f, scoresOf(0.9f), moved = mapOf(0 to (100f to 100f)))
        val second =
            person(300f, 200f, 100f, scoresOf(0.9f, 0.9f), moved = mapOf(0 to (100f to 100f), 1 to (130f to 100f)))

        estimator.update(first, out)
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(100f)
        assertThat(estimator.hasOffset(1)).isFalse()

        estimator.update(second, out)

        // Only the nose (offset 0) anchors the centre; the left eye is learned from it afterwards.
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(100f)
        assertThat(out.varianceImg2).isWithin(RELATIVE_TOLERANCE * 10f).of(9f / 0.9f)
        assertThat(estimator.hasOffset(1)).isTrue()
        assertThat(estimator.offsetXRatio(1)).isWithin(TOLERANCE_RATIO).of(0.3f)
    }

    @Test
    fun scaleChange_hDoubles_keepsTheCentreAndTheOffsetsInUnitsOfH() {
        learn(frames = 10, height = 100f)
        val offsetsBefore = offsets()

        estimator.update(person(340f, 385f, 200f), out)
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(340f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(385f)
        assertOffsetsWithin(offsetsBefore, TOLERANCE_RATIO)

        val nowhere = 0f to 0f
        estimator.update(
            person(
                340f,
                385f,
                200f,
                scores = scoresOf(0.9f, 0.1f, 0.9f, 0.1f, 0.1f),
                moved = mapOf(1 to nowhere, 3 to nowhere, 4 to nowhere),
            ),
            out,
        )
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(340f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(385f)
    }

    @Test
    fun learnedOffsets_matchTheTrueLayout() {
        learn(frames = 3, height = 150f)

        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            assertThat(estimator.hasOffset(k)).isTrue()
            assertThat(estimator.offsetXRatio(k)).isWithin(TOLERANCE_RATIO).of(OFFSET_X_IN_H[k])
            assertThat(estimator.offsetYRatio(k)).isWithin(TOLERANCE_RATIO).of(OFFSET_Y_IN_H[k])
        }
    }

    @Test
    fun nanKeypointCoordinates_areNotVisible_andNeverEnterTheState() {
        val nan = Float.NaN
        val detection = person(300f, 200f, 100f, moved = mapOf(1 to (nan to 200f), 2 to (300f to nan)))

        estimator.update(detection, out)

        assertThat(estimator.isKeypointVisible(1)).isFalse()
        assertThat(estimator.isKeypointVisible(2)).isFalse()
        assertThat(estimator.hasOffset(1)).isFalse()
        assertThat(estimator.hasOffset(2)).isFalse()
        assertThat(out.headXImg.isNaN()).isFalse()
        assertThat(out.headYImg.isNaN()).isFalse()
        assertThat(out.varianceImg2.isNaN()).isFalse()
        assertThat(offsets().none { it.isNaN() }).isTrue()
    }

    @Test
    fun nanScore_ofAVisibleKeypoint_makesItInvisible_insteadOfKeepingItByHysteresis() {
        learn(frames = 3, height = 100f)
        assertThat(estimator.isKeypointVisible(0)).isTrue()

        estimator.update(person(300f, 200f, 100f, scoresOf(Float.NaN, 0.9f, 0.9f, 0.9f, 0.9f)), out)

        assertThat(estimator.isKeypointVisible(0)).isFalse()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg.isNaN()).isFalse()
    }

    @Test
    fun aFrameOfNanKeypoints_doesNotPoisonTheLearnedState() {
        learn(frames = 5, height = 100f)
        val offsetsBefore = offsets()
        val nan = Float.NaN
        val garbage = (0 until TrackerConfig.HEAD_KEYPOINT_COUNT).associateWith { nan to nan }

        estimator.update(person(300f, 200f, 100f, moved = garbage), out)

        assertThat(out.estimated).isTrue()
        assertThat(out.headXImg.isNaN()).isFalse()
        assertOffsetsWithin(offsetsBefore, 0f)

        estimator.update(person(310f, 210f, 100f, scoresOf(0.9f, 0.1f, 0.1f, 0.9f, 0.1f)), out)

        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(310f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(210f)
        assertOffsetsWithin(offsetsBefore, TOLERANCE_RATIO)
    }

    @Test
    fun reset_forgetsOffsetsAndVisibility() {
        learn(frames = 5, height = 100f)

        estimator.reset()

        assertThat(estimator.learnedOffsetCount).isEqualTo(0)
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            assertThat(estimator.isKeypointVisible(k)).isFalse()
            assertThat(estimator.hasOffset(k)).isFalse()
        }
        // Nose and left ear only: the raw mean (no learned offsets any more), not the true centre.
        val detection = person(300f, 200f, 100f, scoresOf(0.9f, 0.1f, 0.1f, 0.9f, 0.1f))
        estimator.update(detection, out)
        val plainX = (detection.keypointXImg(0) + detection.keypointXImg(3)) / 2f
        val plainY = (detection.keypointYImg(0) + detection.keypointYImg(3)) / 2f
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(plainX)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(plainY)
    }

    @Test
    fun nonPositiveOrNonFiniteHeight_isInvalid_andLeavesTheStateUnchanged() {
        learn(frames = 5, height = 100f)
        val offsetsBefore = offsets()
        val visibleBefore = visibility()

        for (height in listOf(0f, -5f, Float.NaN)) {
            // Scores of 0 would make every keypoint invisible if the frame were processed.
            estimator.update(person(300f, 200f, height, scoresOf()), out)

            assertThat(out.valid).isFalse()
            assertThat(out.headXImg.isNaN()).isTrue()
            assertThat(out.headYImg.isNaN()).isTrue()
            assertThat(out.varianceImg2.isNaN()).isTrue()
            assertOffsetsWithin(offsetsBefore, 0f)
            assertThat(visibility()).isEqualTo(visibleBefore)
        }

        estimator.update(person(300f, 200f, 100f), out)
        assertThat(out.valid).isTrue()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
    }

    @Test
    fun aDetectionWithoutKeypoints_usesTheFallback() {
        val detection = PoseDetection(100f, 50f, 140f, 150f, 0.9f, FloatArray(0), FloatArray(0))

        estimator.update(detection, out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isTrue()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(120f)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(60f)
    }

    @Test
    fun theResultObject_isFullyOverwrittenByEveryUpdate() {
        estimator.update(person(300f, 200f, 0f), out)
        assertThat(out.valid).isFalse()

        estimator.update(person(300f, 200f, 100f), out)

        assertThat(out.valid).isTrue()
        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(300f)
    }

    /** Feeds [frames] frames of a person with all five head keypoints visible, the head moving in a straight line. */
    private fun learn(
        frames: Int,
        height: Float,
    ) {
        for (i in 1..frames) {
            estimator.update(person(300f + 2f * i, 400f - 1.5f * i, height), out)
        }
    }

    private fun update(scores: FloatArray) {
        estimator.update(person(300f, 200f, 100f, scores), out)
    }

    /** The learned offsets as `x0, y0, x1, y1, ...`, in units of h (0 for a keypoint without an offset). */
    private fun offsets(): FloatArray {
        val values = FloatArray(TrackerConfig.HEAD_KEYPOINT_COUNT * 2)
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            values[2 * k] = estimator.offsetXRatio(k)
            values[2 * k + 1] = estimator.offsetYRatio(k)
        }
        return values
    }

    private fun assertOffsetsWithin(
        expected: FloatArray,
        tolerance: Float,
    ) {
        val actual = offsets()
        for (i in expected.indices) {
            assertThat(actual[i]).isWithin(tolerance).of(expected[i])
        }
    }

    private fun visibility(): List<Boolean> = (0 until TrackerConfig.HEAD_KEYPOINT_COUNT).map { estimator.isKeypointVisible(it) }

    private companion object {
        const val TOLERANCE_PX = 0.05f
        const val TOLERANCE_RATIO = 1e-4f
        const val RELATIVE_TOLERANCE = 1e-4f
    }
}
