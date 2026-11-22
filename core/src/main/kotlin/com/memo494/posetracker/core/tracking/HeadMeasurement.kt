package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection

/**
 * Stateless head point of one detection, used for association before the detection belongs to a track
 * (ARCHITECTURE §6.2).
 *
 * The point is the plain mean of the head keypoints 0-4 whose score is at least
 * [TrackerConfig.headKeypointEnterScore] (a keypoint with a non-finite position or score is not used). When
 * none qualifies it is the box fallback `(box centre x, box top + headFallbackTopRatio * h)` with
 * [HeadPoint.estimated] set. `h` is the box height; when it is below [TrackerConfig.minBoxHeightImg] (or not
 * finite) the result is invalid ([HeadPoint.valid] is `false`; fail closed, no division by zero).
 *
 * For a box-only (detect-only) model the session passes `boxOnly = true`: the keypoints are never read (the decoder
 * leaves them at (0, 0) with confidence 0) and the point is the box rule of every frame, not [HeadPoint.estimated]
 * (see `setBoxOnlyPoint`). The model's kind is decided by the loaded model, never guessed from the keypoint scores.
 */
object HeadMeasurement {
    /**
     * Computes the head point of [detection] into [out] (allocation-free: the caller owns [out] and every
     * call overwrites all of it). The variance is `(headMeasurementNoiseRatio * h)^2 / max(mean score of the
     * used keypoints, headMinMeanConfidence)`, or `(headFallbackNoiseRatio * h)^2` for the fallback. With [boxOnly]
     * the point is the box rule and the variance `(headBoxOnlyNoiseRatio * h)^2 / max(score, headMinMeanConfidence)`.
     */
    fun of(
        detection: PoseDetection,
        config: TrackerConfig,
        out: HeadPoint,
        boxOnly: Boolean = false,
    ) {
        val heightImg = boxHeightImg(detection)
        if (!isUsableBox(detection, heightImg, config)) {
            out.setInvalid()
            return
        }
        if (boxOnly) {
            setBoxOnlyPoint(detection, heightImg, config, out)
            return
        }
        val headCount = minOf(detection.keypointCount, TrackerConfig.HEAD_KEYPOINT_COUNT)
        var sumXImg = 0f
        var sumYImg = 0f
        var sumScore = 0f
        var used = 0
        for (k in 0 until headCount) {
            if (isFiniteKeypoint(detection, k) && detection.keypointScores[k] >= config.headKeypointEnterScore) {
                sumXImg += detection.keypointXImg(k)
                sumYImg += detection.keypointYImg(k)
                sumScore += detection.keypointScores[k]
                used++
            }
        }
        if (used > 0) {
            out.setPoint(
                xImg = sumXImg / used,
                yImg = sumYImg / used,
                varianceImg2 = keypointVarianceImg2(heightImg, sumScore / used, config),
                estimated = false,
            )
        } else {
            setFallbackPoint(detection, heightImg, config, out)
        }
    }
}
