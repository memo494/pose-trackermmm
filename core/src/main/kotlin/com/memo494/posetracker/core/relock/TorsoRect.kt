package com.memo494.posetracker.core.relock

/**
 * A pixel rectangle of the torso of one person, in the Img space of a frame, written by [TorsoRegion.of] and read by
 * [AppearanceSignature]; one reusable, caller-owned object, so nothing is allocated per frame. [rightImg] and
 * [bottomImg] are exclusive edges (the rectangle covers `leftImg until rightImg` and `topImg until bottomImg`).
 * [clipped] says that clipping to the image removed part of the torso (the person is partly out of the picture).
 * [source] says which rule found it (a signature is only compared with samples of the same source).
 * Not thread-safe.
 */
class TorsoRect {
    /** Left edge, Img pixels, inclusive. */
    var leftImg: Int = 0
        private set

    /** Top edge, Img pixels, inclusive. */
    var topImg: Int = 0
        private set

    /** Right edge, Img pixels, exclusive. */
    var rightImg: Int = 0
        private set

    /** Bottom edge, Img pixels, exclusive. */
    var bottomImg: Int = 0
        private set

    /** True when clipping to the image removed part of the torso, so what is left is not the whole torso. */
    var clipped: Boolean = false
        private set

    /** Which rule found the rectangle, one of the `SOURCE_` constants; [SOURCE_NONE] when it was set by hand or cleared. */
    var source: Int = SOURCE_NONE
        private set

    /**
     * Which signature histogram the rectangle belongs to, [BUCKET_WHOLE] or [BUCKET_UPPER]: the shoulder-to-hip rectangle
     * and the box part are both a whole torso and are compared with each other; the upper-body part is not.
     */
    val bucket: Int
        get() = if (source == SOURCE_UPPER_BODY) BUCKET_UPPER else BUCKET_WHOLE

    /** True when the rectangle holds no pixel. */
    val isEmpty: Boolean
        get() = rightImg <= leftImg || bottomImg <= topImg

    /**
     * The share, 0..1, of this rectangle's area that the box [boxLeftImg], [boxTopImg], [boxRightImg], [boxBottomImg]
     * covers. 1 (fail closed) when this rectangle is empty or a coordinate is not finite.
     */
    fun coveredShareBy(
        boxLeftImg: Float,
        boxTopImg: Float,
        boxRightImg: Float,
        boxBottomImg: Float,
    ): Float {
        if (isEmpty || !(boxLeftImg.isFinite() && boxTopImg.isFinite() && boxRightImg.isFinite() && boxBottomImg.isFinite())) {
            return 1f
        }
        val widthImg = minOf(rightImg.toFloat(), boxRightImg) - maxOf(leftImg.toFloat(), boxLeftImg)
        val heightImg = minOf(bottomImg.toFloat(), boxBottomImg) - maxOf(topImg.toFloat(), boxTopImg)
        if (widthImg <= 0f || heightImg <= 0f) return 0f
        return minOf(1f, widthImg * heightImg / ((rightImg - leftImg).toFloat() * (bottomImg - topImg)))
    }

    /** Sets the four edges (Img pixels), [clipped] and [source]. */
    fun set(
        leftImg: Int,
        topImg: Int,
        rightImg: Int,
        bottomImg: Int,
        clipped: Boolean = false,
        source: Int = SOURCE_NONE,
    ) {
        this.source = source
        this.clipped = clipped
        this.leftImg = leftImg
        this.topImg = topImg
        this.rightImg = rightImg
        this.bottomImg = bottomImg
    }

    /** Makes the rectangle empty. */
    fun clear() {
        set(0, 0, 0, 0)
    }

    companion object {
        /** No rule (set by hand, or cleared). */
        const val SOURCE_NONE = 0

        /** The rectangle through the shoulder and hip keypoints. */
        const val SOURCE_KEYPOINTS = 1

        /** The fixed part of a whole-body person box. */
        const val SOURCE_BOX = 2

        /** The part below the shoulders (hips out of the picture). */
        const val SOURCE_UPPER_BODY = 3

        /** How many sources there are, [SOURCE_NONE] included. */
        const val SOURCE_COUNT = 4

        /** Signature bucket of a whole torso (keypoints, box, or set by hand). */
        const val BUCKET_WHOLE = 0

        /** Signature bucket of the upper body only. */
        const val BUCKET_UPPER = 1

        /** How many buckets there are. */
        const val BUCKET_COUNT = 2
    }
}
