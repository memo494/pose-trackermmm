package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CornerBracketsTest {
    @Test
    fun length_isTheRatioTimesTheSide() {
        assertThat(CornerBrackets.length(side = 200f, ratio = 0.18f)).isWithin(1e-4f).of(36f)
    }

    @Test
    fun length_zeroSide_isZero() {
        assertThat(CornerBrackets.length(side = 0f, ratio = 0.18f)).isEqualTo(0f)
    }

    @Test
    fun length_negativeSide_isNeverNegative() {
        assertThat(CornerBrackets.length(side = -50f, ratio = 0.18f)).isEqualTo(0f)
    }

    @Test
    fun length_nanSide_isZero() {
        assertThat(CornerBrackets.length(side = Float.NaN, ratio = 0.18f)).isEqualTo(0f)
    }

    @Test
    fun length_belowHalfRatio_twoStrokesOfOneSideNeverMeet() {
        val side = 300f
        val ratio = 0.4999f

        assertThat(2f * CornerBrackets.length(side, ratio)).isLessThan(side)
    }
}
