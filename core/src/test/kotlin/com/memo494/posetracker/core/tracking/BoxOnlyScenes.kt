package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection

/**
 * The frames of [scene] as a box-only (detect-only) model would emit them (M-legacy T3): the same boxes and scores,
 * but all keypoints at (0, 0) with confidence 0, as the detect decoder writes them. The truth keeps the true head and
 * height; its "measured" head (the raw series of [TrackingMetrics.rawSeries]) is the box rule applied to the emitted
 * (noisy) box, `(box centre x, box top + headFallbackTopRatio * h)`, which is what a box-only tracker measures.
 *
 * Only for scenes without duplicates and false positives: the truth entries that were emitted are paired with the
 * detections in order, and the pairing is checked.
 */
fun boxOnlyFrames(
    scene: SyntheticScene,
    config: TrackerConfig = TrackerConfig(),
): List<SceneFrame> =
    scene.frames.map { sceneFrame ->
        val emitted = sceneFrame.truth.filter { !it.measuredHeadXImg.isNaN() }
        val detections = sceneFrame.detections
        check(
            emitted.size == detections.size,
        ) { "frame ${sceneFrame.index}: ${emitted.size} emitted people, ${detections.size} detections" }
        val boxOnly = detections.map { it.withoutKeypoints() }
        val truth =
            sceneFrame.truth.map { person ->
                if (person.measuredHeadXImg.isNaN()) {
                    person
                } else {
                    val box = detections[emitted.indexOf(person)]
                    val heightImg = box.boxBottomImg - box.boxTopImg
                    person.copy(
                        measuredHeadXImg = ((box.boxLeftImg + box.boxRightImg) / 2f).toDouble(),
                        measuredHeadYImg = (box.boxTopImg + config.headFallbackTopRatio * heightImg).toDouble(),
                    )
                }
            }
        val frame = sceneFrame.frame
        val boxFrame = DetectionFrame(frame.timestampNs, frame.timestampSource, frame.widthImg, frame.heightImg, boxOnly)
        SceneFrame(sceneFrame.index, sceneFrame.timeS, boxFrame, truth)
    }

/** The same box and score with 17 keypoints at (0, 0) and confidence 0. */
fun PoseDetection.withoutKeypoints(): PoseDetection =
    PoseDetection(
        boxLeftImg,
        boxTopImg,
        boxRightImg,
        boxBottomImg,
        score,
        FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2),
        FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT),
    )
