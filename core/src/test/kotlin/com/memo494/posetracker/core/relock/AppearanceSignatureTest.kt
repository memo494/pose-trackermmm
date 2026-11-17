package com.memo494.posetracker.core.relock

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/** Unit tests of [AppearanceSignature] (docs/plans/M4.md, M4c T1). */
class AppearanceSignatureTest {
    private val config = TrackerConfig()
    private val rect = TorsoRect().apply { set(0, 0, SIDE, SIDE) }

    /** A [SIDE] x [SIDE] RGBA image whose pixel (x, y) is `colourAt(x, y)` as 0xRRGGBB. */
    private fun image(colourAt: (Int, Int) -> Int): ByteArray {
        val rgba = ByteArray(SIDE * SIDE * TrackerConfig.RGBA_BYTES_PER_PIXEL)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val colour = colourAt(x, y)
                val index = (y * SIDE + x) * TrackerConfig.RGBA_BYTES_PER_PIXEL
                rgba[index] = (colour shr RED_SHIFT).toByte()
                rgba[index + 1] = (colour shr GREEN_SHIFT).toByte()
                rgba[index + 2] = colour.toByte()
                rgba[index + 3] = OPAQUE
            }
        }
        return rgba
    }

    private fun signatureOf(
        rgba: ByteArray,
        useConfig: TrackerConfig = config,
        area: TorsoRect = rect,
    ): AppearanceSignature {
        val signature = AppearanceSignature(useConfig)
        assertThat(signature.set(rgba, SIDE, SIDE, area)).isTrue()
        return signature
    }

    private fun solid(colour: Int): ByteArray = image { _, _ -> colour }

    /** Left half [left], right half [right]. */
    private fun halves(
        left: Int,
        right: Int,
    ): ByteArray = image { x, _ -> if (x < SIDE / 2) left else right }

    @Test
    fun identicalPatches_haveADistanceOfAboutZero() {
        val a = signatureOf(halves(ORANGE, TEAL))
        val b = signatureOf(halves(ORANGE, TEAL))

        assertThat(a.distanceTo(b)).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun aSignature_hasADistanceOfAboutZeroToItself() {
        val a = signatureOf(image { x, y -> ((x * 7 + y * 13) % 256 shl 16) or ((x * 3) % 256 shl 8) or ((y * 5) % 256) })

        assertThat(a.distanceTo(a)).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun theLayoutOfTheColours_doesNotMatter() {
        val a = signatureOf(halves(ORANGE, TEAL))
        val b = signatureOf(halves(TEAL, ORANGE))

        assertThat(a.distanceTo(b)).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun twoDifferentColours_areFarApart() {
        val red = signatureOf(solid(RED))
        val blue = signatureOf(solid(BLUE))

        assertThat(red.distanceTo(blue)).isAtLeast(FAR_DISTANCE)
    }

    @Test
    fun theSameColoursAtTwoThirdsOfTheBrightness_stayClose() {
        val bright = signatureOf(halves(ORANGE, TEAL))
        val darker = signatureOf(halves(scaled(ORANGE, DIM), scaled(TEAL, DIM)))

        assertThat(bright.distanceTo(darker)).isAtMost(MAX_RELOCK_DISTANCE)
    }

    @Test
    fun brightnessTimesPointEight_stillCountsAsTheSamePerson() {
        val bright = signatureOf(halves(ORANGE, TEAL))
        val dimmed = signatureOf(halves(scaled(ORANGE, 0.8f), scaled(TEAL, 0.8f)))

        assertThat(bright.distanceTo(dimmed)).isAtMost(MAX_RELOCK_DISTANCE)
    }

    @Test
    fun aGreyPatch_isFarFromAColouredOne_andCloseToTheSameGrey() {
        val grey = signatureOf(solid(GREY))

        assertThat(grey.distanceTo(signatureOf(solid(RED)))).isAtLeast(FAR_DISTANCE)
        assertThat(grey.distanceTo(signatureOf(solid(GREY)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun aVeryDarkColour_countsAsGreyLikeBlack() {
        val nearBlackRed = signatureOf(solid(NEAR_BLACK_RED))
        val black = signatureOf(solid(BLACK))

        assertThat(nearBlackRed.distanceTo(black)).isWithin(DISTANCE_TOLERANCE).of(0f)
        assertThat(nearBlackRed.distanceTo(signatureOf(solid(RED)))).isAtLeast(FAR_DISTANCE)
    }

    @Test
    fun aPaleColourWithAlmostNoSaturation_countsAsGrey() {
        val almostWhite = signatureOf(solid(ALMOST_WHITE))
        val white = signatureOf(solid(WHITE))

        assertThat(almostWhite.distanceTo(white)).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun aSmallRectangle_isReadPixelByPixel() {
        val small = TorsoRect().apply { set(2, 3, 17, 18) }
        val signature = signatureOf(solid(RED), area = small)

        assertThat(signature.lastSampleCount).isEqualTo(SMALL_PIXELS)
    }

    @Test
    fun aBigRectangle_isSampledOnAGridOfAtMostTheLimit() {
        val signature = signatureOf(solid(RED))

        assertThat(signature.lastSampleCount).isAtMost(config.relockMaxSamplePixels)
        assertThat(signature.lastSampleCount).isGreaterThan(config.relockMaxSamplePixels / 4)
    }

    @Test
    fun theSampleLimit_comesFromTheConfig() {
        val tight = tightConfig()

        val signature = signatureOf(solid(RED), useConfig = tight)

        assertThat(signature.lastSampleCount).isAtMost(TrackerConfig.MIN_RELOCK_SAMPLE_PIXELS)
        assertThat(signature.lastSampleCount).isGreaterThan(TrackerConfig.MIN_RELOCK_SAMPLE_PIXELS / 4)
    }

    @Test
    fun aGridSample_stillSeesTheColoursOfTheWholeRectangle() {
        val full = signatureOf(halves(ORANGE, TEAL))
        val tight = signatureOf(halves(ORANGE, TEAL), useConfig = tightConfig())

        assertThat(full.distanceTo(tight)).isAtMost(MAX_RELOCK_DISTANCE)
    }

    @Test
    fun theRectangle_isClippedToTheImage() {
        val over = TorsoRect().apply { set(-50, -50, SIDE + 50, SIDE + 50) }

        val signature = signatureOf(solid(RED), area = over)

        assertThat(signature.lastSampleCount).isAtMost(config.relockMaxSamplePixels)
        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun blend_mixesTheNewSampleInWithTheAlphaWeight() {
        val signature = signatureOf(solid(RED))
        assertThat(signature.blend(solid(BLUE), SIDE, SIDE, rect)).isTrue()

        // Bins are now 0.9 red and 0.1 blue: the distance to pure red is sqrt(1 - sqrt(0.9)), to pure blue sqrt(1 - sqrt(0.1)).
        val toRed = signature.distanceTo(signatureOf(solid(RED)))
        val toBlue = signature.distanceTo(signatureOf(solid(BLUE)))
        assertThat(toRed).isWithin(MIX_TOLERANCE).of(sqrt(1f - sqrt(1f - config.relockSignatureAlpha)))
        assertThat(toBlue).isWithin(MIX_TOLERANCE).of(sqrt(1f - sqrt(config.relockSignatureAlpha)))
    }

    @Test
    fun blend_oneBadFrame_doesNotReplaceTheSignature() {
        val signature = signatureOf(halves(ORANGE, TEAL))

        signature.blend(solid(RED), SIDE, SIDE, rect)

        assertThat(signature.distanceTo(signatureOf(halves(ORANGE, TEAL)))).isAtMost(MAX_RELOCK_DISTANCE)
    }

    @Test
    fun blend_manySamples_moveTheSignatureToTheNewColours() {
        val signature = signatureOf(solid(RED))

        repeat(MANY_BLENDS) { signature.blend(solid(BLUE), SIDE, SIDE, rect) }

        assertThat(signature.distanceTo(signatureOf(solid(BLUE)))).isAtMost(MAX_RELOCK_DISTANCE)
    }

    @Test
    fun blend_withoutData_setsTheSignature() {
        val signature = AppearanceSignature(config)

        assertThat(signature.blend(solid(RED), SIDE, SIDE, rect)).isTrue()

        assertThat(signature.hasData).isTrue()
        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun aSignatureWithoutData_isAtTheLargestDistanceFromEverything() {
        val empty = AppearanceSignature(config)
        val red = signatureOf(solid(RED))

        assertThat(empty.hasData).isFalse()
        assertThat(empty.distanceTo(red)).isEqualTo(1f)
        assertThat(red.distanceTo(empty)).isEqualTo(1f)
        assertThat(empty.distanceTo(empty)).isEqualTo(1f)
    }

    @Test
    fun clear_forgetsTheData() {
        val signature = signatureOf(solid(RED))

        signature.clear()

        assertThat(signature.hasData).isFalse()
        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isEqualTo(1f)
    }

    @Test
    fun aRectangleOutsideTheImage_changesNothing() {
        val signature = signatureOf(solid(RED))
        val outside = TorsoRect().apply { set(SIDE + 5, 0, SIDE + 20, SIDE) }

        assertThat(signature.set(solid(BLUE), SIDE, SIDE, outside)).isFalse()
        assertThat(signature.blend(solid(BLUE), SIDE, SIDE, outside)).isFalse()

        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun anEmptyRectangle_changesNothing() {
        val fresh = AppearanceSignature(config)

        assertThat(fresh.set(solid(RED), SIDE, SIDE, TorsoRect())).isFalse()
        assertThat(fresh.blend(solid(RED), SIDE, SIDE, TorsoRect())).isFalse()

        assertThat(fresh.hasData).isFalse()
    }

    @Test
    fun pixelsOfTheWrongSize_changeNothing() {
        val signature = signatureOf(solid(RED))

        assertThat(signature.set(ByteArray(SIDE), SIDE, SIDE, rect)).isFalse()
        assertThat(signature.blend(solid(BLUE), SIDE + 1, SIDE, rect)).isFalse()
        assertThat(signature.set(solid(BLUE), 0, SIDE, rect)).isFalse()

        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun signaturesWithDifferentBinCounts_areAtTheLargestDistance() {
        val a = signatureOf(solid(RED))
        val b = signatureOf(solid(RED), useConfig = TrackerConfig(relockHueBins = 8))

        assertThat(a.distanceTo(b)).isEqualTo(1f)
    }

    /** The smallest sample limit; the least sample count has to come down with it. */
    private fun tightConfig(): TrackerConfig =
        TrackerConfig(
            relockMaxSamplePixels = TrackerConfig.MIN_RELOCK_SAMPLE_PIXELS,
            relockMinSamplePixels = TrackerConfig.MIN_RELOCK_MIN_SAMPLE_PIXELS,
        )

    @Test
    fun aRectangleWithFewerPointsThanTheLeastSample_changesNothing() {
        val signature = signatureOf(solid(RED))
        val tiny = TorsoRect().apply { set(2, 3, 12, 13) }

        assertThat(signature.set(solid(BLUE), SIDE, SIDE, tiny)).isFalse()
        assertThat(signature.blend(solid(BLUE), SIDE, SIDE, tiny)).isFalse()
        assertThat(AppearanceSignature(config).set(solid(BLUE), SIDE, SIDE, tiny)).isFalse()

        assertThat(signature.distanceTo(signatureOf(solid(RED)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    @Test
    fun aRectangleWithExactlyTheLeastSample_isRead() {
        val exact = TorsoRect().apply { set(0, 0, 10, 15) }

        assertThat(AppearanceSignature(config).set(solid(RED), SIDE, SIDE, exact)).isTrue()
    }

    @Test
    fun afterClear_aFailedReadLeavesNothingToBlendWith() {
        val signature = signatureOf(solid(RED))
        signature.clear()

        assertThat(signature.blend(solid(BLUE), SIDE, SIDE, rect)).isTrue()

        assertThat(signature.distanceTo(signatureOf(solid(BLUE)))).isWithin(DISTANCE_TOLERANCE).of(0f)
    }

    private fun scaled(
        colour: Int,
        factor: Float,
    ): Int {
        val red = ((colour shr RED_SHIFT) and BYTE) * factor
        val green = ((colour shr GREEN_SHIFT) and BYTE) * factor
        val blue = (colour and BYTE) * factor
        return (red.toInt() shl RED_SHIFT) or (green.toInt() shl GREEN_SHIFT) or blue.toInt()
    }

    private companion object {
        const val SIDE = 120
        const val OPAQUE: Byte = -1
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BYTE = 0xFF
        const val RED = 0xFF0000
        const val BLUE = 0x0000FF
        const val ORANGE = 0xC86432
        const val TEAL = 0x2878C8
        const val GREY = 0x808080
        const val BLACK = 0x000000
        const val NEAR_BLACK_RED = 0x1E0000
        const val WHITE = 0xFFFFFF
        const val ALMOST_WHITE = 0xFFF8F8
        const val DIM = 0.67f
        const val DISTANCE_TOLERANCE = 0.01f
        const val MIX_TOLERANCE = 0.02f
        const val FAR_DISTANCE = 0.9f
        const val MAX_RELOCK_DISTANCE = 0.3f
        const val SMALL_PIXELS = 225
        const val MANY_BLENDS = 60
    }
}
