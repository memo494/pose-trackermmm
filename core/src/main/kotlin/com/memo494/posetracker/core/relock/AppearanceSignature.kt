package com.memo494.posetracker.core.relock

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A colour histogram of what a person wears (docs/plans/M4.md, M4c): `relockHueBins * relockSatBins` bins for pixels
 * that have a colour (hue by saturation) and `relockGreyBins` more (by value) for pixels that are too dark
 * (value below `relockMinValue`) or too grey (saturation below `relockMinSaturation`) to have a hue. The bins always
 * add up to 1 once the signature has data. It holds no image and no face, only these numbers.
 *
 * [set] and [blend] read the pixels of a [TorsoRect] on a grid of at most `relockMaxSamplePixels` points;
 * [blend] mixes the new histogram into the stored one as an exponential average (weight `relockSignatureAlpha`), so
 * one bad frame does not replace it. [distanceTo] is the Bhattacharyya distance between two signatures: 0 for the
 * same colours, 1 for nothing in common, and 1 (fail closed) when either one has no data.
 *
 * Allocation-free after construction (two float arrays). One instance belongs to one thread.
 *
 * @param config the bin counts, thresholds, sample limit and the averaging weight.
 */
class AppearanceSignature(
    private val config: TrackerConfig,
) {
    private val colourBins = config.relockHueBins * config.relockSatBins
    private val bins = FloatArray(colourBins + config.relockGreyBins)
    private val scratch = FloatArray(bins.size)

    /** True once a sample was taken and until [clear]. */
    var hasData: Boolean = false
        private set

    /** Pixels the newest successful [set] or [blend] read, count (0 before any). */
    var lastSampleCount: Int = 0
        private set

    /** Forgets everything. */
    fun clear() {
        bins.fill(0f)
        hasData = false
        lastSampleCount = 0
    }

    /**
     * Replaces the signature with the histogram of [rect] in [rgba] (RGBA_8888, [widthImg] x [heightImg]). Returns
     * false and changes nothing when the pixels cannot be read (wrong array size) or [rect], clipped to the image,
     * is empty.
     */
    fun set(
        rgba: ByteArray,
        widthImg: Int,
        heightImg: Int,
        rect: TorsoRect,
    ): Boolean {
        if (!histogram(rgba, widthImg, heightImg, rect)) return false
        scratch.copyInto(bins)
        hasData = true
        return true
    }

    /**
     * Mixes the histogram of [rect] into the signature (`(1 - alpha) * old + alpha * new`), or sets it when there is
     * no data yet. Returns false and changes nothing when the pixels cannot be read, like [set].
     */
    fun blend(
        rgba: ByteArray,
        widthImg: Int,
        heightImg: Int,
        rect: TorsoRect,
    ): Boolean {
        if (!hasData) return set(rgba, widthImg, heightImg, rect)
        if (!histogram(rgba, widthImg, heightImg, rect)) return false
        val alpha = config.relockSignatureAlpha
        for (i in bins.indices) bins[i] = (1f - alpha) * bins[i] + alpha * scratch[i]
        return true
    }

    /**
     * Bhattacharyya distance to [other], `sqrt(1 - sum(sqrt(p * q)))`, in 0..1; 1 when either signature has no data
     * or the two have different bin counts.
     */
    fun distanceTo(other: AppearanceSignature): Float {
        if (!hasData || !other.hasData || bins.size != other.bins.size) return MAX_DISTANCE
        var coefficient = 0f
        for (i in bins.indices) coefficient += sqrt(bins[i] * other.bins[i])
        return sqrt(max(0f, 1f - min(coefficient, 1f)))
    }

    /** Fills [scratch] with the normalised histogram of [rect]; false when nothing could be read. */
    private fun histogram(
        rgba: ByteArray,
        widthImg: Int,
        heightImg: Int,
        rect: TorsoRect,
    ): Boolean {
        if (widthImg <= 0 || heightImg <= 0) return false
        if (rgba.size.toLong() != widthImg.toLong() * heightImg * TrackerConfig.RGBA_BYTES_PER_PIXEL) return false
        val left = max(rect.leftImg, 0)
        val top = max(rect.topImg, 0)
        val right = min(rect.rightImg, widthImg)
        val bottom = min(rect.bottomImg, heightImg)
        if (right <= left || bottom <= top) return false
        val step = gridStep(right - left, bottom - top)
        scratch.fill(0f)
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            var index = (y * widthImg + x) * TrackerConfig.RGBA_BYTES_PER_PIXEL
            while (x < right) {
                val red = rgba[index].toInt() and BYTE_MASK
                val green = rgba[index + 1].toInt() and BYTE_MASK
                val blue = rgba[index + 2].toInt() and BYTE_MASK
                scratch[binOf(red, green, blue)] += 1f
                count++
                x += step
                index += step * TrackerConfig.RGBA_BYTES_PER_PIXEL
            }
            y += step
        }
        val inverse = 1f / count
        for (i in scratch.indices) scratch[i] *= inverse
        lastSampleCount = count
        return true
    }

    /** The smallest grid step with which a [widthImg] x [heightImg] rectangle gives at most `relockMaxSamplePixels` points. */
    private fun gridStep(
        widthImg: Int,
        heightImg: Int,
    ): Int {
        val limit = config.relockMaxSamplePixels.toLong()
        var step = max(1, sqrt(widthImg.toDouble() * heightImg / limit).toInt())
        while (cells(widthImg, step).toLong() * cells(heightImg, step) > limit) step++
        return step
    }

    private fun cells(
        length: Int,
        step: Int,
    ): Int = ceil(length.toDouble() / step).toInt()

    /** The bin of one pixel (components 0..255). */
    private fun binOf(
        red: Int,
        green: Int,
        blue: Int,
    ): Int {
        val maxComponent = max(red, max(green, blue))
        val minComponent = min(red, min(green, blue))
        val value = maxComponent / MAX_COMPONENT
        val delta = (maxComponent - minComponent).toFloat()
        val saturation = if (maxComponent == 0) 0f else delta / maxComponent
        if (value < config.relockMinValue || saturation < config.relockMinSaturation) {
            return colourBins + min(config.relockGreyBins - 1, (value * config.relockGreyBins).toInt())
        }
        var hue =
            when (maxComponent) {
                red -> (green - blue) / delta
                green -> (blue - red) / delta + HUE_SECTORS_TO_GREEN
                else -> (red - green) / delta + HUE_SECTORS_TO_BLUE
            }
        if (hue < 0f) hue += HUE_SECTORS
        val hueBin = min(config.relockHueBins - 1, (hue / HUE_SECTORS * config.relockHueBins).toInt())
        val saturationShare = (saturation - config.relockMinSaturation) / (1f - config.relockMinSaturation)
        val satBin = max(0, min(config.relockSatBins - 1, (saturationShare * config.relockSatBins).toInt()))
        return hueBin * config.relockSatBins + satBin
    }

    private companion object {
        /** Distance of two signatures with nothing in common (and of an unknown one). */
        const val MAX_DISTANCE = 1f

        /** Mask that turns a signed byte into 0..255. */
        const val BYTE_MASK = 0xFF

        /** Largest component value, as a float. */
        const val MAX_COMPONENT = 255f

        /** Sectors of the hue circle (red, yellow, green, cyan, blue, magenta). */
        const val HUE_SECTORS = 6f

        /** Hue offset, in sectors, where green is the largest component. */
        const val HUE_SECTORS_TO_GREEN = 2f

        /** Hue offset, in sectors, where blue is the largest component. */
        const val HUE_SECTORS_TO_BLUE = 4f
    }
}
