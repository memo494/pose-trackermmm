package com.memo494.posetracker.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CoreMathTest {
    @Test
    fun clamp_withinBounds_returnsValue() {
        assertThat(CoreMath.clamp(0.5f, 0f, 1f)).isEqualTo(0.5f)
    }

    @Test
    fun clamp_belowMin_returnsMin() {
        assertThat(CoreMath.clamp(-1f, 0f, 1f)).isEqualTo(0f)
    }

    @Test
    fun clamp_aboveMax_returnsMax() {
        assertThat(CoreMath.clamp(2f, 0f, 1f)).isEqualTo(1f)
    }
}
