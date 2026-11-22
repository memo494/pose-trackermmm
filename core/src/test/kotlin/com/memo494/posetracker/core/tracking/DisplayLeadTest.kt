package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

/** The three pure parts of the display lead (F4): speed gate, stop gate and lead time. */
class DisplayLeadTest {
    private val config = TrackerConfig()

    @Test
    fun speedGain_isZeroAtAndBelowTheLowSpeed_oneAtAndAboveTheHighSpeed_andLinearBetween() {
        assertThat(DisplayLead.speedGain(0f, config)).isEqualTo(0f)
        assertThat(DisplayLead.speedGain(config.displayLeadSpeedLowHps, config)).isEqualTo(0f)
        assertThat(DisplayLead.speedGain(0.75f, config)).isWithin(EPS).of(0.5f)
        assertThat(DisplayLead.speedGain(config.displayLeadSpeedHighHps, config)).isEqualTo(1f)
        assertThat(DisplayLead.speedGain(7f, config)).isEqualTo(1f)
    }

    @Test
    fun speedGain_withEqualLimits_isAStep_andANonNumberGivesZero() {
        val step = TrackerConfig(displayLeadSpeedLowHps = 1f, displayLeadSpeedHighHps = 1f)

        assertThat(DisplayLead.speedGain(0.99f, step)).isEqualTo(0f)
        assertThat(DisplayLead.speedGain(1f, step)).isEqualTo(0f)
        assertThat(DisplayLead.speedGain(1.01f, step)).isEqualTo(1f)
        assertThat(DisplayLead.speedGain(Float.NaN, config)).isEqualTo(0f)
        assertThat(DisplayLead.speedGain(Float.POSITIVE_INFINITY, config)).isEqualTo(0f)
    }

    @Test
    fun stopGain_isOneWhenTheMeasuredHeadKeepsTheSpeed_orIsFaster() {
        assertThat(DisplayLead.stopGain(200f, 0f, 200f, 0f)).isEqualTo(1f)
        assertThat(DisplayLead.stopGain(200f, 0f, 350f, 0f)).isEqualTo(1f)
    }

    @Test
    fun stopGain_isTheShareOfTheSpeedAlongTheTrackerVelocity() {
        assertThat(DisplayLead.stopGain(200f, 0f, 100f, 0f)).isWithin(EPS).of(0.5f)
        assertThat(DisplayLead.stopGain(0f, -200f, 0f, -50f)).isWithin(EPS).of(0.25f)
        assertThat(DisplayLead.stopGain(100f, 100f, 100f, 0f)).isWithin(EPS).of(0.5f)
    }

    @Test
    fun stopGain_isZeroWhenTheMeasuredStepStopsReversesOrIsSideways() {
        assertThat(DisplayLead.stopGain(200f, 0f, 0f, 0f)).isEqualTo(0f)
        assertThat(DisplayLead.stopGain(200f, 0f, -80f, 0f)).isEqualTo(0f)
        assertThat(DisplayLead.stopGain(200f, 0f, 0f, 90f)).isEqualTo(0f)
    }

    @Test
    fun stopGain_withoutATrackerVelocity_orWithNonNumbers_isZero() {
        assertThat(DisplayLead.stopGain(0f, 0f, 100f, 0f)).isEqualTo(0f)
        assertThat(DisplayLead.stopGain(Float.NaN, 0f, 100f, 0f)).isEqualTo(0f)
        assertThat(DisplayLead.stopGain(200f, 0f, Float.NaN, 0f)).isEqualTo(0f)
    }

    @Test
    fun leadTimeMs_isTheResultAgePlusTheExtraPlusHalfTheInterval() {
        // 60 + 15 + 25 = 100 ms, below the 130 ms limit.
        assertThat(DisplayLead.leadTimeMs(60f, 50f, config)).isWithin(EPS).of(100f)
    }

    @Test
    fun leadTimeMs_isCappedAtTheLimit() {
        assertThat(DisplayLead.leadTimeMs(500f, 50f, config)).isEqualTo(config.displayLeadMaxMs)
    }

    @Test
    fun leadTimeMs_treatsAnUnknownAgeOrIntervalAsZero() {
        assertThat(DisplayLead.leadTimeMs(Float.NaN, 40f, config)).isWithin(EPS).of(config.displayLeadExtraMs + 20f)
        assertThat(DisplayLead.leadTimeMs(-5f, 0f, config)).isWithin(EPS).of(config.displayLeadExtraMs)
        assertThat(DisplayLead.leadTimeMs(60f, Float.NaN, config)).isWithin(EPS).of(60f + config.displayLeadExtraMs)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
