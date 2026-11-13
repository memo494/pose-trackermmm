package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Phone bug "nothing is drawn in landscape" (Poco X7 Pro, display 1220x2712, capture scale 0.5): the
 * overlay must map a landscape frame with the landscape display size of the capture plan, which the state
 * carries, never with a display size the view read itself during the previous rotation.
 */
class OverlayMappingTest {
    private val landscape =
        OverlayState(
            captureWidthImg = 1356,
            captureHeightImg = 610,
            displayWidthPx = 2712,
            displayHeightPx = 1220,
        )

    @Test
    fun landscapeFrame_mapsAHeadOntoTheLandscapeScreen() {
        val fit = OverlayMapping.forState(landscape, 0, 0)!!

        assertThat(fit.imgToViewX(1000f)).isEqualTo(2000f)
        assertThat(fit.imgToViewY(300f)).isEqualTo(600f)
    }

    @Test
    fun portraitFrame_mapsAHeadOntoThePortraitScreen() {
        val portrait = OverlayState(captureWidthImg = 610, captureHeightImg = 1356, displayWidthPx = 1220, displayHeightPx = 2712)

        val fit = OverlayMapping.forState(portrait, 0, 0)!!

        assertThat(fit.imgToViewX(300f)).isEqualTo(600f)
        assertThat(fit.imgToViewY(1000f)).isEqualTo(2000f)
    }

    @Test
    fun viewBelowACutout_isShiftedByItsPositionOnScreen() {
        val fit = OverlayMapping.forState(landscape, 0, 50)!!

        assertThat(fit.imgToViewY(300f)).isEqualTo(550f)
    }

    @Test
    fun unknownDisplaySize_declines() {
        assertThat(OverlayMapping.forState(landscape.copy(displayWidthPx = 0, displayHeightPx = 0), 0, 0)).isNull()
    }

    @Test
    fun unknownCaptureSize_declines() {
        assertThat(OverlayMapping.forState(landscape.copy(captureWidthImg = 0, captureHeightImg = 0), 0, 0)).isNull()
    }
}
