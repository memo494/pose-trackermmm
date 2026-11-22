package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/**
 * Birth suppression (docs/plans/M_final.md, F3 A1): an unmatched detection whose box overlaps, with at least
 * `trackBirthSuppressIou`, a detection that a confirmed or lost track took in the same frame is a near duplicate
 * of that person and starts no track.
 */
class BirthSuppressionTest {
    private val config = TrackerConfig()
    private val tracker = Tracker(config)

    @Test
    fun aSecondBoxOverlappingTheConfirmedTracksBox_startsNoTrack() {
        for (k in 0 until HITS) feed(k, person(X_IMG))

        val list = feed(HITS, person(X_IMG), person(X_IMG + DUPLICATE_SHIFT_IMG, LOW_SCORE))

        assertThat(list.size).isEqualTo(1)
        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(1)
        assertThat(tracker.assocBirthNearLive).isEqualTo(0)
    }

    @Test
    fun aSecondBoxOverlappingTheDetectionOfARecoveredLostTrack_startsNoTrack() {
        for (k in 0 until HITS) feed(k, person(X_IMG))
        feed(HITS)

        val list = feed(HITS + 1, person(X_IMG), person(X_IMG + DUPLICATE_SHIFT_IMG, LOW_SCORE))

        assertThat(list.size).isEqualTo(1)
        assertThat(list.id(0)).isEqualTo(1)
        assertThat(tracker.birthCount).isEqualTo(1)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(1)
    }

    @Test
    fun aNeighbourWithoutOverlap_isStillBorn() {
        for (k in 0 until HITS) feed(k, person(X_IMG))

        val list = feed(HITS, person(X_IMG), person(X_IMG + NEIGHBOUR_SHIFT_IMG, LOW_SCORE))

        assertThat(list.size).isEqualTo(2)
        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(0)
    }

    @Test
    fun aSecondBoxNextToATentativeTracksBox_isStillBorn() {
        feed(0, person(X_IMG))

        val list = feed(1, person(X_IMG), person(X_IMG + DUPLICATE_SHIFT_IMG, LOW_SCORE))

        assertThat(list.size).isEqualTo(2)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(0)
    }

    @Test
    fun aBoxOverlappingLessThanTheLimit_isStillBorn() {
        for (k in 0 until HITS) feed(k, person(X_IMG))

        val list = feed(HITS, person(X_IMG), person(X_IMG + WEAK_OVERLAP_SHIFT_IMG, LOW_SCORE))

        assertThat(list.size).isEqualTo(2)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(0)
    }

    @Test
    fun aBoxThatOverlapsButHasItsHeadFarAway_isAnotherPersonAndIsBorn() {
        for (k in 0 until HITS) feed(k, person(X_IMG))

        val list = feed(HITS, person(X_IMG), smallerPersonInsideTheBox())

        assertThat(list.size).isEqualTo(2)
        assertThat(tracker.birthCount).isEqualTo(2)
        assertThat(tracker.assocBirthSuppressed).isEqualTo(0)
    }

    @Test
    fun aSuppressedBox_isNotCountedWhenNoTrackCouldHaveBeenGiven() {
        val full = Tracker(TrackerConfig(maxTracks = 1))
        for (k in 0 until HITS) full.update(frameOf(k, person(X_IMG)))

        full.update(frameOf(HITS, person(X_IMG), person(X_IMG + DUPLICATE_SHIFT_IMG, LOW_SCORE)))

        assertThat(full.birthCount).isEqualTo(1)
        assertThat(full.assocBirthSuppressed).isEqualTo(0)
    }

    /** A person 150 high whose box (60 x 150, IoU 0.5625 with the first person's) lies inside that box; head 0.23 h away. */
    private fun smallerPersonInsideTheBox(): PoseDetection =
        HeadTestDetections.person(X_IMG + INSIDE_SHIFT_X_IMG, INSIDE_HEAD_Y_IMG, INSIDE_HEIGHT_IMG)

    private fun frameOf(
        index: Int,
        vararg detections: PoseDetection,
    ) = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())

    private fun feed(
        index: Int,
        vararg detections: PoseDetection,
    ): TrackList {
        val frame = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())
        return tracker.update(frame)
    }

    private fun person(
        xImg: Float,
        score: Float = HIGH_SCORE,
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
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val X_IMG = 600f
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val HITS = 3
        const val HIGH_SCORE = 0.9f
        const val LOW_SCORE = 0.3f

        /** Boxes are 80 wide: a shift of 20 gives IoU 60 / 100 = 0.6, below the dedupe IoU 0.7. */
        const val DUPLICATE_SHIFT_IMG = 20f

        /** A shift of 40 gives IoU 40 / 120 = 0.33, below the suppression IoU 0.5. */
        const val WEAK_OVERLAP_SHIFT_IMG = 40f

        const val INSIDE_SHIFT_X_IMG = 10f
        const val INSIDE_HEAD_Y_IMG = 195f
        const val INSIDE_HEIGHT_IMG = 150f

        /** A shift of 200 leaves the boxes apart, IoU 0. */
        const val NEIGHBOUR_SHIFT_IMG = 200f
    }
}
