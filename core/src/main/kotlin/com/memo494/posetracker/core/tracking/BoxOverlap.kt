package com.memo494.posetracker.core.tracking

/** Intersection over union of two boxes, shared by the association cost and the dedupe. Allocation-free. */
internal object BoxOverlap {
    /**
     * IoU of the boxes `(left, top, right, bottom)` in Img pixels, unitless in [0, 1]. A box with zero, negative
     * or non-finite area has IoU 0 with everything (fail closed: it is never a duplicate or a match by overlap).
     */
    fun iou(
        leftA: Float,
        topA: Float,
        rightA: Float,
        bottomA: Float,
        leftB: Float,
        topB: Float,
        rightB: Float,
        bottomB: Float,
    ): Float {
        val areaA = (rightA - leftA) * (bottomA - topA)
        val areaB = (rightB - leftB) * (bottomB - topB)
        if (!(areaA > 0f && areaB > 0f)) return 0f
        val width = minOf(rightA, rightB) - maxOf(leftA, leftB)
        val height = minOf(bottomA, bottomB) - maxOf(topA, topB)
        if (!(width > 0f && height > 0f)) return 0f
        val intersection = width * height
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
