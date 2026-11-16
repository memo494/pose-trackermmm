package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig

/** What the scenarios read of one track after a tracker call: id, state, head point and height (Img pixels). */
data class RecordedTrack(
    val id: Int,
    val state: TrackState,
    val isShown: Boolean,
    val headXImg: Float,
    val headYImg: Float,
    val heightImg: Float,
)

/** One `onIdle` call: the argument and the track lists right before and right after it. */
class IdleStep(
    val idleNs: Long,
    val before: List<RecordedTrack>,
    val after: List<RecordedTrack>,
)

/**
 * The result of feeding frames to a [Tracker]: per fed frame the final track list (after any `onIdle` calls that
 * followed it), the highest id ever returned and every `onIdle` step.
 */
class ScenarioRun(
    val sceneFrames: List<SceneFrame>,
    val tracks: List<List<RecordedTrack>>,
    val highestId: Int,
    val idleSteps: List<IdleStep>,
) {
    /** One [MetricsFrame] per fed frame; its tracks are the shown entries of that frame's list. */
    val metricsFrames: List<MetricsFrame> =
        sceneFrames.indices.map { i ->
            val shown = tracks[i].filter { it.isShown }.map { ShownTrack(it.id, it.headXImg.toDouble(), it.headYImg.toDouble()) }
            sceneFrames[i].toMetricsFrame(shown)
        }

    /** The metrics over all frames. */
    val metrics: TrackingMetrics by lazy { TrackingMetrics(metricsFrames) }

    /** Number of shown tracks in the list of frame [frameIndex]. */
    fun shownCount(frameIndex: Int): Int = tracks[frameIndex].count { it.isShown }

    /** Index of the first frame with a shown track, or -1 when there is none. */
    fun firstShownFrame(): Int = tracks.indices.firstOrNull { shownCount(it) > 0 } ?: -1

    /** Largest list size (shown or not) over all frames, 0 without frames. */
    fun maxListSize(): Int = tracks.maxOfOrNull { it.size } ?: 0

    /** Largest number of shown tracks over all frames, 0 without frames. */
    fun maxShownCount(): Int = tracks.indices.maxOfOrNull { shownCount(it) } ?: 0
}

/**
 * Feeds frames and `onIdle` calls to one [Tracker] and records the lists it returns. An `onIdle` call replaces
 * the recorded list of the frame it follows (the frame's final list).
 */
class ScenarioRunner(
    config: TrackerConfig = TrackerConfig(),
) {
    private val tracker = Tracker(config)
    private val fed = ArrayList<SceneFrame>()
    private val lists = ArrayList<List<RecordedTrack>>()
    private val steps = ArrayList<IdleStep>()
    private var highestId = 0

    /** Feeds [frame] and returns the list the tracker answered with. */
    fun feed(frame: SceneFrame): List<RecordedTrack> {
        val snapshot = snapshot(tracker.update(frame.frame))
        fed.add(frame)
        lists.add(snapshot)
        return snapshot
    }

    /** Calls `onIdle(idleNs)` after the newest frame and returns the list the tracker answered with. */
    fun idle(idleNs: Long): List<RecordedTrack> {
        check(lists.isNotEmpty()) { "onIdle before any frame" }
        val before = lists[lists.size - 1]
        val after = snapshot(tracker.onIdle(idleNs))
        lists[lists.size - 1] = after
        steps.add(IdleStep(idleNs, before, after))
        return after
    }

    /** Everything recorded so far. */
    fun finish(): ScenarioRun = ScenarioRun(ArrayList(fed), ArrayList(lists), highestId, ArrayList(steps))

    private fun snapshot(list: TrackList): List<RecordedTrack> {
        val result = ArrayList<RecordedTrack>(list.size)
        for (i in 0 until list.size) {
            val id = list.id(i)
            if (id > highestId) highestId = id
            result.add(
                RecordedTrack(id, list.state(i), list.isShown(i), list.headXImg(i), list.headYImg(i), list.heightImg(i)),
            )
        }
        return result
    }
}

/**
 * Feeds every frame of [scene] to a new [Tracker]; after frame `i` it calls `onIdle` with each value of
 * `idleNsAfterFrame[i]`, in order.
 */
fun runScene(
    scene: SyntheticScene,
    config: TrackerConfig = TrackerConfig(),
    idleNsAfterFrame: Map<Int, LongArray> = emptyMap(),
): ScenarioRun {
    val runner = ScenarioRunner(config)
    for (frame in scene.frames) {
        runner.feed(frame)
        idleNsAfterFrame[frame.index]?.forEach { runner.idle(it) }
    }
    return runner.finish()
}
