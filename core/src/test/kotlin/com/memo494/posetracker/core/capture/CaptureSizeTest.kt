package com.memo494.posetracker.core.capture

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CaptureSizeTest {
    @Test
    fun portraitDisplay_atHalfScale_is610x1356() {
        val size = checkNotNull(CaptureSize.of(1220, 2712, 0.5f))

        assertThat(size.widthImg).isEqualTo(610)
        assertThat(size.heightImg).isEqualTo(1356)
    }

    @Test
    fun landscapeDisplay_atHalfScale_is1356x610() {
        val size = checkNotNull(CaptureSize.of(2712, 1220, 0.5f))

        assertThat(size.widthImg).isEqualTo(1356)
        assertThat(size.heightImg).isEqualTo(610)
    }

    @Test
    fun fullHdDisplay_atHalfScale_keepsTheExactAspect() {
        val size = checkNotNull(CaptureSize.of(1080, 2400, 0.5f))

        assertThat(size.widthImg).isEqualTo(540)
        assertThat(size.heightImg).isEqualTo(1200)
    }

    @Test
    fun fullScale_returnsTheDisplaySize() {
        val size = checkNotNull(CaptureSize.of(1220, 2712, 1.0f))

        assertThat(size.widthImg).isEqualTo(1220)
        assertThat(size.heightImg).isEqualTo(2712)
    }

    @Test
    fun awkwardDisplay_roundsToNearestEvenDimensions() {
        // 1081x2401 has no small exact-aspect even size; fall back to the nearest even width and height.
        val size = checkNotNull(CaptureSize.of(1081, 2401, 0.5f))

        assertThat(size.widthImg % 2).isEqualTo(0)
        assertThat(size.heightImg % 2).isEqualTo(0)
        assertThat(size.widthImg).isEqualTo(540)
        assertThat(size.heightImg).isEqualTo(1200)
    }

    @Test
    fun result_isAlwaysEvenAndAtLeastTwo() {
        val size = checkNotNull(CaptureSize.of(3, 5, 0.25f))

        assertThat(size.widthImg).isAtLeast(2)
        assertThat(size.heightImg).isAtLeast(2)
        assertThat(size.widthImg % 2).isEqualTo(0)
        assertThat(size.heightImg % 2).isEqualTo(0)
    }

    @Test
    fun invalidInput_returnsNull() {
        assertThat(CaptureSize.of(0, 2712, 0.5f)).isNull()
        assertThat(CaptureSize.of(1220, -1, 0.5f)).isNull()
        assertThat(CaptureSize.of(1220, 2712, 0f)).isNull()
        assertThat(CaptureSize.of(1220, 2712, Float.NaN)).isNull()
    }
}
