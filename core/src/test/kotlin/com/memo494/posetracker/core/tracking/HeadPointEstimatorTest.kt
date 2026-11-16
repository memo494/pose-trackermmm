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
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
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
    fun offsets_inAFullView_areAnEmaOfTheKeypointPositionRelativeToThePlainMean() {
        val scores = scoresOf(0.9f, 0.8f, 0.7f, 0.6f, 0.9f)
        val height = 100f
        val alpha = config.headOffsetAlpha
        val firstXs = floatArrayOf(100f, 120f, 150f, 90f, 135f)
        val firstYs = floatArrayOf(100f, 95f, 102f, 99f, 110f)
        val secondXs = floatArrayOf(100f, 130f, 150f, 90f, 135f)
        val secondYs = floatArrayOf(100f, 95f, 102f, 109f, 110f)

        estimator.update(fiveKeypoints(firstXs, firstYs, scores, height), out)

        // The first observation sets every offset directly, against the unweighted mean of the five keypoints.
        val firstMeanX = firstXs.average().toFloat()
        val firstMeanY = firstYs.average().toFloat()
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(firstMeanX)
        assertThat(out.headYImg).isWithin(TOLERANCE_PX).of(firstMeanY)
        val expectedX = FloatArray(TrackerConfig.HEAD_KEYPOINT_COUNT)
        val expectedY = FloatArray(TrackerConfig.HEAD_KEYPOINT_COUNT)
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            expectedX[k] = (firstXs[k] - firstMeanX) / height
            expectedY[k] = (firstYs[k] - firstMeanY) / height
            assertThat(estimator.offsetXRatio(k)).isWithin(TOLERANCE_RATIO).of(expectedX[k])
            assertThat(estimator.offsetYRatio(k)).isWithin(TOLERANCE_RATIO).of(expectedY[k])
        }

        estimator.update(fiveKeypoints(secondXs, secondYs, scores, height), out)

        // offset <- offset + alpha * ((keypoint - plainMean2) / h - offset), for every keypoint.
        val secondMeanX = secondXs.average().toFloat()
        val secondMeanY = secondYs.average().toFloat()
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            val wantedX = expectedX[k] + alpha * ((secondXs[k] - secondMeanX) / height - expectedX[k])
            val wantedY = expectedY[k] + alpha * ((secondYs[k] - secondMeanY) / height - expectedY[k])
            assertThat(estimator.offsetXRatio(k)).isWithin(TOLERANCE_RATIO).of(wantedX)
            assertThat(estimator.offsetYRatio(k)).isWithin(TOLERANCE_RATIO).of(wantedY)
        }
    }

    @Test
    fun offsets_inAPartialView_areNeverChangedByAMovedKeypoint() {
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
        val offsetsAfterFirst = offsets()

        estimator.update(second, out)

        // Two of five keypoints are a partial view: the offsets stay exactly as they were, only the centre moves.
        assertOffsetsWithin(offsetsAfterFirst, 0f)
        assertThat(estimator.offsetXRatio(0)).isWithin(TOLERANCE_RATIO).of(-0.1f)
        assertThat(estimator.offsetXRatio(1)).isWithin(TOLERANCE_RATIO).of(0.1f)
        // Centre estimates 110 and 120 from the unchanged offsets.
        assertThat(out.headXImg).isWithin(TOLERANCE_PX).of(115f)
    }

    @Test
    fun aTrackThatStartsWithAPartialView_endsAtThePlainMeanOnceAllFiveAreVisible() {
        val height = 200f
        val partial = scoresOf(0.9f, 0.9f, 0f, 0.9f, 0f)
        repeat(10) { estimator.update(person(300f, 400f, height, partial), out) }
        val biasInH = hypot(out.headXImg - 300f, out.headYImg - 400f) / height
        // The mean of nose, left eye and left ear alone sits about 0.024 h off the mean of all five.
        assertThat(biasInH).isGreaterThan(0.01f)

        repeat(ceil(3f / config.headOffsetAlpha).toInt()) { estimator.update(person(300f, 400f, height), out) }

        val errorInH = hypot(out.headXImg - 300f, out.headYImg - 400f) / height
        assertThat(errorInH).isLessThan(0.005f)
    }

    @Test
    fun aPartialView_addsAnOffsetOnlyForAKeypointThatHadNone_andLeavesTheOthersAlone() {
        val height = 100f
        val nowhere = 0f to 0f
        val twoKeypoints = scoresOf(0.9f, 0.9f)
        repeat(10) { i ->
            val moved = mapOf(0 to (100f + i to 100f), 1 to (120f - i to 100f + i), 2 to nowhere)
            estimator.update(person(300f, 200f, height, twoKeypoints, moved), out)
        }
        val offsetsBefore = offsets()
        assertThat(estimator.hasOffset(3)).isFalse()

        repeat(20) { i ->
            val moved = mapOf(0 to (150f + 3 * i to 90f), 1 to (170f to 130f - i), 3 to (140f to 105f))
            estimator.update(person(300f, 200f, height, scoresOf(0.9f, 0.9f, 0f, 0.9f), moved), out)
        }

        for (k in intArrayOf(0, 1)) {
            assertThat(estimator.offsetXRatio(k)).isEqualTo(offsetsBefore[2 * k])
            assertThat(estimator.offsetYRatio(k)).isEqualTo(offsetsBefore[2 * k + 1])
        }
        assertThat(estimator.hasOffset(3)).isTrue()
        assertThat(estimator.hasOffset(2)).isFalse()
        assertThat(estimator.hasOffset(4)).isFalse()
        assertThat(estimator.learnedOffsetCount).isEqualTo(3)
    }

    @Test
    fun thousandFullViewFramesWithNoiseAndUnequalConfidences_keepTheOffsetsCentredAndTheOutputOnThePlainMean() {
        val random = Random(SEED)
        val height = 200f
        val scores = scoresOf(0.95f, 0.85f, 0.75f, 0.65f, 0.55f)
        var errorSumInH = 0f

        for (frame in 1..1000) {
            val moved = HashMap<Int, Pair<Float, Float>>()
            var sumX = 0f
            var sumY = 0f
            for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
                val x = 300f + OFFSET_X_IN_H[k] * height + NOISE_IN_H * height * random.nextGaussian().toFloat()
                val y = 400f + OFFSET_Y_IN_H[k] * height + NOISE_IN_H * height * random.nextGaussian().toFloat()
                moved[k] = x to y
                sumX += x
                sumY += y
            }
            estimator.update(person(300f, 400f, height, scores, moved), out)
            val plainMeanX = sumX / TrackerConfig.HEAD_KEYPOINT_COUNT
            val plainMeanY = sumY / TrackerConfig.HEAD_KEYPOINT_COUNT
            errorSumInH += hypot(out.headXImg - plainMeanX, out.headYImg - plainMeanY) / height
        }

        var offsetSumX = 0f
        var offsetSumY = 0f
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            offsetSumX += estimator.offsetXRatio(k)
            offsetSumY += estimator.offsetYRatio(k)
        }
        assertThat(abs(offsetSumX)).isLessThan(1e-3f)
        assertThat(abs(offsetSumY)).isLessThan(1e-3f)
        assertThat(errorSumInH / 1000f).isLessThan(0.002f)
    }

    @Test
    fun firstFullViewFrameOfANewTrack_isExactlyThePlainMean_whateverTheConfidences() {
        val height = 100f
        val xs = floatArrayOf(100f, 140f, 150f, 90f, 135f)
        val ys = floatArrayOf(100f, 95f, 102f, 99f, 130f)

        estimator.update(fiveKeypoints(xs, ys, scoresOf(0.95f, 0.5f, 0.6f, 0.9f, 0.55f), height), out)

        assertThat(out.estimated).isFalse()
        assertThat(out.headXImg).isWithin(1e-3f * height).of(xs.average().toFloat())
        assertThat(out.headYImg).isWithin(1e-3f * height).of(ys.average().toFloat())
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
    fun aBoxLowerThanTheMinimumHeight_isInvalid_andLeavesTheStateUnchanged() {
        learn(frames = 5, height = 100f)
        val offsetsBefore = offsets()
        val visibleBefore = visibility()

        for (height in listOf(1e-25f, 0.5f, config.minBoxHeightImg - 0.01f)) {
            estimator.update(person(300f, 200f, height), out)

            assertThat(out.valid).isFalse()
            assertThat(out.varianceImg2.isNaN()).isTrue()
            assertOffsetsWithin(offsetsBefore, 0f)
            assertThat(visibility()).isEqualTo(visibleBefore)
        }

        estimator.update(person(300f, 200f, config.minBoxHeightImg), out)
        assertThat(out.valid).isTrue()
    }

    @Test
    fun theMinimumBoxHeight_comesFromTheEstimatorsConfig() {
        val strict = HeadPointEstimator(TrackerConfig(minBoxHeightImg = 10f))

        strict.update(person(300f, 200f, 9.9f), out)

        assertThat(out.valid).isFalse()
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

    /** A person whose five head keypoints are at the given absolute positions; the box is [heightImg] high. */
    private fun fiveKeypoints(
        xs: FloatArray,
        ys: FloatArray,
        scores: FloatArray,
        heightImg: Float,
    ): PoseDetection {
        val moved = HashMap<Int, Pair<Float, Float>>()
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            moved[k] = xs[k] to ys[k]
        }
        return person(xs[0], ys[0], heightImg, scores, moved)
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
        const val SEED = 20_261_116L
        const val NOISE_IN_H = 0.01f
    }
}
