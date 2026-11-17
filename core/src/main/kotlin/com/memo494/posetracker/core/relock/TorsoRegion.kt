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
 * `relockTorsoShrink` on every side and clipped to the image. It never reaches the head (shoulders are below it, the
 * box rows start under it), which is where the lock ring is drawn. A torso that clipping to the image cut is marked
 * [TorsoRect.clipped].
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
     * too wide for a whole body (without confident keypoints), or a torso entirely outside the [widthImg] x
     * [heightImg] image.
     */
    fun of(
        detection: PoseDetection,
        widthImg: Int,
        heightImg: Int,
        out: TorsoRect,
    ): Boolean {
        out.clear()
        if (widthImg <= 0 || heightImg <= 0) return false
        if (!fromKeypoints(detection) && !fromBox(detection)) return false
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

    /** The rectangle through the shoulder and hip keypoints; false when one is missing, not confident or not finite. */
    private fun fromKeypoints(detection: PoseDetection): Boolean {
        if (detection.keypointCount <= TrackerConfig.KEYPOINT_RIGHT_HIP) return false
        val leftShoulder = TrackerConfig.KEYPOINT_LEFT_SHOULDER
        val rightShoulder = TrackerConfig.KEYPOINT_RIGHT_SHOULDER
        val leftHip = TrackerConfig.KEYPOINT_LEFT_HIP
        val rightHip = TrackerConfig.KEYPOINT_RIGHT_HIP
        if (!(usable(detection, leftShoulder) && usable(detection, rightShoulder))) return false
        if (!(usable(detection, leftHip) && usable(detection, rightHip))) return false
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

    /** The fixed part of the person box; false when the box has no finite positive size or is not a whole body's shape. */
    private fun fromBox(detection: PoseDetection): Boolean {
        val boxHeightImg = detection.boxBottomImg - detection.boxTopImg
        val boxWidthImg = detection.boxRightImg - detection.boxLeftImg
        if (!(boxHeightImg.isFinite() && boxHeightImg > 0f && boxWidthImg.isFinite() && boxWidthImg > 0f)) return false
        if (boxHeightImg < boxWidthImg * config.relockFullBodyMinAspect) return false
        val centerXImg = (detection.boxLeftImg + detection.boxRightImg) / 2f
        val halfWidthImg = boxWidthImg * config.relockBoxWidthRatio / 2f
        leftImg = centerXImg - halfWidthImg
        rightImg = centerXImg + halfWidthImg
        topImg = detection.boxTopImg + boxHeightImg * config.relockBoxTopRatio
        bottomImg = detection.boxTopImg + boxHeightImg * config.relockBoxBottomRatio
        return true
    }
}
