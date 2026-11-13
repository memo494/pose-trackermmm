package com.memo494.posetracker.core.capture

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Size of the captured frame buffer (`ImageReader` / `VirtualDisplay`), derived from the display size
 * and the capture scale.
 *
 * @property widthImg buffer width, Img pixels; even.
 * @property heightImg buffer height, Img pixels; even.
 */
data class CaptureSize(
    val widthImg: Int,
    val heightImg: Int,
) {
    companion object {
        private const val EVEN = TrackerConfig.EVEN_DIMENSION_MULTIPLE

        /**
         * Capture size for a display of [displayWidthPx] x [displayHeightPx] at [scale]
         * ([TrackerConfig.captureScale]).
         *
         * Rounding: the ideal size is `display * scale`. The display's aspect is reduced to lowest terms
         * `a : b`; the only even sizes with exactly that aspect are `2m*a x 2m*b`, so the nearest such
         * size is used if both axes stay within [TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX] of the
         * ideal (1220x2712 at 0.5 gives exactly 610x1356). Otherwise each axis is rounded to the nearest
         * even number (ties up), so the aspect is off by at most about one pixel. Never smaller than 2.
         *
         * Returns `null` (fail closed) for a non-positive display size or a non-finite / non-positive scale.
         */
        fun of(
            displayWidthPx: Int,
            displayHeightPx: Int,
            scale: Float,
        ): CaptureSize? {
            if (displayWidthPx <= 0 || displayHeightPx <= 0 || !scale.isFinite() || scale <= 0f) return null
            val idealWidth = displayWidthPx * scale
            val idealHeight = displayHeightPx * scale
            val exact = exactAspect(displayWidthPx, displayHeightPx, idealWidth)
            if (exact != null) {
                val widthOk = abs(exact.widthImg - idealWidth) <= TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX
                val heightOk = abs(exact.heightImg - idealHeight) <= TrackerConfig.CAPTURE_EXACT_ASPECT_TOLERANCE_PX
                if (widthOk && heightOk) return exact
            }
            return CaptureSize(nearestEven(idealWidth), nearestEven(idealHeight))
        }

        private fun exactAspect(
            displayWidthPx: Int,
            displayHeightPx: Int,
            idealWidth: Float,
        ): CaptureSize? {
            val divisor = gcd(displayWidthPx, displayHeightPx)
            val aspectWidth = displayWidthPx / divisor
            val aspectHeight = displayHeightPx / divisor
            val step = EVEN * aspectWidth
            val multiples = (idealWidth / step).roundToInt()
            if (multiples < 1) return null
            val widthImg = multiples.toLong() * step
            val heightImg = multiples.toLong() * EVEN * aspectHeight
            if (heightImg > Int.MAX_VALUE) return null
            return CaptureSize(widthImg.toInt(), heightImg.toInt())
        }

        private fun nearestEven(ideal: Float): Int = maxOf(EVEN, (ideal / EVEN).roundToInt() * EVEN)

        private fun gcd(
            a: Int,
            b: Int,
        ): Int {
            var x = a
            var y = b
            while (y != 0) {
                val rest = x % y
                x = y
                y = rest
            }
            return x
        }
    }
}
