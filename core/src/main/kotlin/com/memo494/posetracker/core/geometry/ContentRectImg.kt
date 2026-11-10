package com.memo494.posetracker.core.geometry

/**
 * The capture content rect: where the display's pixels sit inside the captured frame buffer, in image
 * space (`Img`, ARCHITECTURE §4.2). Equals the whole buffer when the buffer has the display's aspect;
 * otherwise the system fit-centres the content and leaves bars (Android 12L+).
 *
 * @property leftImg left edge, Img pixels (buffer coordinates).
 * @property topImg top edge, Img pixels.
 * @property widthImg width, Img pixels.
 * @property heightImg height, Img pixels.
 */
data class ContentRectImg(
    val leftImg: Float,
    val topImg: Float,
    val widthImg: Float,
    val heightImg: Float,
) {
    /** Right edge, Img pixels. */
    val rightImg: Float
        get() = leftImg + widthImg

    /** Bottom edge, Img pixels. */
    val bottomImg: Float
        get() = topImg + heightImg
}
