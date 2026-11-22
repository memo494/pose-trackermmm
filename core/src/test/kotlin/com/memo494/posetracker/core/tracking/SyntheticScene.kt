package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToLong

/** Half-open time interval `[fromS, toS)` in seconds of scene time (the first frame is at 0). */
data class TimeInterval(
    val fromS: Double,
    val toS: Double,
) {
    init {
        require(toS >= fromS) { "interval ends ($toS) before it starts ($fromS)" }
    }

    /** True when [timeS] lies inside the interval. */
    operator fun contains(timeS: Double): Boolean = timeS >= fromS && timeS < toS
}

/**
 * One waypoint of a head path: time in seconds, head centre and box height in Img pixels. Between two
 * waypoints everything is linear.
 */
data class PathPoint(
    val timeS: Double,
    val headXImg: Double,
    val headYImg: Double,
    val heightImg: Double,
)

/** While [interval] lasts, the head keypoints in [keypoints] get a low confidence; their positions stay. */
data class KeypointDropout(
    val interval: TimeInterval,
    val keypoints: Set<Int>,
)

/** An extra detection of nobody: a box at the given head position and height with [score], head keypoints at low confidence. */
data class FalsePositive(
    val interval: TimeInterval,
    val headXImg: Double,
    val headYImg: Double,
    val heightImg: Double,
    val score: Double,
)

/**
 * One person of a [SyntheticScene].
 *
 * @property id person id (the truth uses it; not the tracker's track id).
 * @property path waypoints with strictly increasing times; outside them the person does not exist.
 * @property score person score as a function of scene time (constant [DEFAULT_SCORE] by default).
 * @property dropouts intervals in which the person exists but the detector emits nothing.
 * @property keypointDropouts intervals in which chosen head keypoints get a low confidence.
 * @property duplicates intervals in which the same person is emitted twice (second box slightly shifted, lower score).
 * @property nearDuplicates intervals in which the detector also emits a looser box of the same person (IoU 0.54 to
 *   0.67 with the original, below the dedupe IoU; score 0.25 to 0.45), its shift and score cycling with the frame.
 */
class ScenePerson(
    val id: Int,
    val path: List<PathPoint>,
    val score: (Double) -> Double = { DEFAULT_SCORE },
    val dropouts: List<TimeInterval> = emptyList(),
    val keypointDropouts: List<KeypointDropout> = emptyList(),
    val duplicates: List<TimeInterval> = emptyList(),
    val nearDuplicates: List<TimeInterval> = emptyList(),
) {
    init {
        require(path.isNotEmpty()) { "a person needs at least one waypoint" }
        for (i in 1 until path.size) {
            require(path[i].timeS > path[i - 1].timeS) { "waypoint times must strictly increase" }
        }
    }

    /** The interpolated waypoint at [timeS], or null when the person does not exist then. */
    fun pathAt(timeS: Double): PathPoint? {
        if (timeS < path.first().timeS || timeS > path.last().timeS) return null
        if (path.size == 1) return path[0]
        var upper = 1
        while (upper < path.size - 1 && path[upper].timeS < timeS) upper++
        val a = path[upper - 1]
        val b = path[upper]
        val f = (timeS - a.timeS) / (b.timeS - a.timeS)
        return PathPoint(
            timeS,
            a.headXImg + f * (b.headXImg - a.headXImg),
            a.headYImg + f * (b.headYImg - a.headYImg),
            a.heightImg + f * (b.heightImg - a.heightImg),
        )
    }

    companion object {
        /** Score of a person without a score function. */
        const val DEFAULT_SCORE = 0.9

        /** A path that stays at one place from [fromS] to [toS]. */
        fun stillPath(
            headXImg: Double,
            headYImg: Double,
            heightImg: Double,
            fromS: Double,
            toS: Double,
        ): List<PathPoint> =
            listOf(
                PathPoint(fromS, headXImg, headYImg, heightImg),
                PathPoint(toS, headXImg, headYImg, heightImg),
            )
    }
}

