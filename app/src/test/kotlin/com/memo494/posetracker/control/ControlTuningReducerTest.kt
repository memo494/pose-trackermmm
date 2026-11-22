package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import org.junit.Test

class ControlTuningReducerTest {
    private val ready = ControlState(overlayPermission = true)

    private fun reduce(
        state: ControlState,
        event: ControlEvent,
    ): ControlStep = ControlReducer.reduce(state, event, notificationPermissionMissing = false)

    @Test
    fun default_hasNoOverrides() {
        assertThat(ControlState().tuning).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun tuningChanged_updatesTheStateWithoutEffectWhileTheSliderMoves() {
        val step = reduce(ready, ControlEvent.TuningChanged(TuningKey.SCORE_THRESHOLD, 0.3f))

        assertThat(step.state.tuning.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.3f)
        assertThat(step.state.tuningTouched).isTrue()
        assertThat(step.effect).isNull()
    }

    @Test
    fun tuningChanged_clampsToTheKeysBounds() {
        val step = reduce(ready, ControlEvent.TuningChanged(TuningKey.RELOCK_MAX_DISTANCE, 50f))

        assertThat(step.state.tuning.valueOf(TuningKey.RELOCK_MAX_DISTANCE)).isEqualTo(TuningKey.RELOCK_MAX_DISTANCE.max)
    }

    @Test
    fun tuningChanged_aNotANumberLeavesTheOverrides() {
        val before = reduce(ready, ControlEvent.TuningChanged(TuningKey.SMOOTH_BETA, 7f)).state

        val step = reduce(before, ControlEvent.TuningChanged(TuningKey.SMOOTH_BETA, Float.NaN))

        assertThat(step.state.tuning).isEqualTo(before.tuning)
    }

    @Test
    fun tuningChanged_isAllowedWhileRunningAndLeavesTheRestAlone() {
        val running = ready.copy(running = true, debugMarks = true)

        val step = reduce(running, ControlEvent.TuningChanged(TuningKey.TRACK_MAX_LOST_MS, 1500f))

        assertThat(step.state).isEqualTo(running.copy(tuning = step.state.tuning, tuningTouched = true))
        assertThat(step.state.tuning.valueOf(TuningKey.TRACK_MAX_LOST_MS)).isEqualTo(1500f)
    }

    @Test
    fun tuningCommitted_asksToPersistTheCurrentOverrides() {
        val moved = reduce(ready, ControlEvent.TuningChanged(TuningKey.SMOOTH_BETA, 7f)).state

        val step = reduce(moved, ControlEvent.TuningCommitted)

        assertThat(step.effect).isEqualTo(ControlEffect.PERSIST_TUNING)
        assertThat(step.tuning).isEqualTo(moved.tuning)
        assertThat(step.state).isEqualTo(moved)
    }

    @Test
    fun resetClicked_clearsEveryOverrideAndAsksToPersistTheEmptySet() {
        val moved =
            reduce(ready, ControlEvent.TuningChanged(TuningKey.SMOOTH_BETA, 7f))
                .state
                .let { reduce(it, ControlEvent.TuningChanged(TuningKey.SCORE_THRESHOLD, 0.4f)).state }

        val step = reduce(moved, ControlEvent.TuningResetClicked)

        assertThat(step.state.tuning).isEqualTo(TuningOverrides.NONE)
        assertThat(step.state.tuningTouched).isTrue()
        assertThat(step.effect).isEqualTo(ControlEffect.PERSIST_TUNING)
        assertThat(step.tuning).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun tuningLoaded_appliesTheSavedOverridesWithoutEffect() {
        val saved = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.4f)

        val step = reduce(ready, ControlEvent.TuningLoaded(saved))

        assertThat(step.state.tuning).isEqualTo(saved)
        assertThat(step.effect).isNull()
        assertThat(step.tuning).isNull()
    }

    @Test
    fun tuningLoadedAfterTheUserMovedASlider_isDropped() {
        val moved = reduce(ready, ControlEvent.TuningChanged(TuningKey.SMOOTH_BETA, 7f)).state

        val step = reduce(moved, ControlEvent.TuningLoaded(TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.4f)))

        assertThat(step.state).isEqualTo(moved)
    }

    @Test
    fun tuningLoadedAfterAReset_isDropped() {
        val reset = reduce(ready, ControlEvent.TuningResetClicked).state

        val step = reduce(reset, ControlEvent.TuningLoaded(TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.4f)))

        assertThat(step.state.tuning).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun startService_carriesTheOverridesOfThatMoment() {
        val moved = reduce(ready, ControlEvent.TuningChanged(TuningKey.RELOCK_MIN_MARGIN, 0.3f)).state

        val step = reduce(moved, ControlEvent.ConsentResult(true))

        assertThat(step.effect).isEqualTo(ControlEffect.START_SERVICE)
        assertThat(step.tuning).isEqualTo(moved.tuning)
    }

    @Test
    fun startService_withNoOverridesCarriesNone() {
        val step = reduce(ready, ControlEvent.ConsentResult(true))

        assertThat(step.tuning).isEqualTo(TuningOverrides.NONE)
    }
}
