package com.memo494.posetracker.core.geometry

import kotlin.math.min

/**
 * Img -> View mapping (ARCHITECTURE §4.2): a uniform scale plus an offset, `View = Img * scale + offset`.
 * No rotation, no mirror; invertible (View -> Img is used for taps). Plain numbers, never an Android
 * `Matrix`. Velocities are transformed with [scale] only (no offset).
 *
 * @property scale uniform Img -> View scale (unitless, view pixels per image pixel).
 * @property offsetXView x of Img origin (0) in View pixels.
 * @property offsetYView y of Img origin (0) in View pixels.
 */
class FitCenter private constructor(
    val scale: Float,
    val offsetXView: Float,
    val offsetYView: Float,
) {
    /** Maps an x coordinate from Img to View pixels. */
    fun imgToViewX(xImg: Float): Float = xImg * scale + offsetXView

    /** Maps a y coordinate from Img to View pixels. */
    fun imgToViewY(yImg: Float): Float = yImg * scale + offsetYView

    /** Maps an x coordinate from View back to Img pixels (inverse of [imgToViewX]). */
    fun viewToImgX(xView: Float): Float = (xView - offsetXView) / scale

    /** Maps a y coordinate from View back to Img pixels (inverse of [imgToViewY]). */
    fun viewToImgY(yView: Float): Float = (yView - offsetYView) / scale

    companion object {
        /**
         * The capture content rect inside a frame buffer of [bufferWidthImg] x [bufferHeightImg] when the
         * display is [displayWidthPx] x [displayHeightPx]: the display aspect, scaled uniformly to fit the
         * buffer and centred. Returns `null` (fail closed) when any size is not positive.
         */
        fun contentRectImg(
            displayWidthPx: Int,
            displayHeightPx: Int,
            bufferWidthImg: Int,
            bufferHeightImg: Int,
        ): ContentRectImg? {
            if (displayWidthPx <= 0 || displayHeightPx <= 0 || bufferWidthImg <= 0 || bufferHeightImg <= 0) {
                return null
            }
            val fit =
                min(
                    bufferWidthImg.toFloat() / displayWidthPx,
                    bufferHeightImg.toFloat() / displayHeightPx,
                )
            val widthImg = displayWidthPx * fit
            val heightImg = displayHeightPx * fit
            return ContentRectImg(
                leftImg = (bufferWidthImg - widthImg) / 2f,
                topImg = (bufferHeightImg - heightImg) / 2f,
                widthImg = widthImg,
                heightImg = heightImg,
            )
        }

        /**
         * Maps [contentRectImg] onto a view of [viewWidthView] x [viewHeightView]: uniform fit-centre
         * scale, the offset places the content in the middle of the view. Returns `null` when a size is
         * not positive.
         */
        fun fitInto(
            contentRectImg: ContentRectImg,
            viewWidthView: Int,
            viewHeightView: Int,
        ): FitCenter? {
            if (contentRectImg.widthImg <= 0f || contentRectImg.heightImg <= 0f) return null
            if (viewWidthView <= 0 || viewHeightView <= 0) return null
            val scale =
                min(
                    viewWidthView / contentRectImg.widthImg,
                    viewHeightView / contentRectImg.heightImg,
                )
            val offsetXView = (viewWidthView - contentRectImg.widthImg * scale) / 2f - contentRectImg.leftImg * scale
            val offsetYView = (viewHeightView - contentRectImg.heightImg * scale) / 2f - contentRectImg.topImg * scale
            return FitCenter(scale, offsetXView, offsetYView)
        }

        /**
         * Maps [contentRectImg] (the whole display) onto the screen, then shifts into a view whose top-left
         * is at ([viewLeftOnScreenPx], [viewTopOnScreenPx]) on screen (`getLocationOnScreen`). Use this when
         * the overlay window does not start at (0, 0), e.g. below a display cutout; the offset then absorbs
         * the window origin. Returns `null` when [displayWidthPx] is not positive.
         */
        fun forDisplay(
            contentRectImg: ContentRectImg,
            displayWidthPx: Int,
            viewLeftOnScreenPx: Int,
            viewTopOnScreenPx: Int,
        ): FitCenter? {
            if (contentRectImg.widthImg <= 0f || displayWidthPx <= 0) return null
            val scale = displayWidthPx / contentRectImg.widthImg
            val offsetXView = -viewLeftOnScreenPx - contentRectImg.leftImg * scale
            val offsetYView = -viewTopOnScreenPx - contentRectImg.topImg * scale
            return FitCenter(scale, offsetXView, offsetYView)
        }
    }
}
