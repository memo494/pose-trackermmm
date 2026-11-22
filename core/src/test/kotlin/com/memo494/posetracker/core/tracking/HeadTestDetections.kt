package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection

/**
 * Hand-built detections for the head-point tests. The five head keypoints (nose, eyes, ears) sit at fixed
 * offsets of the head centre, in units of the box height `h`; the offsets average to (0, 0), so the mean
 * of all five keypoints is the head centre. The box is `0.4 h` wide, its top is `0.1 h` above the head
 * centre and its height is `h`.
 */
internal object HeadTestDetections {
    /** Head keypoint x offsets from the head centre, in units of h (nose, left eye, right eye, left ear, right ear). */
    val OFFSET_X_IN_H = floatArrayOf(0f, -0.02f, 0.02f, -0.05f, 0.05f)

    /** Head keypoint y offsets from the head centre, in units of h. */
    val OFFSET_Y_IN_H = floatArrayOf(0.02f, -0.01f, -0.01f, 0f, 0f)

    /** Box half width, in units of h. */
    const val BOX_HALF_WIDTH_IN_H = 0.2f

    /** Distance from the box top to the head centre, in units of h. */
    const val BOX_TOP_ABOVE_HEAD_IN_H = 0.1f

    /** All 17 scores: the first five from [head] (missing ones are 0), the other twelve are 1. */
    fun scoresOf(vararg head: Float): FloatArray {
        val scores = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT) { 1f }
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) {
            scores[k] = if (k < head.size) head[k] else 0f
        }
        return scores
    }

    /** Five head scores that are all [score]. */
    fun allHead(score: Float): FloatArray = scoresOf(score, score, score, score, score)

    /**
     * A person whose head centre is ([centreXImg], [centreYImg]) and whose box is [heightImg] high. A keypoint
     * listed in [moved] is put at the given absolute position instead of at its offset of the head centre.
     */
    fun person(
        centreXImg: Float,
        centreYImg: Float,
        heightImg: Float,
        scores: FloatArray = allHead(0.9f),
        moved: Map<Int, Pair<Float, Float>> = emptyMap(),
    ): PoseDetection {
        val top = centreYImg - BOX_TOP_ABOVE_HEAD_IN_H * heightImg
        val bottom = top + heightImg
        val xy = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2)
        for (k in 0 until TrackerConfig.POSE_KEYPOINT_COUNT) {
            if (k < TrackerConfig.HEAD_KEYPOINT_COUNT) {
                xy[2 * k] = centreXImg + OFFSET_X_IN_H[k] * heightImg
                xy[2 * k + 1] = centreYImg + OFFSET_Y_IN_H[k] * heightImg
            } else {
                xy[2 * k] = centreXImg
                xy[2 * k + 1] = (top + bottom) / 2f
            }
        }
        for ((k, position) in moved) {
            xy[2 * k] = position.first
            xy[2 * k + 1] = position.second
        }
        return PoseDetection(
            boxLeftImg = centreXImg - BOX_HALF_WIDTH_IN_H * heightImg,
            boxTopImg = top,
            boxRightImg = centreXImg + BOX_HALF_WIDTH_IN_H * heightImg,
            boxBottomImg = bottom,
            score = 0.9f,
            keypointsXyImg = xy,
            keypointScores = scores,
        )
    }

    /**
     * A detection of a box-only (detect-only) model: the same box as [person] but no usable keypoints, as the
     * detect decoder writes them (17 keypoints at (0, 0) with confidence 0).
     */
    fun boxOnlyPerson(
        centreXImg: Float,
        centreYImg: Float,
        heightImg: Float,
        score: Float = 0.9f,
    ): PoseDetection {
        val top = centreYImg - BOX_TOP_ABOVE_HEAD_IN_H * heightImg
        return PoseDetection(
            boxLeftImg = centreXImg - BOX_HALF_WIDTH_IN_H * heightImg,
            boxTopImg = top,
            boxRightImg = centreXImg + BOX_HALF_WIDTH_IN_H * heightImg,
            boxBottomImg = top + heightImg,
            score = score,
            keypointsXyImg = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2),
            keypointScores = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT),
        )
    }
}
