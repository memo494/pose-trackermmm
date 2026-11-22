package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

/**
 * F4 / W1: the low-rate scaling of the head process noise must not shrink a track that coasts (LOST, or after a
 * miss). A fast walker who reverses or speeds up while the detector drops him for half a second must keep his id, as
 * with `trackAccelMinScale = 1` (no scaling), at 12 fps and 12 fps +-30 %, seeds 1 to 6.
 */
class TrackerLowRateCoastingTest {
    private val scaled = TrackerConfig()
    private val unscaled = TrackerConfig(trackAccelMinScale = 1f)

    @Test
    fun walkerAtTwoHeightsPerSecond_whoReversesInsideADropout_keepsTheSameIdsAsWithoutScaling() =
        // 2 h/s to the right, turns at 2.4 s (the dropout is 2.0 to 2.5 s) and walks back at 3 h/s.
        check("reverse", listOf(pt(0.0, 100.0), pt(2.4, 1060.0), pt(4.0, 100.0)), 4.0)

    @Test
    fun walkerAtTwoHeightsPerSecond_whoSpeedsUpInsideADropout_keepsTheSameIdsAsWithoutScaling() =
        check("accelerate", listOf(pt(0.0, 1300.0), pt(2.25, 400.0), pt(2.75, 100.0)), 3.0)

    private fun check(
        name: String,
        path: List<PathPoint>,
        durationS: Double,
    ) {
        for (jitter in listOf(0.0, JITTER)) {
            for (seed in 1L..SEEDS) {
                val person = ScenePerson(1, path, dropouts = listOf(TimeInterval(DROP_FROM_S, DROP_TO_S)))
                val scene =
                    SyntheticScene(
                        listOf(person),
                        durationS,
                        fps = FPS,
                        seed = seed,
                        frameJitterFraction = jitter,
                        keypointNoiseRatio = NOISE,
                        boxNoiseRatio = NOISE,
                    )
                val with = runScene(scene, scaled)
                val without = runScene(scene, unscaled)
                val where = "$name, 12 fps ${if (jitter > 0.0) "+-30 %" else ""}, seed $seed"
                assertWithMessage("$where: highest id with scaling ${with.highestId}, without ${without.highestId}")
                    .that(with.highestId)
                    .isEqualTo(without.highestId)
            }
        }
    }

    /** After `reset()` the frame interval of the old stream is forgotten: a 50 ms step is not scaled (fresh tracker = same). */
    @Test
    fun reset_forgetsTheLowRateInterval_soTheNextStepIsUnscaled() {
        val used = Tracker(scaled)
        val fresh = Tracker(scaled)
        for (k in 0 until PRIME_FRAMES) used.update(frameAt(k * LOW_STEP_NS, 100f + 30f * k))
        used.reset()

        var usedX = 0f
        var freshX = 0f
        for (k in 0 until AFTER_FRAMES) {
            val frame = frameAt(START_AFTER_NS + k * FAST_STEP_NS, 300f + 15f * k + if (k % 2 == 0) 3f else -3f)
            usedX = used.update(frame).headXImg(0)
            freshX = fresh.update(frame).headXImg(0)
        }

        assertThat(usedX).isEqualTo(freshX)
    }

    private fun frameAt(
        timestampNs: Long,
        headXImg: Float,
    ) = DetectionFrame(
        timestampNs,
        TimestampSource.IMAGE,
        IMAGE_WIDTH,
        IMAGE_HEIGHT,
        listOf(HeadTestDetections.person(headXImg, ROW_Y.toFloat(), HEIGHT.toFloat())),
    )

    private fun pt(
        timeS: Double,
        xImg: Double,
    ) = PathPoint(timeS, xImg, ROW_Y, HEIGHT)

    private companion object {
        const val LOW_STEP_NS = 83_333_333L
        const val FAST_STEP_NS = 50_000_000L
        const val START_AFTER_NS = 20_000_000_000L
        const val PRIME_FRAMES = 40
        const val AFTER_FRAMES = 6
        const val IMAGE_WIDTH = 1356
        const val IMAGE_HEIGHT = 610
        const val FPS = 12.0
        const val JITTER = 0.3
        const val SEEDS = 6L
        const val NOISE = 0.01
        const val ROW_Y = 150.0
        const val HEIGHT = 200.0
        const val DROP_FROM_S = 2.0
        const val DROP_TO_S = 2.5
    }
}
