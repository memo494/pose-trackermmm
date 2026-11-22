package com.memo494.posetracker.core.modelimport

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
}
