package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.hypot

/** One track as the overlay draws it in a frame: the id, the displayed head point (Img pixels) and the lead applied. */
internal class DisplayedTrack(
    val id: Int,
    val xImg: Double,
    val yImg: Double,
    val leadMs: Float,
)

/** The tracks shown for one frame taken at [timeS] (scene time), after smoothing and the display lead. */
internal class DisplayedFrame(
    val timeS: Double,
    val tracks: List<DisplayedTrack>,
)

/** A scene fed through `Tracker` and `TrackSnapshot.ofSmoothed`, as the pipeline does, one displayed frame per scene frame. */
internal class DisplayedRun(
    val scene: SyntheticScene,
    val frames: List<DisplayedFrame>,
    val highestId: Int,
)

/** Feeds [scene] to a new tracker and smoother; every snapshot is taken with [resultAgeMs] as the result age. */
internal fun runDisplayed(
    scene: SyntheticScene,
    config: TrackerConfig = TrackerConfig(),
    resultAgeMs: Float = DisplayModel.RESULT_AGE_MS,
): DisplayedRun {
    val tracker = Tracker(config)
    val smoother = HeadSmoother(config)
    val frames = ArrayList<DisplayedFrame>()
    var highestId = 0
    for (frame in scene.frames) {
        val list = tracker.update(frame.frame)
        for (i in 0 until list.size) if (list.id(i) > highestId) highestId = list.id(i)
        val snapshot = TrackSnapshot.ofSmoothed(list, scene.widthImg, scene.heightImg, smoother, frame.frame.timestampNs, resultAgeMs)
        val tracks = ArrayList<DisplayedTrack>(snapshot.count)
        for (i in 0 until snapshot.count) {
            tracks.add(DisplayedTrack(snapshot.id(i), snapshot.headXImg(i).toDouble(), snapshot.headYImg(i).toDouble(), snapshot.leadMs(i)))
        }
        frames.add(DisplayedFrame(frame.timeS, tracks))
    }
    return DisplayedRun(scene, frames, highestId)
}

/**
 * The display model of F4: the point of frame `k` is on the screen over `[t_k + latency, t_(k+1) + latency)`, with
 * `t_k` the frame's capture time. What the viewer sees at time `t` is compared with the true head of a person at
 * `t`, sampled every [SAMPLE_STEP_S] (1 ms). The point of a person in frame `k` is the shown track nearest to the
 * person's true head at `t_k`, within [MATCH_RADIUS_RATIO] heights; a frame without such a track shows nothing,
 * and the samples it covers are not counted.
 */
internal object DisplayModel {
    /**
     * Capture to screen: 60 ms until the snapshot is made plus 15 ms of draw and screen. The lead adds only 10 ms of it
     * (`displayLeadExtraMs`), which keeps the overshoot after a stop down.
     */
    const val DISPLAY_LATENCY_S = MetricsThresholds.F4_DISPLAY_LATENCY_MS / 1000.0

    /** The result age the pipeline would pass for [DISPLAY_LATENCY_S]: the latency minus the extra the lead adds itself. */
    const val RESULT_AGE_MS = 60f

    /** Sampling step of the time average, 1 ms. */
    const val SAMPLE_STEP_S = 0.001

    /** A displayed point counts as the person's when it is within this many heights of the true head. */
    const val MATCH_RADIUS_RATIO = 0.5

    /** The displayed point of [person] and its time, one per frame; null where the frame shows nothing for the person. */
    private fun pointsOf(
        run: DisplayedRun,
        person: ScenePerson,
    ): List<DisplayedTrack?> =
        run.frames.map { frame ->
            val truth = person.pathAt(frame.timeS) ?: return@map null
            frame.tracks
                .minByOrNull { hypot(it.xImg - truth.headXImg, it.yImg - truth.headYImg) }
                ?.takeIf { hypot(it.xImg - truth.headXImg, it.yImg - truth.headYImg) <= MATCH_RADIUS_RATIO * truth.heightImg }
        }

    /** Index of the frame on screen at [timeS], or -1 before the first frame is visible. */
    private fun frameOnScreen(
        run: DisplayedRun,
        timeS: Double,
        from: Int,
    ): Int {
        var k = from
        while (k + 1 < run.frames.size && run.frames[k + 1].timeS + DISPLAY_LATENCY_S <= timeS) k++
        return if (run.frames[k].timeS + DISPLAY_LATENCY_S <= timeS) k else -1
    }

    /**
     * Time average, in track heights, of the distance between what is on screen for [person] and the person's true
     * head over `[fromS, toS)`; `NaN` when no sample had a point.
     */
    fun meanErrorInH(
        run: DisplayedRun,
        person: ScenePerson,
        fromS: Double,
        toS: Double,
    ): Double {
        val points = pointsOf(run, person)
        var sum = 0.0
        var count = 0
        var k = 0
        var t = fromS
        while (t < toS) {
            k = maxOf(0, frameOnScreen(run, t, k))
            val point = points[k]
            val truth = person.pathAt(t)
            if (point != null && truth != null && run.frames[k].timeS + DISPLAY_LATENCY_S <= t) {
                sum += hypot(point.xImg - truth.headXImg, point.yImg - truth.headYImg) / truth.heightImg
                count++
            }
            t += SAMPLE_STEP_S
        }
        return if (count == 0) Double.NaN else sum / count
    }

    /** What an abrupt stop looks like on screen: the largest overshoot and how long the point stays past the stop. */
    class StopResult(
        val overshootInH: Double,
        val secondsPastAfterStop: Double,
    )

    /**
     * For a person who walks in a straight line and stands still from [stopS] on: along the direction of travel,
     * the largest distance (in heights, 0 when never) by which what is on screen is past the stop point after
     * [stopS], and the time after [stopS] until it was last more than [pastLimitH] heights past it (0 when never).
     */
    fun stopOf(
        run: DisplayedRun,
        person: ScenePerson,
        stopS: Double,
        pastLimitH: Double,
    ): StopResult {
        val stop = checkNotNull(person.pathAt(stopS)) { "no person at the stop time" }
        val before = checkNotNull(person.pathAt(stopS - DIRECTION_BACK_S)) { "no person before the stop" }
        val length = hypot(stop.headXImg - before.headXImg, stop.headYImg - before.headYImg)
        require(length > 0.0) { "the person does not move before the stop" }
        val dirX = (stop.headXImg - before.headXImg) / length
        val dirY = (stop.headYImg - before.headYImg) / length
        val points = pointsOf(run, person)
        var best = 0.0
        var lastPast = stopS
        var k = 0
        var t = stopS
        val end = run.frames.last().timeS + DISPLAY_LATENCY_S
        while (t < end) {
            k = maxOf(0, frameOnScreen(run, t, k))
            val point = points[k]
            if (point != null && run.frames[k].timeS + DISPLAY_LATENCY_S <= t) {
                val along = ((point.xImg - stop.headXImg) * dirX + (point.yImg - stop.headYImg) * dirY) / stop.heightImg
                if (along > best) best = along
                if (along > pastLimitH) lastPast = t
            }
            t += SAMPLE_STEP_S
        }
        return StopResult(best, lastPast - stopS)
    }

    private const val DIRECTION_BACK_S = 0.1
}
