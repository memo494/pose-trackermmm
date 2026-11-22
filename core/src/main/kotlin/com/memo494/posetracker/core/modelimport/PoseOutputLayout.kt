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

    /** `[1, N, 4 + C]` with `N` the anchor count of the input: like [DETECT_RAW_CHANNELS_FIRST] with one row per candidate. */
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
         * Rows `N` of a raw detect head for a model input of [inputWidth] x [inputHeight]: the cells of the three scales
         * (strides [TrackerConfig.DETECT_STRIDE_FINE], [TrackerConfig.DETECT_STRIDE_MEDIUM], [TrackerConfig.DETECT_STRIDE_COARSE]),
         * 2100 for 320 x 320, 8400 for 640 x 640; 0 when a side is not a positive multiple of the coarsest stride.
         */
        fun detectAnchorCount(
            inputWidth: Int,
            inputHeight: Int,
        ): Int {
            if (inputWidth <= 0 || inputHeight <= 0) return 0
            if (inputWidth % TrackerConfig.DETECT_STRIDE_COARSE != 0 || inputHeight % TrackerConfig.DETECT_STRIDE_COARSE != 0) return 0
            return cells(inputWidth, inputHeight, TrackerConfig.DETECT_STRIDE_FINE) +
                cells(inputWidth, inputHeight, TrackerConfig.DETECT_STRIDE_MEDIUM) +
                cells(inputWidth, inputHeight, TrackerConfig.DETECT_STRIDE_COARSE)
        }

        private fun cells(
            width: Int,
            height: Int,
            stride: Int,
        ): Int = (width / stride) * (height / stride)

        /**
         * The detect-only layout of an output [shape] of a model with input [inputWidth] x [inputHeight], else `null`
         * (batch must be 1). With `N` = [detectAnchorCount]:
         * - `[1, 4 + C, N]` with `1 <= C <= 80`: [DETECT_RAW_CHANNELS_FIRST];
         * - `[1, N, 4 + C]` with the same `C`: [DETECT_RAW_CHANNELS_LAST];
         * - `[1, M, 6]` with `0 < M <=` [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS]: [DETECT_END_TO_END].
         * A raw head must have exactly the anchor count of the input, so a YOLOv5 head with an objectness column
         * (`[1, 6300, 9]` at 320, three anchors per cell) is never read as a detect head. Channel counts of a pose head
         * (`5 + 3k`, which includes 56, and 57) are never a detect head, and a shape that fits two layouts (`[1, 5, 6]`)
         * is `null` (fail closed). The one rule the import check and the vendored decoder share.
         */
        fun detectOf(
            shape: List<Int>,
            inputWidth: Int,
            inputHeight: Int,
        ): PoseOutputLayout? = if (shape.size == RANK) detectOf(shape[0], shape[1], shape[2], inputWidth, inputHeight) else null

        /** Same as [detectOf] for an `IntArray` shape. */
        fun detectOf(
            shape: IntArray,
            inputWidth: Int,
            inputHeight: Int,
        ): PoseOutputLayout? = if (shape.size == RANK) detectOf(shape[0], shape[1], shape[2], inputWidth, inputHeight) else null

        /** Class score columns `C` of a raw detect head of [shape], 0 for an end-to-end head or no detect head. */
        fun detectClassesOf(
            shape: List<Int>,
            inputWidth: Int,
            inputHeight: Int,
        ): Int = classesOf(detectOf(shape, inputWidth, inputHeight), shape.getOrElse(1) { 0 }, shape.getOrElse(2) { 0 })

        /** Same as [detectClassesOf] for an `IntArray` shape. */
        fun detectClassesOf(
            shape: IntArray,
            inputWidth: Int,
            inputHeight: Int,
        ): Int = classesOf(detectOf(shape, inputWidth, inputHeight), shape.getOrElse(1) { 0 }, shape.getOrElse(2) { 0 })

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
            inputWidth: Int,
            inputHeight: Int,
        ): PoseOutputLayout? {
            if (batch != BATCH) return null
            val anchors = detectAnchorCount(inputWidth, inputHeight)
            val first = anchors > 0 && isDetectChannelCount(axis1) && axis2 == anchors
            val last = anchors > 0 && isDetectChannelCount(axis2) && axis1 == anchors
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
