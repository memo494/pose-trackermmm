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
 * 2. Full view (all five visible): the offsets are first learned against the plain (unweighted) mean of the
 *    five keypoints, `target = (keypoint - plainMean) / h`; the first observation sets an offset, later ones
 *    use `offset += alpha * (target - offset)`. The sum of the five targets is zero, so the reference cannot
 *    drift whatever the confidences are, and a track that started with a partial view converges to the plain
 *    mean. Only then is the centre computed (step 4).
 * 3. Partial view (one to four visible): the centre is computed first (step 4); then an offset is added for
 *    each visible keypoint that has none, `(keypoint - centre) / h`. Existing offsets are never changed.
 * 4. The centre is the confidence-weighted mean of `keypoint - offset * h` over the visible keypoints that
 *    already have an offset; visible keypoints without one are used raw (offset 0) only while no keypoint
 *    has an offset yet (the first frames of a track).
 * 5. When no keypoint can anchor the centre, the point is the box fallback ([HeadPoint.estimated]); a
 *    partial view learns nothing then, and the learned offsets are kept.
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
        val learnedInFullView = learnInFullView(detection, heightImg)
        if (!anchorCentre(detection, heightImg, out)) {
            setFallbackPoint(detection, heightImg, config, out)
        } else if (!learnedInFullView) {
            addMissingOffsets(detection, heightImg, out.headXImg, out.headYImg)
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
     * Full view only: learns the offsets of all five keypoints against their plain mean. Returns false (and
     * learns nothing) when fewer than five keypoints are visible or the mean is not finite.
     */
    private fun learnInFullView(
        detection: PoseDetection,
        heightImg: Float,
    ): Boolean {
        for (k in 0 until HEAD_COUNT) {
            if (!visible[k]) {
                return false
            }
        }
        var sumXImg = 0f
        var sumYImg = 0f
        for (k in 0 until HEAD_COUNT) {
            sumXImg += detection.keypointXImg(k)
            sumYImg += detection.keypointYImg(k)
        }
        val meanXImg = sumXImg / HEAD_COUNT
        val meanYImg = sumYImg / HEAD_COUNT
        if (!meanXImg.isFinite() || !meanYImg.isFinite()) {
            return false
        }
        val alpha = config.headOffsetAlpha
        for (k in 0 until HEAD_COUNT) {
            val targetX = (detection.keypointXImg(k) - meanXImg) / heightImg
            val targetY = (detection.keypointYImg(k) - meanYImg) / heightImg
            if (learned[k]) {
                offsetXRatios[k] += alpha * (targetX - offsetXRatios[k])
                offsetYRatios[k] += alpha * (targetY - offsetYRatios[k])
            } else {
                setNewOffset(k, targetX, targetY)
            }
        }
        return true
    }

    /**
     * Computes the centre from the visible keypoints and writes the point into [out]; returns false (writing
     * nothing) when no keypoint can anchor the centre. Does not learn.
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
        out.setPoint(
            xImg = centreXImg,
            yImg = centreYImg,
            varianceImg2 = keypointVarianceImg2(heightImg, sumWeight / used, config),
            estimated = false,
        )
        return true
    }

    /** Partial view: gives each visible keypoint that has no offset one, relative to the centre; others stay. */
    private fun addMissingOffsets(
        detection: PoseDetection,
        heightImg: Float,
        centreXImg: Float,
        centreYImg: Float,
    ) {
        for (k in 0 until HEAD_COUNT) {
            if (visible[k] && !learned[k]) {
                val offsetX = (detection.keypointXImg(k) - centreXImg) / heightImg
                val offsetY = (detection.keypointYImg(k) - centreYImg) / heightImg
                setNewOffset(k, offsetX, offsetY)
            }
        }
    }

    private fun setNewOffset(
        k: Int,
        offsetXRatio: Float,
        offsetYRatio: Float,
    ) {
        offsetXRatios[k] = offsetXRatio
        offsetYRatios[k] = offsetYRatio
        learned[k] = true
        learnedOffsetCount++
    }

    private companion object {
        /** Number of head keypoints (0-4). */
        const val HEAD_COUNT = TrackerConfig.HEAD_KEYPOINT_COUNT
    }
}
