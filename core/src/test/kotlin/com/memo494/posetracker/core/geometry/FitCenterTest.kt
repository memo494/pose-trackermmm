package com.memo494.posetracker.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FitCenterTest {
    private val eps = 1e-3f

    @Test
    fun contentRect_whenBufferHasTheDisplayAspect_isTheWholeBuffer() {
        val rect = checkNotNull(FitCenter.contentRectImg(1220, 2712, 610, 1356))

        assertThat(rect.leftImg).isWithin(eps).of(0f)
        assertThat(rect.topImg).isWithin(eps).of(0f)
        assertThat(rect.widthImg).isWithin(eps).of(610f)
        assertThat(rect.heightImg).isWithin(eps).of(1356f)
    }

    @Test
    fun contentRect_whenBufferIsWider_isCenteredWithSideBars() {
        // Display 1000x2000 inside a 600x1000 buffer: scale 0.5 -> content 500x1000, bars of 50 px.
        val rect = checkNotNull(FitCenter.contentRectImg(1000, 2000, 600, 1000))

        assertThat(rect.leftImg).isWithin(eps).of(50f)
        assertThat(rect.topImg).isWithin(eps).of(0f)
        assertThat(rect.widthImg).isWithin(eps).of(500f)
        assertThat(rect.heightImg).isWithin(eps).of(1000f)
    }

    @Test
    fun contentRect_whenBufferIsTaller_isCenteredWithTopBottomBars() {
        // Display 2000x1000 inside a 1000x600 buffer: content 1000x500, bars of 50 px top and bottom.
        val rect = checkNotNull(FitCenter.contentRectImg(2000, 1000, 1000, 600))

        assertThat(rect.leftImg).isWithin(eps).of(0f)
        assertThat(rect.topImg).isWithin(eps).of(50f)
        assertThat(rect.widthImg).isWithin(eps).of(1000f)
        assertThat(rect.heightImg).isWithin(eps).of(500f)
    }

    @Test
    fun contentRect_withNonPositiveSize_returnsNull() {
        assertThat(FitCenter.contentRectImg(0, 2712, 610, 1356)).isNull()
        assertThat(FitCenter.contentRectImg(1220, 2712, 610, 0)).isNull()
    }

    @Test
    fun portrait_contentCornersMapToViewCorners() {
        val rect = checkNotNull(FitCenter.contentRectImg(1220, 2712, 610, 1356))
        val fit = checkNotNull(FitCenter.fitInto(rect, 1220, 2712))

        assertThat(fit.scale).isWithin(eps).of(2f)
        assertThat(fit.imgToViewX(0f)).isWithin(eps).of(0f)
        assertThat(fit.imgToViewY(0f)).isWithin(eps).of(0f)
        assertThat(fit.imgToViewX(610f)).isWithin(eps).of(1220f)
        assertThat(fit.imgToViewY(1356f)).isWithin(eps).of(2712f)
    }

    @Test
    fun landscape_contentCornersMapToViewCorners() {
        val rect = checkNotNull(FitCenter.contentRectImg(2712, 1220, 1356, 610))
        val fit = checkNotNull(FitCenter.fitInto(rect, 2712, 1220))

        assertThat(fit.scale).isWithin(eps).of(2f)
        assertThat(fit.imgToViewX(1356f)).isWithin(eps).of(2712f)
        assertThat(fit.imgToViewY(610f)).isWithin(eps).of(1220f)
    }

    @Test
    fun withBars_contentCornersMapToViewCorners() {
        val rect = checkNotNull(FitCenter.contentRectImg(1000, 2000, 600, 1000))
        val fit = checkNotNull(FitCenter.fitInto(rect, 1000, 2000))

        assertThat(fit.scale).isWithin(eps).of(2f)
        assertThat(fit.offsetXView).isWithin(eps).of(-100f)
        assertThat(fit.imgToViewX(rect.leftImg)).isWithin(eps).of(0f)
        assertThat(fit.imgToViewY(rect.topImg)).isWithin(eps).of(0f)
        assertThat(fit.imgToViewX(rect.rightImg)).isWithin(eps).of(1000f)
        assertThat(fit.imgToViewY(rect.bottomImg)).isWithin(eps).of(2000f)
    }

    @Test
    fun viewWithDifferentAspect_centersTheContentWithAnOffset() {
        // Content 500x1000 at (50, 0) fitted into a 1200x2000 view: scale 2, content 1000x2000,
        // centered horizontally (100 px bars): content left (50 Img) -> 100 View.
        val rect = checkNotNull(FitCenter.contentRectImg(1000, 2000, 600, 1000))
        val fit = checkNotNull(FitCenter.fitInto(rect, 1200, 2000))

        assertThat(fit.scale).isWithin(eps).of(2f)
        assertThat(fit.imgToViewX(rect.leftImg)).isWithin(eps).of(100f)
        assertThat(fit.imgToViewX(rect.rightImg)).isWithin(eps).of(1100f)
        assertThat(fit.imgToViewY(rect.bottomImg)).isWithin(eps).of(2000f)
    }

    @Test
    fun forDisplay_withWindowOffset_subtractsTheWindowOrigin() {
        val rect = checkNotNull(FitCenter.contentRectImg(1220, 2712, 610, 1356))
        // View starts 40 px below the top of the screen (e.g. below a cutout).
        val fit = checkNotNull(FitCenter.forDisplay(rect, 1220, 0, 40))

        assertThat(fit.scale).isWithin(eps).of(2f)
        assertThat(fit.imgToViewX(0f)).isWithin(eps).of(0f)
        assertThat(fit.imgToViewY(0f)).isWithin(eps).of(-40f)
        assertThat(fit.imgToViewY(1356f)).isWithin(eps).of(2712f - 40f)
    }

    @Test
    fun viewToImg_isTheInverseOfImgToView() {
        val rect = checkNotNull(FitCenter.contentRectImg(1000, 2000, 600, 1000))
        val fit = checkNotNull(FitCenter.fitInto(rect, 1200, 2000))

        for ((xImg, yImg) in listOf(50f to 0f, 550f to 1000f, 123.5f to 456.25f)) {
            val xView = fit.imgToViewX(xImg)
            val yView = fit.imgToViewY(yImg)

            assertThat(fit.viewToImgX(xView)).isWithin(eps).of(xImg)
            assertThat(fit.viewToImgY(yView)).isWithin(eps).of(yImg)
        }
    }

    @Test
    fun fitInto_withNonPositiveViewSize_returnsNull() {
        val rect = checkNotNull(FitCenter.contentRectImg(1220, 2712, 610, 1356))

        assertThat(FitCenter.fitInto(rect, 0, 2712)).isNull()
        assertThat(FitCenter.fitInto(rect, 1220, 0)).isNull()
        assertThat(FitCenter.forDisplay(rect, 0, 0, 0)).isNull()
    }
}
