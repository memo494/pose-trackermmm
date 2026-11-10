package com.memo494.posetracker.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LetterboxTest {
    private val eps = 1e-3f

    @Test
    fun portraitCapture_to320_matchesUltralyticsLetterBoxNumbers() {
        // Hand-computed from Ultralytics ImageUtils.letterboxTransform for 610x1356 -> 320x320:
        // gain = min(320/610, 320/1356) = 320/1356; resized = (round(610 * gain) = 144, 320);
        // pad = (176, 0); padX = round(176/2 - 0.1) = round(87.9) = 88; padY = round(0 - 0.1) = 0.
        val letterbox = checkNotNull(Letterbox.create(610, 1356, 320, 320))

        assertThat(letterbox.gain).isWithin(1e-6f).of(320f / 1356f)
        assertThat(letterbox.resizedWidthMdl).isEqualTo(144)
        assertThat(letterbox.resizedHeightMdl).isEqualTo(320)
        assertThat(letterbox.padLeftMdl).isEqualTo(88f)
        assertThat(letterbox.padTopMdl).isEqualTo(0f)
    }

    @Test
    fun roundTrip_imgToMdlToImg_returnsTheSamePoint() {
        val letterbox = checkNotNull(Letterbox.create(610, 1356, 320, 320))

        for ((xImg, yImg) in listOf(0f to 0f, 610f to 1356f, 305f to 678f, 12.5f to 1301.25f)) {
            val xMdl = letterbox.imgToMdlX(xImg)
            val yMdl = letterbox.imgToMdlY(yImg)

            assertThat(letterbox.mdlToImgX(xMdl)).isWithin(eps).of(xImg)
            assertThat(letterbox.mdlToImgY(yMdl)).isWithin(eps).of(yImg)
        }
    }

    @Test
    fun imgCorners_landInsideTheModelInput() {
        val letterbox = checkNotNull(Letterbox.create(610, 1356, 320, 320))

        assertThat(letterbox.imgToMdlX(0f)).isWithin(eps).of(88f)
        assertThat(letterbox.imgToMdlX(610f)).isWithin(eps).of(88f + 610f * 320f / 1356f)
        assertThat(letterbox.imgToMdlY(0f)).isWithin(eps).of(0f)
        assertThat(letterbox.imgToMdlY(1356f)).isWithin(eps).of(320f)
    }

    @Test
    fun landscapeCapture_padsTopAndBottom() {
        val letterbox = checkNotNull(Letterbox.create(1356, 610, 320, 320))

        assertThat(letterbox.padLeftMdl).isEqualTo(0f)
        assertThat(letterbox.padTopMdl).isEqualTo(88f)
        assertThat(letterbox.mdlToImgY(88f)).isWithin(eps).of(0f)
    }

    @Test
    fun create_withNonPositiveSize_returnsNull() {
        assertThat(Letterbox.create(0, 1356, 320, 320)).isNull()
        assertThat(Letterbox.create(610, -1, 320, 320)).isNull()
        assertThat(Letterbox.create(610, 1356, 0, 320)).isNull()
        assertThat(Letterbox.create(610, 1356, 320, 0)).isNull()
    }
}
