package com.memo494.posetracker.core.model

/**
 * All detections of one captured frame.
 *
 * @property timestampNs capture time of the frame. System.nanoTime() timebase (ARCHITECTURE §4.1).
 * @property timestampSource whether [timestampNs] is the image stamp or the arrival fallback.
 * @property widthImg width of the Img space the detections live in (capture content rect), pixels.
 * @property heightImg height of the Img space the detections live in, pixels.
 * @property detections people found, in Img space; empty when nobody was detected.
 */
data class DetectionFrame(
    val timestampNs: Long,
    val timestampSource: TimestampSource,
    val widthImg: Int,
    val heightImg: Int,
    val detections: List<PoseDetection>,
)
