package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/**
 * Association order (docs/plans/M_final.md, F3 A2, ByteTrack): confirmed and lost tracks pick their detections
 * first; a tentative track only gets what is left over, so a newborn next to an old track cannot take its person.
 */
class AssociationOrderTest {
    private val tracker = Tracker(TrackerConfig())

    @Test
    fun anOlderTrack_beatsACloserTentativeTrack_toTheDetectionBetweenThem() {
        for (k in 0 until HITS) feed(k, person(OLD_X_IMG))
        feed(HITS, person(OLD_X_IMG), person(NEWBORN_X_IMG))

        val list = feed(HITS + 1, person(DETECTION_X_IMG))

        val old = indexOfId(list, OLD_ID)
        assertThat(old).isAtLeast(0)
        assertThat(list.state(old)).isEqualTo(TrackState.CONFIRMED)
        assertThat(list.headXImg(old)).isWithin(HEAD_TOLERANCE_IMG).of(DETECTION_X_IMG)
        val newborn = indexOfId(list, NEWBORN_ID)
        assertThat(newborn).isAtLeast(0)
        assertThat(list.state(newborn)).isEqualTo(TrackState.TENTATIVE)
    }

    @Test
    fun aTentativeTrack_stillTakesADetectionNoOtherTrackWants() {
        for (k in 0 until HITS) feed(k, person(OLD_X_IMG))
        feed(HITS, person(OLD_X_IMG), person(NEWBORN_X_IMG))

        val list = feed(HITS + 1, person(OLD_X_IMG), person(NEWBORN_X_IMG))

        assertThat(list.size).isEqualTo(2)
        assertThat(list.state(indexOfId(list, OLD_ID))).isEqualTo(TrackState.CONFIRMED)
        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.confirmCount).isEqualTo(1)
    }

    private fun indexOfId(
        list: TrackList,
        id: Int,
    ): Int = (0 until list.size).firstOrNull { list.id(it) == id } ?: -1

    private fun feed(
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList {
        val frame = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())
        return tracker.update(frame)
    }

    private fun person(xImg: Float): PoseDetection = HeadTestDetections.person(xImg, ROW_Y, HEIGHT)

    private companion object {
        const val START_NS = 5_000_000_000L
        const val STEP_NS = 50_000_000L
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val HITS = 3
        const val OLD_ID = 1
        const val NEWBORN_ID = 2
        const val HEAD_TOLERANCE_IMG = 20f

        /** The old track's person, then the newborn 50 px to the right (box IoU 0.23: no birth suppression). */
        const val OLD_X_IMG = 100f
        const val NEWBORN_X_IMG = 150f

        /** Closer to the newborn (20 px) than to the old track (30 px), but both inside the gates and cost limits. */
        const val DETECTION_X_IMG = 130f
    }
}