/**
 * The truth of one existing person in one frame. [headXImg], [headYImg], [heightImg] are the true head centre
 * and box height; they exist also while the detector drops the person. [measuredHeadXImg] and
 * [measuredHeadYImg] are the mean of the five emitted head keypoints (noise included), `NaN` when nothing was
 * emitted for the person (dropout).
 */
data class TruthPerson(
    val personId: Int,
    val headXImg: Double,
    val headYImg: Double,
    val heightImg: Double,
    val measuredHeadXImg: Double = Double.NaN,
    val measuredHeadYImg: Double = Double.NaN,
)

/** One emitted frame with its truth. [index] counts emitted frames from 0; [timeS] is the scene time. */
class SceneFrame(
    val index: Int,
    val timeS: Double,
    val frame: DetectionFrame,
    val truth: List<TruthPerson>,
) {
    /** The detections of [frame]. */
    val detections: List<PoseDetection>
        get() = frame.detections
}

/**
 * Deterministic synthetic detections for tracker tests. The only randomness is one `java.util.Random(seed)`,
 * so the same arguments give the same frames bit for bit.
 *
 * Frame times start at scene time 0 and advance by `1 / fps` seconds times `1 + U(-j, +j)` (`j` =
 * [frameJitterFraction]); frames whose time falls into an [idleGaps] interval are not emitted (time still
 * advances). Frames are emitted while the time is below [durationS]. Timestamps are nanoseconds, strictly
 * increasing, [TimestampSource.IMAGE].
 *
 * Detection layout from the true head centre `(hx, hy)` and height `h`: box left/right `hx -/+ 0.2h`, top
 * `hy - 0.1h`, bottom `top + h`; head keypoints (COCO order nose, left eye, right eye, left ear, right ear)
 * at fixed offsets that average to exactly the head centre; the other twelve keypoints inside the box with
 * confidence [KEYPOINT_CONFIDENCE]. Noise: independent Gaussian per keypoint coordinate
 * (`keypointNoiseRatio * h`) and per box edge (`boxNoiseRatio * h`). Per frame the detections are, in order:
 * for each person (list order) the detection and its duplicate, then the false positives.
 */
