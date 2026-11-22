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
    ;

    /** `true` for the two raw layouts, which need [com.memo494.posetracker.core.decode.RawPoseDecoder]. */
    val isRaw: Boolean
        get() = this != END_TO_END

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
