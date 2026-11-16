package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The acceptance scenarios S1 to S14 of docs/plans/M3.md (T5). The Python functions in
 * `docs/plans/M3_sim/scenarios.py` define each scenario; every asserted number is in [MetricsThresholds].
 * Each scenario runs at 20, 30 and 60 fps and at 30 fps +-30 %, with seeds 1, 2 and 3; a failure message names
 * the scenario, the rate, the seed and the quantity.
 */
class TrackerScenarioTest {
    private class Rate(
        val fps: Double,
        val jitter: Double,
    ) {
        val label: String =
            if (jitter > 0.0) "${fps.roundToInt()} fps +-${(jitter * PERCENT).roundToInt()} %" else "${fps.roundToInt()} fps"
    }

    private class RunCase(
        val scenario: String,
        val rate: Rate,
        val seed: Long,
    ) {
        fun message(what: String): String = "$scenario, ${rate.label}, seed $seed: $what"
    }

    @Test
    fun s1_twoPeopleCrossInOneRow_keepTheirIds() = crossing("S1 cross, one row", 0.0)

    @Test
    fun s1_twoPeopleCrossOneThirdOfAHeightApart_keepTheirIds() = crossing("S1 cross, 0.3 h lower", LOWER_ROW_RATIO)

    @Test
    fun s2_walkerStopsAbruptly_overshootsLittleAndLagsLittle() =
        forEachRun("S2 stop") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 200.0), pt(2.0, 1000.0), pt(4.0, 1000.0)))
            val run = runScene(scene(listOf(person), 4.0, c))
            val m = run.metrics
            assertBelow(c.message("overshoot"), m.overshoot(1, 2.0), MetricsThresholds.S2_MAX_OVERSHOOT_H)
            val lag = m.lagFrames(1, m.firstFrameAtOrAfter(1.0), m.firstFrameAtOrAfter(2.0))
            assertBelow(c.message("|lag| in frames"), abs(lag), MetricsThresholds.S2_MAX_ABS_LAG_FRAMES)
            assertAtLeast(c.message("coverage"), m.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S2_MIN_COVERAGE)
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S2_EXPECTED_IDS)
        }

    @Test
    fun s3_stillPerson_jitterIsBelowTheRawJitter() =
        forEachRun("S3 still") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 600.0), pt(20.0, 600.0)))
            val run = runScene(scene(listOf(person), 20.0, c))
            val from = c.rate.fps.roundToInt()
            val ratio =
                listOf(Axis.X, Axis.Y).maxOf { axis ->
                    TrackingMetrics.jitterRms(run.metrics.matchedTrackSeries(1, axis, fromFrame = from)) /
                        TrackingMetrics.jitterRms(run.metrics.rawSeries(1, axis, fromFrame = from))
                }
            val limit =
                when {
                    c.rate.fps <= LOW_FPS -> MetricsThresholds.S3_MAX_JITTER_RATIO_20_FPS
                    c.rate.fps <= MID_FPS -> MetricsThresholds.S3_MAX_JITTER_RATIO_30_FPS
                    else -> MetricsThresholds.S3_MAX_JITTER_RATIO_60_FPS
                }
            assertAtMost(c.message("jitter ratio track / raw"), ratio, limit)
        }

    @Test
    fun s4_walkerMissingForHalfASecond_keepsTheIdAndStaysShown() = dropout("S4 dropout, walking", 1100.0)

    @Test
    fun s4_standingPersonMissingForHalfASecond_keepsTheIdAndStaysShown() = dropout("S4 dropout, standing", 300.0)

    @Test
    fun s5_headKeypointsLowForASecond_headStaysOnTheTruth() =
        forEachRun("S5 keypoints") { c ->
            val person =
                ScenePerson(
                    1,
                    listOf(pt(0.0, 300.0), pt(4.0, 1100.0)),
                    keypointDropouts = listOf(KeypointDropout(TimeInterval(2.0, 3.0), setOf(0, 1, 3))),
                )
            val run = runScene(scene(listOf(person), 4.0, c))
            assertBelow(c.message("max error in h"), run.metrics.maxErrorInH(1, 10), MetricsThresholds.S5_MAX_ERROR_H)
            assertCount(c.message("id switches"), run.metrics.idSwitches(1), MetricsThresholds.S5_EXPECTED_SWITCHES)
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S5_EXPECTED_IDS)
        }

    @Test
    fun s6_fiveSecondsWithoutFrames_walker_keepsTheIdAndPicksUpAgain() = gap("S6 gap", pause = false)

    @Test
    fun s6_fiveSecondsWithoutFrames_afterAPlayersPause_keepsTheIdAndPicksUpAgain() = gap("S6 gap, player pause", pause = true)

    @Test
    fun s7_oneStillFrame_isShownOnlyAfterTheIdlePromotion() = stillPicture("S7 still picture, 1 frame", 1, STILL_SCORE)

    @Test
    fun s7_twoStillFrames_areShownOnlyAfterTheIdlePromotion() = stillPicture("S7 still picture, 2 frames", 2, STILL_SCORE)

    @Test
    fun s7_oneStillFrameWithScore030_isShownOnlyAfterTheIdlePromotion() =
        stillPicture("S7 still picture, 1 frame, score 0.30", 1, LOW_SCORE)

    @Test
    fun s8_steadyScore030_isShownFromTheThirdFrame() = lowScore("S8 score 0.30") { LOW_SCORE }

    @Test
    fun s8_scoreSwingingAroundTheBirthScore_isShownFromTheThirdFrame() =
        lowScore("S8 score 0.25 + 0.08 sin 9t") { t -> SWING_CENTER + SWING_AMPLITUDE * sin(SWING_RATE_PER_S * t) }

    @Test
    fun s9_shortFalsePositives_showNothing() = falsePositives("S9 false positives", withPerson = false)

    @Test
    fun s9_shortFalsePositivesBesideAStillPerson_showOnlyThePerson() = falsePositives("S9 false positives + person", withPerson = true)

    @Test
    fun s10_scoreBelowTheBirthScore_startsNoTrack() =
        forEachRun("S10 score 0.20") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 300.0), pt(4.0, 1100.0)), score = { BELOW_BIRTH_SCORE })
            val run = runScene(scene(listOf(person), 4.0, c))
            assertAtMost(c.message("largest list size"), run.maxListSize().toDouble(), MetricsThresholds.S10_MAX_LIST_SIZE.toDouble())
        }

    @Test
    fun s11_walkerLeavesTheImage_trackIsRemovedWithinTheMargin() =
        forEachRun("S11 exit") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 956.0), pt(1.0, IMAGE_WIDTH)))
            val config = TrackerConfig()
            val sceneIn = scene(listOf(person), 3.0, c)
            val run = runScene(sceneIn, config)
            var lastShownAfterEnd = 0.0
            for (i in run.tracks.indices) {
                val t = run.sceneFrames[i].timeS
                if (t > 1.0 && run.shownCount(i) > 0) lastShownAfterEnd = max(lastShownAfterEnd, t - 1.0)
            }
            assertAtMost(c.message("seconds shown after the end"), lastShownAfterEnd, MetricsThresholds.S11_MAX_SHOWN_AFTER_END_S)
            var beyond = Double.NEGATIVE_INFINITY
            for (list in run.tracks) {
                for (track in list) {
                    val x = track.headXImg.toDouble()
                    val y = track.headYImg.toDouble()
                    val outside = max(max(-x, x - sceneIn.widthImg), max(-y, y - sceneIn.heightImg))
                    beyond = max(beyond, outside / track.heightImg)
                }
            }
            assertAtMost(c.message("head point beyond the image, in track heights"), beyond, config.trackExitMarginRatio.toDouble())
            assertAtLeast(c.message("coverage"), run.metrics.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S11_MIN_COVERAGE)
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S11_EXPECTED_IDS)
        }

    @Test
    fun s12_duplicateBoxForTheWholeScene_isOneTrack() =
        forEachRun("S12 duplicate") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 300.0), pt(3.0, 900.0)), duplicates = listOf(TimeInterval(0.0, 3.0)))
            val run = runScene(scene(listOf(person), 3.0, c))
            assertAtMost(c.message("largest list size"), run.maxListSize().toDouble(), MetricsThresholds.S12_MAX_LIST_SIZE.toDouble())
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S12_EXPECTED_IDS)
            assertAtLeast(c.message("coverage"), run.metrics.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S12_MIN_COVERAGE)
        }

    @Test
    fun s13_threePeopleWithRandomlyMissingDetections_keepTheirIds() =
        forEachRun("S13 flicker") { c ->
            val times = scene(emptyList(), FLICKER_DURATION_S, c, noise = false).frames.map { it.timeS }
            val random = Random(c.seed * SEED_FACTOR + SEED_OFFSET)
            val people =
                (0 until FLICKER_PEOPLE).map { k ->
                    val dropouts =
                        times.indices
                            .filter { it >= FLICKER_FROM_FRAME && random.nextDouble() < FLICKER_PROBABILITY }
                            .map { TimeInterval(times[it] - TIME_EPS_S, times[it] + TIME_EPS_S) }
                    val x = 200.0 + FLICKER_SPACING_IMG * k
                    ScenePerson(k + 1, listOf(pt(0.0, x), pt(FLICKER_DURATION_S, x + FLICKER_TRAVEL_IMG)), dropouts = dropouts)
                }
            val sceneIn = scene(people, FLICKER_DURATION_S, c, noise = false)
            assertWithMessage(c.message("frame times stay those of the scene without people"))
                .that(sceneIn.frames.map { it.timeS })
                .isEqualTo(times)
            val run = runScene(sceneIn)
            val m = run.metrics
            for (k in 1..FLICKER_PEOPLE) {
                assertCount(c.message("id switches of person $k"), m.idSwitches(k), MetricsThresholds.S13_EXPECTED_SWITCHES_EACH)
            }
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S13_EXPECTED_IDS)
            assertCount(c.message("ghost frames"), m.ghostFrames(), MetricsThresholds.S13_EXPECTED_GHOSTS)
            val coverage = (1..FLICKER_PEOPLE).minOf { m.coverage(it, FLICKER_FROM_FRAME) }
            assertAtLeast(c.message("lowest coverage from frame $FLICKER_FROM_FRAME"), coverage, MetricsThresholds.S13_MIN_COVERAGE)
        }

    @Test
    fun s14_personGrowsFrom100To250Pixels_isTrackedClosely() =
        forEachRun("S14 approach") { c ->
            val person = ScenePerson(1, listOf(pt(0.0, 400.0, 100.0, 100.0), pt(3.0, 700.0, 100.0, 250.0)))
            val run = runScene(scene(listOf(person), 3.0, c))
            assertBelow(c.message("max error in h"), run.metrics.maxErrorInH(1, 10), MetricsThresholds.S14_MAX_ERROR_H)
            assertCount(c.message("ids"), run.highestId, MetricsThresholds.S14_EXPECTED_IDS)
            assertAtLeast(c.message("coverage"), run.metrics.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S14_MIN_COVERAGE)
        }

    private fun crossing(
        name: String,
        lowerRowRatio: Double,
    ) = forEachRun(name) { c ->
        val rowB = ROW_Y + lowerRowRatio * HEIGHT
        val a = ScenePerson(1, listOf(pt(0.0, 250.0), pt(5.0, 1050.0)))
        val b = ScenePerson(2, listOf(pt(0.0, 1050.0, rowB), pt(5.0, 250.0, rowB)))
        val run = runScene(scene(listOf(a, b), 5.0, c))
        val m = run.metrics
        val before = m.firstFrameAtOrAfter(1.5)
        val after = m.firstFrameAtOrAfter(3.5)
        for (person in 1..2) {
            val idBefore = m.matchedTrackId(before, person)
            assertWithMessage(c.message("person $person has a track at 1.5 s")).that(idBefore).isNotNull()
            assertWithMessage(c.message("person $person keeps the id from 1.5 s to 3.5 s"))
                .that(m.matchedTrackId(after, person))
                .isEqualTo(idBefore)
        }
        assertWithMessage(c.message("the two people have different ids"))
            .that(m.matchedTrackId(before, 1))
            .isNotEqualTo(m.matchedTrackId(before, 2))
        val outside = TrackingMetrics(run.metricsFrames.filter { it.timeS < OVERLAP_FROM_S || it.timeS > OVERLAP_TO_S })
        assertCount(
            c.message("id switches outside the overlap"),
            outside.idSwitches(1) + outside.idSwitches(2),
            MetricsThresholds.S1_EXPECTED_SWITCHES_OUTSIDE_OVERLAP,
        )
        assertAtLeast(
            c.message("lowest coverage"),
            minOf(m.coverage(1, COVERAGE_FROM_FRAME), m.coverage(2, COVERAGE_FROM_FRAME)),
            MetricsThresholds.S1_MIN_COVERAGE,
        )
        assertCount(c.message("ghost frames"), m.ghostFrames(), MetricsThresholds.S1_EXPECTED_GHOSTS)
        assertCount(c.message("ids"), run.highestId, MetricsThresholds.S1_EXPECTED_IDS)
    }

    private fun dropout(
        name: String,
        endXImg: Double,
    ) = forEachRun(name) { c ->
        val person = ScenePerson(1, listOf(pt(0.0, 300.0), pt(5.0, endXImg)), dropouts = listOf(TimeInterval(2.0, 2.5)))
        val run = runScene(scene(listOf(person), 5.0, c))
        val m = run.metrics
        val before = m.firstFrameAtOrAfter(2.0) - 1
        val after = m.firstFrameAtOrAfter(2.5)
        val idBefore = m.matchedTrackId(before, 1)
        assertWithMessage(c.message("a shown track just before the dropout")).that(idBefore).isNotNull()
        assertWithMessage(c.message("same id at the first frame after the dropout"))
            .that(m.matchedTrackId(after, 1))
            .isEqualTo(idBefore)
        assertCount(c.message("id switches"), m.idSwitches(1), MetricsThresholds.S4_EXPECTED_SWITCHES)
        assertAtLeast(c.message("coverage"), m.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S4_MIN_COVERAGE)
        assertCount(c.message("ids"), run.highestId, MetricsThresholds.S4_EXPECTED_IDS)
    }

    private fun gap(
        name: String,
        pause: Boolean,
    ) = forEachRun(name) { c ->
        val path =
            if (pause) {
                listOf(pt(0.0, 100.0), pt(2.0, 900.0), pt(7.3, 900.0), pt(8.3, 1300.0))
            } else {
                listOf(pt(0.0, 300.0), pt(2.0, 500.0), pt(7.0, 500.0), pt(9.0, 700.0))
            }
        val noFrames = if (pause) TimeInterval(2.3, 7.3) else TimeInterval(2.0, 7.0)
        val sceneIn = scene(listOf(ScenePerson(1, path)), if (pause) 8.3 else 9.0, c, idleGaps = listOf(noFrames))
        val last = sceneIn.frames.indexOfLast { it.timeS < noFrames.fromS }
        assertWithMessage(c.message("frames before and after the gap")).that(last in 0 until sceneIn.frames.size - 1).isTrue()
        val idleNs = IDLE_MS_AFTER_LAST_FRAME.map { it * TrackerConfig.NANOS_PER_MILLI }.toLongArray()
        val run = runScene(sceneIn, idleNsAfterFrame = mapOf(last to idleNs))
        for (step in run.idleSteps) {
            assertWithMessage(c.message("onIdle(${step.idleNs} ns) changes nothing")).that(step.after).isEqualTo(step.before)
        }
        val m = run.metrics
        val idBefore = m.matchedTrackId(last, 1)
        assertWithMessage(c.message("a shown track before the gap")).that(idBefore).isNotNull()
        assertWithMessage(c.message("same id after the gap")).that(m.matchedTrackId(last + 1, 1)).isEqualTo(idBefore)
        for (k in 0 until FRAMES_AFTER_GAP_CHECKED) {
            val error = m.errorInH(1, last + 1 + k)
            assertBelow(c.message("error in h, frame ${k + 1} after the gap"), error, MetricsThresholds.S6_MAX_ERROR_H)
        }
        for (i in EMPTY_LIST_CHECK_FROM_FRAME until run.tracks.size) {
            val size = run.tracks[i].size.toDouble()
            assertAtLeast(c.message("list size at frame $i"), size, MetricsThresholds.S6_MIN_LIST_SIZE.toDouble())
        }
        assertCount(c.message("ids"), run.highestId, MetricsThresholds.S6_EXPECTED_IDS)
    }

    private fun stillPicture(
        name: String,
        fedFrames: Int,
        score: Double,
    ) = forEachRun(name) { c ->
        val config = TrackerConfig()
        val person = ScenePerson(1, listOf(pt(0.0, 600.0), pt(5.0, 600.0)), score = { score })
        val frames = scene(listOf(person), 1.0, c).frames
        val runner = ScenarioRunner(config)
        var list = emptyList<RecordedTrack>()
        for (i in 0 until fedFrames) list = runner.feed(frames[i])
        val shownBefore = list.count { it.isShown }
        val shownJustBelow = runner.idle(config.trackIdlePromoteNs - TrackerConfig.NANOS_PER_MILLI).count { it.isShown }
        assertCount(
            c.message("shown after the frames and after onIdle(promote - 1 ms)"),
            shownBefore + shownJustBelow,
            MetricsThresholds.S7_EXPECTED_SHOWN_BEFORE_PROMOTE,
        )
        val promoted = runner.idle(config.trackIdlePromoteNs)
        assertCount(
            c.message("shown after onIdle(promote)"),
            promoted.count { it.isShown },
            MetricsThresholds.S7_EXPECTED_SHOWN_AFTER_PROMOTE,
        )
        val error = if (promoted.isEmpty()) Double.NaN else hypot(promoted[0].headXImg - 600.0, promoted[0].headYImg - ROW_Y) / HEIGHT
        assertBelow(c.message("head error in h after the promotion"), error, MetricsThresholds.S7_MAX_ERROR_H)
        for (i in fedFrames until fedFrames + FRAMES_AFTER_PROMOTION) list = runner.feed(frames[i])
        assertWithMessage(c.message("the next frames keep one track with the promoted id"))
            .that(list.map { it.id })
            .isEqualTo(promoted.map { it.id })
        assertCount(c.message("ids"), runner.finish().highestId, MetricsThresholds.S7_EXPECTED_IDS)
    }

    private fun lowScore(
        name: String,
        score: (Double) -> Double,
    ) = forEachRun(name) { c ->
        val person = ScenePerson(1, listOf(pt(0.0, 300.0), pt(4.0, 1100.0)), score = score)
        val run = runScene(scene(listOf(person), 4.0, c))
        assertCount(c.message("first frame with a shown track"), run.firstShownFrame(), MetricsThresholds.S8_EXPECTED_FIRST_SHOWN_FRAME)
        assertAtLeast(c.message("coverage"), run.metrics.coverage(1, COVERAGE_FROM_FRAME), MetricsThresholds.S8_MIN_COVERAGE)
        assertCount(c.message("ids"), run.highestId, MetricsThresholds.S8_EXPECTED_IDS)
        assertAtMost(c.message("largest list size"), run.maxListSize().toDouble(), MetricsThresholds.S8_MAX_LIST_SIZE.toDouble())
    }

    private fun falsePositives(
        name: String,
        withPerson: Boolean,
    ) = forEachRun(name) { c ->
        val times = scene(emptyList(), FALSE_POSITIVE_DURATION_S, c, noise = false).frames.map { it.timeS }
        val n = times.size
        val first = n / 6
        val second = n / 3
        val third = n / 2
        val positives =
            listOf(
                FalsePositive(TimeInterval(times[first] - TIME_EPS_S, times[first] + TIME_EPS_S), 300.0, 200.0, HEIGHT, 0.55),
                FalsePositive(TimeInterval(times[second] - TIME_EPS_S, times[second] + TIME_EPS_S), 700.0, 250.0, HEIGHT, 0.90),
                FalsePositive(TimeInterval(times[third] - TIME_EPS_S, times[third + 1] + TIME_EPS_S), 1000.0, 200.0, HEIGHT, 0.40),
            )
        val standing = ScenePerson(1, listOf(pt(0.0, 500.0), pt(FALSE_POSITIVE_DURATION_S, 500.0)))
        val people = if (withPerson) listOf(standing) else emptyList()
        val sceneIn = scene(people, FALSE_POSITIVE_DURATION_S, c, noise = false, falsePositives = positives)
        assertWithMessage(c.message("frame times stay those of the scene without false positives"))
            .that(sceneIn.frames.map { it.timeS })
            .isEqualTo(times)
        val detections = sceneIn.frames.sumOf { it.detections.size }
        assertCount(c.message("detections in the scene"), detections, FALSE_POSITIVE_DETECTIONS + if (withPerson) n else 0)
        val run = runScene(sceneIn)
        assertCount(c.message("ghost frames"), run.metrics.ghostFrames(), MetricsThresholds.S9_EXPECTED_GHOSTS)
        val expectedShown =
            if (withPerson) MetricsThresholds.S9_EXPECTED_MAX_SHOWN_WITH_PERSON else MetricsThresholds.S9_EXPECTED_MAX_SHOWN_ALONE
        assertCount(c.message("most tracks shown in one frame"), run.maxShownCount(), expectedShown)
    }

    private fun scene(
        people: List<ScenePerson>,
        durationS: Double,
        case: RunCase,
        noise: Boolean = true,
        idleGaps: List<TimeInterval> = emptyList(),
        falsePositives: List<FalsePositive> = emptyList(),
    ) = SyntheticScene(
        people = people,
        durationS = durationS,
        fps = case.rate.fps,
        seed = case.seed,
        frameJitterFraction = case.rate.jitter,
        idleGaps = idleGaps,
        falsePositives = falsePositives,
        keypointNoiseRatio = if (noise) NOISE_RATIO else 0.0,
        boxNoiseRatio = if (noise) NOISE_RATIO else 0.0,
    )

    private fun forEachRun(
        scenario: String,
        body: (RunCase) -> Unit,
    ) {
        for (rate in RATES) {
            for (seed in SEEDS) body(RunCase(scenario, rate, seed))
        }
    }

    private fun pt(
        timeS: Double,
        xImg: Double,
        yImg: Double = ROW_Y,
        heightImg: Double = HEIGHT,
    ) = PathPoint(timeS, xImg, yImg, heightImg)

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

    private fun assertCount(
        message: String,
        value: Int,
        expected: Int,
    ) = assertWithMessage(message).that(value).isEqualTo(expected)

    private companion object {
        private val RATES = listOf(Rate(20.0, 0.0), Rate(30.0, 0.0), Rate(60.0, 0.0), Rate(30.0, JITTER_FRACTION))
        private val SEEDS = listOf(1L, 2L, 3L)
        private val IDLE_MS_AFTER_LAST_FRAME = listOf(500L, 1000L, 4000L)

        const val JITTER_FRACTION = 0.3
        const val PERCENT = 100.0
        const val NOISE_RATIO = 0.01
        const val ROW_Y = 150.0
        const val HEIGHT = 200.0
        const val IMAGE_WIDTH = 1356.0
        const val LOWER_ROW_RATIO = 0.3
        const val LOW_FPS = 20.0
        const val MID_FPS = 30.0
        const val COVERAGE_FROM_FRAME = 2
        const val OVERLAP_FROM_S = 2.1
        const val OVERLAP_TO_S = 2.9
        const val FRAMES_AFTER_GAP_CHECKED = 3
        const val EMPTY_LIST_CHECK_FROM_FRAME = 3
        const val FRAMES_AFTER_PROMOTION = 5
        const val STILL_SCORE = 0.9
        const val LOW_SCORE = 0.30
        const val BELOW_BIRTH_SCORE = 0.20
        const val SWING_CENTER = 0.25
        const val SWING_AMPLITUDE = 0.08
        const val SWING_RATE_PER_S = 9.0
        const val TIME_EPS_S = 1e-7
        const val FALSE_POSITIVE_DURATION_S = 3.0
        const val FALSE_POSITIVE_DETECTIONS = 4
        const val FLICKER_DURATION_S = 4.0
        const val FLICKER_PEOPLE = 3
        const val FLICKER_FROM_FRAME = 5
        const val FLICKER_PROBABILITY = 0.3
        const val FLICKER_SPACING_IMG = 90.0
        const val FLICKER_TRAVEL_IMG = 640.0
        const val SEED_FACTOR = 31L
        const val SEED_OFFSET = 7L
    }
}
