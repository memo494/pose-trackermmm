package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * M-legacy T3: the tracker with a box-only (detect-only) model. The scenes are those of `TrackerScenarioTest` (S1, S2,
 * S3, S4, S13) with every keypoint at (0, 0) with confidence 0 ([boxOnlyFrames]) and the tracker told `boxOnly`; the
 * head point is then `box top + headFallbackTopRatio * h` of every frame. Box noise only (1 % of the height per edge),
 * as the keypoints do not exist. The bars are the ones of the pose scenarios (`MetricsThresholds`, read, not changed)
 * except where a comment says so. Rates 12, 20 and 30 fps, seeds 1 to 3.
 */
class TrackerBoxOnlyScenarioTest {
    private class RunCase(
        val scenario: String,
        val fps: Double,
        val seed: Long,
    ) {
        fun message(what: String): String = "$scenario, ${fps.roundToInt()} fps, seed $seed: $what"
    }

    @Test
    fun twoPeopleCrossing_keepTheirIdsAndGetNoGhost() =
        forEachRun("box-only cross") { c ->
            val a = ScenePerson(1, listOf(pt(0.0, 250.0), pt(5.0, 1050.0)))
            val b = ScenePerson(2, listOf(pt(0.0, 1050.0), pt(5.0, 250.0)))
            val run = run(listOf(a, b), 5.0, c)
            val m = run.metrics
            val before = m.firstFrameAtOrAfter(1.5)
            val after = m.firstFrameAtOrAfter(3.5)
            for (person in 1..2) {
                val idBefore = m.matchedTrackId(before, person)
                assertWithMessage(c.message("person $person has a track at 1.5 s")).that(idBefore).isNotNull()
                assertWithMessage(c.message("person $person keeps the id")).that(m.matchedTrackId(after, person)).isEqualTo(idBefore)
            }
            val outside = TrackingMetrics(run.metricsFrames.filter { it.timeS < OVERLAP_FROM_S || it.timeS > OVERLAP_TO_S })
            assertWithMessage(c.message("id switches outside the overlap"))
                .that(outside.idSwitches(1) + outside.idSwitches(2))
                .isEqualTo(MetricsThresholds.S1_EXPECTED_SWITCHES_OUTSIDE_OVERLAP)
            assertWithMessage(c.message("ids")).that(run.highestId).isEqualTo(MetricsThresholds.S1_EXPECTED_IDS)
            assertWithMessage(c.message("ghost frames")).that(m.ghostFrames()).isEqualTo(MetricsThresholds.S1_EXPECTED_GHOSTS)
            assertAtLeast(
                c.message("coverage"),
                minOf(m.coverage(1, FROM_FRAME), m.coverage(2, FROM_FRAME)),
                MetricsThresholds.S1_MIN_COVERAGE,
            )
        }

    @Test
    fun threePeopleStanding_getOneTrackEachAndNoMore() =
        forEachRun("box-only three standing") { c ->
            val people =
                (1..3).map { id -> ScenePerson(id, ScenePerson.stillPath(200.0 + 400.0 * (id - 1), ROW_Y, HEIGHT, 0.0, 4.0)) }
            val run = run(people, 4.0, c)
            assertWithMessage(c.message("ids")).that(run.highestId).isEqualTo(MetricsThresholds.S13_EXPECTED_IDS)
            assertWithMessage(c.message("ghost frames")).that(run.metrics.ghostFrames()).isEqualTo(0)
            for (person in 1..3) {
                assertAtLeast(
                    c.message("coverage of $person"),
                    run.metrics.coverage(person, FROM_FRAME),
                    MetricsThresholds.S13_MIN_COVERAGE,
                )
                assertWithMessage(c.message("id switches of $person")).that(run.metrics.idSwitches(person)).isEqualTo(0)
            }
        }

