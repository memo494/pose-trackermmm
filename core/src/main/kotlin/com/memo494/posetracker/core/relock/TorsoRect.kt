package com.memo494.posetracker.core.relock

/**
 * A pixel rectangle of the torso of one person, in the Img space of a frame, written by [TorsoRegion.of] and read by
 * [AppearanceSignature]; one reusable, caller-owned object, so nothing is allocated per frame. [rightImg] and
 * [bottomImg] are exclusive edges (the rectangle covers `leftImg until rightImg` and `topImg until bottomImg`).
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

    /** True when the rectangle holds no pixel. */
    val isEmpty: Boolean
        get() = rightImg <= leftImg || bottomImg <= topImg

    /** Sets the four edges (Img pixels). */
    fun set(
        leftImg: Int,
        topImg: Int,
        rightImg: Int,
        bottomImg: Int,
    ) {
        this.leftImg = leftImg
        this.topImg = topImg
        this.rightImg = rightImg
        this.bottomImg = bottomImg
    }

    /** Makes the rectangle empty. */
    fun clear() {
        set(0, 0, 0, 0)
    }
}
