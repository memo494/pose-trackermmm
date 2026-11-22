package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The display lead (F4, docs/plans/M_final.md) judged on the display model (`DisplayModel`): the point of frame k
 * is on screen 75 ms after its capture until the next frame's point replaces it. Every scenario runs at 12 fps, 20 fps
 * and 20 fps +-30 % with seeds 1 to 6, once with the lead (the defaults) and once without it
 * (`displayLeadEnabled = false`); a failure message names the scenario, rate, seed and quantity. The numbers are in
 * `MetricsThresholds` (the `F4_` constants).
 */
class DisplayLeadScenarioTest {
    private class Rate(
        val fps: Double,
        val jitter: Double,
    ) {
        val label: String =
            if (jitter > 0.0) "${fps.roundToInt()} fps +-${(jitter * PERCENT).roundToInt()} %" else "${fps.roundToInt()} fps"
    }

    private val withLead = TrackerConfig()
    private val withoutLead = TrackerConfig(displayLeadEnabled = false)

    @Test
    fun walkerAtOneHeightPerSecond_isShownCloserToTheTruthWithTheLead() = walker(1.0)

    @Test
    fun walkerAtTwoHeightsPerSecond_isShownCloserToTheTruthWithTheLead() = walker(2.0)

    @Test
    fun stillPerson_isShownBitIdenticallyWithAndWithoutTheLead() =
        forEachRun { rate, seed ->
            val person = ScenePerson(1, listOf(pt(0.0, 600.0), pt(STILL_S, 600.0)))
            val scene = scene(listOf(person), STILL_S, rate, seed)
            val on = runDisplayed(scene, withLead)
            val off = runDisplayed(scene, withoutLead)
            for (i in on.frames.indices) {
                val a = on.frames[i].tracks
                val b = off.frames[i].tracks
                val where = "still, ${rate.label}, seed $seed, frame $i"
                assertWithMessage("$where: tracks").that(a.size).isEqualTo(b.size)
                for (t in a.indices) {
                    assertWithMessage("$where: x").that(a[t].xImg.toRawBits()).isEqualTo(b[t].xImg.toRawBits())
                    assertWithMessage("$where: y").that(a[t].yImg.toRawBits()).isEqualTo(b[t].yImg.toRawBits())
                    assertWithMessage("$where: lead").that(a[t].leadMs).isEqualTo(0f)
                }
            }
        }

    @Test
    fun walkerStoppingAbruptlyAtOneHeightPerSecond_overshootsLittleOnScreen() = abruptStop(1.0)

    @Test
    fun walkerStoppingAbruptlyAtTwoHeightsPerSecond_overshootsLittleOnScreen() = abruptStop(2.0)

    @Test
    fun twoPeopleCrossing_keepTheirIdsWithTheLead_andEachRingIsNearerItsOwnHead() =
        forEachRun { rate, seed ->
            val a = ScenePerson(1, listOf(pt(0.0, 250.0), pt(CROSS_S, 1050.0)))
            val b =
                ScenePerson(
                    2,
                    listOf(
                        pt(0.0, 1050.0, ROW_Y + LOWER_ROW_RATIO * HEIGHT),
                        pt(
                            CROSS_S,
                            250.0,
                            ROW_Y + LOWER_ROW_RATIO * HEIGHT,
                        ),
                    ),
                )
            sameIdsAndRingsNearTheirOwnHead(
                "S1 crossing",
                scene(listOf(a, b), CROSS_S, rate, seed),
                a,
                b,
                rate,
                seed,
                OVERLAP_FROM_S,
                OVERLAP_TO_S,
            )
        }

    @Test
    fun twoPeopleCrossingInOneRow_keepTheirIdsWithTheLead_andEachRingIsNearerItsOwnHead() =
        forEachRun { rate, seed ->
            val a = ScenePerson(1, listOf(pt(0.0, 250.0), pt(CROSS_S, 1050.0)))
            val b = ScenePerson(2, listOf(pt(0.0, 1050.0), pt(CROSS_S, 250.0)))
            sameIdsAndRingsNearTheirOwnHead(
                "S1 crossing, one row",
                scene(listOf(a, b), CROSS_S, rate, seed),
                a,
                b,
                rate,
                seed,
                OVERLAP_FROM_S,
                OVERLAP_TO_S,
            )
        }

