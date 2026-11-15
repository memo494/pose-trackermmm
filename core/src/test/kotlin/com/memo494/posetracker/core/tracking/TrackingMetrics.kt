package com.memo494.posetracker.core.tracking

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A track the tracker shows in one frame: its id and head point, Img pixels. Neutral: no tracker type involved. */
data class ShownTrack(
    val trackId: Int,
    val headXImg: Double,
    val headYImg: Double,
)

/** One frame for the metrics: time in seconds, the truth of every existing person and the tracks shown. */
class MetricsFrame(
    val timeS: Double,
    val truth: List<TruthPerson>,
    val tracks: List<ShownTrack>,
)

/** A coordinate axis of Img space. */
enum class Axis {
    X,
    Y,
}

/** The [SceneFrame]'s time and truth with the tracks a tracker showed in it. */
fun SceneFrame.toMetricsFrame(tracks: List<ShownTrack>): MetricsFrame = MetricsFrame(timeS, truth, tracks)

/**
 * Judges a tracker's shown tracks against the truth of a [SyntheticScene].
 *
 * Matching, per frame: a truth person is matched to the nearest shown track within [MATCH_RADIUS_RATIO] times
 * its height; one track serves at most one person (nearest pair first, ties by lower person id, then lower
 * track index). Undefined results are `NaN`, never a guessed number, so a threshold check on them fails.
 * Frame indices are positions in [frames].
 */
