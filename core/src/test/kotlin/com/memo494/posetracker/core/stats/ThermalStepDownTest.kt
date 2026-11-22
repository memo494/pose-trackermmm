package com.memo494.posetracker.core.stats

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

private const val SEC = 1_000_000_000L
private const val NONE = 0
private const val LIGHT = 1
private const val MODERATE = 2
private const val SEVERE = 3
private const val UNAVAILABLE = ThermalReader.UNAVAILABLE

class ThermalStepDownTest {
    private val config = TrackerConfig() // hot 0.9, cool 0.75, confirm 3 s, cool-down 60 s
    private val policy = ThermalStepDown(config)

    /** Feeds one reading per [stepSec] seconds from [fromSec] to [toSec] inclusive; returns the times a transition happened. */
    private fun feed(
        status: Int,
        headroom: Float,
        fromSec: Long,
        toSec: Long,
        stepSec: Long = 2,
    ): List<Long> {
        val changes = mutableListOf<Long>()
        var t = fromSec
        while (t <= toSec) {
            if (policy.update(status, headroom, t * SEC)) changes.add(t)
            t += stepSec
        }
        return changes
    }

    @Test
    fun startsNormal() {
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)
        assertThat(policy.stepDowns).isEqualTo(0)
        assertThat(policy.hotMs(10 * SEC)).isEqualTo(0L)
    }

    @Test
    fun hotStatus_entersHotOnlyAfterTheConfirmTime() {
        assertThat(policy.update(MODERATE, Float.NaN, 0)).isFalse()
        assertThat(policy.update(MODERATE, Float.NaN, 2 * SEC)).isFalse()
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)

        assertThat(policy.update(MODERATE, Float.NaN, 3 * SEC)).isTrue()

        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
        assertThat(policy.stepDowns).isEqualTo(1)
    }

    @Test
    fun aHotReadingThatDoesNotLast_neverEntersHot() {
        policy.update(SEVERE, 1.0f, 0)
        policy.update(NONE, 0.2f, 2 * SEC)
        policy.update(SEVERE, 1.0f, 4 * SEC)
        policy.update(NONE, 0.2f, 6 * SEC)

        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)
        assertThat(policy.stepDowns).isEqualTo(0)
    }

    @Test
    fun aReadingBetweenHotAndCool_resetsTheConfirmTimer() {
        policy.update(NONE, 0.95f, 0)
        policy.update(NONE, 0.8f, 2 * SEC) // not hot: the timer restarts at the next hot reading
        policy.update(NONE, 0.95f, 4 * SEC)

        assertThat(policy.update(NONE, 0.95f, 6 * SEC)).isFalse() // only 2 s since 4 s
        assertThat(policy.update(NONE, 0.95f, 7 * SEC)).isTrue()
    }

    @Test
    fun headroomAlone_entersHot_whenAtOrAboveTheThreshold() {
        assertThat(feed(UNAVAILABLE, 0.9f, 0, 4)).containsExactly(4L)
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
    }

    @Test
    fun headroomJustBelowTheThreshold_doesNotEnterHot() {
        assertThat(feed(NONE, 0.89f, 0, 20)).isEmpty()
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)
    }

    @Test
    fun unavailableReadings_neverTriggerHot() {
        assertThat(feed(UNAVAILABLE, Float.NaN, 0, 600)).isEmpty()
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)
    }

    @Test
    fun anUnavailableReading_interruptsTheConfirmTimer() {
        policy.update(MODERATE, Float.NaN, 0)
        policy.update(UNAVAILABLE, Float.NaN, 2 * SEC)

        assertThat(policy.update(MODERATE, Float.NaN, 4 * SEC)).isFalse()
        assertThat(policy.update(MODERATE, Float.NaN, 7 * SEC)).isTrue()
    }

    @Test
    fun anOutOfRangeStatus_isNotHot() {
        assertThat(feed(TrackerConfig.THERMAL_STATUS_MAX + 1, Float.NaN, 0, 60)).isEmpty()
        assertThat(feed(-2, Float.NaN, 62, 120)).isEmpty()
    }

    @Test
    fun leavingHot_needsAStatusOfLightOrBetter_andALowHeadroom_forTheCoolDown() {
        feed(MODERATE, 0.5f, 0, 4)
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)

        // cool from t=10; the 60 s run is complete at t=70 (the first cool reading starts it)
        assertThat(feed(LIGHT, 0.75f, 10, 68)).isEmpty()
        assertThat(feed(LIGHT, 0.75f, 70, 70)).containsExactly(70L)
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)
    }

    @Test
    fun whileHot_aWarmReading_restartsTheCoolDown_soItNeverFlaps() {
        feed(SEVERE, 1.0f, 0, 4)

        assertThat(feed(NONE, 0.5f, 10, 60)).isEmpty() // 50 s cool
        assertThat(feed(NONE, 0.8f, 62, 62)).isEmpty() // headroom between 0.75 and 0.9: neither hot nor cool
        assertThat(feed(NONE, 0.5f, 64, 122)).isEmpty() // 58 s cool
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
        assertThat(feed(NONE, 0.5f, 124, 124)).containsExactly(124L)
        assertThat(policy.stepDowns).isEqualTo(1)
    }

    @Test
    fun whileHot_aModerateStatus_isNotCool_evenWithLowHeadroom() {
        feed(MODERATE, 0.5f, 0, 4)

        assertThat(feed(MODERATE, 0.3f, 10, 300)).isEmpty()
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
    }

    @Test
    fun whileHot_unavailableReadings_neverCoolItDown() {
        feed(MODERATE, 0.5f, 0, 4)

        assertThat(feed(UNAVAILABLE, Float.NaN, 10, 400)).isEmpty()
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
    }

    @Test
    fun whileHot_statusLightWithoutHeadroom_isCool() {
        feed(MODERATE, Float.NaN, 0, 4)

        assertThat(feed(LIGHT, Float.NaN, 10, 70)).containsExactly(70L)
    }

    @Test
    fun whileHot_headroomAlone_coolsDownWhenAtOrBelowTheLowerThreshold() {
        feed(UNAVAILABLE, 0.95f, 0, 4)

        assertThat(feed(UNAVAILABLE, 0.75f, 10, 70)).containsExactly(70L)
    }

    @Test
    fun anOscillatingReading_changesTheStateAtMostOnce() {
        val changes = mutableListOf<Long>()
        var t = 0L
        while (t <= 600) {
            val hot = (t / 2) % 2 == 0L // alternates every sample
            if (policy.update(if (hot) MODERATE else LIGHT, if (hot) 0.95f else 0.5f, t * SEC)) changes.add(t)
            t += 2
        }
        assertThat(changes).isEmpty()
    }

    @Test
    fun hotThenCoolThenHotAgain_countsEachStepDown() {
        feed(MODERATE, 0.95f, 0, 4)
        feed(NONE, 0.5f, 10, 70)
        assertThat(policy.state).isEqualTo(ThermalStepDown.State.NORMAL)

        feed(MODERATE, 0.95f, 80, 84)

        assertThat(policy.state).isEqualTo(ThermalStepDown.State.HOT)
        assertThat(policy.stepDowns).isEqualTo(2)
    }

    @Test
    fun hotMs_countsTheTimeInHot_includingTheRunningStretch() {
        feed(MODERATE, 0.95f, 0, 4) // HOT from t=4
        assertThat(policy.hotMs(10 * SEC)).isEqualTo(6_000L)

        feed(NONE, 0.5f, 10, 70) // NORMAL again at t=70
        assertThat(policy.hotMs(100 * SEC)).isEqualTo(66_000L)

        feed(MODERATE, 0.95f, 80, 84) // HOT again from t=84
        assertThat(policy.hotMs(90 * SEC)).isEqualTo(66_000L + 6_000L)
    }

    @Test
    fun customThresholds_areUsed() {
        val custom =
            ThermalStepDown(
                TrackerConfig(
                    thermalHotHeadroom = 0.6f,
                    thermalCoolHeadroom = 0.4f,
                    thermalHotConfirmMs = 1_000L,
                    thermalCoolDownMs = 5_000L,
                ),
            )

        custom.update(NONE, 0.6f, 0)
        assertThat(custom.update(NONE, 0.6f, SEC)).isTrue()
        custom.update(NONE, 0.4f, 2 * SEC)
        assertThat(custom.update(NONE, 0.4f, 7 * SEC)).isTrue()
        assertThat(custom.state).isEqualTo(ThermalStepDown.State.NORMAL)
    }

    @Test
    fun transitionLine_namesTheNewStateTheStatusAndTheHeadroom() {
        assertThat(ThermalStepDown.transitionLine(ThermalStepDown.State.HOT, "MODERATE", 0.934f))
            .isEqualTo("thermal: HOT (status MODERATE, headroom 0.93), using the small model")
        assertThat(ThermalStepDown.transitionLine(ThermalStepDown.State.NORMAL, "LIGHT", 0.5f))
            .isEqualTo("thermal: NORMAL (status LIGHT, headroom 0.50), using the normal model again")
    }

    @Test
    fun transitionLine_withoutAHeadroom_saysNotAvailable() {
        assertThat(ThermalStepDown.transitionLine(ThermalStepDown.State.HOT, "SEVERE", Float.NaN))
            .isEqualTo("thermal: HOT (status SEVERE, headroom n/a), using the small model")
    }
}