    @Test
    fun twoPeopleWalkingSideBySide_keepTheirIdsWithTheLead_andEachRingIsNearerItsOwnHead() =
        forEachRun { rate, seed ->
            val a = ScenePerson(1, listOf(pt(0.0, SIDE_START_X), pt(SIDE_S, SIDE_START_X + SIDE_SPEED_H * HEIGHT * SIDE_S)))
            val b =
                ScenePerson(
                    2,
                    listOf(
                        pt(0.0, SIDE_START_X + SIDE_GAP_H * HEIGHT),
                        pt(SIDE_S, SIDE_START_X + SIDE_GAP_H * HEIGHT + SIDE_SPEED_H * HEIGHT * SIDE_S),
                    ),
                )
            sameIdsAndRingsNearTheirOwnHead("side by side", scene(listOf(a, b), SIDE_S, rate, seed), a, b, rate, seed, 0.0, 0.0)
        }

    private fun walker(heightsPerS: Double) =
        forEachRun { rate, seed ->
            val speedImg = heightsPerS * HEIGHT
            val seconds = (WALK_END_X - WALK_START_X) / speedImg
            val person = ScenePerson(1, listOf(pt(0.0, WALK_START_X), pt(seconds, WALK_END_X)))
            val scene = scene(listOf(person), seconds, rate, seed)
            val to = seconds - WALK_TAIL_S
            val on = DisplayModel.meanErrorInH(runDisplayed(scene, withLead), person, WALK_FROM_S, to)
            val off = DisplayModel.meanErrorInH(runDisplayed(scene, withoutLead), person, WALK_FROM_S, to)
            val where = "walker $heightsPerS h/s, ${rate.label}, seed $seed"
            assertWithMessage(
                "$where: mean error with lead $on, without $off, limit <= ${MetricsThresholds.F4_MAX_ERROR_RATIO_WITH_LEAD} x without",
            ).that(on <= MetricsThresholds.F4_MAX_ERROR_RATIO_WITH_LEAD * off)
                .isTrue()
            assertWithMessage("$where: mean error with lead $on, limit <= ${MetricsThresholds.F4_MAX_WALKER_ERROR_H} h")
                .that(on <= MetricsThresholds.F4_MAX_WALKER_ERROR_H)
                .isTrue()
        }

    private fun abruptStop(heightsPerS: Double) =
        forEachRun { rate, seed ->
            val person =
                ScenePerson(
                    1,
                    listOf(
                        pt(0.0, STOP_START_X),
                        pt(STOP_S, STOP_START_X + heightsPerS * HEIGHT * STOP_S),
                        pt(
                            STOP_END_S,
                            STOP_START_X + heightsPerS * HEIGHT * STOP_S,
                        ),
                    ),
                )
            val scene = scene(listOf(person), STOP_END_S, rate, seed)
            val run = runDisplayed(scene, withLead)
            val result = DisplayModel.stopOf(run, person, STOP_S, MetricsThresholds.F4_STOP_PAST_LIMIT_H)
            val where = "abrupt stop from $heightsPerS h/s, ${rate.label}, seed $seed"
            assertWithMessage("$where: overshoot ${result.overshootInH} h, limit <= ${MetricsThresholds.F4_MAX_STOP_OVERSHOOT_H}")
                .that(result.overshootInH <= MetricsThresholds.F4_MAX_STOP_OVERSHOOT_H)
                .isTrue()
            // The stop is seen in the frame after it, which is on screen 75 ms later; one more frame covers the lag of
            // the filters: the point must not stay past the stop point longer than that.
            val longestFrameS = scene.frames.zipWithNext { x, y -> y.timeS - x.timeS }.max()
            val limitS = longestFrameS + MetricsThresholds.F4_DISPLAY_LATENCY_MS / MS_PER_S + longestFrameS
            assertWithMessage(
                "$where: time past the stop by more than ${MetricsThresholds.F4_STOP_PAST_LIMIT_H} h is ${result.secondsPastAfterStop} s, limit <= $limitS",
            ).that(result.secondsPastAfterStop <= limitS)
                .isTrue()
        }