class TrackingMetrics(
    private val frames: List<MetricsFrame>,
) {
    private class Candidate(
        val distance: Double,
        val personId: Int,
        val trackIndex: Int,
    )

    /** Per frame: person id to the index of the matched track in that frame's track list. */
    private val matches: List<Map<Int, Int>> = frames.map { match(it) }

    /** Number of frames. */
    val frameCount: Int
        get() = frames.size

    /** Index of the first frame at or after [timeS], or [frameCount] when there is none. */
    fun firstFrameAtOrAfter(timeS: Double): Int {
        val index = frames.indexOfFirst { it.timeS >= timeS - TIME_EPSILON_S }
        return if (index < 0) frames.size else index
    }

    /** Id of the track matched to [personId] in [frameIndex], or null. */
    fun matchedTrackId(
        frameIndex: Int,
        personId: Int,
    ): Int? {
        return trackOf(frameIndex, personId)?.trackId
    }

    /** How often the matched track id of [personId] changes between consecutive matched frames. */
    fun idSwitches(personId: Int): Int {
        var switches = 0
        var previous: Int? = null
        for (i in frames.indices) {
            val id = trackOf(i, personId)?.trackId ?: continue
            if (previous != null && previous != id) switches++
            previous = id
        }
        return switches
    }

    /**
     * Share of the frames in `[fromFrame, toFrameExclusive)` that contain [personId] and match a track;
     * `NaN` when the person is in none of them.
     */
    fun coverage(
        personId: Int,
        fromFrame: Int = 0,
        toFrameExclusive: Int = frames.size,
    ): Double {
        var present = 0
        var matched = 0
        for (i in max(0, fromFrame) until min(toFrameExclusive, frames.size)) {
            if (truthOf(i, personId) == null) continue
            present++
            if (trackOf(i, personId) != null) matched++
        }
        return if (present == 0) Double.NaN else matched.toDouble() / present
    }

    /**
     * Number of (frame, shown track) pairs with no truth person within [MATCH_RADIUS_RATIO] times the height of
     * the nearest person; with nobody in the frame every shown track is a ghost.
     */
    fun ghostFrames(): Int {
        var ghosts = 0
        for (frame in frames) {
            for (track in frame.tracks) {
                var nearest: TruthPerson? = null
                var nearestDistance = Double.MAX_VALUE
                for (person in frame.truth) {
                    val distance = hypot(track.headXImg - person.headXImg, track.headYImg - person.headYImg)
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearest = person
                    }
                }
                if (nearest == null || nearestDistance > MATCH_RADIUS_RATIO * nearest.heightImg) ghosts++
            }
        }
        return ghosts
    }

    /** Distance between the matched track and the true head of [personId] in [frameIndex], units of h; `NaN` when unmatched. */
    fun errorInH(
        personId: Int,
        frameIndex: Int,
    ): Double {
        val truth = truthOf(frameIndex, personId) ?: return Double.NaN
        val track = trackOf(frameIndex, personId) ?: return Double.NaN
        return hypot(track.headXImg - truth.headXImg, track.headYImg - truth.headYImg) / truth.heightImg
    }

    /** Largest [errorInH] over the matched frames in `[fromFrame, toFrameExclusive)`; `NaN` when none matched. */
    fun maxErrorInH(
        personId: Int,
        fromFrame: Int = 0,
        toFrameExclusive: Int = frames.size,
    ): Double {
        var worst = Double.NaN
        for (i in max(0, fromFrame) until min(toFrameExclusive, frames.size)) {
            val error = errorInH(personId, i)
            if (!error.isNaN() && (worst.isNaN() || error > worst)) worst = error
        }
        return worst
    }

    /**
     * The coordinate of the track matched to [personId] per frame of `[fromFrame, toFrameExclusive)`; `NaN` in
     * frames without a match.
     */
    fun matchedTrackSeries(
        personId: Int,
        axis: Axis,
        fromFrame: Int = 0,
        toFrameExclusive: Int = frames.size,
    ): DoubleArray {
        val from = max(0, fromFrame)
        val to = min(toFrameExclusive, frames.size)
        val series = DoubleArray(max(0, to - from))
        for (i in from until to) {
            val track = trackOf(i, personId)
            series[i - from] = if (track == null) Double.NaN else coordinate(axis, track.headXImg, track.headYImg)
        }
        return series
    }

    /**
     * The raw measurement (mean of the five emitted head keypoints) of [personId] per frame of
     * `[fromFrame, toFrameExclusive)`; `NaN` in frames without a detection of the person.
     */
    fun rawSeries(
        personId: Int,
        axis: Axis,
        fromFrame: Int = 0,
        toFrameExclusive: Int = frames.size,
    ): DoubleArray {
        val from = max(0, fromFrame)
        val to = min(toFrameExclusive, frames.size)
        val series = DoubleArray(max(0, to - from))
        for (i in from until to) {
            val truth = truthOf(i, personId)
            series[i - from] = if (truth == null) Double.NaN else coordinate(axis, truth.measuredHeadXImg, truth.measuredHeadYImg)
        }
        return series
    }

    /**
     * The largest distance after [stopTimeS] by which the matched track of [personId] is past the true stop
     * point along the direction of travel, in units of h (0 when it never passes it). The stop point is the
     * truth at the first frame at or after [stopTimeS] (the path must stand still from then on); the direction
     * is the last non-zero truth step before it. `NaN` when undefined.
     */
    fun overshoot(
        personId: Int,
        stopTimeS: Double,
    ): Double {
        val stopIndex = firstFrameAtOrAfter(stopTimeS)
        val stop = truthOf(stopIndex, personId) ?: return Double.NaN
        var dirX = 0.0
        var dirY = 0.0
        var length = 0.0
        var before = stopIndex - 1
        while (before >= 0 && length < MIN_STEP_IMG) {
            val earlier = truthOf(before, personId) ?: return Double.NaN
            dirX = stop.headXImg - earlier.headXImg
            dirY = stop.headYImg - earlier.headYImg
            length = hypot(dirX, dirY)
            before--
        }
        if (length < MIN_STEP_IMG) return Double.NaN
        var best = 0.0
        for (i in stopIndex until frames.size) {
            val track = trackOf(i, personId) ?: continue
            val along = ((track.headXImg - stop.headXImg) * dirX + (track.headYImg - stop.headYImg) * dirY) / length
            best = max(best, along / stop.heightImg)
        }
        return best
    }

    /**
     * Mean distance by which the matched track of [personId] is behind the truth along the direction of travel
     * (positive = behind), divided by the mean distance travelled per frame, over the frames in
     * `[fromFrame, toFrameExclusive)` where the truth moves at constant velocity (same velocity over the last two
     * steps) and the person is matched. `NaN` when there is no such frame.
     */
    fun lagFrames(
        personId: Int,
        fromFrame: Int = 0,
        toFrameExclusive: Int = frames.size,
    ): Double {
        var lagSum = 0.0
        var stepSum = 0.0
        var count = 0
        for (i in max(2, fromFrame) until min(toFrameExclusive, frames.size)) {
            val older = truthOf(i - 2, personId) ?: continue
            val middle = truthOf(i - 1, personId) ?: continue
            val now = truthOf(i, personId) ?: continue
            val track = trackOf(i, personId) ?: continue
            val dtOld = frames[i - 1].timeS - frames[i - 2].timeS
            val dtNow = frames[i].timeS - frames[i - 1].timeS
            if (dtOld <= 0.0 || dtNow <= 0.0) continue
            val stepX = now.headXImg - middle.headXImg
            val stepY = now.headYImg - middle.headYImg
            val step = hypot(stepX, stepY)
            if (step < MIN_STEP_IMG) continue
            val velOldX = (middle.headXImg - older.headXImg) / dtOld
            val velOldY = (middle.headYImg - older.headYImg) / dtOld
            val velNowX = stepX / dtNow
            val velNowY = stepY / dtNow
            if (hypot(velNowX - velOldX, velNowY - velOldY) > CONSTANT_VELOCITY_TOLERANCE * hypot(velNowX, velNowY)) continue
            lagSum += ((now.headXImg - track.headXImg) * stepX + (now.headYImg - track.headYImg) * stepY) / step
            stepSum += step
            count++
        }
        return if (count == 0) Double.NaN else lagSum / stepSum
    }

    private fun truthOf(
        frameIndex: Int,
        personId: Int,
    ): TruthPerson? {
        if (frameIndex < 0 || frameIndex >= frames.size) return null
        return frames[frameIndex].truth.firstOrNull { it.personId == personId }
    }

    private fun trackOf(
        frameIndex: Int,
        personId: Int,
    ): ShownTrack? {
        if (frameIndex < 0 || frameIndex >= frames.size) return null
        val trackIndex = matches[frameIndex][personId] ?: return null
        return frames[frameIndex].tracks[trackIndex]
    }

    private fun match(frame: MetricsFrame): Map<Int, Int> {
        val candidates = ArrayList<Candidate>()
        for (person in frame.truth) {
            for (t in frame.tracks.indices) {
                val track = frame.tracks[t]
                val distance = hypot(track.headXImg - person.headXImg, track.headYImg - person.headYImg)
                if (distance <= MATCH_RADIUS_RATIO * person.heightImg) candidates.add(Candidate(distance, person.personId, t))
            }
        }
        candidates.sortWith(compareBy<Candidate>({ it.distance }, { it.personId }, { it.trackIndex }))
        val result = HashMap<Int, Int>()
        val usedTracks = HashSet<Int>()
        for (candidate in candidates) {
            if (candidate.personId in result || candidate.trackIndex in usedTracks) continue
            result[candidate.personId] = candidate.trackIndex
            usedTracks.add(candidate.trackIndex)
        }
        return result
    }

    private fun coordinate(
        axis: Axis,
        x: Double,
        y: Double,
    ): Double {
        return if (axis == Axis.X) x else y
    }

    companion object {
        /** A truth person is matched to a track within this many heights. */
        const val MATCH_RADIUS_RATIO = 0.5

        private const val TIME_EPSILON_S = 1e-9
        private const val MIN_STEP_IMG = 1e-6
        private const val CONSTANT_VELOCITY_TOLERANCE = 1e-6

        /**
         * RMS of the second difference `x[i+1] - 2 x[i] + x[i-1]` of [series], over the triples without `NaN`;
         * `NaN` when there is no such triple. Samples are taken as equally spaced.
         */
        fun jitterRms(series: DoubleArray): Double {
            var sumSquares = 0.0
            var count = 0
            for (i in 1 until series.size - 1) {
                val second = series[i + 1] - 2.0 * series[i] + series[i - 1]
                if (second.isNaN()) continue
                sumSquares += second * second
                count++
            }
            return if (count == 0) Double.NaN else sqrt(sumSquares / count)
        }
    }
}
