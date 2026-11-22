package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/**
 * How an imported pose model lays out its one output tensor (M_final F2). Decided from the output shape alone.
 */
enum class PoseOutputLayout {
    /** `[1, N, 57]`: per row 4 box values (x1, y1, x2, y2), score, class, 17 x (x, y, conf); suppression is in the model (`nms=False`). */
    END_TO_END,

    /** `[1, 56, N]`: channel rows cx, cy, w, h, person score, then 17 x (x, y, conf); NOT suppressed (Ultralytics `nms=None`). */
    RAW_CHANNELS_FIRST,

    /** `[1, N, 56]`: like [RAW_CHANNELS_FIRST] with one row of 56 values per candidate. */
    RAW_CHANNELS_LAST,

    /** `[1, 4 + C, N]` of a detect-only model (no keypoints): cx, cy, w, h, then C class scores (already sigmoid, no objectness); not suppressed. */
    DETECT_RAW_CHANNELS_FIRST,

    /** `[1, N, 4 + C]` with `N` above the end-to-end row limit: like [DETECT_RAW_CHANNELS_FIRST] with one row per candidate. */
    DETECT_RAW_CHANNELS_LAST,

    /** `[1, N, 6]` with `0 < N <= 300` of a detect-only model: x1, y1, x2, y2, score, class; suppression is in the model. */
    DETECT_END_TO_END,
    ;

    /** `true` for the two raw POSE layouts, which need [com.memo494.posetracker.core.decode.RawPoseDecoder]. */
    val isRaw: Boolean
        get() = this == RAW_CHANNELS_FIRST || this == RAW_CHANNELS_LAST

    /** `true` for the three detect-only layouts (no keypoints), which need [com.memo494.posetracker.core.decode.RawDetectDecoder]. */
    val isDetectOnly: Boolean
        get() = this == DETECT_RAW_CHANNELS_FIRST || this == DETECT_RAW_CHANNELS_LAST || this == DETECT_END_TO_END

    companion object {
        private const val RANK = 3
        private const val BATCH = 1

        /**
         * The raw layout of an output [shape] `[1, 56, N]` or `[1, N, 56]` with `N > 56` (so that the channel axis is
         * unambiguous), else `null`. Batch must be 1. The one rule the import check and the vendored decoder share.
         */
        fun rawOf(shape: List<Int>): PoseOutputLayout? = if (shape.size == RANK) rawOf(shape[0], shape[1], shape[2]) else null

        /** Same as [rawOf] for a `List`, for an `IntArray` shape. */
        fun rawOf(shape: IntArray): PoseOutputLayout? = if (shape.size == RANK) rawOf(shape[0], shape[1], shape[2]) else null

        /**
         * The detect-only layout of an output [shape], else `null` (batch must be 1):
         * - `[1, 4 + C, N]` with `1 <= C <= 80` and `4 + C < N`: [DETECT_RAW_CHANNELS_FIRST];
         * - `[1, N, 4 + C]` with the same `C` and `N` above [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS]: [DETECT_RAW_CHANNELS_LAST];
         * - `[1, N, 6]` with `0 < N <=` [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS]: [DETECT_END_TO_END].
         * Channel counts of a pose head (`5 + 3k`, which includes 56, and 57) are never a detect head, and a shape that
         * fits two layouts (`[1, 5, 6]`) is `null` (fail closed). The one rule the import check and the vendored decoder share.
         */
        fun detectOf(shape: List<Int>): PoseOutputLayout? = if (shape.size == RANK) detectOf(shape[0], shape[1], shape[2]) else null

        /** Same as [detectOf] for an `IntArray` shape. */
        fun detectOf(shape: IntArray): PoseOutputLayout? = if (shape.size == RANK) detectOf(shape[0], shape[1], shape[2]) else null

        /** Class score columns `C` of a raw detect head of [shape], 0 for an end-to-end head or no detect head. */
        fun detectClassesOf(shape: List<Int>): Int = classesOf(detectOf(shape), shape.getOrElse(1) { 0 }, shape.getOrElse(2) { 0 })

        /** Same as [detectClassesOf] for an `IntArray` shape. */
        fun detectClassesOf(shape: IntArray): Int = classesOf(detectOf(shape), shape.getOrElse(1) { 0 }, shape.getOrElse(2) { 0 })

        private fun classesOf(
            layout: PoseOutputLayout?,
            axis1: Int,
            axis2: Int,
        ): Int =
            when (layout) {
                DETECT_RAW_CHANNELS_FIRST -> axis1 - TrackerConfig.POSE_BOX_VALUES
                DETECT_RAW_CHANNELS_LAST -> axis2 - TrackerConfig.POSE_BOX_VALUES
                else -> 0
            }

        private fun detectOf(
            batch: Int,
            axis1: Int,
            axis2: Int,
        ): PoseOutputLayout? {
            if (batch != BATCH) return null
            val first = isDetectChannelCount(axis1) && axis2 > axis1
            val last = isDetectChannelCount(axis2) && axis1 > TrackerConfig.IMPORT_MAX_OUTPUT_ROWS
            val endToEnd = axis2 == TrackerConfig.DETECT_END_TO_END_COLUMNS && axis1 in 1..TrackerConfig.IMPORT_MAX_OUTPUT_ROWS
            return when {
                first && !last && !endToEnd -> DETECT_RAW_CHANNELS_FIRST
                last && !first && !endToEnd -> DETECT_RAW_CHANNELS_LAST
                endToEnd && !first && !last -> DETECT_END_TO_END
                else -> null
            }
        }

        /** `4 + C` for `C` in 1..80, minus the counts that are pose heads: `5 + 3k` (k >= 1, includes 56) and 57. */
        private fun isDetectChannelCount(channels: Int): Boolean {
            val minChannels = TrackerConfig.POSE_BOX_VALUES + TrackerConfig.DETECT_MIN_CLASSES
            val maxChannels = TrackerConfig.POSE_BOX_VALUES + TrackerConfig.DETECT_MAX_CLASSES
            if (channels !in minChannels..maxChannels || channels == TrackerConfig.POSE_OUTPUT_COLUMNS) return false
            val beyondBoxAndScore = channels - TrackerConfig.POSE_BOX_VALUES - TrackerConfig.POSE_SCORE_VALUES
            return beyondBoxAndScore % TrackerConfig.POSE_VALUES_PER_KEYPOINT != 0 || beyondBoxAndScore == 0
        }

        private fun rawOf(
            batch: Int,
            axis1: Int,
            axis2: Int,
        ): PoseOutputLayout? {
            val channels = TrackerConfig.POSE_RAW_CHANNELS
            return when {
                batch != BATCH -> null
                axis1 == channels && axis2 > channels -> RAW_CHANNELS_FIRST
                axis2 == channels && axis1 > channels -> RAW_CHANNELS_LAST
                else -> null
            }
        }
    }
}
