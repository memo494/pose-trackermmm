package com.memo494.posetracker.core.overlay

/**
 * Size rule of the head ring. The overlay is part of the screen capture, so a ring that is too small is
 * not enough of a mark and one that is too large hides the person from the model (M0c, "Measured after
 * T1"): the radius follows the box width but never drops below a minimum.
 */
object HeadRing {
    /**
     * Radius of the head ring: `boxWidth * ratio`, but at least [minRadius]. A zero, negative or NaN
     * [boxWidth] gives [minRadius] (fail closed: never a guessed size). All values must be in the same unit;
     * the caller passes View pixels ([minRadius] = `minHeadRingRadiusDp` x density).
     *
     * @param boxWidth width of the person box, in View pixels.
     * @param ratio `headRingRadiusRatio`, unitless fraction of [boxWidth].
     * @param minRadius smallest radius, in View pixels.
     */
    fun radius(
        boxWidth: Float,
        ratio: Float,
        minRadius: Float,
    ): Float {
        val scaled = boxWidth * ratio
        return if (scaled.isNaN() || scaled < minRadius) minRadius else scaled
    }
}
