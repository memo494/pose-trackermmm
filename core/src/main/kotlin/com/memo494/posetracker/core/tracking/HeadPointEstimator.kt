package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection

/**
 * The head point of one track, frame by frame (ARCHITECTURE §6.2). One instance per track; [reset] makes it
 * reusable for a new track. Not thread-safe (the tracker runs on one thread).
 *
 * Why it exists: the model drops head keypoints now and then (a turned head, a hat, a far person). The mean
 * of whichever keypoints are left would jump by a few percent of the person's height each time one appears
 * or vanishes. So each head keypoint's offset from the head centre, in units of the box height `h`, is
 * learned (EMA, [TrackerConfig.headOffsetAlpha]) and a keypoint's estimate of the centre is
 * `keypoint - offset * h`; the centre is the confidence-weighted mean of those estimates.
 *
 * Per frame:
 * 1. A keypoint is visible from a score of at least [TrackerConfig.headKeypointEnterScore] and stays
 *    visible until its score is below [TrackerConfig.headKeypointLeaveScore]. A keypoint with a non-finite
 *    position or score is not visible and never touches the state.
 * 2. The centre uses the visible keypoints that already have an offset. Visible keypoints without one are
 *    used raw (offset 0) only while no keypoint has an offset yet (the first frames of a track).
 * 3. With the centre known, the offsets of all visible keypoints are updated:
 *    `offset += alpha * ((keypoint - centre) / h - offset)`; the first observation sets the offset directly.
 * 4. When no keypoint can anchor the centre, the point is the box fallback ([HeadPoint.estimated]); the
 *    learned offsets are kept.
 *
 * A frame whose box height is not positive gives an invalid result and changes nothing.
 *
 * Allocation-free after construction (AGENTS rule 8): five-element arrays are made once; the caller owns the
 * [HeadPoint] passed to [update].
 *
 * @param config source of the thresholds, alpha and noise ratios.
 */
class HeadPointEstimator(
    private val config: TrackerConfig,
) {
    private val visible = BooleanArray(HEAD_COUNT)
    private val learned = BooleanArray(HEAD_COUNT)
    private val offsetXRatios = FloatArray(HEAD_COUNT)
    private val offsetYRatios = FloatArray(HEAD_COUNT)

    /** Number of head keypoints that have a learned offset (0 to 5); kept across frames without keypoints. */
    var learnedOffsetCount: Int = 0
        private set

    /** True when head keypoint [index] (0-4) is visible after the newest valid [update]. */
    fun isKeypointVisible(index: Int): Boolean = visible[index]

    /** True when head keypoint [index] (0-4) has a learned offset. */
    fun hasOffset(index: Int): Boolean = learned[index]

    /** Learned x offset of head keypoint [index] from the head centre, in units of h (unitless); 0 when not learned. */
    fun offsetXRatio(index: Int): Float = offsetXRatios[index]

    /** Learned y offset of head keypoint [index] from the head centre, in units of h (unitless); 0 when not learned. */
    fun offsetYRatio(index: Int): Float = offsetYRatios[index]

    /** Forgets all offsets and visibility: the estimator is as new. */
    fun reset() {
        visible.fill(false)
        learned.fill(false)
        offsetXRatios.fill(0f)
        offsetYRatios.fill(0f)
        learnedOffsetCount = 0
    }

    /**
     * Folds one frame of this track's [detection] into the estimator and writes the head point into [out]
     * (the caller owns [out]; every call overwrites all of it).
     */
    fun update(
        detection: PoseDetection,
        out: HeadPoint,
    ) {
        val heightImg = boxHeightImg(detection)
        if (!isUsableBox(detection, heightImg)) {
            out.setInvalid()
            return
        }
        updateVisibility(detection)
        if (!anchorCentre(detection, heightImg, out)) {
            setFallbackPoint(detection, heightImg, config, out)
        }
    }

    private fun updateVisibility(detection: PoseDetection) {
        val keypointCount = minOf(detection.keypointCount, HEAD_COUNT)
        for (k in 0 until HEAD_COUNT) {
            val minScore = if (visible[k]) config.headKeypointLeaveScore else config.headKeypointEnterScore
            visible[k] = k < keypointCount && isFiniteKeypoint(detection, k) && detection.keypointScores[k] >= minScore
        }
    }

    /**
     * Computes the centre from the visible keypoints, learns their offsets and writes the point into [out];
     * returns false (writing nothing, learning nothing) when no keypoint can anchor the centre.
     */
    private fun anchorCentre(
        detection: PoseDetection,
        heightImg: Float,
        out: HeadPoint,
    ): Boolean {
        val rawAllowed = learnedOffsetCount == 0
        var sumWeight = 0f
        var sumXImg = 0f
        var sumYImg = 0f
        var used = 0
        for (k in 0 until HEAD_COUNT) {
            if (visible[k] && (learned[k] || rawAllowed)) {
                val weight = detection.keypointScores[k]
                sumWeight += weight
                sumXImg += weight * (detection.keypointXImg(k) - offsetXRatios[k] * heightImg)
                sumYImg += weight * (detection.keypointYImg(k) - offsetYRatios[k] * heightImg)
                used++
            }
        }
        if (used == 0 || !(sumWeight > 0f)) {
            return false
        }
        val centreXImg = sumXImg / sumWeight
        val centreYImg = sumYImg / sumWeight
        if (!centreXImg.isFinite() || !centreYImg.isFinite()) {
            return false
        }
        learnOffsets(detection, heightImg, centreXImg, centreYImg)
        out.setPoint(
            xImg = centreXImg,
            yImg = centreYImg,
            varianceImg2 = keypointVarianceImg2(heightImg, sumWeight / used, config),
            estimated = false,
        )
        return true
    }

    private fun learnOffsets(
        detection: PoseDetection,
        heightImg: Float,
        centreXImg: Float,
        centreYImg: Float,
    ) {
        val alpha = config.headOffsetAlpha
        for (k in 0 until HEAD_COUNT) {
            if (!visible[k]) {
                continue
            }
            val observedX = (detection.keypointXImg(k) - centreXImg) / heightImg
            val observedY = (detection.keypointYImg(k) - centreYImg) / heightImg
            if (learned[k]) {
                offsetXRatios[k] += alpha * (observedX - offsetXRatios[k])
                offsetYRatios[k] += alpha * (observedY - offsetYRatios[k])
            } else {
                offsetXRatios[k] = observedX
                offsetYRatios[k] = observedY
                learned[k] = true
                learnedOffsetCount++
            }
        }
    }

    private companion object {
        /** Number of head keypoints (0-4). */
        const val HEAD_COUNT = TrackerConfig.HEAD_KEYPOINT_COUNT
    }
}
