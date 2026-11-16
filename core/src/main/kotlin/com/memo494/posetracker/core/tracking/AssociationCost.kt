package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.sqrt

/**
 * Cost of pairing one track with one detection (ARCHITECTURE §6.2), a pure function of primitives:
 *
 * `assocIouWeight * (1 - IoU) + assocHeadWeight * min(1, dHead / (assocHeadScaleRatio * h))`
 *
 * with the defaults `0.5 * (1 - IoU) + 0.5 * min(1, dHead / (0.5 h))`. `IoU` is of the track box and the
 * detection box (0 when either has no area), `dHead` the distance between the track's predicted head point
 * and the detection's head point, `h` the track height. The cost is in `[0, assocIouWeight + assocHeadWeight]`.
 *
 * The pair is [Assigner.FORBIDDEN] when the squared Mahalanobis distance of the head innovation,
 * `dx^2 / varX + dy^2 / varY` (the two axes independent), is above [TrackerConfig.trackGateChi2], and, failing
 * closed, when any input is not finite or a variance or the height is not positive.
 */
object AssociationCost {
    /**
     * Cost of a track with the box `(trackLeftImg, trackTopImg, trackRightImg, trackBottomImg)` whose predicted
     * head point is (`predHeadXImg`, `predHeadYImg`) with innovation variances `innovVarXImg2` and
     * `innovVarYImg2` (Img px^2, the filter's predicted variance plus the measurement variance) and height
     * `trackHeightImg`, paired with a detection with the box `(detLeftImg, ...)` and head point
     * (`measHeadXImg`, `measHeadYImg`). All coordinates are Img pixels.
     */
    fun cost(
        trackLeftImg: Float,
        trackTopImg: Float,
        trackRightImg: Float,
        trackBottomImg: Float,
        detLeftImg: Float,
        detTopImg: Float,
        detRightImg: Float,
        detBottomImg: Float,
        predHeadXImg: Float,
        predHeadYImg: Float,
        measHeadXImg: Float,
        measHeadYImg: Float,
        innovVarXImg2: Float,
        innovVarYImg2: Float,
        trackHeightImg: Float,
        config: TrackerConfig,
    ): Float {
        val finite =
            trackLeftImg.isFinite() && trackTopImg.isFinite() && trackRightImg.isFinite() && trackBottomImg.isFinite() &&
                detLeftImg.isFinite() && detTopImg.isFinite() && detRightImg.isFinite() && detBottomImg.isFinite() &&
                predHeadXImg.isFinite() && predHeadYImg.isFinite() && measHeadXImg.isFinite() && measHeadYImg.isFinite() &&
                innovVarXImg2.isFinite() && innovVarYImg2.isFinite() && trackHeightImg.isFinite()
        if (!finite || innovVarXImg2 <= 0f || innovVarYImg2 <= 0f || trackHeightImg <= 0f) return Assigner.FORBIDDEN

        val dxImg = measHeadXImg - predHeadXImg
        val dyImg = measHeadYImg - predHeadYImg
        val mahalanobis2 = dxImg * dxImg / innovVarXImg2 + dyImg * dyImg / innovVarYImg2
        if (!(mahalanobis2 <= config.trackGateChi2)) return Assigner.FORBIDDEN

        val iou =
            BoxOverlap.iou(
                trackLeftImg,
                trackTopImg,
                trackRightImg,
                trackBottomImg,
                detLeftImg,
                detTopImg,
                detRightImg,
                detBottomImg,
            )
        val headTerm = minOf(1f, sqrt(dxImg * dxImg + dyImg * dyImg) / (config.assocHeadScaleRatio * trackHeightImg))
        return config.assocIouWeight * (1f - iou) + config.assocHeadWeight * headTerm
    }
}
