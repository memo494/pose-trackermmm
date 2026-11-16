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
 * [HeadPoint.estimated] set. `h` is the box height; when it is
 * not positive the result is invalid ([HeadPoint.valid] is `false`; fail closed, no division by zero).
 */
object HeadMeasurement {
    /**
     * Computes the head point of [detection] into [out] (allocation-free: the caller owns [out] and every
     * call overwrites all of it). The variance is `(headMeasurementNoiseRatio * h)^2 / max(mean score of the
     * used keypoints, headMinMeanConfidence)`, or `(headFallbackNoiseRatio * h)^2` for the fallback.
     */
    fun of(
        detection: PoseDetection,
        config: TrackerConfig,
        out: HeadPoint,
    ) {
        val heightImg = boxHeightImg(detection)
        if (!isUsableBox(detection, heightImg)) {
            out.setInvalid()
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
