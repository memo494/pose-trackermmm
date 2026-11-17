package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LabelPlacementTest {
    private val viewWidth = 400f
    private val viewHeight = 1000f

    @Test
    fun leftX_insideTheView_isTheAnchor() {
        assertThat(LabelPlacement.leftX(anchorX = 100f, labelWidth = 30f, viewWidth = viewWidth)).isEqualTo(100f)
    }

    @Test
    fun leftX_endingExactlyAtTheRightEdge_isTheAnchor() {
        assertThat(LabelPlacement.leftX(anchorX = 370f, labelWidth = 30f, viewWidth = viewWidth)).isEqualTo(370f)
    }

    @Test
    fun leftX_pastTheRightEdge_movesLeftSoTheLabelEndsAtTheEdge() {
        assertThat(LabelPlacement.leftX(anchorX = 390f, labelWidth = 30f, viewWidth = viewWidth)).isEqualTo(370f)
    }

    @Test
    fun leftX_leftOfTheView_startsAtZero() {
        assertThat(LabelPlacement.leftX(anchorX = -50f, labelWidth = 30f, viewWidth = viewWidth)).isEqualTo(0f)
    }

    @Test
    fun leftX_aLabelWiderThanTheView_startsAtZero() {
        assertThat(LabelPlacement.leftX(anchorX = 10f, labelWidth = 500f, viewWidth = viewWidth)).isEqualTo(0f)
    }

    @Test
    fun leftX_aValueThatIsNotFinite_givesNaN() {
        assertThat(LabelPlacement.leftX(Float.NaN, 30f, viewWidth).isNaN()).isTrue()
        assertThat(LabelPlacement.leftX(100f, Float.NaN, viewWidth).isNaN()).isTrue()
        assertThat(LabelPlacement.leftX(100f, 30f, Float.POSITIVE_INFINITY).isNaN()).isTrue()
        assertThat(LabelPlacement.leftX(Float.NEGATIVE_INFINITY, 30f, viewWidth).isNaN()).isTrue()
    }

    @Test
    fun baselineY_roomAbove_putsTheTextBottomOneGapAboveTheAnchor() {
        // Text bottom = baseline + descent = 190 + 6 = 196 = anchor 200 - gap 4.
        val baseline = LabelPlacement.baselineY(anchorY = 200f, ascent = 20f, descent = 6f, gap = 4f, viewHeight = viewHeight)

        assertThat(baseline).isEqualTo(190f)
    }

    @Test
    fun baselineY_noRoomAbove_movesDownSoTheTextTopIsAtZero() {
        val baseline = LabelPlacement.baselineY(anchorY = 10f, ascent = 20f, descent = 6f, gap = 4f, viewHeight = viewHeight)

        assertThat(baseline).isEqualTo(20f)
    }

    @Test
    fun baselineY_anchorAboveTheView_movesDownSoTheTextTopIsAtZero() {
        val baseline = LabelPlacement.baselineY(anchorY = -300f, ascent = 20f, descent = 6f, gap = 4f, viewHeight = viewHeight)

        assertThat(baseline).isEqualTo(20f)
    }

    @Test
    fun baselineY_anchorBelowTheView_movesUpSoTheTextBottomIsAtTheEdge() {
        val baseline = LabelPlacement.baselineY(anchorY = 2000f, ascent = 20f, descent = 6f, gap = 4f, viewHeight = viewHeight)

        assertThat(baseline).isEqualTo(994f)
    }

    @Test
    fun baselineY_aValueThatIsNotFinite_givesNaN() {
        assertThat(LabelPlacement.baselineY(Float.NaN, 20f, 6f, 4f, viewHeight).isNaN()).isTrue()
        assertThat(LabelPlacement.baselineY(200f, Float.NaN, 6f, 4f, viewHeight).isNaN()).isTrue()
        assertThat(LabelPlacement.baselineY(200f, 20f, Float.NaN, 4f, viewHeight).isNaN()).isTrue()
        assertThat(LabelPlacement.baselineY(200f, 20f, 6f, Float.NaN, viewHeight).isNaN()).isTrue()
        assertThat(LabelPlacement.baselineY(200f, 20f, 6f, 4f, Float.POSITIVE_INFINITY).isNaN()).isTrue()
    }
}
