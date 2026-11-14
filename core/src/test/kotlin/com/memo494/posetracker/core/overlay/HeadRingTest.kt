package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HeadRingTest {
    private val ratio = 0.12f
    private val minRadius = 12f

    @Test
    fun radius_smallBox_theMinimumWins() {
        assertThat(HeadRing.radius(boxWidth = 40f, ratio = ratio, minRadius = minRadius)).isEqualTo(minRadius)
    }

    @Test
    fun radius_largeBox_theRatioWins() {
        assertThat(HeadRing.radius(boxWidth = 400f, ratio = ratio, minRadius = minRadius)).isWithin(1e-4f).of(48f)
    }

    @Test
    fun radius_atTheCrossover_bothAreEqual() {
        // 100 x 0.12 = 12 = the minimum.
        assertThat(HeadRing.radius(boxWidth = 100f, ratio = ratio, minRadius = minRadius)).isWithin(1e-4f).of(minRadius)
    }

    @Test
    fun radius_zeroWidth_givesTheMinimum() {
        assertThat(HeadRing.radius(boxWidth = 0f, ratio = ratio, minRadius = minRadius)).isEqualTo(minRadius)
    }

    @Test
    fun radius_negativeWidth_givesTheMinimum() {
        assertThat(HeadRing.radius(boxWidth = -300f, ratio = ratio, minRadius = minRadius)).isEqualTo(minRadius)
    }

    @Test
    fun radius_nanWidth_givesTheMinimum() {
        assertThat(HeadRing.radius(boxWidth = Float.NaN, ratio = ratio, minRadius = minRadius)).isEqualTo(minRadius)
    }

    @Test
    fun radius_zeroMinimum_isThePlainRatioRule() {
        assertThat(HeadRing.radius(boxWidth = 50f, ratio = ratio, minRadius = 0f)).isWithin(1e-4f).of(6f)
    }
}
