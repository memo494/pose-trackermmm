package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The association-miss diagnostics of [Tracker] (docs/plans/M4.md, M4e T1): a track born next to a LOST one is
 * recorded with the values the matcher used; the records change nothing about the tracks.
 */
class AssociationMissTest {
    private val config = TrackerConfig()
    private val tracker = Tracker(config)

    @Test
    fun aBirthFarFromTheLostTrack_leavesNoRecord() {
        confirmedThenLost(tracker)

        feed(tracker, SHORT_BIRTH_FRAME, person(600f + FAR_OFFSET_IMG))

        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocMisses).isEqualTo(0)
    }

    @Test
    fun aBirthNextToTheLostTrack_outsideTheGate_isRecordedWithTheMatcherValues() {
        confirmedThenLost(tracker)

        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(1)
        val miss = tracker.miss(0)
        assertThat(miss.lostId).isEqualTo(1)
        assertThat(miss.newId).isEqualTo(2)
        assertThat(miss.reason).isEqualTo(AssociationMissReason.GATE)
        assertThat(miss.highStage).isTrue()
        assertThat(miss.score).isEqualTo(0.9f)
        assertThat(miss.lostMs).isWithin(0.5f).of((SHORT_BIRTH_FRAME - HITS + 1) * STEP_MS)
        assertThat(miss.gateChi2).isGreaterThan(config.trackGateChi2)
        assertThat(miss.iou).isEqualTo(0f)
        assertThat(miss.headRatio).isWithin(RATIO_TOLERANCE).of(NEAR_OFFSET_IMG / (config.assocHeadScaleRatio * HEIGHT))
        assertThat(miss.hRatio).isWithin(RATIO_TOLERANCE).of(1f)
        assertThat(miss.cost).isWithin(RATIO_TOLERANCE).of(config.assocIouWeight + config.assocHeadWeight)
        assertThat(tracker.assocMisses).isEqualTo(1)
        assertThat(tracker.assocMissGate).isEqualTo(1)
        assertThat(tracker.assocMissCost).isEqualTo(0)
        assertThat(tracker.assocMissTaken).isEqualTo(0)
        assertThat(tracker.assocMissLowBirth).isEqualTo(0)
    }

    @Test
    fun aBirthInsideTheGate_butAboveTheCostLimit_isRecordedAsCost() {
        confirmedThenLost(tracker, birthFrame = LONG_BIRTH_FRAME)

        feed(tracker, LONG_BIRTH_FRAME, person(600f + COST_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(1)
        val miss = tracker.miss(0)
        assertThat(miss.reason).isEqualTo(AssociationMissReason.COST)
        assertThat(miss.gateChi2).isAtMost(config.trackGateChi2)
        assertThat(miss.cost).isAtLeast(config.trackMatchMaxCost)
        assertThat(tracker.assocMissCost).isEqualTo(1)
        assertThat(tracker.assocMissGate).isEqualTo(0)
    }

    @Test
    fun theRecordedCost_isExactlyTheCostTheMatcherComputes() {
        confirmedThenLost(tracker, birthFrame = LONG_BIRTH_FRAME)
        val detection = person(600f + COST_OFFSET_IMG)

        val list = feed(tracker, LONG_BIRTH_FRAME, detection)

        val miss = tracker.miss(0)
        val lost = (0 until list.size).first { list.id(it) == miss.lostId }
        val head = HeadPoint()
        HeadMeasurement.of(detection, config, head)
        val matcherCost =
            AssociationCost.cost(
                list.boxLeftImg(lost),
                list.boxTopImg(lost),
                list.boxRightImg(lost),
                list.boxBottomImg(lost),
                detection.boxLeftImg,
                detection.boxTopImg,
                detection.boxRightImg,
                detection.boxBottomImg,
                list.headXImg(lost),
                list.headYImg(lost),
                head.headXImg,
                head.headYImg,
                HUGE_VARIANCE_IMG2,
                HUGE_VARIANCE_IMG2,
                list.heightImg(lost),
                config,
            )
        assertThat(matcherCost).isNotEqualTo(Assigner.FORBIDDEN)
        assertThat(miss.cost).isEqualTo(matcherCost)
    }

    @Test
    fun aBirthNextToAConfirmedTrackThatTookAnotherDetection_isRecordedAsLive() {
        for (k in 0 until HITS) feed(tracker, k, person(600f))

        feed(tracker, HITS, person(600f), person(600f + LIVE_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(1)
        val record = tracker.miss(0)
        assertThat(record.reason).isEqualTo(AssociationMissReason.LIVE)
        assertThat(record.trackState).isEqualTo(TrackState.CONFIRMED)
        assertThat(record.lostId).isEqualTo(1)
        assertThat(record.newId).isEqualTo(2)
        assertThat(record.detIou).isWithin(IOU_TOLERANCE).of(LIVE_EXPECTED_IOU)
        assertThat(tracker.assocBirthNearLive).isEqualTo(1)
        assertThat(tracker.assocMisses).isEqualTo(0)
        assertThat(tracker.assocBirthAfterRemoval).isEqualTo(0)
    }

    @Test
    fun aBirthNextToATentativeTrack_isRecordedAsLiveWithThatState() {
        feed(tracker, 0, person(600f))

        feed(tracker, 1, person(600f), person(600f + LIVE_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(1)
        assertThat(tracker.miss(0).reason).isEqualTo(AssociationMissReason.LIVE)
        assertThat(tracker.miss(0).trackState).isEqualTo(TrackState.TENTATIVE)
        assertThat(tracker.assocBirthNearLive).isEqualTo(1)
    }

    @Test
    fun aBirthFarFromEveryTrack_isNotALiveBirth() {
        for (k in 0 until HITS) feed(tracker, k, person(600f))

        feed(tracker, HITS, person(600f), person(600f + FAR_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocBirthNearLive).isEqualTo(0)
    }

    @Test
    fun aBirthInTheFrameATrackIsRemoved_isRecordedWithTheRemovalCause() {
        for (k in 0 until HITS) feed(tracker, k, person(600f))
        for (k in HITS until REMOVAL_FRAME) feed(tracker, k)
        assertThat(tracker.removeCount).isEqualTo(0)

        feed(tracker, REMOVAL_FRAME, person(600f + COST_OFFSET_IMG))

        assertThat(tracker.removeCount).isEqualTo(1)
        assertThat(tracker.missCount).isEqualTo(1)
        val record = tracker.miss(0)
        assertThat(record.reason).isEqualTo(AssociationMissReason.REMOVED)
        assertThat(record.cause).isEqualTo(RemovalCause.LOST_TIME)
        assertThat(record.lostId).isEqualTo(1)
        assertThat(record.lostMs).isGreaterThan(config.trackMaxLostMs)
        assertThat(tracker.assocBirthAfterRemoval).isEqualTo(1)
        assertThat(tracker.assocMisses).isEqualTo(0)
        assertThat(tracker.assocBirthNearLive).isEqualTo(0)
    }

    @Test
    fun aRemovedTrackIsOnlyRemembered_forTheFrameItWasRemovedIn() {
        for (k in 0 until HITS) feed(tracker, k, person(600f))
        for (k in HITS until REMOVAL_FRAME) feed(tracker, k)
        feed(tracker, REMOVAL_FRAME)

        feed(tracker, REMOVAL_FRAME + 1, person(600f + COST_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocBirthAfterRemoval).isEqualTo(0)
    }

    @Test
    fun aLowScoreBirth_isRecordedWithTheLowStage_andTheLowLimitDecides() {
        confirmedThenLost(tracker)

        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG, score = LOW_STAGE_SCORE))

        assertThat(tracker.missCount).isEqualTo(1)
        assertThat(tracker.miss(0).highStage).isFalse()
        assertThat(tracker.miss(0).score).isEqualTo(LOW_STAGE_SCORE)
    }

    @Test
    fun anUnmatchedDetectionBelowTheBirthScore_nextToTheLostTrack_isOnlyCounted() {
        confirmedThenLost(tracker)

        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG, score = BELOW_BIRTH_SCORE))

        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocMisses).isEqualTo(0)
        assertThat(tracker.assocMissLowBirth).isEqualTo(1)
    }

    @Test
    fun aTentativeTrackThatMissed_isNotALostTrack() {
        feed(tracker, 0, person(600f))
        feed(tracker, 1)

        feed(tracker, 2, person(600f + NEAR_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocMisses).isEqualTo(0)
    }

    @Test
    fun theRecords_areClearedByTheNextUpdate_butTheCountersStay() {
        confirmedThenLost(tracker)
        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))
        assertThat(tracker.missCount).isEqualTo(1)

        feed(tracker, SHORT_BIRTH_FRAME + 1, person(600f + NEAR_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(0)
        assertThat(tracker.assocMisses).isEqualTo(1)
    }

    @Test
    fun aRepeatedTimestamp_clearsTheRecords() {
        confirmedThenLost(tracker)
        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))
        assertThat(tracker.missCount).isEqualTo(1)

        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))

        assertThat(tracker.missCount).isEqualTo(0)
    }

    @Test
    fun anIndexOutsideTheRecords_throws() {
        confirmedThenLost(tracker)
        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))

        assertThrows(IndexOutOfBoundsException::class.java) { tracker.miss(1) }
        assertThrows(IndexOutOfBoundsException::class.java) { tracker.miss(-1) }
    }

    @Test
    fun reset_keepsTheMissCounters() {
        confirmedThenLost(tracker)
        feed(tracker, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))

        tracker.reset()

        assertThat(tracker.assocMisses).isEqualTo(1)
        assertThat(tracker.missCount).isEqualTo(0)
    }

    @Test
    fun aSmallerNearRatio_recordsNothingForTheSameBirth() {
        val narrow = Tracker(TrackerConfig(assocMissNearRatio = TrackerConfig.MIN_ASSOC_MISS_NEAR_RATIO))
        confirmedThenLost(narrow)

        feed(narrow, SHORT_BIRTH_FRAME, person(600f + NEAR_OFFSET_IMG))

        assertThat(narrow.assocMisses).isEqualTo(0)
    }

    @Test
    fun classify_aValueAboveTheGate_isGate_evenWhenTheCostIsLow() {
        val above = AssociationMissReason.classify(GATE_LIMIT + 1f, 0.1f, MAX_COST, GATE_LIMIT)
        val notANumber = AssociationMissReason.classify(Float.NaN, 0.1f, MAX_COST, GATE_LIMIT)

        assertThat(above).isEqualTo(AssociationMissReason.GATE)
        assertThat(notANumber).isEqualTo(AssociationMissReason.GATE)
    }

    @Test
    fun classify_insideTheGate_aCostAtOrAboveTheLimit_isCost() {
        val atLimit = AssociationMissReason.classify(1f, MAX_COST, MAX_COST, GATE_LIMIT)
        val above = AssociationMissReason.classify(GATE_LIMIT, MAX_COST + 0.1f, MAX_COST, GATE_LIMIT)

        assertThat(atLimit).isEqualTo(AssociationMissReason.COST)
        assertThat(above).isEqualTo(AssociationMissReason.COST)
    }

    @Test
    fun classify_aPairTheMatcherWouldHaveAccepted_isTaken() {
        val reason = AssociationMissReason.classify(1f, MAX_COST - 0.1f, MAX_COST, GATE_LIMIT)

        assertThat(reason).isEqualTo(AssociationMissReason.TAKEN)
    }

    @Test
    fun theTracks_areIdenticalWhateverTheDiagnosticsRecordOrRead() {
        val reading = Tracker(config)
        val narrow = Tracker(TrackerConfig(assocMissNearRatio = TrackerConfig.MIN_ASSOC_MISS_NEAR_RATIO))
        val wide = Tracker(TrackerConfig(assocMissNearRatio = TrackerConfig.MAX_ASSOC_MISS_NEAR_RATIO))
        var recordsRead = 0

        for (k in 0 until SCRIPT_FRAMES) {
            val detections = scriptedDetections(k)
            val a = snapshot(feed(reading, k, *detections))
            val b = snapshot(feed(narrow, k, *detections))
            val c = snapshot(feed(wide, k, *detections))
            for (i in 0 until reading.missCount) {
                recordsRead += 1
                assertThat(reading.miss(i).newId).isGreaterThan(reading.miss(i).lostId)
            }
            assertThat(b).isEqualTo(a)
            assertThat(c).isEqualTo(a)
            val idleNs = if (k % IDLE_EVERY == 0) config.trackIdlePromoteNs else 0L
            val idleA = snapshot(reading.onIdle(idleNs))
            assertThat(snapshot(narrow.onIdle(idleNs))).isEqualTo(idleA)
            assertThat(snapshot(wide.onIdle(idleNs))).isEqualTo(idleA)
        }

        assertThat(recordsRead).isGreaterThan(0)
        assertThat(reading.assocMisses).isGreaterThan(0)
        assertThat(reading.assocBirthNearLive).isGreaterThan(0)
        assertThat(reading.assocMisses + reading.assocBirthNearLive + reading.assocBirthAfterRemoval).isEqualTo(recordsRead)
        assertThat(narrow.assocMisses).isEqualTo(0)
        assertThat(wide.assocMisses).isAtLeast(reading.assocMisses)
        for (other in listOf(narrow, wide)) {
            assertThat(other.birthCount).isEqualTo(reading.birthCount)
            assertThat(other.confirmCount).isEqualTo(reading.confirmCount)
            assertThat(other.recoverCount).isEqualTo(reading.recoverCount)
            assertThat(other.idlePromoteCount).isEqualTo(reading.idlePromoteCount)
            assertThat(other.removeCount).isEqualTo(reading.removeCount)
            assertThat(other.mostShownAtOnce).isEqualTo(reading.mostShownAtOnce)
        }
    }

    /** One person with dropouts that come back displaced (new ids next to lost tracks), one flickering low-score person. */
    private fun scriptedDetections(frame: Int): Array<PoseDetection> {
        val list = ArrayList<PoseDetection>()
        val phase = frame % SCRIPT_PERIOD
        val cycle = frame / SCRIPT_PERIOD
        if (phase < SCRIPT_PERIOD - SCRIPT_DROPPED) {
            list.add(person(SCRIPT_START_X + phase * SCRIPT_STEP_X + (cycle % 2) * NEAR_OFFSET_IMG))
        }
        if (frame % FLICKER_PERIOD != 0) list.add(person(FLICKER_X, score = LOW_STAGE_SCORE))
        if (frame % NEIGHBOUR_PERIOD == NEIGHBOUR_PHASE) {
            list.add(person(SCRIPT_START_X + phase * SCRIPT_STEP_X + (cycle % 2) * NEAR_OFFSET_IMG + LIVE_OFFSET_IMG))
        }
        return list.toTypedArray()
    }

    /** What a caller can read of the list, with exact float bits, to compare two runs for identity. */
    private fun snapshot(list: TrackList): List<String> =
        (0 until list.size).map { i ->
            "${list.id(i)} ${list.state(i)} ${list.headXImg(i).toRawBits()} ${list.headYImg(i).toRawBits()} " +
                "${list.heightImg(i).toRawBits()} ${list.score(i).toRawBits()} ${list.lostMs(i).toRawBits()} " +
                "${list.matchedDetectionIndex(i)}"
        }

    /** Three hits at x = 600 (confirmed), then frames without people up to frame [birthFrame] (exclusive). */
    private fun confirmedThenLost(
        target: Tracker,
        birthFrame: Int = SHORT_BIRTH_FRAME,
    ) {
        for (k in 0 until HITS) feed(target, k, person(600f))
        for (k in HITS until birthFrame) feed(target, k)
    }

    private fun feed(
        target: Tracker,
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList {
        val frame = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())
        return target.update(frame)
    }

    private fun person(
        xImg: Float,
        score: Float = 0.9f,
    ): PoseDetection {
        val base = HeadTestDetections.person(xImg, ROW_Y, HEIGHT)
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

    private companion object {
        const val START_NS = 5_000_000_000L
        const val STEP_NS = 50_000_000L
        const val STEP_MS = 50f
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f

        /** Frames 0..2 are hits (confirmed), frame 3 is the first without people. */
        const val HITS = 3

        /** The birth frame of the short scenarios: frame 3 had no people, so the track is LOST in frame 4. */
        const val SHORT_BIRTH_FRAME = 4

        /** Eight frames without people (400 ms lost), so the position variance has grown. */
        const val LONG_BIRTH_FRAME = 11
        const val NEAR_OFFSET_IMG = 150f
        const val FAR_OFFSET_IMG = 450f
        const val COST_OFFSET_IMG = 120f
        const val LOW_STAGE_SCORE = 0.4f
        const val BELOW_BIRTH_SCORE = 0.2f
        const val RATIO_TOLERANCE = 0.1f
        const val GATE_LIMIT = 9.21f
        const val MAX_COST = 0.7f
        const val SCRIPT_FRAMES = 160
        const val SCRIPT_PERIOD = 20
        const val SCRIPT_DROPPED = 2
        const val SCRIPT_START_X = 300f
        const val SCRIPT_STEP_X = 4f
        const val FLICKER_PERIOD = 7
        const val FLICKER_X = 1100f
        const val NEIGHBOUR_PERIOD = 11
        const val NEIGHBOUR_PHASE = 5
        const val IDLE_EVERY = 5
        const val HUGE_VARIANCE_IMG2 = 1e9f

        /** A second person this far right of the first: boxes 80 wide overlap by 30 px, IoU 6000 / 26000. */
        const val LIVE_OFFSET_IMG = 50f
        const val LIVE_EXPECTED_IOU = 6000f / 26000f
        const val IOU_TOLERANCE = 0.01f

        /** Frame 3 is the first without people: lost time 850 ms at frame 19 is above the 800 ms limit. */
        const val REMOVAL_FRAME = 19
    }
}
