package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Unit tests of [Tracker] (docs/plans/M3.md, T6); the acceptance scenarios are in `TrackerScenarioTest`. */
class TrackerTest {
    private val config = TrackerConfig()
    private val tracker = Tracker(config)

    @Test
    fun aRepeatedTimestamp_isIgnoredAndTheListStaysTheSame() {
        feed(tracker, 0, person(600f))
        val before = feed(tracker, 1, person(600f))
        val headX = before.headXImg(0)

        val after = tracker.update(frameAt(START_NS + STEP_NS, person(900f)))

        assertThat(after.size).isEqualTo(1)
        assertThat(after.headXImg(0)).isEqualTo(headX)
    }

    @Test
    fun aBackwardTimestamp_isIgnoredAndCountsNoHit() {
        feed(tracker, 0, person(600f))
        feed(tracker, 1, person(600f))

        val backward = tracker.update(frameAt(START_NS + STEP_NS / 2, person(600f)))
        assertThat(backward.state(0)).isEqualTo(TrackState.TENTATIVE)

        val next = feed(tracker, 2, person(600f))
        assertThat(next.state(0)).isEqualTo(TrackState.CONFIRMED)
    }

    @Test
    fun idsStartAtOne_andAFrameWithoutPeopleGivesAnEmptyList() {
        assertThat(feed(tracker, 0).size).isEqualTo(0)

        val list = feed(tracker, 1, person(600f))

        assertThat(list.id(0)).isEqualTo(1)
    }

    @Test
    fun reset_forgetsTheTracksButKeepsTheIdCounter() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        tracker.reset()
        assertThat(tracker.onIdle(config.trackIdlePromoteNs).size).isEqualTo(0)

