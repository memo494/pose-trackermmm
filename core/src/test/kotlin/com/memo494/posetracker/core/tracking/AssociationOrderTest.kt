package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/**
 * Association order (docs/plans/M_final.md, F3 A2): a tentative track that is a shadow of an older track (its
 * predicted box overlaps that track's with the birth-suppression IoU and its head is within the head ratio) only
 * gets the detections the older tracks leave over. A tentative track that is another person competes as before.
 */
class AssociationOrderTest {
    private val tracker = Tracker(TrackerConfig())

    @Test
    fun aShadowTentativeTrack_doesNotTakeTheDetectionOfTheOlderTrack() {
        feed(0, person(OLD_X_IMG))
        feed(1, person(OLD_X_IMG), person(SHADOW_X_IMG))
        feed(2, person(OLD_X_IMG))

        val list = feed(3, person(DETECTION_X_IMG))

        val old = indexOfId(list, OLD_ID)
        assertThat(old).isAtLeast(0)
        assertThat(list.state(old)).isEqualTo(TrackState.CONFIRMED)
        assertThat(indexOfId(list, SECOND_ID)).isEqualTo(-1)
    }

    @Test
    fun aDistinctNewborn_stillTakesTheDetectionThatIsCloserToIt() {
        for (k in 0 until HITS) feed(k, person(OLD_X_IMG))
        feed(HITS, person(OLD_X_IMG), person(NEIGHBOUR_X_IMG))

        val list = feed(HITS + 1, person(BETWEEN_X_IMG))

        val old = indexOfId(list, OLD_ID)
        assertThat(old).isAtLeast(0)
        assertThat(list.state(old)).isEqualTo(TrackState.LOST)
        val newborn = indexOfId(list, SECOND_ID)
        assertThat(newborn).isAtLeast(0)
        assertThat(list.state(newborn)).isEqualTo(TrackState.TENTATIVE)
    }

    @Test
    fun aSecondPersonInsideTheFirstOnesBox_keepsHisDetectionWhenTheFirstOneDisappears() {
        for (k in 0 until HITS) feed(k, person(OLD_X_IMG))
        feed(HITS, person(OLD_X_IMG), smallPersonInsideTheBox())

        val list = feed(HITS + 1, smallPersonInsideTheBox())

        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(0)
        assertThat(list.state(indexOfId(list, OLD_ID))).isEqualTo(TrackState.LOST)
        assertThat(list.state(indexOfId(list, SECOND_ID))).isEqualTo(TrackState.TENTATIVE)
        feed(HITS + 2, smallPersonInsideTheBox())
        assertThat(tracker.recoverCount).isEqualTo(0)
        assertThat(tracker.confirmCount).isEqualTo(2)
    }

    @Test
    fun aTentativeTrack_stillTakesADetectionNoOtherTrackWants() {
        for (k in 0 until HITS) feed(k, person(OLD_X_IMG))
        feed(HITS, person(OLD_X_IMG), person(NEIGHBOUR_X_IMG))

        val list = feed(HITS + 1, person(OLD_X_IMG), person(NEIGHBOUR_X_IMG))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.state(indexOfId(list, OLD_ID))).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.confirmCount).isEqualTo(1)
    }

    @Test
    fun aShadowNextToALostTrack_doesNotTakeTheDetectionOfTheLostTrack() {
        val patient = Tracker(TrackerConfig(trackTentativeMaxMisses = 2))
        feedInto(patient, 0, person(OLD_X_IMG))
        feedInto(patient, 1, person(OLD_X_IMG), person(SHADOW_X_IMG))
        feedInto(patient, 2, person(OLD_X_IMG))
        feedInto(patient, 3)

        val list = feedInto(patient, 4, person(DETECTION_X_IMG))

        val old = indexOfId(list, OLD_ID)
        assertThat(old).isAtLeast(0)
        assertThat(list.state(old)).isEqualTo(TrackState.CONFIRMED)
        assertThat(patient.recoverCount).isEqualTo(1)
        assertThat(indexOfId(list, SECOND_ID)).isEqualTo(-1)
    }

    private fun indexOfId(
        list: TrackList,
        id: Int,
    ): Int = (0 until list.size).firstOrNull { list.id(it) == id } ?: -1

    private fun feed(
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList = feedInto(tracker, index, *detections)

    private fun feedInto(
        target: Tracker,
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList {
        val frame = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())
        return target.update(frame)
    }

    private fun person(xImg: Float): PoseDetection = HeadTestDetections.person(xImg, ROW_Y, HEIGHT)

    /** 150 high, box 60 x 150 inside the box of [person] at [OLD_X_IMG] (IoU 0.5625), head 0.23 h lower and 10 px right. */
    private fun smallPersonInsideTheBox(): PoseDetection =
        HeadTestDetections.person(OLD_X_IMG + INSIDE_SHIFT_X_IMG, INSIDE_HEAD_Y_IMG, INSIDE_HEIGHT_IMG)

    private companion object {
        const val START_NS = 5_000_000_000L
        const val STEP_NS = 50_000_000L
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val HITS = 3
        const val OLD_ID = 1

        /** The id of the second track in every test: the second person born (a shadow or a neighbour). */
        const val SECOND_ID = 2
        const val OLD_X_IMG = 100f

        /** 20 px right of the old person: box IoU 0.6 (below the dedupe IoU), head 0.1 h away: a near duplicate. */
        const val SHADOW_X_IMG = 120f

        /** Closer to the shadow (5 px) than to the old track (15 px). */
        const val DETECTION_X_IMG = 115f

        /** 50 px right of the old person: box IoU 0.23, a separate person (no suppression, no shadow). */
        const val NEIGHBOUR_X_IMG = 150f

        /** Closer to the neighbour (20 px) than to the old track (30 px), both inside the gates and cost limits. */
        const val BETWEEN_X_IMG = 130f

        const val INSIDE_SHIFT_X_IMG = 10f
        const val INSIDE_HEAD_Y_IMG = 195f
        const val INSIDE_HEIGHT_IMG = 150f
    }
}
