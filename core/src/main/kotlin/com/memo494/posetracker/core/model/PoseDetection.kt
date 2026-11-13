package com.memo494.posetracker.core.model

import com.memo494.posetracker.core.TrackerConfig

/**
 * One detected person, in image space (`Img`, ARCHITECTURE §4.2): pixels of the capture content rect,
 * no rotation, no mirror.
 *
 * A plain class on purpose (arrays inside, so no structural equality); instances are produced by the
 * detector and are not mutated afterwards.
 *
 * @property boxLeftImg left edge of the person box, Img pixels.
 * @property boxTopImg top edge of the person box, Img pixels.
 * @property boxRightImg right edge of the person box, Img pixels.
 * @property boxBottomImg bottom edge of the person box, Img pixels.
 * @property score person confidence, unitless in [0, 1].
 * @property keypointsXyImg keypoint coordinates as interleaved `x0, y0, x1, y1, ...`, Img pixels.
 * @property keypointScores per-keypoint confidence, unitless in [0, 1]; one entry per keypoint.
 */
class PoseDetection(
    val boxLeftImg: Float,
    val boxTopImg: Float,
    val boxRightImg: Float,
    val boxBottomImg: Float,
    val score: Float,
    val keypointsXyImg: FloatArray,
    val keypointScores: FloatArray,
) {
    init {
        require(keypointsXyImg.size == keypointScores.size * XY_PER_KEYPOINT) {
            "keypointsXyImg has ${keypointsXyImg.size} floats for ${keypointScores.size} keypoint scores"
        }
    }

    /** Number of keypoints. */
    val keypointCount: Int
        get() = keypointScores.size

    /** X of keypoint [index], Img pixels. */
    fun keypointXImg(index: Int): Float = keypointsXyImg[index * XY_PER_KEYPOINT]

    /** Y of keypoint [index], Img pixels. */
    fun keypointYImg(index: Int): Float = keypointsXyImg[index * XY_PER_KEYPOINT + 1]

    /**
     * X of the head point, Img pixels (the M0b head-ring centre): the mean x of the head keypoints scoring at
     * least [minScore], or the box centre x when none does. Allocation-free.
     */
    fun headXImg(minScore: Float): Float {
        val headCount = minOf(keypointCount, TrackerConfig.HEAD_KEYPOINT_COUNT)
        var sum = 0f
        var used = 0
        for (k in 0 until headCount) {
            if (keypointScores[k] >= minScore) {
                sum += keypointXImg(k)
                used++
            }
        }
        return if (used > 0) sum / used else (boxLeftImg + boxRightImg) / 2f
    }

    /** Y of the head point, Img pixels: like [headXImg], falling back to the box top. Allocation-free. */
    fun headYImg(minScore: Float): Float {
        val headCount = minOf(keypointCount, TrackerConfig.HEAD_KEYPOINT_COUNT)
        var sum = 0f
        var used = 0
        for (k in 0 until headCount) {
            if (keypointScores[k] >= minScore) {
                sum += keypointYImg(k)
                used++
            }
        }
        return if (used > 0) sum / used else boxTopImg
    }

    private companion object {
        /** Floats per keypoint in [keypointsXyImg] (x and y). */
        const val XY_PER_KEYPOINT = 2
    }
}