        val list = feed(tracker, 0, person(600f))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.id(0)).isEqualTo(2)
        assertThat(list.state(0)).isEqualTo(TrackState.TENTATIVE)
    }

    @Test
    fun reset_keepsTheCounters() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        tracker.reset()

        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.confirmCount).isEqualTo(1)
        assertThat(tracker.mostShownAtOnce).isEqualTo(1)
    }

    @Test
    fun theListIsOneObjectOwnedByTheTracker() {
        val first = feed(tracker, 0, person(600f))
        val second = feed(tracker, 1, person(600f))

        assertThat(second).isSameInstanceAs(first)
        assertThat(tracker.onIdle(0L)).isSameInstanceAs(first)
    }

    @Test
    fun anIndexOutsideTheList_throws() {
        val list = feed(tracker, 0, person(600f))

        assertThrows(IndexOutOfBoundsException::class.java) { list.id(1) }
        assertThrows(IndexOutOfBoundsException::class.java) { list.headXImg(-1) }
    }

    @Test
    fun theTrackCap_stopsBirths() {
        val capped = Tracker(TrackerConfig(maxTracks = 2))

        val list = feed(capped, 0, person(200f), person(500f), person(800f))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.id(1)).isEqualTo(2)
    }

    @Test
    fun moreThanThirtyDetections_useTheFirstThirtyAndNeverThrow() {
        val many = Array(MANY_DETECTIONS) { person(SPREAD_START_X + it * SPREAD_STEP_X, heightImg = SMALL_HEIGHT) }

        val list = feed(tracker, 0, *many)

        assertThat(list.size).isEqualTo(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
        assertThat(list.headXImg(TrackerConfig.MAX_DETECTIONS_PER_FRAME - 1))
            .isWithin(1f)
            .of(SPREAD_START_X + (TrackerConfig.MAX_DETECTIONS_PER_FRAME - 1) * SPREAD_STEP_X)
    }

    @Test
    fun twoTrackersOnTheSameFrames_giveTheSameLists() {
        val people =
            listOf(
                ScenePerson(1, listOf(PathPoint(0.0, 200.0, 150.0, 200.0), PathPoint(3.0, 900.0, 150.0, 200.0))),
                ScenePerson(2, listOf(PathPoint(0.0, 900.0, 160.0, 200.0), PathPoint(3.0, 200.0, 160.0, 200.0))),
            )
        val scene = SyntheticScene(people, 3.0, fps = 30.0, seed = 5L, keypointNoiseRatio = 0.01, boxNoiseRatio = 0.01)
        val other = Tracker(config)

        for (frame in scene.frames) {
            val a = tracker.update(frame.frame)
            val b = other.update(frame.frame)
            assertThat(b.size).isEqualTo(a.size)
            for (i in 0 until a.size) {
                assertThat(b.id(i)).isEqualTo(a.id(i))
                assertThat(b.state(i)).isEqualTo(a.state(i))
                assertThat(b.headXImg(i)).isEqualTo(a.headXImg(i))
                assertThat(b.headYImg(i)).isEqualTo(a.headYImg(i))
                assertThat(b.velocityXImgPerS(i)).isEqualTo(a.velocityXImgPerS(i))
                assertThat(b.boxLeftImg(i)).isEqualTo(a.boxLeftImg(i))
                assertThat(b.heightImg(i)).isEqualTo(a.heightImg(i))
            }
        }
    }

    @Test
    fun aLowScoreDetection_keepsAnExistingTrackButStartsNone() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        feed(tracker, 3, person(600f, score = BELOW_BIRTH_SCORE))
        val list = feed(tracker, 4, person(600f, score = BELOW_BIRTH_SCORE), person(1000f, score = BELOW_BIRTH_SCORE))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.lostMs(0)).isEqualTo(0f)
    }

    @Test
    fun aDetectionBelowTheBirthScoreNextToNobody_startsNoTrack_andOneAtItDoes() {
        assertThat(feed(tracker, 0, person(600f, score = BELOW_BIRTH_SCORE)).size).isEqualTo(0)

        val atBirthScore = Tracker(config)
        val list = feed(atBirthScore, 0, person(600f, score = config.trackBirthScore))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(list.isShown(0)).isFalse()
    }

    @Test
    fun aDetectionBelowTheScoreThreshold_isIgnoredEvenForAnExistingTrack() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        val list = feed(tracker, 3, person(600f, score = config.scoreThreshold / 2f))

        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
    }

    @Test
    fun aLostTrack_isConfirmedAgainOnAHit() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        feed(tracker, 3)
        val lost = feed(tracker, 4)
        assertThat(lost.state(0)).isEqualTo(TrackState.LOST)
        assertThat(lost.isShown(0)).isTrue()
        assertThat(lost.lostMs(0)).isWithin(0.5f).of(2f * STEP_MS)

        val back = feed(tracker, 5, person(600f))

        assertThat(back.size).isEqualTo(1)
        assertThat(back.id(0)).isEqualTo(1)
        assertThat(back.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(back.lostMs(0)).isEqualTo(0f)
        assertThat(tracker.recoverCount).isEqualTo(1)
    }

    @Test
    fun aLostTrack_isRemovedAfterTheMaxLostTime_atTheSameTimeAt20And60Fps() {
        for (fps in listOf(20, 60)) {
            val stepNs = NANOS_PER_S / fps
            val local = Tracker(config)
            var timeNs = START_NS
            for (k in 0 until 3) {
                local.update(frameAt(timeNs, person(600f)))
                timeNs += stepNs
            }
            val lastHitNs = timeNs - stepNs
            var goneAfterMs = -1.0
            for (k in 0 until MAX_EMPTY_FRAMES) {
                val list = local.update(frameAt(timeNs))
                if (list.size == 0) {
                    goneAfterMs = (timeNs - lastHitNs) / NANOS_PER_MS
                    break
                }
                timeNs += stepNs
            }
            val stepMs = stepNs / NANOS_PER_MS
            assertThat(goneAfterMs).isGreaterThan(config.trackMaxLostMs.toDouble())
            assertThat(goneAfterMs).isAtMost(config.trackMaxLostMs + stepMs + 0.001)
        }
    }

    @Test
    fun aFiveSecondGap_agesALostTrackByAtMostTheMaxStep() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        feed(tracker, 3)
        val gapNs = START_NS + 3 * STEP_NS + GAP_NS

        val list = tracker.update(frameAt(gapNs))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.lostMs(0)).isAtMost(STEP_MS + config.trackMaxDtMs + 0.01f)
        assertThat(list.lostMs(0)).isWithin(0.5f).of(STEP_MS + config.trackMaxDtMs)
    }

    @Test
    fun aMovingPerson_isFollowedByTheHeadBoxAndVelocity() {
        val path = listOf(PathPoint(0.0, 200.0, 150.0, 200.0), PathPoint(2.0, 1000.0, 150.0, 200.0))
        val scene = SyntheticScene(listOf(ScenePerson(1, path)), 2.0, fps = 20.0)
        var list: TrackList = tracker.update(scene.frames[0].frame)
        for (i in 1 until scene.frames.size) list = tracker.update(scene.frames[i].frame)
        val truth = scene.frames.last().truth[0]

        assertThat(list.size).isEqualTo(1)
        assertThat(list.headXImg(0).toDouble()).isWithin(2.0).of(truth.headXImg)
        assertThat(list.headYImg(0).toDouble()).isWithin(2.0).of(truth.headYImg)
        assertThat(list.velocityXImgPerS(0)).isWithin(EXPECTED_SPEED * VELOCITY_TOLERANCE).of(EXPECTED_SPEED)
        assertThat(list.velocityYImgPerS(0)).isWithin(EXPECTED_SPEED * VELOCITY_TOLERANCE).of(0f)
        assertThat(list.heightImg(0)).isWithin(2f).of(HEIGHT)
        assertThat(list.boxLeftImg(0).toDouble()).isWithin(2.0).of(truth.headXImg - BOX_HALF_WIDTH_IN_H * HEIGHT)
        assertThat(list.boxRightImg(0).toDouble()).isWithin(2.0).of(truth.headXImg + BOX_HALF_WIDTH_IN_H * HEIGHT)
        assertThat(list.boxTopImg(0).toDouble()).isWithin(2.0).of(truth.headYImg - BOX_TOP_ABOVE_HEAD_IN_H * HEIGHT)
        assertThat(list.boxBottomImg(0)).isWithin(2f).of(list.boxTopImg(0) + HEIGHT)
        assertThat(list.score(0)).isEqualTo(0.9f)
        assertThat(list.isHeadEstimated(0)).isFalse()
    }

    @Test
    fun anUnusableBox_isIgnored() {
        val upsideDown = withBox(person(600f), top = 300f, bottom = 100f)
        val nanEdge = withBox(person(600f), left = Float.NaN)

        assertThat(feed(tracker, 0, upsideDown, nanEdge).size).isEqualTo(0)
    }

    @Test
    fun aNonFiniteScore_isIgnoredAndTheGoodDetectionStillStartsATrack() {
        val nanScore = person(300f, score = Float.NaN)
        val infiniteScore = person(900f, score = Float.POSITIVE_INFINITY)

        val list = feed(tracker, 0, nanScore, person(600f), infiniteScore)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.headXImg(0)).isWithin(1f).of(600f)
    }

    @Test
    fun anUnmatchedTentativeTrack_isRemovedAndTheOthersKeepTheirOrder() {
        feed(tracker, 0, person(200f), person(600f), person(1000f))

        val list = feed(tracker, 1, person(200f), person(1000f))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.id(1)).isEqualTo(3)
    }

    @Test
    fun onIdle_promotesTentativeTracksOnlyAtThePromoteTime() {
        feed(tracker, 0, person(600f))

        val below = tracker.onIdle(config.trackIdlePromoteNs - 1L)
        assertThat(below.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(below.isShown(0)).isFalse()
        assertThat(tracker.idlePromoteCount).isEqualTo(0)

        val at = tracker.onIdle(config.trackIdlePromoteNs)
        assertThat(at.size).isEqualTo(1)
        assertThat(at.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(at.isShown(0)).isTrue()
        assertThat(tracker.idlePromoteCount).isEqualTo(1)
    }

    @Test
    fun onIdle_changesNothingElse() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        feed(tracker, 3)
        val headX = tracker.onIdle(0L).headXImg(0)

        val list = tracker.onIdle(config.trackIdlePromoteNs * 10L)

        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.headXImg(0)).isEqualTo(headX)
        assertThat(list.lostMs(0)).isWithin(0.5f).of(STEP_MS)
    }

    @Test
    fun theCounters_followABuiltSequence() {
        // Frames 0-2: one person, born then confirmed by hits.
        for (k in 0 until 3) feed(tracker, k, person(600f))
        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.confirmCount).isEqualTo(1)
        assertThat(tracker.mostShownAtOnce).isEqualTo(1)

        // Frame 3 misses, frame 4 hits again: LOST then CONFIRMED.
        feed(tracker, 3)
        feed(tracker, 4, person(600f))
        assertThat(tracker.recoverCount).isEqualTo(1)
        assertThat(tracker.removeCount).isEqualTo(0)

        // Empty frames until the track is gone: one removal of a confirmed track.
        var k = 5
        while (feed(tracker, k).size > 0 && k < MAX_EMPTY_FRAMES) k++
        assertThat(tracker.removeCount).isEqualTo(1)

        // A new person is only tentative: promoted by onIdle, which is not a confirmation by hits.
        k++
        feed(tracker, k, person(300f))
        assertThat(tracker.birthCount).isEqualTo(2)
        tracker.onIdle(config.trackIdlePromoteNs)
        assertThat(tracker.idlePromoteCount).isEqualTo(1)
        assertThat(tracker.confirmCount).isEqualTo(1)

        // A tentative track that vanishes is no removal.
        tracker.reset()
        k++
        feed(tracker, k, person(900f))
        feed(tracker, k + 1)
        assertThat(tracker.birthCount).isEqualTo(3)
        assertThat(tracker.removeCount).isEqualTo(1)
        assertThat(tracker.mostShownAtOnce).isEqualTo(1)
    }

    @Test
    fun theCounters_countTheMostShownAtOnce() {
        for (k in 0 until 3) feed(tracker, k, person(200f), person(900f))

        assertThat(tracker.mostShownAtOnce).isEqualTo(2)
        assertThat(tracker.birthCount).isEqualTo(2)
    }

    /** A frame with the capture time [timestampNs]. */
    private fun frameAt(
        timestampNs: Long,
        vararg detections: PoseDetection,
    ) = DetectionFrame(timestampNs, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())

    private fun feed(
        target: Tracker,
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList = target.update(frameAt(START_NS + index * STEP_NS, *detections))

    private fun person(
        xImg: Float,
        score: Float = 0.9f,
        heightImg: Float = HEIGHT,
    ): PoseDetection {
        val base = HeadTestDetections.person(xImg, ROW_Y, heightImg)
        return PoseDetection(
            base.boxLeftImg,
            base.boxTopImg,
            base.boxRightImg,
            base.boxBottomImg,
            score,
            base.keypointsXyImg,
            base.keypointScores,
        )
    }

    private fun withBox(
        source: PoseDetection,
        left: Float = source.boxLeftImg,
        top: Float = source.boxTopImg,
        bottom: Float = source.boxBottomImg,
    ) = PoseDetection(left, top, source.boxRightImg, bottom, source.score, source.keypointsXyImg, source.keypointScores)

    private companion object {
        const val START_NS = 5_000_000_000L
        const val NANOS_PER_S = 1_000_000_000L
        const val NANOS_PER_MS = 1_000_000.0
        const val STEP_NS = 50_000_000L
        const val STEP_MS = 50f
        const val GAP_NS = 5_000_000_000L
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val SMALL_HEIGHT = 20f
        const val BOX_HALF_WIDTH_IN_H = HeadTestDetections.BOX_HALF_WIDTH_IN_H
        const val BOX_TOP_ABOVE_HEAD_IN_H = HeadTestDetections.BOX_TOP_ABOVE_HEAD_IN_H
        const val BELOW_BIRTH_SCORE = 0.20f
        const val MANY_DETECTIONS = 40
        const val SPREAD_START_X = 20f
        const val SPREAD_STEP_X = 30f
        const val MAX_EMPTY_FRAMES = 200
        const val EXPECTED_SPEED = 400f
        const val VELOCITY_TOLERANCE = 0.1f
    }
}
