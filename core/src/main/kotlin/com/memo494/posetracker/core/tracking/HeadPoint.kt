package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.PoseDetection
import kotlin.math.max

/**
 * The head point of one detection, in image space (`Img`, ARCHITECTURE §4.2), as one frame's measurement for
 * the tracker.
 *
 * Allocation-free pattern (AGENTS rule 8): the caller owns one instance and passes it as `out` to
 * [HeadMeasurement.of] or [HeadPointEstimator.update]; every call overwrites all five fields, so the object
 * can be reused for the next detection without any allocation and without stale values.
 *
 * Read [valid] first: when it is `false` the box was unusable (height below `TrackerConfig.minBoxHeightImg` or not
 * finite, or a box edge not finite), the position and variance are `NaN`, and the caller must not use the detection for a head point.
 */
class HeadPoint {
    /** X of the head point, Img pixels; `NaN` when [valid] is `false`. */
    var headXImg: Float = Float.NaN
        private set

    /** Y of the head point, Img pixels; `NaN` when [valid] is `false`. */
    var headYImg: Float = Float.NaN
        private set

    /** Variance of the head point per axis, in Img pixels squared; `NaN` when [valid] is `false`. */
    var varianceImg2: Float = Float.NaN
        private set

    /**
     * `true` when no head keypoint anchored the point and it comes from the box (the fallback): less
     * trustworthy and with a larger variance. Also `true` when [valid] is `false`.
     */
    var estimated: Boolean = true
        private set

    /** `false` when the detection's box cannot give a head point (height too low, a non-finite edge). */
    var valid: Boolean = false
        private set

    internal fun setPoint(
        xImg: Float,
        yImg: Float,
        varianceImg2: Float,
        estimated: Boolean,
    ) {
        headXImg = xImg
        headYImg = yImg
        this.varianceImg2 = varianceImg2
        this.estimated = estimated
        valid = true
    }

    internal fun setInvalid() {
        headXImg = Float.NaN
        headYImg = Float.NaN
        varianceImg2 = Float.NaN
        estimated = true
        valid = false
    }
}

/** Height of the detection's box, Img pixels (`boxBottomImg - boxTopImg`); not checked here. */
internal fun boxHeightImg(detection: PoseDetection): Float = detection.boxBottomImg - detection.boxTopImg

/**
 * True when the box has a finite height [heightImg] of at least [TrackerConfig.minBoxHeightImg] and finite left,
 * right and top edges. A sub-pixel box is refused because head offsets are learned in units of the box height.
 */
internal fun isUsableBox(
    detection: PoseDetection,
    heightImg: Float,
    config: TrackerConfig,
): Boolean {
    val edgesFinite = detection.boxLeftImg.isFinite() && detection.boxRightImg.isFinite() && detection.boxTopImg.isFinite()
    return heightImg >= config.minBoxHeightImg && heightImg.isFinite() && edgesFinite
}

/** True when keypoint [index] of [detection] has a finite position and a finite score. */
internal fun isFiniteKeypoint(
    detection: PoseDetection,
    index: Int,
): Boolean {
    val positionFinite = detection.keypointXImg(index).isFinite() && detection.keypointYImg(index).isFinite()
    return positionFinite && detection.keypointScores[index].isFinite()
}

/** Variance of a head point measured from keypoints with mean confidence [meanConfidence], Img pixels squared. */
internal fun keypointVarianceImg2(
    heightImg: Float,
    meanConfidence: Float,
    config: TrackerConfig,
): Float {
    val sigmaImg = config.headMeasurementNoiseRatio * heightImg
    return sigmaImg * sigmaImg / max(meanConfidence, config.headMinMeanConfidence)
}

/** Writes the box fallback head point of [detection] (height [heightImg], already checked) into [out]. */
internal fun setFallbackPoint(
    detection: PoseDetection,
    heightImg: Float,
    config: TrackerConfig,
    out: HeadPoint,
) {
    val sigmaImg = config.headFallbackNoiseRatio * heightImg
    out.setPoint(
        xImg = (detection.boxLeftImg + detection.boxRightImg) / 2f,
        yImg = detection.boxTopImg + config.headFallbackTopRatio * heightImg,
        varianceImg2 = sigmaImg * sigmaImg,
        estimated = true,
    )
}
