package com.memo494.posetracker.core.overlay

import kotlin.math.max
import kotlin.math.min

/**
 * Where a small text label sits beside a person's box (the track id of the debug marks): above the box's
 * top-left corner, moved just enough to stay inside the view. Pure arithmetic in View pixels, so the draw
 * phase allocates nothing. A value that is not finite gives `NaN` (fail closed: the caller draws nothing
 * instead of a guessed position).
 */
object LabelPlacement {
    /**
     * Left edge of a label [labelWidth] wide that should start at [anchorX]: [anchorX] itself, moved left when
     * the label would end beyond [viewWidth], and never below 0 (a label wider than the view starts at 0).
     */
    fun leftX(
        anchorX: Float,
        labelWidth: Float,
        viewWidth: Float,
    ): Float {
        if (!anchorX.isFinite() || !labelWidth.isFinite() || !viewWidth.isFinite()) return Float.NaN
        return max(0f, min(anchorX, viewWidth - labelWidth))
    }

    /**
     * Text baseline of a label whose lowest point (its descent) sits [gap] above [anchorY], moved down just
     * enough that its top (baseline minus [ascent]) is not above 0, then up if its bottom (baseline plus
     * [descent]) would be below [viewHeight].
     *
     * @param anchorY top edge of the box, View pixels.
     * @param ascent distance from the baseline up to the top of the text, View pixels, positive.
     * @param descent distance from the baseline down to the bottom of the text, View pixels, positive.
     * @param gap free space between the bottom of the text and [anchorY], View pixels.
     */
    fun baselineY(
        anchorY: Float,
        ascent: Float,
        descent: Float,
        gap: Float,
        viewHeight: Float,
    ): Float {
        if (!anchorY.isFinite() || !ascent.isFinite() || !descent.isFinite() || !gap.isFinite() || !viewHeight.isFinite()) {
            return Float.NaN
        }
        return min(max(anchorY - gap - descent, ascent), viewHeight - descent)
    }
}
