package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/** The raw measured head velocity the tracker exposes for the display lead's stop gate (F4). */
class TrackerMeasuredVelocityTest {
    private val tracker = Tracker(TrackerConfig())

    @Test
    fun firstMeasurementOfATrack_hasNoStep() {
        val list = feed(0, 600f)

        assertThat(list.measuredVelocityXImgPerS(0)).isEqualTo(0f)
        assertThat(list.measuredVelocityYImgPerS(0)).isEqualTo(0f)
    }

    @Test
    fun matchedTrack_reportsTheStepOfTheLastTwoMeasurementsOverTheirTime() {
        feed(0, 600f)
        feed(1, 610f)
        val list = feed(2, 630f)

        // 20 px in 50 ms, to the right; the measured head is the mean of the keypoints, i.e. the drawn centre.
        assertThat(list.measuredVelocityXImgPerS(0)).isWithin(TOLERANCE).of(400f)
        assertThat(list.measuredVelocityYImgPerS(0)).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun aStepBackwards_isNegative() {
        feed(0, 600f)
        feed(1, 620f)
        val list = feed(2, 610f)

        assertThat(list.measuredVelocityXImgPerS(0)).isWithin(TOLERANCE).of(-200f)
    }

    @Test
    fun aTrackWithoutAMatchInTheNewestFrame_reportsNoStep() {
        for (k in 0 until 4) feed(k, 600f + 10f * k)
        val list = tracker.update(frameAt(4))

        assertThat(list.state(0)).isEqualTo(TrackState.LOST)
        assertThat(list.measuredVelocityXImgPerS(0)).isEqualTo(0f)
        assertThat(list.measuredVelocityYImgPerS(0)).isEqualTo(0f)
    }

    @Test
    fun theFirstMeasurementAfterALostFrame_isTheStepOverTheWholeTime() {
        for (k in 0 until 4) feed(k, 600f + 10f * k)
        tracker.update(frameAt(4))
        val list = feed(5, 660f)

        // From 630 at frame 3 to 660 at frame 5: 30 px in 100 ms.
        assertThat(list.measuredVelocityXImgPerS(0)).isWithin(TOLERANCE).of(300f)
    }

    private fun frameAt(
        index: Int,
        vararg detections: com.memo494.posetracker.core.model.PoseDetection,
    ) = DetectionFrame(START_NS + index * STEP_NS, TimestampSource.IMAGE, IMAGE_WIDTH, IMAGE_HEIGHT, detections.toList())

    private fun feed(
        index: Int,
        xImg: Float,
    ): TrackList = tracker.update(frameAt(index, HeadTestDetections.person(xImg, ROW_Y, HEIGHT)))

    private companion object {
        const val START_NS = 5_000_000_000L
        const val STEP_NS = 50_000_000L
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val ROW_Y = 150f
        const val HEIGHT = 200f
        const val TOLERANCE = 5f
    }
}
