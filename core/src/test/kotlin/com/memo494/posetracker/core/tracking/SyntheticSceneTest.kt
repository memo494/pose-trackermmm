package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class SyntheticSceneTest {
    private val headX = 500.0
    private val headY = 300.0
    private val height = 200.0

    private fun stillPerson(
        dropouts: List<TimeInterval> = emptyList(),
        keypointDropouts: List<KeypointDropout> = emptyList(),
        duplicates: List<TimeInterval> = emptyList(),
        score: (Double) -> Double = { ScenePerson.DEFAULT_SCORE },
    ): ScenePerson =
        ScenePerson(
            id = 1,
            path = ScenePerson.stillPath(headX, headY, height, 0.0, 1000.0),
            score = score,
            dropouts = dropouts,
            keypointDropouts = keypointDropouts,
            duplicates = duplicates,
        )

    private fun sameFrames(
        a: SyntheticScene,
        b: SyntheticScene,
    ): Boolean {
        if (a.frames.size != b.frames.size) return false
        for (i in a.frames.indices) {
            val fa = a.frames[i].frame
            val fb = b.frames[i].frame
            if (fa.timestampNs != fb.timestampNs || fa.detections.size != fb.detections.size) return false
            for (d in fa.detections.indices) {
                val da: PoseDetection = fa.detections[d]
                val db: PoseDetection = fb.detections[d]
                val sameBox =
                    da.boxLeftImg == db.boxLeftImg && da.boxTopImg == db.boxTopImg &&
                        da.boxRightImg == db.boxRightImg && da.boxBottomImg == db.boxBottomImg
                val sameKeypoints =
                    da.keypointsXyImg.contentEquals(db.keypointsXyImg) && da.keypointScores.contentEquals(db.keypointScores)
                if (!sameBox || !sameKeypoints || da.score != db.score) return false
            }
        }
        return true
    }

    private fun busyScene(seed: Long): SyntheticScene =
        SyntheticScene(
            people = listOf(stillPerson(dropouts = listOf(TimeInterval(1.0, 1.5)), duplicates = listOf(TimeInterval(2.0, 2.5)))),
            durationS = 4.0,
            seed = seed,
            frameJitterFraction = 0.3,
            falsePositives = listOf(FalsePositive(TimeInterval(3.0, 3.5), 100.0, 200.0, 150.0, 0.55)),
            keypointNoiseRatio = 0.01,
            boxNoiseRatio = 0.02,
        )

    @Test
    fun sameSeed_givesTheSameFramesBitForBit() {
        assertThat(sameFrames(busyScene(7L), busyScene(7L))).isTrue()
    }

    @Test
    fun anotherSeed_givesOtherFrames() {
        assertThat(sameFrames(busyScene(7L), busyScene(8L))).isFalse()
    }

    @Test
    fun noiseFreeLayout_putsBoxAndHeadAtTheDocumentedPlaces() {
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 0.1)
        val detection = scene.frames[0].detections.single()
        assertThat(detection.boxLeftImg).isWithin(1e-3f).of(460f)
        assertThat(detection.boxRightImg).isWithin(1e-3f).of(540f)
        assertThat(detection.boxTopImg).isWithin(1e-3f).of(280f)
        assertThat(detection.boxBottomImg).isWithin(1e-3f).of(480f)
        assertThat(detection.score).isWithin(1e-6f).of(0.9f)
        assertThat(detection.keypointCount).isEqualTo(SyntheticScene.KEYPOINT_COUNT)
    }

    @Test
    fun headKeypoints_averageToTheTrueHeadCentre() {
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 0.1)
        val detection = scene.frames[0].detections.single()
        var sumX = 0.0
        var sumY = 0.0
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            sumX += detection.keypointXImg(k)
            sumY += detection.keypointYImg(k)
        }
        assertThat(sumX / TrackerConfig.HEAD_KEYPOINT_COUNT).isWithin(1e-3).of(headX)
        assertThat(sumY / TrackerConfig.HEAD_KEYPOINT_COUNT).isWithin(1e-3).of(headY)
        assertThat(detection.keypointXImg(1)).isLessThan(detection.keypointXImg(2))
    }

    @Test
    fun otherKeypoints_lieInsideTheBoxAtHighConfidence() {
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 0.1)
        val detection = scene.frames[0].detections.single()
        for (k in TrackerConfig.HEAD_KEYPOINT_COUNT until SyntheticScene.KEYPOINT_COUNT) {
            assertThat(detection.keypointXImg(k)).isAtLeast(detection.boxLeftImg)
            assertThat(detection.keypointXImg(k)).isAtMost(detection.boxRightImg)
            assertThat(detection.keypointYImg(k)).isAtLeast(detection.boxTopImg)
            assertThat(detection.keypointYImg(k)).isAtMost(detection.boxBottomImg)
            assertThat(detection.keypointScores[k]).isEqualTo(SyntheticScene.KEYPOINT_CONFIDENCE)
        }
    }

    @Test
    fun noise_hasTheRequestedSigmaPerKeypointCoordinateAndBoxEdge() {
        val scene =
            SyntheticScene(
                people = listOf(stillPerson()),
                durationS = 200.0,
                keypointNoiseRatio = 0.01,
                boxNoiseRatio = 0.02,
            )
        var noseSumSquares = 0.0
        var leftSumSquares = 0.0
        for (sceneFrame in scene.frames) {
            val detection = sceneFrame.detections.single()
            val noseError = detection.keypointXImg(0) - headX
            val leftError = detection.boxLeftImg - (headX - 0.2 * height)
            noseSumSquares += noseError * noseError
            leftSumSquares += leftError * leftError
        }
        val count = scene.frames.size
        assertThat(count).isEqualTo(4000)
        assertThat(sqrt(noseSumSquares / count)).isWithin(0.2).of(0.01 * height)
        assertThat(sqrt(leftSumSquares / count)).isWithin(0.4).of(0.02 * height)
    }

    @Test
    fun frameTimes_withoutJitter_areEvenImageStampedAndSizedAsAsked() {
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 1.0, fps = 20.0)
        assertThat(scene.frames).hasSize(20)
        for (i in 1 until scene.frames.size) {
            val step = scene.frames[i].frame.timestampNs - scene.frames[i - 1].frame.timestampNs
            assertThat(step).isEqualTo(50_000_000L)
        }
        val first = scene.frames[0].frame
        assertThat(first.timestampNs).isEqualTo(SyntheticScene.DEFAULT_START_NS)
        assertThat(first.timestampSource).isEqualTo(TimestampSource.IMAGE)
        assertThat(first.widthImg).isEqualTo(1356)
        assertThat(first.heightImg).isEqualTo(610)
        assertThat(scene.frames[10].timeS).isEqualTo(0.5)
    }

    @Test
    fun frameTimes_withJitter_varyWithinTheFractionAndStayStrictlyIncreasing() {
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 10.0, fps = 20.0, frameJitterFraction = 0.3)
        val steps = LongArray(scene.frames.size - 1)
        for (i in steps.indices) {
            steps[i] = scene.frames[i + 1].frame.timestampNs - scene.frames[i].frame.timestampNs
        }
        assertThat(steps.min()).isAtLeast(35_000_000L - 1L)
        assertThat(steps.max()).isAtMost(65_000_000L + 1L)
        assertThat(steps.max() - steps.min()).isGreaterThan(10_000_000L)
    }

    @Test
    fun idleGap_removesTheFramesInsideItAndKeepsTheClock() {
        val scene =
            SyntheticScene(
                people = listOf(stillPerson()),
                durationS = 4.0,
                fps = 20.0,
                idleGaps = listOf(TimeInterval(1.0, 3.0)),
            )
        assertThat(scene.frames).hasSize(40)
        assertThat(scene.frames.none { it.timeS >= 1.0 && it.timeS < 3.0 }).isTrue()
        val firstAfter = scene.frames[20]
        assertThat(firstAfter.timeS).isEqualTo(3.0)
        assertThat(firstAfter.index).isEqualTo(20)
        assertThat(firstAfter.frame.timestampNs - scene.frames[19].frame.timestampNs).isEqualTo(2_050_000_000L)
    }

    @Test
    fun path_isLinearBetweenWaypointsAndPersonExistsOnlyInsideIt() {
        val person =
            ScenePerson(
                id = 3,
                path = listOf(PathPoint(0.5, 100.0, 100.0, 200.0), PathPoint(1.5, 300.0, 200.0, 400.0)),
            )
        val scene = SyntheticScene(listOf(person), durationS = 2.0, fps = 20.0)
        assertThat(scene.frames.count { it.truth.isNotEmpty() }).isEqualTo(21)
        assertThat(scene.frames[9].truth).isEmpty()
        assertThat(scene.frames[9].detections).isEmpty()
        val middle = scene.frames[20].truth.single()
        assertThat(middle.personId).isEqualTo(3)
        assertThat(middle.headXImg).isWithin(1e-9).of(200.0)
        assertThat(middle.headYImg).isWithin(1e-9).of(150.0)
        assertThat(middle.heightImg).isWithin(1e-9).of(300.0)
        val detection = scene.frames[20].detections.single()
        assertThat(detection.boxBottomImg - detection.boxTopImg).isWithin(1e-3f).of(300f)
        assertThat(scene.frames[30].truth).hasSize(1)
        assertThat(scene.frames[31].truth).isEmpty()
    }

    @Test
    fun scoreFunction_isEvaluatedAtTheFrameTimeAndClamped() {
        val scene = SyntheticScene(listOf(stillPerson(score = { t -> 0.5 + 0.4 * t })), durationS = 2.0, fps = 20.0)
        val scores = scene.frames.map { it.detections.single().score }
        assertThat(scores[0]).isWithin(1e-6f).of(0.5f)
        assertThat(scores[10]).isWithin(1e-6f).of(0.7f)
        assertThat(scores[39]).isAtMost(1f)
    }

    @Test
    fun dropout_emitsNothingButTheTruthStaysAndTheMeasurementIsNaN() {
        val scene = SyntheticScene(listOf(stillPerson(dropouts = listOf(TimeInterval(0.5, 1.0)))), durationS = 2.0, fps = 20.0)
        assertThat(scene.frames).hasSize(40)
        assertThat(scene.frames.count { it.detections.isEmpty() }).isEqualTo(10)
        assertThat(scene.frames.all { it.truth.size == 1 }).isTrue()
        val dropped = scene.frames[12]
        assertThat(dropped.detections).isEmpty()
        val droppedTruth = dropped.truth.single()
        assertThat(droppedTruth.measuredHeadXImg.isNaN()).isTrue()
        assertThat(droppedTruth.headXImg).isEqualTo(headX)
        val shownTruth = scene.frames[5].truth.single()
        assertThat(shownTruth.measuredHeadXImg).isWithin(1e-3).of(headX)
        assertThat(shownTruth.measuredHeadYImg).isWithin(1e-3).of(headY)
    }

    @Test
    fun duplicate_emitsTheSamePersonTwiceWithASmallOffsetAndALowerScore() {
        val scene = SyntheticScene(listOf(stillPerson(duplicates = listOf(TimeInterval(0.5, 1.0)))), durationS = 2.0, fps = 20.0)
        assertThat(scene.frames.count { it.detections.size == 2 }).isEqualTo(10)
        assertThat(scene.frames.count { it.detections.size == 1 }).isEqualTo(30)
        val both = scene.frames[12].detections
        assertThat(both[1].score).isLessThan(both[0].score)
        assertThat(both[1].score).isGreaterThan(both[0].score - 0.1f)
        val shift = both[1].boxLeftImg - both[0].boxLeftImg
        assertThat(shift).isGreaterThan(0f)
        assertThat(shift).isLessThan(0.1f * height.toFloat())
        assertThat(scene.frames[12].truth).hasSize(1)
    }

    @Test
    fun falsePositive_addsAnExtraDetectionOfNobodyWithLowHeadConfidence() {
        val fp = FalsePositive(TimeInterval(0.5, 1.0), 900.0, 200.0, 120.0, 0.55)
        val scene = SyntheticScene(listOf(stillPerson()), durationS = 2.0, fps = 20.0, falsePositives = listOf(fp))
        assertThat(scene.frames.count { it.detections.size == 2 }).isEqualTo(10)
        assertThat(scene.frames.all { it.truth.size == 1 }).isTrue()
        val extra = scene.frames[12].detections[1]
        assertThat(extra.score).isWithin(1e-6f).of(0.55f)
        assertThat(extra.boxBottomImg - extra.boxTopImg).isWithin(1e-3f).of(120f)
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            assertThat(extra.keypointScores[k]).isEqualTo(SyntheticScene.LOW_CONFIDENCE)
        }
        assertThat(extra.keypointScores[TrackerConfig.HEAD_KEYPOINT_COUNT]).isEqualTo(SyntheticScene.KEYPOINT_CONFIDENCE)
        assertThat(scene.frames[2].detections).hasSize(1)
    }

    @Test
    fun keypointDropout_lowersOnlyTheChosenConfidencesAndKeepsThePositions() {
        val dropout = KeypointDropout(TimeInterval(0.5, 1.0), setOf(0, 1, 2))
        val scene = SyntheticScene(listOf(stillPerson(keypointDropouts = listOf(dropout))), durationS = 2.0, fps = 20.0)
        val inside = scene.frames[12].detections.single()
        val outside = scene.frames[2].detections.single()
        for (k in 0 until SyntheticScene.KEYPOINT_COUNT) {
            val expected = if (k <= 2) SyntheticScene.LOW_CONFIDENCE else SyntheticScene.KEYPOINT_CONFIDENCE
            assertThat(inside.keypointScores[k]).isEqualTo(expected)
            assertThat(outside.keypointScores[k]).isEqualTo(SyntheticScene.KEYPOINT_CONFIDENCE)
            assertThat(inside.keypointXImg(k)).isEqualTo(outside.keypointXImg(k))
            assertThat(inside.keypointYImg(k)).isEqualTo(outside.keypointYImg(k))
        }
        assertThat(scene.frames.count { it.detections.single().keypointScores[0] == SyntheticScene.LOW_CONFIDENCE }).isEqualTo(10)
    }
}