    /**
     * The tracker never sees the lead, so ids and id switches are the same with and without it; and, outside the window
     * [overlapFromS], [overlapToS] where the two heads touch, the ring of each person is nearer to that person's head
     * than to the other's.
     */
    private fun sameIdsAndRingsNearTheirOwnHead(
        name: String,
        scene: SyntheticScene,
        a: ScenePerson,
        b: ScenePerson,
        rate: Rate,
        seed: Long,
        overlapFromS: Double,
        overlapToS: Double,
    ) {
        val on = runDisplayed(scene, withLead)
        val off = runDisplayed(scene, withoutLead)
        val where = "$name, ${rate.label}, seed $seed"
        assertWithMessage("$where: highest id").that(on.highestId).isEqualTo(off.highestId)
        for (i in on.frames.indices) {
            assertWithMessage(
                "$where: ids in frame $i",
            ).that(on.frames[i].tracks.map { it.id }).isEqualTo(off.frames[i].tracks.map { it.id })
        }
        // Like S1, id switches are counted outside the window where the heads touch (the matching is by position).
        val outsideOn = on.frames.indices.filter { scene.frames[it].timeS < overlapFromS || scene.frames[it].timeS > overlapToS }
        val metricsOn =
            TrackingMetrics(
                outsideOn.map {
                    scene.frames[it].toMetricsFrame(
                        on.frames[it].tracks.map { t ->
                            ShownTrack(t.id, t.xImg, t.yImg)
                        },
                    )
                },
            )
        val metricsOff =
            TrackingMetrics(
                outsideOn.map {
                    scene.frames[it].toMetricsFrame(
                        off.frames[it].tracks.map { t ->
                            ShownTrack(t.id, t.xImg, t.yImg)
                        },
                    )
                },
            )
        for (person in listOf(a.id, b.id)) {
            assertWithMessage(
                "$where: id switches of person $person",
            ).that(metricsOn.idSwitches(person)).isEqualTo(metricsOff.idSwitches(person))
        }
        val metricsSettled =
            TrackingMetrics(
                off.frames.mapIndexed {
                    i,
                    f,
                    ->
                    scene.frames[i].toMetricsFrame(f.tracks.map { ShownTrack(it.id, it.xImg, it.yImg) })
                },
            )
        val settled = metricsSettled.firstFrameAtOrAfter(RING_CHECK_FROM_S)
        val idA = metricsSettled.matchedTrackId(settled, a.id)
        val idB = metricsSettled.matchedTrackId(settled, b.id)
        assertWithMessage(
            "$where: both people have a track at $RING_CHECK_FROM_S s",
        ).that(idA != null && idB != null && idA != idB).isTrue()
        for (i in settled until on.frames.size) {
            val t = on.frames[i].timeS
            if (t >= overlapFromS && t <= overlapToS) continue
            val truthA = a.pathAt(t) ?: continue
            val truthB = b.pathAt(t) ?: continue
            val ringA = on.frames[i].tracks.firstOrNull { it.id == idA } ?: continue
            val ringB = on.frames[i].tracks.firstOrNull { it.id == idB } ?: continue
            assertWithMessage("$where: ring of person ${a.id} at ${"%.2f".format(t)} s is nearer its own head")
                .that(
                    hypot(ringA.xImg - truthA.headXImg, ringA.yImg - truthA.headYImg) <
                        hypot(ringA.xImg - truthB.headXImg, ringA.yImg - truthB.headYImg),
                ).isTrue()
            assertWithMessage("$where: ring of person ${b.id} at ${"%.2f".format(t)} s is nearer its own head")
                .that(
                    hypot(ringB.xImg - truthB.headXImg, ringB.yImg - truthB.headYImg) <
                        hypot(ringB.xImg - truthA.headXImg, ringB.yImg - truthA.headYImg),
                ).isTrue()
        }
    }

    private fun scene(
        people: List<ScenePerson>,
        durationS: Double,
        rate: Rate,
        seed: Long,
    ) = SyntheticScene(
        people = people,
        durationS = durationS,
        fps = rate.fps,
        seed = seed,
        frameJitterFraction = rate.jitter,
        keypointNoiseRatio = NOISE_RATIO,
        boxNoiseRatio = NOISE_RATIO,
    )

    private fun forEachRun(body: (Rate, Long) -> Unit) {
        for (rate in RATES) {
            for (seed in SEEDS) body(rate, seed)
        }
    }

    private fun pt(
        timeS: Double,
        xImg: Double,
        yImg: Double = ROW_Y,
    ) = PathPoint(timeS, xImg, yImg, HEIGHT)

    private companion object {
        val RATES = listOf(Rate(12.0, 0.0), Rate(20.0, 0.0), Rate(20.0, 0.3))
        val SEEDS = (1L..6L).toList()
        const val PERCENT = 100.0
        const val MS_PER_S = 1000.0
        const val NOISE_RATIO = 0.01
        const val ROW_Y = 150.0
        const val HEIGHT = 200.0
        const val LOWER_ROW_RATIO = 0.3
        const val STILL_S = 6.0
        const val WALK_START_X = 60.0
        const val WALK_END_X = 1300.0
        const val WALK_FROM_S = 1.0
        const val WALK_TAIL_S = 0.1
        const val STOP_START_X = 60.0
        const val STOP_S = 2.0
        const val STOP_END_S = 3.5
        const val CROSS_S = 5.0
        const val OVERLAP_FROM_S = 2.1
        const val OVERLAP_TO_S = 2.9
        const val RING_CHECK_FROM_S = 1.5
        const val SIDE_S = 4.0
        const val SIDE_START_X = 200.0
        const val SIDE_GAP_H = 0.6
        const val SIDE_SPEED_H = 1.5
    }
}
