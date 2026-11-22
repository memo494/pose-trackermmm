package com.memo494.posetracker.tuning

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.tuning.TuningGroup
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import org.junit.Test

class TuningSheetMapperTest {
    @Test
    fun valueText_showsTheValueWithItsUnitAndTheDecimalsOfTheStep() {
        assertThat(TuningSheetMapper.valueText(TuningKey.SCORE_THRESHOLD, 0.15f)).isEqualTo("0.15")
        assertThat(TuningSheetMapper.valueText(TuningKey.SMOOTH_MIN_CUTOFF_HZ, 1f)).isEqualTo("1.00 Hz")
        assertThat(TuningSheetMapper.valueText(TuningKey.SMOOTH_BETA, 3f)).isEqualTo("3.0")
        assertThat(TuningSheetMapper.valueText(TuningKey.TRACK_MAX_LOST_MS, 800f)).isEqualTo("800 ms")
        assertThat(TuningSheetMapper.valueText(TuningKey.RELOCK_JUDGE_FRAMES, 10f)).isEqualTo("10 frames")
    }

    @Test
    fun valueText_usesADotWhateverTheLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertThat(TuningSheetMapper.valueText(TuningKey.SCORE_THRESHOLD, 0.15f)).isEqualTo("0.15")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun defaultText_namesTheDefaultWithItsUnit() {
        assertThat(TuningSheetMapper.defaultText(TuningKey.TRACK_MAX_LOST_MS)).isEqualTo("default 800 ms")
        assertThat(TuningSheetMapper.defaultText(TuningKey.SCORE_THRESHOLD)).isEqualTo("default 0.15")
    }

    @Test
    fun rows_haveOneRowPerKeyInGroupOrderWithDefaultsWhenNothingIsOverridden() {
        val rows = TuningSheetMapper.rows(TuningOverrides.NONE)

        assertThat(rows.map { it.key }).containsExactlyElementsIn(TuningKey.entries)
        assertThat(rows.none { it.overridden }).isTrue()
        assertThat(rows.map { it.key.group }).isInOrder(compareBy<TuningGroup> { it.ordinal })
        for (row in rows) assertThat(row.sliderValue).isEqualTo(row.key.defaultValue)
    }

    @Test
    fun rows_markAnOverriddenRowAndShowItsValue() {
        val rows = TuningSheetMapper.rows(TuningOverrides.NONE.with(TuningKey.TRACK_MAX_LOST_MS, 1500f))

        val row = rows.single { it.key == TuningKey.TRACK_MAX_LOST_MS }
        assertThat(row.overridden).isTrue()
        assertThat(row.sliderValue).isEqualTo(1500f)
        assertThat(row.valueText).isEqualTo("1500 ms")
        assertThat(row.defaultText).isEqualTo("default 800 ms")
        assertThat(rows.count { it.overridden }).isEqualTo(1)
    }

    @Test
    fun snap_roundsToTheStepAndStaysInRange() {
        assertThat(TuningSheetMapper.snap(TuningKey.TRACK_MAX_LOST_MS, 1234f)).isEqualTo(1250f)
        assertThat(TuningSheetMapper.snap(TuningKey.TRACK_MAX_LOST_MS, 1e9f)).isEqualTo(TuningKey.TRACK_MAX_LOST_MS.max)
        assertThat(TuningSheetMapper.snap(TuningKey.TRACK_MAX_LOST_MS, 3f)).isEqualTo(TuningKey.TRACK_MAX_LOST_MS.min)
        assertThat(TuningSheetMapper.snap(TuningKey.SCORE_THRESHOLD, -3f)).isEqualTo(TuningKey.SCORE_THRESHOLD.min)
        assertThat(TuningSheetMapper.snap(TuningKey.RELOCK_JUDGE_FRAMES, 12.4f)).isEqualTo(12f)
    }

    @Test
    fun snap_aDefaultOnTheGridComesBackEqualToTheDefault() {
        for (key in TuningKey.entries) {
            assertThat(TuningSheetMapper.snap(key, key.defaultValue)).isEqualTo(key.defaultValue)
        }
    }

    @Test
    fun lockWarning_isOnlyForTheLockGroup() {
        assertThat(TuningSheetMapper.hasSafetyWarning(TuningGroup.LOCK)).isTrue()
        assertThat(TuningSheetMapper.hasSafetyWarning(TuningGroup.DETECTION)).isFalse()
        assertThat(TuningSheetMapper.hasSafetyWarning(TuningGroup.SMOOTHING)).isFalse()
        assertThat(TuningSheetMapper.hasSafetyWarning(TuningGroup.TRACKING)).isFalse()
    }
}
