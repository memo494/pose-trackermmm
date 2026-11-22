package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The display model itself (F4): a hand-built run whose answer is known. */
class DisplayModelTest {
    private val walker =
        ScenePerson(1, listOf(PathPoint(0.0, 100.0, 150.0, 200.0), PathPoint(2.0, 500.0, 150.0, 200.0)))
    private val scene = SyntheticScene(listOf(walker), durationS = 2.0, fps = 20.0)

    /** A run that shows the true head of every frame, i.e. a point that is only late by the display latency. */
    private fun perfectRun(): DisplayedRun =
        DisplayedRun(
            scene,
            scene.frames.map { frame ->
                val truth = frame.truth[0]
                DisplayedFrame(frame.timeS, listOf(DisplayedTrack(1, truth.headXImg, truth.headYImg, 0f)))
            },
            1,
        )

    @Test
    fun aPerfectPointThatIsOnlyLate_hasTheErrorOfLatencyPlusHalfAFrame() {
        // 200 px/s = 1 h/s; the age of the picture is 75 ms to 125 ms (saw-tooth), mean 100 ms = 0.1 h.
        val error = DisplayModel.meanErrorInH(perfectRun(), walker, 0.5, 1.8)

        assertThat(abs(error - 0.1)).isLessThan(0.005)
    }

    @Test
    fun aPointAheadByTheDisplayedAge_hasAboutNoError() {
        val ahead =
            DisplayedRun(
                scene,
                scene.frames.map { frame ->
                    val truth = frame.truth[0]
                    val lead = 200.0 * (DisplayModel.DISPLAY_LATENCY_S + 0.025)
                    DisplayedFrame(frame.timeS, listOf(DisplayedTrack(1, truth.headXImg + lead, truth.headYImg, 0f)))
                },
                1,
            )

        assertThat(DisplayModel.meanErrorInH(ahead, walker, 0.5, 1.8)).isLessThan(0.02)
    }

    @Test
    fun withoutAnyDisplayedTrack_theErrorIsNotANumber() {
        val empty = DisplayedRun(scene, scene.frames.map { DisplayedFrame(it.timeS, emptyList()) }, 0)

        assertThat(DisplayModel.meanErrorInH(empty, walker, 0.5, 1.8).isNaN()).isTrue()
    }

    @Test
    fun aStopPointThatNeverPassesTheStop_hasNoOvershootAndNoTimePast() {
        val stopper =
            ScenePerson(
                1,
                listOf(PathPoint(0.0, 100.0, 150.0, 200.0), PathPoint(1.0, 300.0, 150.0, 200.0), PathPoint(2.0, 300.0, 150.0, 200.0)),
            )
        val stopScene = SyntheticScene(listOf(stopper), durationS = 2.0, fps = 20.0)
        val run =
            DisplayedRun(
                stopScene,
                stopScene.frames.map { f ->
                    DisplayedFrame(f.timeS, listOf(DisplayedTrack(1, f.truth[0].headXImg, f.truth[0].headYImg, 0f)))
                },
                1,
            )

        val result = DisplayModel.stopOf(run, stopper, 1.0, 0.05)

        assertThat(result.overshootInH).isEqualTo(0.0)
        assertThat(result.secondsPastAfterStop).isEqualTo(0.0)
    }

    @Test
    fun aPointThatKeepsGoingAfterTheStop_isMeasuredAsOvershootForTheTimeItStaysPast() {
        val stopper =
            ScenePerson(
                1,
                listOf(PathPoint(0.0, 100.0, 150.0, 200.0), PathPoint(1.0, 300.0, 150.0, 200.0), PathPoint(2.0, 300.0, 150.0, 200.0)),
            )
        val stopScene = SyntheticScene(listOf(stopper), durationS = 2.0, fps = 20.0)
        // Always 40 px (0.2 h) ahead of the truth, also while standing still.
        val run =
            DisplayedRun(
                stopScene,
                stopScene.frames.map { f ->
                    DisplayedFrame(f.timeS, listOf(DisplayedTrack(1, f.truth[0].headXImg + 40.0, f.truth[0].headYImg, 0f)))
                },
                1,
            )

        val result = DisplayModel.stopOf(run, stopper, 1.0, 0.05)

        assertThat(result.overshootInH).isWithin(0.01).of(0.2)
        assertThat(result.secondsPastAfterStop).isGreaterThan(0.9)
    }

    @Test
    fun runDisplayed_followsAStillPersonAndNumbersTheIds() {
        val still = ScenePerson(1, listOf(PathPoint(0.0, 600.0, 150.0, 200.0), PathPoint(3.0, 600.0, 150.0, 200.0)))
        val run = runDisplayed(SyntheticScene(listOf(still), durationS = 3.0, fps = 20.0, keypointNoiseRatio = 0.01), TrackerConfig())

        assertThat(run.highestId).isEqualTo(1)
        assertThat(run.frames.last().tracks).hasSize(1)
        assertThat(
            abs(
                run.frames
                    .last()
                    .tracks[0]
                    .xImg - 600.0,
            ),
        ).isLessThan(5.0)
    }
}
