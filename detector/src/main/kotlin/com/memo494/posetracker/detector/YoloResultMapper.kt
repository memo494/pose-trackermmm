package com.memo494.posetracker.detector

import com.memo494.posetracker.core.model.PoseDetection
import com.ultralytics.yolo.Box
import com.ultralytics.yolo.Keypoints
import com.ultralytics.yolo.YOLOResult

/** Turns the vendored decoder's [YOLOResult] into our [PoseDetection]s. */
internal object YoloResultMapper {
    /** Floats per keypoint in the interleaved coordinate array (x and y). */
    private const val XY_PER_KEYPOINT = 2

    /**
     * Maps every box with the keypoints at the same index, keeping the decoder's order.
     *
     * Upstream fills `Box.xywh` with left, top, right and bottom in image pixels despite its name, and
     * `Keypoints.xy` with image pixels, so the values are copied as they are (Img space, since the
     * adapter passes the Img size as the original size).
     *
     * @throws IllegalStateException when the box and keypoint lists differ in size, or a keypoint list has a
     *   different number of coordinates and scores (fail closed, never a guessed pairing).
     */
    fun toDetections(result: YOLOResult): List<PoseDetection> {
        val boxes = result.boxes
        val keypointsList = result.keypointsList
        check(boxes.size == keypointsList.size) {
            "decoder returned ${boxes.size} boxes but ${keypointsList.size} keypoint lists"
        }
        val detections = ArrayList<PoseDetection>(boxes.size)
        for (i in boxes.indices) {
            detections.add(toDetection(boxes[i], keypointsList[i]))
        }
        return detections
    }

    private fun toDetection(
        box: Box,
        keypoints: Keypoints,
    ): PoseDetection {
        val count = keypoints.xy.size
        check(count == keypoints.conf.size) {
            "keypoint list has $count coordinates but ${keypoints.conf.size} scores"
        }
        val xyImg = FloatArray(count * XY_PER_KEYPOINT)
        val scores = FloatArray(count)
        for (k in 0 until count) {
            xyImg[k * XY_PER_KEYPOINT] = keypoints.xy[k].first
            xyImg[k * XY_PER_KEYPOINT + 1] = keypoints.xy[k].second
            scores[k] = keypoints.conf[k]
        }
        return PoseDetection(
            boxLeftImg = box.xywh.left,
            boxTopImg = box.xywh.top,
            boxRightImg = box.xywh.right,
            boxBottomImg = box.xywh.bottom,
            score = box.conf,
            keypointsXyImg = xyImg,
            keypointScores = scores,
        )
    }
}
