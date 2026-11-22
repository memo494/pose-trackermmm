package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
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

    private fun pt(
        timeS: Double,
        xImg: Double,
    ) = PathPoint(timeS, xImg, ROW_Y, HEIGHT)

    private companion object {
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
