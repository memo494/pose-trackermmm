package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.abs

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
        val goneAfterMs = DoubleArray(2)
        for ((n, fps) in listOf(20, 60).withIndex()) {
            val stepNs = NANOS_PER_S / fps
            val local = Tracker(config)
            var timeNs = START_NS
            for (k in 0 until 3) {
                local.update(frameAt(timeNs, person(600f)))
                timeNs += stepNs
            }
            val lastHitNs = timeNs - stepNs
            goneAfterMs[n] = -1.0
            for (k in 0 until MAX_EMPTY_FRAMES) {
                if (local.update(frameAt(timeNs)).size == 0) {
                    goneAfterMs[n] = (timeNs - lastHitNs) / NANOS_PER_MS
                    break
                }
                timeNs += stepNs
            }
            // The step of 1/60 s is cut to whole nanoseconds, so the sum may fall a few micro-seconds short of the limit.
            assertThat(goneAfterMs[n]).isAtLeast(config.trackMaxLostMs - ROUNDING_MS)
            assertThat(goneAfterMs[n]).isAtMost(config.trackMaxLostMs + stepNs / NANOS_PER_MS + ROUNDING_MS)
        }
        assertThat(goneAfterMs[0] - goneAfterMs[1]).isWithin(STEP_MS + ROUNDING_MS).of(0.0)
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
        val strict = Tracker(TrackerConfig(trackTentativeMaxMisses = 0))
        feed(strict, 0, person(200f), person(600f), person(1000f))

        val list = feed(strict, 1, person(200f), person(1000f))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.id(1)).isEqualTo(3)
    }

    @Test
    fun withToleranceZero_aMissRemovesATentativeTrack() {
        val strict = Tracker(TrackerConfig(trackTentativeMaxMisses = 0))
        feed(strict, 0, person(600f))
        feed(strict, 1, person(600f))

        val list = feed(strict, 2)

        assertThat(list.size).isEqualTo(0)
        assertThat(strict.removeCount).isEqualTo(0)
    }

    @Test
    fun aTentativeTrack_survivesOneMissAndIsConfirmedOnTheThirdHit() {
        feed(tracker, 0, person(600f))
        val missed = feed(tracker, 1)

        assertThat(missed.size).isEqualTo(1)
        assertThat(missed.id(0)).isEqualTo(1)
        assertThat(missed.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(missed.isShown(0)).isFalse()
        assertThat(missed.lostMs(0)).isEqualTo(0f)

        val second = feed(tracker, 2, person(600f))
        assertThat(second.state(0)).isEqualTo(TrackState.TENTATIVE)

        val third = feed(tracker, 3, person(600f))
        assertThat(third.size).isEqualTo(1)
        assertThat(third.id(0)).isEqualTo(1)
        assertThat(third.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(third.isShown(0)).isTrue()
        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.confirmCount).isEqualTo(1)
        assertThat(tracker.recoverCount).isEqualTo(0)
    }

    @Test
    fun aMissDoesNotResetTheHitCounter() {
        feed(tracker, 0, person(600f))
        feed(tracker, 1, person(600f))
        feed(tracker, 2)

        val list = feed(tracker, 3, person(600f))

        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
    }

    @Test
    fun twoMissesInARow_removeATentativeTrack_andTheNextHitStartsANewOne() {
        feed(tracker, 0, person(600f))
        assertThat(feed(tracker, 1).size).isEqualTo(1)

        val gone = feed(tracker, 2)
        assertThat(gone.size).isEqualTo(0)
        assertThat(tracker.removeCount).isEqualTo(0)

        val again = feed(tracker, 3, person(600f))
        assertThat(again.size).isEqualTo(1)
        assertThat(again.id(0)).isEqualTo(2)
        assertThat(again.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(tracker.birthCount).isEqualTo(2)
    }

    @Test
    fun aHitResetsTheMissCounter_soAHitMissHitMissHitPersonIsShown() {
        feed(tracker, 0, person(600f))
        feed(tracker, 1)
        feed(tracker, 2, person(600f))
        val secondMiss = feed(tracker, 3)
        assertThat(secondMiss.size).isEqualTo(1)
        assertThat(secondMiss.state(0)).isEqualTo(TrackState.TENTATIVE)

        val list = feed(tracker, 4, person(600f))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.isShown(0)).isTrue()
        assertThat(tracker.birthCount).isEqualTo(1)
    }

    @Test
    fun withToleranceTwo_aTentativeTrackSurvivesTwoMissesButNotThree() {
        val patient = Tracker(TrackerConfig(trackTentativeMaxMisses = 2))
        feed(patient, 0, person(600f))
        assertThat(feed(patient, 1).size).isEqualTo(1)
        assertThat(feed(patient, 2).size).isEqualTo(1)

        assertThat(feed(patient, 3).size).isEqualTo(0)
    }

    @Test
    fun aMissedTentativeTrack_coastsWithItsVelocityDecayedLikeALostOne() {
        val tentative = Tracker(TrackerConfig(trackConfirmHits = COAST_HITS + 1))
        val confirmed = Tracker(TrackerConfig(trackConfirmHits = 1))
        for (k in 0 until COAST_HITS) {
            feed(tentative, k, person(300f + COAST_STEP * k))
            feed(confirmed, k, person(300f + COAST_STEP * k))
        }

        val coasting = feed(tentative, COAST_HITS)
        val lost = feed(confirmed, COAST_HITS)

        assertThat(coasting.size).isEqualTo(1)
        assertThat(coasting.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(lost.state(0)).isEqualTo(TrackState.LOST)
        assertThat(coasting.headXImg(0)).isEqualTo(lost.headXImg(0))
        assertThat(coasting.velocityXImgPerS(0)).isEqualTo(lost.velocityXImgPerS(0))
        assertThat(coasting.velocityXImgPerS(0)).isGreaterThan(0f)
        assertThat(coasting.lostMs(0)).isEqualTo(0f)
    }

    @Test
    fun onIdle_leavesATentativeTrackThatHasMissedAFrameTentativeAndHidden() {
        feed(tracker, 0, person(600f))
        feed(tracker, 1)

        val list = tracker.onIdle(config.trackIdlePromoteNs)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.TENTATIVE)
        assertThat(list.isShown(0)).isFalse()
        assertThat(tracker.idlePromoteCount).isEqualTo(0)
        assertThat(tracker.confirmCount).isEqualTo(0)
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

        val list = tracker.onIdle(config.trackIdlePromoteNs)

        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.headXImg(0)).isEqualTo(headX)
        assertThat(list.lostMs(0)).isWithin(0.5f).of(STEP_MS)
    }

    @Test
    fun onIdle_removesALostTrackOnceItsLostTimePlusTheIdleTimeExceedsTheLimit() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        feed(tracker, 3)
        val lastIdleNsToKeep = ((config.trackMaxLostMs - STEP_MS) * NANOS_PER_MS).toLong()

        val kept = tracker.onIdle(lastIdleNsToKeep)
        assertThat(kept.size).isEqualTo(1)
        assertThat(kept.state(0)).isEqualTo(TrackState.LOST)
        assertThat(tracker.removeCount).isEqualTo(0)

        val gone = tracker.onIdle(lastIdleNsToKeep + NANOS_PER_MS.toLong() * 2L)
        assertThat(gone.size).isEqualTo(0)
        assertThat(tracker.removeCount).isEqualTo(1)
    }

    @Test
    fun onIdle_doesNotChangeLostMs_soRepeatedCallsWithGrowingIdleTimeStayConsistent() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        feed(tracker, 3)

        tracker.onIdle(config.trackIdlePromoteNs / 2L)
        val list = tracker.onIdle(config.trackIdlePromoteNs * 3L / 2L)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.lostMs(0)).isWithin(0.5f).of(STEP_MS)
        assertThat(feed(tracker, 4, person(600f)).state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.recoverCount).isEqualTo(1)
    }

    @Test
    fun onIdle_keepsAConfirmedTrackThatIsNotLost_evenAfterTenSeconds() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        val list = tracker.onIdle(10L * NANOS_PER_S)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.removeCount).isEqualTo(0)
    }

    @Test
    fun onIdle_removesOnlyTheLostTrack_andKeepsTheConfirmedOne() {
        for (k in 0 until 3) feed(tracker, k, person(300f), person(900f))
        feed(tracker, 3, person(900f))

        val list = tracker.onIdle(10L * NANOS_PER_S)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.headXImg(0)).isWithin(HEAD_X_TOLERANCE_IMG).of(900f)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.removeCount).isEqualTo(1)
    }

    @Test
    fun onIdle_removesALostTrackAndPromotesATentativeOneInTheSameCall() {
        for (k in 0 until 3) feed(tracker, k, person(300f))
        feed(tracker, 3, person(900f))
        val idleNs = ((config.trackMaxLostMs - STEP_MS) * NANOS_PER_MS).toLong() + NANOS_PER_MS.toLong() * 2L
        assertThat(idleNs).isAtLeast(config.trackIdlePromoteNs)

        val list = tracker.onIdle(idleNs)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.removeCount).isEqualTo(1)
        assertThat(tracker.idlePromoteCount).isEqualTo(1)
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

    /**
     * Was "a matched detection with a zero variance is a miss". With `minBoxHeightImg` the variance of a usable box
     * is at least `(0.005 * 0.5)^2 / 1` (never zero), and a box so high that the variance overflows is forbidden
     * by the association cost, so no construction through the public API reaches the zero-variance guard of
     * `applyHit` any more (the guard stays in the code). The purpose, a degenerate detection never corrupts a
     * track, is kept: the sub-pixel box is ignored, the track misses the frame and stays finite.
     */
    @Test
    fun aDegenerateDetectionNextToATrack_isIgnoredAndTheTrackMissesTheFrameAndStaysFinite() {
        for (k in 0 until 3) feed(tracker, k, person(600f))
        val control = Tracker(config)
        for (k in 0 until 3) feed(control, k, person(600f))
        val tiny = tinyDetection()
        val head = HeadPoint()
        HeadMeasurement.of(tiny, config, head)
        // Precondition: the box is unusable, so the detection never reaches association.
        assertThat(head.valid).isFalse()

        val list = feed(tracker, 3, tiny)
        val expected = feed(control, 3)

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.lostMs(0)).isWithin(0.5f).of(STEP_MS)
        assertThat(list.headXImg(0).isFinite()).isTrue()
        assertThat(list.headYImg(0).isFinite()).isTrue()
        assertThat(list.heightImg(0)).isEqualTo(expected.heightImg(0))
        assertThat(list.headXImg(0)).isEqualTo(expected.headXImg(0))
        assertThat(list.headYImg(0)).isEqualTo(expected.headYImg(0))
        assertThat(list.boxLeftImg(0)).isEqualTo(expected.boxLeftImg(0))
        assertThat(tracker.recoverCount).isEqualTo(0)
        assertThat(tracker.birthCount).isEqualTo(1)
    }

    @Test
    fun aDetectionOnePixelHigh_isIgnored_theTrackMissesTheFrameAndNoTrackIsBorn() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        val list = feed(tracker, 3, person(900f, heightImg = 1f))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(tracker.birthCount).isEqualTo(1)
    }

    @Test
    fun theSameDetectionOnePixelHigh_startsATrack_whenTheMinimumBoxHeightIsLowEnough() {
        val lenient = Tracker(TrackerConfig(minBoxHeightImg = TrackerConfig.MIN_MIN_BOX_HEIGHT_IMG))
        for (k in 0 until 3) feed(lenient, k, person(600f))

        feed(lenient, 3, person(900f, heightImg = 1f))

        assertThat(lenient.birthCount).isEqualTo(2)
    }

    @Test
    fun withOneConfirmHit_aTrackIsShownFromItsFirstDetection() {
        val once = Tracker(TrackerConfig(trackConfirmHits = 1))

        val list = feed(once, 0, person(600f))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.isShown(0)).isTrue()
        assertThat(once.birthCount).isEqualTo(1)
        assertThat(once.confirmCount).isEqualTo(1)
        assertThat(once.mostShownAtOnce).isEqualTo(1)
    }

    @Test
    fun aLostTrackCoastingOffTheLeftEdge_isRemovedInTheFrameItPassesTheMargin() {
        val emptyFrames = coastOffTheImage(startXImg = 120f, startYImg = ROW_Y, stepXImg = -COAST_STEP, stepYImg = 0f)

        assertThat(emptyFrames).isLessThan(MAX_COAST_FRAMES)
    }

    @Test
    fun aLostTrackCoastingOffTheTopEdge_isRemovedInTheFrameItPassesTheMargin() {
        val emptyFrames = coastOffTheImage(startXImg = 600f, startYImg = 120f, stepXImg = 0f, stepYImg = -COAST_STEP)

        assertThat(emptyFrames).isLessThan(MAX_COAST_FRAMES)
    }

    @Test
    fun aLostTrackCoastingOffTheBottomEdge_isRemovedInTheFrameItPassesTheMargin() {
        val startYImg = IMAGE_HEIGHT - 120f

        val emptyFrames = coastOffTheImage(startXImg = 600f, startYImg = startYImg, stepXImg = 0f, stepYImg = COAST_STEP)

        assertThat(emptyFrames).isLessThan(MAX_COAST_FRAMES)
    }

    @Test
    fun aLostTrackCoastingInsideTheImage_staysUntilTheMaxLostTime() {
        val local = Tracker(config)
        for (k in 0 until COAST_HITS) local.update(frameAt(START_NS + k * STEP_NS, personAt(600f - k * COAST_STEP, ROW_Y)))

        var list: TrackList = local.update(frameAt(START_NS + COAST_HITS * STEP_NS))
        for (k in 1 until MAX_COAST_FRAMES) list = local.update(frameAt(START_NS + (COAST_HITS + k) * STEP_NS))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(local.removeCount).isEqualTo(0)
    }

    @Test
    fun aGapStep_correctsMoreThanAnOrdinaryStep_becauseItsNoiseIsInflated() {
        val ordinary = Tracker(config)
        val gap = Tracker(config)
        for (k in 0 until 3) {
            feed(ordinary, k, person(600f))
            feed(gap, k, person(600f))
        }
        val lastHitNs = START_NS + 2 * STEP_NS
        val maxDtNs = config.trackMaxDtMs.toLong() * NANOS_PER_MS.toLong()
        val displaced = person(600f + DISPLACEMENT)

        val afterOrdinary = ordinary.update(frameAt(lastHitNs + maxDtNs, displaced))
        val afterGap = gap.update(frameAt(lastHitNs + GAP_NS, displaced))

        assertThat(afterOrdinary.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(afterGap.state(0)).isEqualTo(TrackState.CONFIRMED)
        val ordinaryErrorImg = abs(afterOrdinary.headXImg(0) - (600f + DISPLACEMENT))
        val gapErrorImg = abs(afterGap.headXImg(0) - (600f + DISPLACEMENT))
        assertThat(ordinaryErrorImg).isGreaterThan(MIN_ORDINARY_ERROR)
        assertThat(gapErrorImg).isLessThan(ordinaryErrorImg)
    }

    @Test
    fun aLostTrackWhoseHeightShrinksToZero_isRemovedBeforeTheMaxLostTimeAndCounted() {
        val heights = floatArrayOf(100f, 80f, 60f, 40f)
        for ((k, heightImg) in heights.withIndex()) feed(tracker, k, person(600f, heightImg = heightImg))
        assertThat(tracker.confirmCount).isEqualTo(1)
        assertThat(tracker.removeCount).isEqualTo(0)

        var emptyFrames = 0
        while (emptyFrames < MAX_COAST_FRAMES && feed(tracker, heights.size + emptyFrames).size > 0) emptyFrames++

        // The head stays well inside the image, so only the health check can remove it, and 8 frames are 400 ms < 800 ms.
        assertThat(emptyFrames).isLessThan(MAX_COAST_FRAMES)
        assertThat(emptyFrames * STEP_MS).isLessThan(config.trackMaxLostMs)
        assertThat(tracker.removeCount).isEqualTo(1)
    }

    @Test
    fun matchedDetectionIndex_followsTheDetectionNotTheTrackOrder() {
        for (k in 0 until 3) feed(tracker, k, person(300f), person(900f))

        val list = feed(tracker, 3, person(905f), person(305f))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.matchedDetectionIndex(0)).isEqualTo(1)
        assertThat(list.matchedDetectionIndex(1)).isEqualTo(0)
    }

    @Test
    fun matchedDetectionIndex_isMinusOneForATrackWithoutAMatchInThatFrame() {
        for (k in 0 until 3) feed(tracker, k, person(300f), person(900f))

        val list = feed(tracker, 3, person(900f))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.matchedDetectionIndex(0)).isEqualTo(-1)
        assertThat(list.matchedDetectionIndex(1)).isEqualTo(0)
    }

    @Test
    fun matchedDetectionIndex_ofANewTrack_isTheDetectionThatStartedIt() {
        val list = feed(tracker, 0, person(300f, BELOW_BIRTH_SCORE), person(900f))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.matchedDetectionIndex(0)).isEqualTo(1)
    }

    @Test
    fun matchedDetectionIndex_ofATrackThatBecomesConfirmed_isTheDetectionOfThatFrame() {
        feed(tracker, 0, person(900f))
        feed(tracker, 1, person(900f))

        val list = feed(tracker, 2, person(300f, BELOW_BIRTH_SCORE), person(900f))

        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.matchedDetectionIndex(0)).isEqualTo(1)
    }

    @Test
    fun matchedDetectionIndex_afterAnIgnoredFrame_isMinusOne() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        val ignored = tracker.update(frameAt(START_NS + 2 * STEP_NS, person(600f)))

        assertThat(ignored.matchedDetectionIndex(0)).isEqualTo(-1)
    }

    @Test
    fun matchedDetectionIndex_staysAsTheLastUpdateLeftItAfterOnIdle() {
        for (k in 0 until 3) feed(tracker, k, person(600f))

        val list = tracker.onIdle(config.trackIdlePromoteNs)

        assertThat(list.matchedDetectionIndex(0)).isEqualTo(0)
    }

    @Test
    fun matchedDetectionIndex_outsideTheList_throws() {
        feed(tracker, 0, person(600f))

        assertThrows(IndexOutOfBoundsException::class.java) { feed(tracker, 1, person(600f)).matchedDetectionIndex(1) }
    }

    /**
     * Feeds [COAST_HITS] detections that move by (`stepXImg`, `stepYImg`) per frame from (`startXImg`, `startYImg`),
     * then empty frames until the track is gone; returns how many empty frames left it alive (at most [MAX_COAST_FRAMES]).
     */
    private fun coastOffTheImage(
        startXImg: Float,
        startYImg: Float,
        stepXImg: Float,
        stepYImg: Float,
    ): Int {
        val local = Tracker(config)
        for (k in 0 until COAST_HITS) {
            local.update(frameAt(START_NS + k * STEP_NS, personAt(startXImg + k * stepXImg, startYImg + k * stepYImg)))
        }
        assertThat(local.confirmCount).isEqualTo(1)
        var emptyFrames = 0
        while (emptyFrames < MAX_COAST_FRAMES && local.update(frameAt(START_NS + (COAST_HITS + emptyFrames) * STEP_NS)).size > 0) {
            emptyFrames++
        }
        assertThat(local.removeCount).isEqualTo(if (emptyFrames < MAX_COAST_FRAMES) 1 else 0)
        return emptyFrames
    }

    /** A person at the head point (`xImg`, `yImg`) with the default height; its box edges follow the head. */
    private fun personAt(
        xImg: Float,
        yImg: Float,
    ) = HeadTestDetections.person(xImg, yImg, HEIGHT)

    /** A box 1e-25 px high at the top of the image whose five head keypoints sit on the head of [person]: variance 0 in float. */
    private fun tinyDetection(): PoseDetection {
        val keypointsXyImg = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2) { if (it % 2 == 0) 600f else ROW_Y }
        return PoseDetection(590f, 0f, 610f, TINY_HEIGHT, 0.9f, keypointsXyImg, HeadTestDetections.allHead(0.9f))
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
        const val ROUNDING_MS = 0.01
        const val HEAD_X_TOLERANCE_IMG = 20f
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
        const val COAST_HITS = 4
        const val COAST_STEP = 30f
        const val MAX_COAST_FRAMES = 8
        const val DISPLACEMENT = 30f
        const val MIN_ORDINARY_ERROR = 0.1f
        const val TINY_HEIGHT = 1e-25f
    }
}
