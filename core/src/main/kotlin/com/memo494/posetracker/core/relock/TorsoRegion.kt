package com.memo494.posetracker.core.relock

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where the torso of a detected person is, for the clothing-colour signature (docs/plans/M4.md, M4c): the rectangle
 * through the shoulder and hip keypoints when all four are confident, else, only when the box looks like a whole
 * standing body (height at least `relockFullBodyMinAspect` times its width), a fixed part of it (rows
 * `relockBoxTopRatio..relockBoxBottomRatio` of its height, the middle `relockBoxWidthRatio` of its width); then cut by
 * `relockTorsoShrink` on every side and clipped to the image. When the hips are not confident and the box is not a
 * whole body (a person at a desk, cut by the frame edge, M4d) but both shoulders are, the torso is the part between
 * the shoulders, from the lower shoulder down by `relockUpperTorsoRatio` times the horizontal distance between the
 * shoulders. It never reaches the head (shoulders are below it, the box rows start under it), which is where the lock
 * ring is drawn. A torso that clipping to the image cut is marked [TorsoRect.clipped].
 *
 * Allocation-free after construction; the four working floats are fields, so one instance belongs to one thread.
 */
class TorsoRegion(
    private val config: TrackerConfig,
) {
    private var leftImg = 0f
    private var topImg = 0f
    private var rightImg = 0f
    private var bottomImg = 0f

    /**
     * Writes the torso of [detection] into [out] and returns true, or clears [out] and returns false (fail closed)
     * when no rectangle of at least one pixel is left: a box or coordinate that is not finite, a box with no size or
     * too wide for a whole body and no confident shoulders (or hips), four confident keypoints that span no
     * rectangle (the box is then not tried), or a torso entirely outside the [widthImg] x [heightImg] image. With
     * the four shoulder and hip keypoints confident only they are used; otherwise the box part (whole-body box) or the
     * shoulders-only part (other boxes) is.
     */
    fun of(
        detection: PoseDetection,
        widthImg: Int,
        heightImg: Int,
        out: TorsoRect,
    ): Boolean {
        out.clear()
        if (widthImg <= 0 || heightImg <= 0) return false
        val found =
            if (hasFourKeypoints(detection)) {
                fromKeypoints(detection)
            } else {
                fromBox(detection) || fromUpperBody(detection)
            }
        if (!found) return false
        val cutXImg = (rightImg - leftImg) * config.relockTorsoShrink
        val cutYImg = (bottomImg - topImg) * config.relockTorsoShrink
        val widthLimit = widthImg.toFloat()
        val heightLimit = heightImg.toFloat()
        val wholeLeftImg = leftImg + cutXImg
        val wholeTopImg = topImg + cutYImg
        val wholeRightImg = rightImg - cutXImg
        val wholeBottomImg = bottomImg - cutYImg
        val clipped = wholeLeftImg < 0f || wholeTopImg < 0f || wholeRightImg > widthLimit || wholeBottomImg > heightLimit
        val left = floor(wholeLeftImg.coerceIn(0f, widthLimit)).toInt()
        val top = floor(wholeTopImg.coerceIn(0f, heightLimit)).toInt()
        val right = ceil(wholeRightImg.coerceIn(0f, widthLimit)).toInt()
        val bottom = ceil(wholeBottomImg.coerceIn(0f, heightLimit)).toInt()
        out.set(left, top, right, bottom, clipped)
        if (out.isEmpty) {
            out.clear()
            return false
        }
        return true
    }

    /** True when the shoulder and hip keypoints all exist, are confident and finite. */
    private fun hasFourKeypoints(detection: PoseDetection): Boolean =
        detection.keypointCount > TrackerConfig.KEYPOINT_RIGHT_HIP &&
            usable(detection, TrackerConfig.KEYPOINT_LEFT_SHOULDER) &&
            usable(detection, TrackerConfig.KEYPOINT_RIGHT_SHOULDER) &&
            usable(detection, TrackerConfig.KEYPOINT_LEFT_HIP) &&
            usable(detection, TrackerConfig.KEYPOINT_RIGHT_HIP)

    /** The rectangle through the shoulder and hip keypoints (see [hasFourKeypoints]); false when it has no area. */
    private fun fromKeypoints(detection: PoseDetection): Boolean {
        val leftShoulder = TrackerConfig.KEYPOINT_LEFT_SHOULDER
        val rightShoulder = TrackerConfig.KEYPOINT_RIGHT_SHOULDER
        val leftHip = TrackerConfig.KEYPOINT_LEFT_HIP
        val rightHip = TrackerConfig.KEYPOINT_RIGHT_HIP
        val shoulderLeftImg = min(detection.keypointXImg(leftShoulder), detection.keypointXImg(rightShoulder))
        val hipLeftImg = min(detection.keypointXImg(leftHip), detection.keypointXImg(rightHip))
        val shoulderRightImg = max(detection.keypointXImg(leftShoulder), detection.keypointXImg(rightShoulder))
        val hipRightImg = max(detection.keypointXImg(leftHip), detection.keypointXImg(rightHip))
        leftImg = min(shoulderLeftImg, hipLeftImg)
        rightImg = max(shoulderRightImg, hipRightImg)
        topImg = min(detection.keypointYImg(leftShoulder), detection.keypointYImg(rightShoulder))
        bottomImg = max(detection.keypointYImg(leftHip), detection.keypointYImg(rightHip))
        return rightImg > leftImg && bottomImg > topImg
    }

    private fun usable(
        detection: PoseDetection,
        keypoint: Int,
    ): Boolean =
        detection.keypointScores[keypoint] >= config.relockKeypointMinScore &&
            detection.keypointXImg(keypoint).isFinite() &&
            detection.keypointYImg(keypoint).isFinite()

    /** True when the box has a finite positive size. */
    private fun boxIsUsable(detection: PoseDetection): Boolean {
        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        val boxWidthImg = detection.boxRightImg - detection.boxLeftImg
        return boxHeightImg.isFinite() && boxHeightImg > 0f && boxWidthImg.isFinite() && boxWidthImg > 0f
    }

    /**
     * From the lower shoulder down by `relockUpperTorsoRatio` times the horizontal distance between the shoulders (so
     * never above either shoulder, even when they are tilted); false when the box is not usable, is a whole
     * body's shape (the box part answers that case), or a shoulder is missing, not confident or not finite.
     */
    private fun fromUpperBody(detection: PoseDetection): Boolean {
        if (!boxIsUsable(detection) || isWholeBody(detection)) return false
        if (detection.keypointCount <= TrackerConfig.KEYPOINT_RIGHT_SHOULDER) return false
        val leftShoulder = TrackerConfig.KEYPOINT_LEFT_SHOULDER
        val rightShoulder = TrackerConfig.KEYPOINT_RIGHT_SHOULDER
        if (!(usable(detection, leftShoulder) && usable(detection, rightShoulder))) return false
        leftImg = min(detection.keypointXImg(leftShoulder), detection.keypointXImg(rightShoulder))
        rightImg = max(detection.keypointXImg(leftShoulder), detection.keypointXImg(rightShoulder))
        topImg = max(detection.keypointYImg(leftShoulder), detection.keypointYImg(rightShoulder))
        bottomImg = topImg + (rightImg - leftImg) * config.relockUpperTorsoRatio
        return rightImg > leftImg && bottomImg > topImg
    }

    private fun isWholeBody(detection: PoseDetection): Boolean =
        detection.boxBottomImg - detection.boxTopImg >=
            (detection.boxRightImg - detection.boxLeftImg) * config.relockFullBodyMinAspect

    /** The fixed part of the person box; false when the box has no finite positive size or is not a whole body's shape. */
    private fun fromBox(detection: PoseDetection): Boolean {
        if (!boxIsUsable(detection) || !isWholeBody(detection)) return false
        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        val boxWidthImg = detection.boxRightImg - detection.boxLeftImg
        val centerXImg = (detection.boxLeftImg + detection.boxRightImg) / 2f
        val halfWidthImg = boxWidthImg * config.relockBoxWidthRatio / 2f
        leftImg = centerXImg - halfWidthImg
        rightImg = centerXImg + halfWidthImg
        topImg = detection.boxTopImg + boxHeightImg * config.relockBoxTopRatio
        bottomImg = detection.boxTopImg + boxHeightImg * config.relockBoxBottomRatio
        return true
    }
}
