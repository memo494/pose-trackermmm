package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MarkFadeTest {
    @Test
    fun alpha_withNoLostTime_isOpaque() {
        assertThat(MarkFade.alpha(0f, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(1f)
    }

    @Test
    fun alpha_atHalfTheLimit_isHalf() {
        assertThat(MarkFade.alpha(MAX_LOST_MS / 2f, MAX_LOST_MS, MIN_ALPHA)).isWithin(TOLERANCE).of(0.5f)
    }

    @Test
    fun alpha_fallsLinearlyWithTheLostTime() {
        assertThat(MarkFade.alpha(160f, MAX_LOST_MS, MIN_ALPHA)).isWithin(TOLERANCE).of(0.8f)
        assertThat(MarkFade.alpha(560f, MAX_LOST_MS, MIN_ALPHA)).isWithin(TOLERANCE).of(0.3f)
    }

    @Test
    fun alpha_atTheLimit_isTheFloor() {
        assertThat(MarkFade.alpha(MAX_LOST_MS, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
    }

    @Test
    fun alpha_whereTheLineIsBelowTheFloor_isTheFloor() {
        assertThat(MarkFade.alpha(700f, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
    }

    @Test
    fun alpha_beyondTheLimit_staysAtTheFloor() {
        assertThat(MarkFade.alpha(5_000f, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
        assertThat(MarkFade.alpha(Float.POSITIVE_INFINITY, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
    }

    @Test
    fun alpha_withAFloorOfZero_endsInvisible() {
        assertThat(MarkFade.alpha(MAX_LOST_MS, MAX_LOST_MS, 0f)).isEqualTo(0f)
    }

    @Test
    fun alpha_withAFloorOfOne_neverFades() {
        assertThat(MarkFade.alpha(MAX_LOST_MS / 2f, MAX_LOST_MS, 1f)).isEqualTo(1f)
    }

    @Test
    fun alpha_withNegativeLostTime_isOpaque() {
        assertThat(MarkFade.alpha(-50f, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(1f)
    }

    @Test
    fun alpha_neverRisesWithTheLostTime() {
        var previous = 1f
        var lostMs = 0f
        while (lostMs <= 2f * MAX_LOST_MS) {
            val alpha = MarkFade.alpha(lostMs, MAX_LOST_MS, MIN_ALPHA)
            assertThat(alpha).isAtMost(previous)
            assertThat(alpha).isAtLeast(MIN_ALPHA)
            previous = alpha
            lostMs += 10f
        }
    }

    @Test
    fun alpha_withALostTimeThatIsNotANumber_isTheFloor() {
        assertThat(MarkFade.alpha(Float.NaN, MAX_LOST_MS, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
    }

    @Test
    fun alpha_withALimitThatIsNotPositive_isTheFloor() {
        assertThat(MarkFade.alpha(10f, 0f, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
        assertThat(MarkFade.alpha(10f, -800f, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
        assertThat(MarkFade.alpha(10f, Float.NaN, MIN_ALPHA)).isEqualTo(MIN_ALPHA)
    }

    private companion object {
        const val MAX_LOST_MS = 800f
        const val MIN_ALPHA = 0.25f
        const val TOLERANCE = 1e-5f
    }
}
