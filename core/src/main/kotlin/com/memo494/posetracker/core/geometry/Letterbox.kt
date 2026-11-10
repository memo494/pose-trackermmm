package com.memo494.posetracker.core.geometry

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Letterbox mapping between image space (`Img`, the capture content rect) and the square-ish model
 * input (`Mdl`), ARCHITECTURE §4.2. Uses the same gain and pad rounding as Ultralytics `LetterBox`
 * (`ImageUtils.letterboxTransform`), so Img <-> Mdl agrees with the vendored decoder.
 *
 * Uniform gain plus a leading pad: `Mdl = Img * gain + pad`. Immutable; no per-call allocation.
 *
 * @property gain uniform Img -> Mdl scale (unitless, model pixels per image pixel).
 * @property padLeftMdl left pad of the letterboxed content inside the model input, Mdl pixels.
 * @property padTopMdl top pad of the letterboxed content inside the model input, Mdl pixels.
 * @property resizedWidthMdl width of the content inside the model input, Mdl pixels.
 * @property resizedHeightMdl height of the content inside the model input, Mdl pixels.
 */
class Letterbox private constructor(
    val gain: Float,
    val padLeftMdl: Float,
    val padTopMdl: Float,
    val resizedWidthMdl: Int,
    val resizedHeightMdl: Int,
) {
    /** Maps an x coordinate from Img to Mdl pixels. */
    fun imgToMdlX(xImg: Float): Float = xImg * gain + padLeftMdl

    /** Maps a y coordinate from Img to Mdl pixels. */
    fun imgToMdlY(yImg: Float): Float = yImg * gain + padTopMdl

    /** Maps an x coordinate from Mdl back to Img pixels (inverse of [imgToMdlX]). */
    fun mdlToImgX(xMdl: Float): Float = (xMdl - padLeftMdl) / gain

    /** Maps a y coordinate from Mdl back to Img pixels (inverse of [imgToMdlY]). */
    fun mdlToImgY(yMdl: Float): Float = (yMdl - padTopMdl) / gain

    companion object {
        /**
         * Builds the mapping from an image of [sourceWidthImg] x [sourceHeightImg] to a model input of
         * [targetWidthMdl] x [targetHeightMdl]. Returns `null` (fail closed) when any size is not positive.
         *
         * Rounding matches Ultralytics: `resized = round(source * gain)`,
         * `pad = round((target - resized) / 2 - bias)` (bias from [TrackerConfig.LETTERBOX_PAD_BIAS_MDL]).
         */
        fun create(
            sourceWidthImg: Int,
            sourceHeightImg: Int,
            targetWidthMdl: Int,
            targetHeightMdl: Int,
        ): Letterbox? {
            if (sourceWidthImg <= 0 || sourceHeightImg <= 0 || targetWidthMdl <= 0 || targetHeightMdl <= 0) {
                return null
            }
            val scaleX = targetWidthMdl.toFloat() / sourceWidthImg
            val scaleY = targetHeightMdl.toFloat() / sourceHeightImg
            val gain = min(scaleX, scaleY)
            val resizedWidthMdl = (sourceWidthImg * gain).roundToInt()
            val resizedHeightMdl = (sourceHeightImg * gain).roundToInt()
            val padWidthMdl = targetWidthMdl - resizedWidthMdl
            val padHeightMdl = targetHeightMdl - resizedHeightMdl
            val padLeftMdl = (padWidthMdl / 2f - TrackerConfig.LETTERBOX_PAD_BIAS_MDL).roundToInt().toFloat()
            val padTopMdl = (padHeightMdl / 2f - TrackerConfig.LETTERBOX_PAD_BIAS_MDL).roundToInt().toFloat()
            return Letterbox(gain, padLeftMdl, padTopMdl, resizedWidthMdl, resizedHeightMdl)
        }
    }
}
