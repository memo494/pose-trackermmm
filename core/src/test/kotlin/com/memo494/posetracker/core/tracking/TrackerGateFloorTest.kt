package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/**
 * The gate floor of [Tracker] (docs/plans/M4.md, M4f T1): the gate's per-axis variance is at least
 * `(trackGateMinSigmaRatio * track height)^2`, so a head that moved 0.3 h between two detections is still the same
 * track after one missed frame, while two people 0.4 h apart keep their own ids.
 */
class TrackerGateFloorTest {
    private val config = TrackerConfig()
    private val floor = Tracker(config)
    private val noFloor = Tracker(TrackerConfig(trackGateMinSigmaRatio = 0f))

    @Test
    fun aHeadThatMovedThreeTenthsOfTheHeight_afterOneMissedFrame_isTheSameTrack() {
        confirmedThenOneMissedFrame(floor)

        val list = feed(floor, MISSED_FRAME + 1, person(START_X + JUMP_IMG))

        assertThat(floor.birthCount).isEqualTo(1)
        assertThat(list.size).isEqualTo(1)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(list.state(0)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.matchedDetectionIndex(0)).isEqualTo(0)
    }

    @Test
    fun theSameJump_withTheFloorOff_isOutsideTheGate_andStartsANewTrack() {
        confirmedThenOneMissedFrame(noFloor)

        val list = feed(noFloor, MISSED_FRAME + 1, person(START_X + JUMP_IMG))

        assertThat(noFloor.birthCount).isEqualTo(2)
        assertThat((0 until list.size).map { list.id(it) }).contains(2)
    }

    @Test
    fun twoPeopleFourTenthsApart_keepTheirIds_whenOneIsMissedForAFrame() {
        for (k in 0 until HITS) feed(floor, k, person(START_X), person(START_X + NEIGHBOUR_IMG))
        feed(floor, MISSED_FRAME, person(START_X + NEIGHBOUR_IMG))

        val back = feed(floor, MISSED_FRAME + 1, person(START_X), person(START_X + NEIGHBOUR_IMG))

        assertThat(floor.birthCount).isEqualTo(2)
        assertThat(back.size).isEqualTo(2)
        for (i in 0 until back.size) {
            val expectedId = if (back.matchedDetectionIndex(i) == 0) 1 else 2
            assertThat(back.id(i)).isEqualTo(expectedId)
            assertThat(back.state(i)).isEqualTo(TrackState.CONFIRMED)
        }
        assertThat((0 until back.size).map { back.id(it) }).containsExactly(1, 2)
    }

    @Test
    fun theNeighbourIsNotTakenByTheMissedTrack_whileItsOwnTrackIsThere() {
        for (k in 0 until HITS) feed(floor, k, person(START_X), person(START_X + NEIGHBOUR_IMG))

        val list = feed(floor, MISSED_FRAME, person(START_X + NEIGHBOUR_IMG))

        val second = (0 until list.size).first { list.id(it) == 2 }
        val first = (0 until list.size).first { list.id(it) == 1 }
        assertThat(list.matchedDetectionIndex(second)).isEqualTo(0)
        assertThat(list.matchedDetectionIndex(first)).isEqualTo(-1)
        assertThat(list.state(first)).isEqualTo(TrackState.LOST)
        assertThat(floor.birthCount).isEqualTo(2)
    }

    @Test
    fun withTheFloorOff_twoPeopleFourTenthsApart_behaveAsBefore() {
        for (k in 0 until HITS) feed(noFloor, k, person(START_X), person(START_X + NEIGHBOUR_IMG))
        feed(noFloor, MISSED_FRAME, person(START_X + NEIGHBOUR_IMG))

        val back = feed(noFloor, MISSED_FRAME + 1, person(START_X), person(START_X + NEIGHBOUR_IMG))

        assertThat(noFloor.birthCount).isEqualTo(2)
        assertThat((0 until back.size).map { back.id(it) }).containsExactly(1, 2)
    }

    @Test
    fun aMissRecord_withTheFloor_usesTheFlooredGateValue() {
        val tracker = Tracker(config)
        for (k in 0 until HITS) feed(tracker, k, person(START_X))
        feed(tracker, HITS)

        // 0.75 h away: outside the gate even with the floor (chi2 >= 150^2 / 40^2 = 14.06).
        feed(tracker, HITS + 1, person(START_X + FAR_JUMP_IMG))

        assertThat(tracker.missCount).isEqualTo(1)
        val miss = tracker.miss(0)
        assertThat(miss.reason).isEqualTo(AssociationMissReason.GATE)
        assertThat(miss.gateChi2).isGreaterThan(config.trackGateChi2)
        assertThat(miss.gateChi2).isAtMost(FAR_JUMP_IMG * FAR_JUMP_IMG / FLOOR_VARIANCE_IMG2 + CHI2_TOLERANCE)
    }

    @Test
    fun aMissRecord_gateValue_isExactlyWhatMahalanobis2GivesWithTheFloor() {
        for (k in 0 until HITS) feed(floor, k, person(START_X))
        feed(floor, HITS)
        val detection = person(START_X + FAR_JUMP_IMG)

        val list = feed(floor, HITS + 1, detection)

        assertThat(floor.missCount).isEqualTo(1)
        val miss = floor.miss(0)
        val lost = (0 until list.size).first { list.id(it) == miss.lostId }
        val head = HeadPoint()
        HeadMeasurement.of(detection, config, head)
        val dxImg = head.headXImg - list.headXImg(lost)
        val dyImg = head.headYImg - list.headYImg(lost)
        // The filter's variance is far below the floor here, so any variance below it gives the same value.
        val expected = AssociationCost.mahalanobis2(dxImg, dyImg, 1f, 1f, 0.2f * list.heightImg(lost))
        assertThat(miss.gateChi2).isEqualTo(expected)
    }

    /** Three hits at [START_X] (confirmed), then frame [MISSED_FRAME] without people. */
    private fun confirmedThenOneMissedFrame(target: Tracker) {
        for (k in 0 until HITS) feed(target, k, person(START_X))
        feed(target, MISSED_FRAME)
    }

    private fun feed(
        target: Tracker,
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList {
        val frame = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())
        return target.update(frame)
    }

    /**
     * A person with a wide box (half width [BOX_HALF_WIDTH_IMG], like a walking person seen from the side), so
     * that a head 0.3 h away still overlaps the old box: the cost passes, only the gate decides.
     */
    private fun person(xImg: Float): PoseDetection {
        val base = HeadTestDetections.person(xImg, ROW_Y, HEIGHT)
        return PoseDetection(
            xImg - BOX_HALF_WIDTH_IMG,
            base.boxTopImg,
            xImg + BOX_HALF_WIDTH_IMG,
            base.boxBottomImg,
            base.score,
            base.keypointsXyImg,
            base.keypointScores,
        )
    }

    private companion object {
        const val START_NS = 5_000_000_000L
        const val STEP_NS = 50_000_000L
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val BOX_HALF_WIDTH_IMG = 100f
        const val START_X = 600f

        /** Three hits (frames 0..2), then frame 3 has no people. */
        const val HITS = 3
        const val MISSED_FRAME = 3

        /** 0.3 h. */
        const val JUMP_IMG = 60f

        /** 0.4 h. */
        const val NEIGHBOUR_IMG = 80f

        /** 0.75 h. */
        const val FAR_JUMP_IMG = 150f

        /** The floor variance of the default ratio 0.2 at h = 200: (0.2 * 200)^2. */
        const val FLOOR_VARIANCE_IMG2 = 1600f
        const val CHI2_TOLERANCE = 0.5f
    }
}