class SyntheticScene(
    val people: List<ScenePerson>,
    val durationS: Double,
    val fps: Double = DEFAULT_FPS,
    val seed: Long = 1L,
    val frameJitterFraction: Double = 0.0,
    val idleGaps: List<TimeInterval> = emptyList(),
    val falsePositives: List<FalsePositive> = emptyList(),
    val keypointNoiseRatio: Double = 0.0,
    val boxNoiseRatio: Double = 0.0,
    val widthImg: Int = DEFAULT_WIDTH_IMG,
    val heightImg: Int = DEFAULT_HEIGHT_IMG,
    val startNs: Long = DEFAULT_START_NS,
) {
    init {
        require(fps > 0.0) { "fps must be positive" }
        require(frameJitterFraction >= 0.0 && frameJitterFraction < MAX_JITTER_FRACTION) { "jitter fraction out of range" }
    }

    private val random = Random(seed)

    /** Every emitted frame, in time order. */
    val frames: List<SceneFrame> = build()

    private fun build(): List<SceneFrame> {
        val result = ArrayList<SceneFrame>()
        val durationNs = toNs(durationS)
        val nominalNs = NS_PER_S / fps
        var tNs = 0L
        while (tNs < durationNs) {
            if (idleGaps.none { tNs >= toNs(it.fromS) && tNs < toNs(it.toS) }) {
                result.add(buildFrame(result.size, tNs))
            }
            val factor = if (frameJitterFraction > 0.0) 1.0 + (random.nextDouble() * 2.0 - 1.0) * frameJitterFraction else 1.0
            tNs += max(1L, (nominalNs * factor).roundToLong())
        }
        return result
    }

    private fun buildFrame(
        index: Int,
        tNs: Long,
    ): SceneFrame {
        val timeS = tNs / NS_PER_S
        val detections = ArrayList<PoseDetection>()
        val truth = ArrayList<TruthPerson>()
        for (person in people) {
            val at = person.pathAt(timeS) ?: continue
            if (person.dropouts.any { timeS in it }) {
                truth.add(TruthPerson(person.id, at.headXImg, at.headYImg, at.heightImg))
                continue
            }
            val low = HashSet<Int>()
            for (dropout in person.keypointDropouts) {
                if (timeS in dropout.interval) low.addAll(dropout.keypoints)
            }
            val score = person.score(timeS).coerceIn(0.0, 1.0)
            val detection = makeDetection(at.headXImg, at.headYImg, at.heightImg, score, low)
            detections.add(detection)
            if (person.duplicates.any { timeS in it }) {
                detections.add(shifted(detection, at.heightImg))
            }
            if (person.nearDuplicates.any { timeS in it }) {
                detections.add(nearShifted(detection, at.heightImg, index))
            }
            var sumX = 0.0
            var sumY = 0.0
            for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
                sumX += detection.keypointXImg(k)
                sumY += detection.keypointYImg(k)
            }
            val measuredX = sumX / TrackerConfig.HEAD_KEYPOINT_COUNT
            val measuredY = sumY / TrackerConfig.HEAD_KEYPOINT_COUNT
            truth.add(TruthPerson(person.id, at.headXImg, at.headYImg, at.heightImg, measuredX, measuredY))
        }
        for (fp in falsePositives) {
            if (timeS !in fp.interval) continue
            val allHead = (0 until TrackerConfig.HEAD_KEYPOINT_COUNT).toSet()
            detections.add(makeDetection(fp.headXImg, fp.headYImg, fp.heightImg, fp.score.coerceIn(0.0, 1.0), allHead))
        }
        val detectionFrame = DetectionFrame(startNs + tNs, TimestampSource.IMAGE, widthImg, heightImg, detections)
        return SceneFrame(index, timeS, detectionFrame, truth)
    }

    private fun makeDetection(
        headX: Double,
        headY: Double,
        h: Double,
        score: Double,
        lowConfidence: Set<Int>,
    ): PoseDetection {
        val xy = FloatArray(KEYPOINT_COUNT * 2)
        val conf = FloatArray(KEYPOINT_COUNT)
        val keypointSigma = keypointNoiseRatio * h
        for (k in 0 until KEYPOINT_COUNT) {
            val x = headX + OFFSETS_X[k] * h + gaussian(keypointSigma)
            val y = headY + OFFSETS_Y[k] * h + gaussian(keypointSigma)
            xy[2 * k] = x.toFloat()
            xy[2 * k + 1] = y.toFloat()
            conf[k] = if (k in lowConfidence) LOW_CONFIDENCE else KEYPOINT_CONFIDENCE
        }
        val boxSigma = boxNoiseRatio * h
        val top = headY - BOX_TOP_OFFSET * h
        val left = headX - BOX_HALF_WIDTH * h + gaussian(boxSigma)
        val topNoisy = top + gaussian(boxSigma)
        val right = headX + BOX_HALF_WIDTH * h + gaussian(boxSigma)
        val bottom = top + h + gaussian(boxSigma)
        return PoseDetection(left.toFloat(), topNoisy.toFloat(), right.toFloat(), bottom.toFloat(), score.toFloat(), xy, conf)
    }

    private fun shifted(
        source: PoseDetection,
        h: Double,
    ): PoseDetection {
        val shift = (DUPLICATE_OFFSET_RATIO * h).toFloat()
        val xy = FloatArray(source.keypointsXyImg.size)
        for (i in xy.indices) xy[i] = source.keypointsXyImg[i] + shift
        return PoseDetection(
            source.boxLeftImg + shift,
            source.boxTopImg + shift,
            source.boxRightImg + shift,
            source.boxBottomImg + shift,
            max(0f, source.score - DUPLICATE_SCORE_DROP),
            xy,
            source.keypointScores.copyOf(),
        )
    }

    private fun nearShifted(
        source: PoseDetection,
        h: Double,
        frameIndex: Int,
    ): PoseDetection {
        val step = frameIndex % NEAR_DUPLICATE_SHIFT_RATIOS.size
        val shift = (NEAR_DUPLICATE_SHIFT_RATIOS[step] * h).toFloat()
        val xy = FloatArray(source.keypointsXyImg.size)
        for (i in xy.indices) xy[i] = source.keypointsXyImg[i] + if (i % 2 == 0) shift else 0f
        return PoseDetection(
            source.boxLeftImg + shift,
            source.boxTopImg,
            source.boxRightImg + shift,
            source.boxBottomImg,
            NEAR_DUPLICATE_SCORES[step],
            xy,
            source.keypointScores.copyOf(),
        )
    }

    private fun gaussian(sigma: Double): Double = if (sigma > 0.0) random.nextGaussian() * sigma else 0.0

    private fun toNs(seconds: Double): Long = (seconds * NS_PER_S).roundToLong()

    companion object {
        /** Default frame rate, frames per second. */
        const val DEFAULT_FPS = 20.0

        /** Default Img width, pixels (the owner's capture). */
        const val DEFAULT_WIDTH_IMG = 1356

        /** Default Img height, pixels. */
        const val DEFAULT_HEIGHT_IMG = 610

        /** Timestamp of scene time 0, nanoseconds. */
        const val DEFAULT_START_NS = 5_000_000_000L

        /** Keypoints per detection (COCO). */
        const val KEYPOINT_COUNT = 17

        /** Confidence of a normal keypoint. */
        const val KEYPOINT_CONFIDENCE = 0.9f

        /** Confidence of a dropped keypoint and of a false positive's head keypoints. */
        const val LOW_CONFIDENCE = 0.1f

        /** Box half width in units of the height. */
        const val BOX_HALF_WIDTH = 0.2

        /** Distance of the head centre below the box top in units of the height. */
        const val BOX_TOP_OFFSET = 0.1

        /** Shift of a duplicate box in x and y, units of the height. */
        const val DUPLICATE_OFFSET_RATIO = 0.03

        /** Score a duplicate loses against its original. */
        const val DUPLICATE_SCORE_DROP = 0.05f

        /** Horizontal shifts of a near duplicate in units of the height: box IoU (0.4 - r) / (0.4 + r) = 0.67, 0.54, 0.60. */
        private val NEAR_DUPLICATE_SHIFT_RATIOS = doubleArrayOf(0.08, 0.12, 0.10)

        /** Scores of a near duplicate, one per shift. */
        private val NEAR_DUPLICATE_SCORES = floatArrayOf(0.30f, 0.40f, 0.45f)

        private const val NS_PER_S = 1e9
        private const val MAX_JITTER_FRACTION = 0.95

        /** Keypoint x offsets of the head centre in units of h (left side negative); the five head ones sum to 0. */
        private val OFFSETS_X =
            doubleArrayOf(0.0, -0.03, 0.03, -0.07, 0.07, -0.15, 0.15, -0.18, 0.18, -0.17, 0.17, -0.1, 0.1, -0.1, 0.1, -0.1, 0.1)

        /** Keypoint y offsets of the head centre in units of h; the five head ones sum to 0. */
        private val OFFSETS_Y =
            doubleArrayOf(0.02, -0.01, -0.01, 0.0, 0.0, 0.2, 0.2, 0.4, 0.4, 0.55, 0.55, 0.5, 0.5, 0.7, 0.7, 0.88, 0.88)
    }
}
