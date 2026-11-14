package com.memo494.posetracker.core.overlay

/**
 * Size rule of the corner brackets that replace the full box in the default marks: at each box corner two
 * short strokes along the box edges. A full box lowers the model's score of a small person on the next
 * frame; short corners keep it (M0c, "Measured after T1").
 */
object CornerBrackets {
    /**
     * Length of one bracket stroke along a box side of length [side]: `side * ratio`, never negative
     * (a zero, negative or NaN [side] gives 0). For `ratio < 0.5` the two strokes on one side never meet.
     * The caller passes View pixels; the result has the same unit.
     *
     * @param side length of the box side the stroke runs along, in View pixels.
     * @param ratio `boxCornerRatio`, unitless fraction of [side].
     */
    fun length(
        side: Float,
        ratio: Float,
    ): Float {
        val length = side * ratio
        return if (length.isNaN() || length < 0f) 0f else length
    }
}