    @Test
    fun aWalkerWhoStops_overshootsAndLagsNoMoreThanWithKeypoints() =
        forEachRun("box-only stop") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 200.0), pt(2.0, 1000.0), pt(4.0, 1000.0)))
            val run = run(listOf(person), 4.0, c)
            val m = run.metrics
            assertBelow(c.message("overshoot"), m.overshoot(1, 2.0), MetricsThresholds.S2_MAX_OVERSHOOT_H)
            val lag = m.lagFrames(1, m.firstFrameAtOrAfter(1.0), m.firstFrameAtOrAfter(2.0))
            assertBelow(c.message("|lag| in frames"), abs(lag), MetricsThresholds.S2_MAX_ABS_LAG_FRAMES)
            assertAtLeast(c.message("coverage"), m.coverage(1, FROM_FRAME), MetricsThresholds.S2_MIN_COVERAGE)
            assertWithMessage(c.message("ids")).that(run.highestId).isEqualTo(MetricsThresholds.S2_EXPECTED_IDS)
        }

    @Test
    fun aStillPerson_theDotJittersLessThanTheBoxTopItIsMadeFrom() =
        forEachRun("box-only still") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 600.0), pt(20.0, 600.0)))
            val run = run(listOf(person), 20.0, c)
            val from = c.fps.roundToInt()
            val ratio =
                listOf(Axis.X, Axis.Y).maxOf { axis ->
                    TrackingMetrics.jitterRms(run.metrics.matchedTrackSeries(1, axis, fromFrame = from)) /
                        TrackingMetrics.jitterRms(run.metrics.rawSeries(1, axis, fromFrame = from))
                }
            // The S3 bars: 0.60 at 20 fps, 0.45 at 30 fps; 12 fps uses the 20 fps one like the pose scenario.
            val limit = if (c.fps <= LOW_FPS) MetricsThresholds.S3_MAX_JITTER_RATIO_20_FPS else MetricsThresholds.S3_MAX_JITTER_RATIO_30_FPS
            assertAtMost(c.message("jitter ratio dot / box rule"), ratio, limit)
        }

    @Test
    fun aHalfSecondDropout_keepsTheIdAndTheTrackShown() =
        forEachRun("box-only dropout") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 300.0), pt(5.0, 1100.0)), dropouts = listOf(TimeInterval(2.0, 2.5)))
            val run = run(listOf(person), 5.0, c)
            assertWithMessage(c.message("ids")).that(run.highestId).isEqualTo(MetricsThresholds.S4_EXPECTED_IDS)
            assertWithMessage(c.message("id switches")).that(run.metrics.idSwitches(1)).isEqualTo(MetricsThresholds.S4_EXPECTED_SWITCHES)
            assertAtLeast(c.message("coverage"), run.metrics.coverage(1, FROM_FRAME), MetricsThresholds.S4_MIN_COVERAGE)
        }

    @Test
    fun theDotOfAStandingPerson_sitsAtTheHeadHeightOfTheBoxRule() {
        val scene = scene(listOf(ScenePerson(1, ScenePerson.stillPath(600.0, ROW_Y, HEIGHT, 0.0, 2.0))), 2.0, RunCase("pos", 20.0, 1L))
        val run = runFrames(boxOnlyFrames(scene), boxOnly = true)

        val last = run.tracks.last().single()

        // The scene box top is 0.1 h above the true head and the rule puts the dot 0.10 h below the top.
        assertThat(last.headYImg.toDouble()).isWithin(0.02 * HEIGHT).of(ROW_Y)
        assertThat(last.headXImg.toDouble()).isWithin(0.02 * HEIGHT).of(600.0)
    }

    @Test
    fun theTrackedHeadOfABoxOnlyModel_isNeverMarkedEstimated_butThePoseRuleOnTheSameFramesIs() {
        val scene = scene(listOf(ScenePerson(1, ScenePerson.stillPath(600.0, ROW_Y, HEIGHT, 0.0, 1.0))), 1.0, RunCase("flag", 20.0, 1L))
        val frames = boxOnlyFrames(scene)
        val boxOnlyTracker = Tracker(TrackerConfig(), boxOnly = true)
        val poseTracker = Tracker(TrackerConfig())
        var boxOnlyList: TrackList? = null
        var poseList: TrackList? = null
        for (frame in frames) {
            boxOnlyList = boxOnlyTracker.update(frame.frame)
            poseList = poseTracker.update(frame.frame)
        }

        assertThat(boxOnlyList!!.isHeadEstimated(0)).isFalse()
        assertThat(poseList!!.isHeadEstimated(0)).isTrue()
    }

    @Test
    fun changingTheModelKind_resetsTheTracks() {
        val scene = scene(listOf(ScenePerson(1, ScenePerson.stillPath(600.0, ROW_Y, HEIGHT, 0.0, 1.0))), 1.0, RunCase("kind", 20.0, 1L))
        val tracker = Tracker(TrackerConfig())
        var list: TrackList? = null
        for (frame in boxOnlyFrames(scene)) list = tracker.update(frame.frame)
        assertThat(list!!.size).isEqualTo(1)

        tracker.boxOnly = true

        assertThat(tracker.onIdle(1L).size).isEqualTo(0)
        assertThat(tracker.boxOnly).isTrue()
    }

    private fun scene(
        people: List<ScenePerson>,
        durationS: Double,
        case: RunCase,
    ) = SyntheticScene(
        people = people,
        durationS = durationS,
        fps = case.fps,
        seed = case.seed,
        boxNoiseRatio = NOISE_RATIO,
    )

    private fun run(
        people: List<ScenePerson>,
        durationS: Double,
        case: RunCase,
    ): ScenarioRun = runFrames(boxOnlyFrames(scene(people, durationS, case)), boxOnly = true)

    private fun forEachRun(
        scenario: String,
        body: (RunCase) -> Unit,
    ) {
        for (fps in FPS_LIST) {
            for (seed in SEEDS) body(RunCase(scenario, fps, seed))
        }
    }

    private fun pt(
        timeS: Double,
        xImg: Double,
    ) = PathPoint(timeS, xImg, ROW_Y, HEIGHT)

    private fun assertBelow(
        message: String,
        value: Double,
        limit: Double,
    ) = assertWithMessage("$message: $value, limit < $limit").that(value < limit).isTrue()

    private fun assertAtMost(
        message: String,
        value: Double,
        limit: Double,
    ) = assertWithMessage("$message: $value, limit <= $limit").that(value <= limit).isTrue()

    private fun assertAtLeast(
        message: String,
        value: Double,
        limit: Double,
    ) = assertWithMessage("$message: $value, limit >= $limit").that(value >= limit).isTrue()

    private companion object {
        val FPS_LIST = listOf(12.0, 20.0, 30.0)
        val SEEDS = listOf(1L, 2L, 3L)
        const val NOISE_RATIO = 0.01
        const val ROW_Y = 150.0
        const val HEIGHT = 200.0
        const val FROM_FRAME = 2
        const val OVERLAP_FROM_S = 2.1
        const val OVERLAP_TO_S = 2.9
        const val LOW_FPS = 20.0
    }
}
