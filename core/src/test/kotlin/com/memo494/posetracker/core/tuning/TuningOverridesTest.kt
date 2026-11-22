package com.memo494.posetracker.core.tuning

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class TuningOverridesTest {
    private val defaults = TrackerConfig()

    @Test
    fun keys_haveUniqueIdsAndDefaultsInsideTheirBounds() {
        val ids = TuningKey.entries.map { it.id }
        assertThat(ids).containsNoDuplicates()
        for (key in TuningKey.entries) {
            assertThat(key.defaultValue).isAtLeast(key.min)
            assertThat(key.defaultValue).isAtMost(key.max)
            assertThat(key.step).isGreaterThan(0f)
        }
    }

    @Test
    fun keys_areBoundedByTheTrackerConfigConstantsAndTheSheetBounds() {
        assertThat(TuningKey.SCORE_THRESHOLD.min).isEqualTo(TrackerConfig.MIN_SCORE_THRESHOLD)
        assertThat(TuningKey.SCORE_THRESHOLD.max).isEqualTo(TrackerConfig.MAX_SCORE_THRESHOLD)
        assertThat(TuningKey.SMOOTH_MIN_CUTOFF_HZ.min).isEqualTo(TrackerConfig.TUNING_MIN_SMOOTH_MIN_CUTOFF_HZ)
        assertThat(TuningKey.SMOOTH_MIN_CUTOFF_HZ.max).isEqualTo(TrackerConfig.TUNING_MAX_SMOOTH_MIN_CUTOFF_HZ)
        assertThat(TuningKey.SMOOTH_BETA.min).isEqualTo(TrackerConfig.TUNING_MIN_SMOOTH_BETA)
        assertThat(TuningKey.SMOOTH_BETA.max).isEqualTo(TrackerConfig.TUNING_MAX_SMOOTH_BETA)
        assertThat(TuningKey.TRACK_MAX_LOST_MS.min).isEqualTo(TrackerConfig.TUNING_MIN_TRACK_MAX_LOST_MS)
        assertThat(TuningKey.TRACK_MAX_LOST_MS.max).isEqualTo(TrackerConfig.TUNING_MAX_TRACK_MAX_LOST_MS)
        assertThat(TuningKey.TRACK_MATCH_MAX_COST.min).isEqualTo(TrackerConfig.MIN_TRACK_MATCH_MAX_COST)
        assertThat(TuningKey.TRACK_MATCH_MAX_COST.max).isEqualTo(TrackerConfig.MAX_TRACK_MATCH_MAX_COST)
        assertThat(TuningKey.TRACK_LOW_MATCH_MAX_COST.min).isEqualTo(TrackerConfig.MIN_TRACK_MATCH_MAX_COST)
        assertThat(TuningKey.TRACK_LOW_MATCH_MAX_COST.max).isEqualTo(TrackerConfig.MAX_TRACK_MATCH_MAX_COST)
        assertThat(TuningKey.RELOCK_MAX_DISTANCE.min).isEqualTo(TrackerConfig.MIN_RELOCK_MAX_DISTANCE)
        assertThat(TuningKey.RELOCK_MAX_DISTANCE.max).isEqualTo(TrackerConfig.TUNING_MAX_RELOCK_MAX_DISTANCE)
        assertThat(TuningKey.RELOCK_MIN_MARGIN.min).isEqualTo(TrackerConfig.TUNING_MIN_RELOCK_MIN_MARGIN)
        assertThat(TuningKey.RELOCK_MIN_MARGIN.max).isEqualTo(TrackerConfig.MAX_RELOCK_MIN_MARGIN)
        assertThat(TuningKey.RELOCK_JUDGE_FRAMES.min).isEqualTo(TrackerConfig.MIN_RELOCK_JUDGE_FRAMES.toFloat())
        assertThat(TuningKey.RELOCK_JUDGE_FRAMES.max).isEqualTo(TrackerConfig.MAX_RELOCK_JUDGE_FRAMES.toFloat())
    }

    @Test
    fun keys_defaultsAreTheTrackerConfigDefaults() {
        assertThat(TuningKey.SCORE_THRESHOLD.defaultValue).isEqualTo(defaults.scoreThreshold)
        assertThat(TuningKey.TRACK_MAX_LOST_MS.defaultValue).isEqualTo(defaults.trackMaxLostMs)
        assertThat(TuningKey.RELOCK_MAX_DISTANCE.defaultValue).isEqualTo(defaults.relockMaxDistance)
        assertThat(TuningKey.RELOCK_JUDGE_FRAMES.defaultValue).isEqualTo(defaults.relockJudgeFrames.toFloat())
    }

    @Test
    fun none_appliesNothingAndKeepsTheBaseConfig() {
        val base = TrackerConfig(debugMarks = true)

        val result = TuningOverrides.NONE.applyTo(base)

        assertThat(result.config).isEqualTo(base)
        assertThat(result.applied).isEqualTo(0)
    }

    @Test
    fun applyTo_setsEveryOverriddenField() {
        val overrides =
            TuningOverrides.NONE
                .with(TuningKey.SCORE_THRESHOLD, 0.3f)
                .with(TuningKey.SMOOTH_MIN_CUTOFF_HZ, 2f)
                .with(TuningKey.SMOOTH_BETA, 5f)
                .with(TuningKey.TRACK_MAX_LOST_MS, 1500f)
                .with(TuningKey.TRACK_MATCH_MAX_COST, 0.9f)
                .with(TuningKey.TRACK_LOW_MATCH_MAX_COST, 0.8f)
                .with(TuningKey.RELOCK_MAX_DISTANCE, 0.3f)
                .with(TuningKey.RELOCK_MIN_MARGIN, 0.2f)
                .with(TuningKey.RELOCK_JUDGE_FRAMES, 12f)

        val result = overrides.applyTo(TrackerConfig(debugMarks = true))

        assertThat(result.applied).isEqualTo(9)
        assertThat(result.config.scoreThreshold).isEqualTo(0.3f)
        assertThat(result.config.smoothMinCutoffHz).isEqualTo(2f)
        assertThat(result.config.smoothBeta).isEqualTo(5f)
        assertThat(result.config.trackMaxLostMs).isEqualTo(1500f)
        assertThat(result.config.trackMatchMaxCost).isEqualTo(0.9f)
        assertThat(result.config.trackLowMatchMaxCost).isEqualTo(0.8f)
        assertThat(result.config.relockMaxDistance).isEqualTo(0.3f)
        assertThat(result.config.relockMinMargin).isEqualTo(0.2f)
        assertThat(result.config.relockJudgeFrames).isEqualTo(12)
        assertThat(result.config.debugMarks).isTrue()
    }

    @Test
    fun with_clampsAValueAboveTheMaximumAndBelowTheMinimum() {
        val high = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 5f)
        val low = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, -1f)

        assertThat(high.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(TrackerConfig.MAX_SCORE_THRESHOLD)
        assertThat(low.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(TrackerConfig.MIN_SCORE_THRESHOLD)
    }

    @Test
    fun with_ignoresAValueThatIsNotANumber() {
        val base = TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 7f)

        assertThat(base.with(TuningKey.SMOOTH_BETA, Float.NaN)).isEqualTo(base)
        assertThat(base.with(TuningKey.SMOOTH_BETA, Float.POSITIVE_INFINITY)).isEqualTo(base)
    }

    @Test
    fun with_theDefaultValueIsNoOverride() {
        val overridden = TuningOverrides.NONE.with(TuningKey.TRACK_MAX_LOST_MS, 1200f)
        assertThat(overridden.count).isEqualTo(1)

        val back = overridden.with(TuningKey.TRACK_MAX_LOST_MS, TuningKey.TRACK_MAX_LOST_MS.defaultValue)

        assertThat(back.count).isEqualTo(0)
        assertThat(back).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun with_roundsAnIntegerKeyToAWholeNumber() {
        val overrides = TuningOverrides.NONE.with(TuningKey.RELOCK_JUDGE_FRAMES, 14.6f)

        assertThat(overrides.valueOf(TuningKey.RELOCK_JUDGE_FRAMES)).isEqualTo(15f)
        assertThat(overrides.applyTo(TrackerConfig()).config.relockJudgeFrames).isEqualTo(15)
    }

    @Test
    fun valueOf_isTheDefaultWhenNotOverridden() {
        assertThat(TuningOverrides.NONE.valueOf(TuningKey.RELOCK_MIN_MARGIN)).isEqualTo(defaults.relockMinMargin)
    }

    @Test
    fun reset_dropsEveryOverride() {
        val overrides =
            TuningOverrides.NONE
                .with(TuningKey.SCORE_THRESHOLD, 0.4f)
                .with(TuningKey.RELOCK_MAX_DISTANCE, 0.2f)

        val reset = overrides.reset()

        assertThat(reset.count).isEqualTo(0)
        assertThat(reset.applyTo(TrackerConfig()).config).isEqualTo(TrackerConfig())
    }

    @Test
    fun applyTo_aJudgeFrameCountAboveTheJudgeMaximumRaisesTheMaximum() {
        val overrides = TuningOverrides.NONE.with(TuningKey.RELOCK_JUDGE_FRAMES, 80f)

        val config = overrides.applyTo(TrackerConfig()).config

        assertThat(config.relockJudgeFrames).isEqualTo(80)
        assertThat(config.relockJudgeMaxFrames).isEqualTo(80)
    }

    @Test
    fun applyTo_aJudgeFrameCountBelowTheJudgeMaximumLeavesTheMaximum() {
        val config =
            TuningOverrides.NONE
                .with(TuningKey.RELOCK_JUDGE_FRAMES, 5f)
                .applyTo(TrackerConfig())
                .config

        assertThat(config.relockJudgeMaxFrames).isEqualTo(defaults.relockJudgeMaxFrames)
    }

    @Test
    fun encode_thenDecode_roundTrips() {
        val overrides =
            TuningOverrides.NONE
                .with(TuningKey.SCORE_THRESHOLD, 0.27f)
                .with(TuningKey.SMOOTH_BETA, 12.5f)
                .with(TuningKey.RELOCK_JUDGE_FRAMES, 20f)

        val decoded = TuningOverrides.decode(overrides.encode())

        assertThat(decoded).isEqualTo(overrides)
    }

    @Test
    fun encode_ofNoOverridesDecodesToNone() {
        assertThat(TuningOverrides.decode(TuningOverrides.NONE.encode())).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun decode_ignoresUnknownKeysAndKeepsTheKnownOnes() {
        val text = HEADER + "\nfutureKnob=3.0\nscoreThreshold=0.3\n"

        val decoded = TuningOverrides.decode(text)

        assertThat(decoded?.count).isEqualTo(1)
        assertThat(decoded?.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.3f)
    }

    @Test
    fun decode_ignoresAValueOutsideTheCurrentRange() {
        val text = HEADER + "\ntrackMaxLostMs=99999\nscoreThreshold=0.3\nrelockMaxDistance=-0.5\n"

        val decoded = TuningOverrides.decode(text)

        assertThat(decoded?.valueOf(TuningKey.TRACK_MAX_LOST_MS)).isEqualTo(defaults.trackMaxLostMs)
        assertThat(decoded?.valueOf(TuningKey.RELOCK_MAX_DISTANCE)).isEqualTo(defaults.relockMaxDistance)
        assertThat(decoded?.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.3f)
        assertThat(decoded?.count).isEqualTo(1)
    }

    @Test
    fun decode_ignoresMalformedValuesAndLines() {
        val text = HEADER + "\nscoreThreshold=abc\nsmoothBeta=NaN\nsmoothMinCutoffHz=Infinity\nnonsense\n=1\ntrackMaxLostMs=\n"

        val decoded = TuningOverrides.decode(text)

        assertThat(decoded).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun decode_ignoresAFractionalValueOfAnIntegerKey() {
        val decoded = TuningOverrides.decode(HEADER + "\nrelockJudgeFrames=12.5\n")

        assertThat(decoded).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun decode_aValueEqualToTheDefaultIsNoOverride() {
        val decoded = TuningOverrides.decode(HEADER + "\nscoreThreshold=${defaults.scoreThreshold}\n")

        assertThat(decoded).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun decode_theLastLineOfAKeyWins() {
        val decoded = TuningOverrides.decode(HEADER + "\nscoreThreshold=0.3\nscoreThreshold=0.4\n")

        assertThat(decoded?.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.4f)
    }

    @Test
    fun decode_aWrongOrMissingHeaderIsUnreadable() {
        assertThat(TuningOverrides.decode("scoreThreshold=0.3\n")).isNull()
        assertThat(TuningOverrides.decode("")).isNull()
        assertThat(TuningOverrides.decode("posetracker-tuning v999\nscoreThreshold=0.3\n")).isNull()
        assertThat(TuningOverrides.decode("\u0000\u0001garbage")).isNull()
    }

    @Test
    fun decode_toleratesWindowsLineEndingsAndBlankLines() {
        val decoded = TuningOverrides.decode(HEADER + "\r\n\r\nscoreThreshold=0.3\r\n")

        assertThat(decoded?.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.3f)
    }

    @Test
    fun leadKeys_areBoundedByTheTrackerConfigConstants() {
        assertThat(TuningKey.DISPLAY_LEAD_MAX_MS.min).isEqualTo(TrackerConfig.MIN_DISPLAY_LEAD_MAX_MS)
        assertThat(TuningKey.DISPLAY_LEAD_MAX_MS.max).isEqualTo(TrackerConfig.MAX_DISPLAY_LEAD_MAX_MS)
        assertThat(TuningKey.DISPLAY_LEAD_MAX_RATIO.min).isEqualTo(TrackerConfig.MIN_DISPLAY_LEAD_MAX_RATIO)
        assertThat(TuningKey.DISPLAY_LEAD_MAX_RATIO.max).isEqualTo(TrackerConfig.MAX_DISPLAY_LEAD_MAX_RATIO)
        assertThat(TuningKey.DISPLAY_LEAD_SPEED_LOW_HPS.min).isEqualTo(TrackerConfig.MIN_DISPLAY_LEAD_SPEED_HPS)
        assertThat(TuningKey.DISPLAY_LEAD_SPEED_LOW_HPS.max).isEqualTo(TrackerConfig.MAX_DISPLAY_LEAD_SPEED_HPS)
        assertThat(TuningKey.DISPLAY_LEAD_ENABLED.defaultValue).isEqualTo(1f)
    }

    @Test
    fun applyTo_setsTheLeadFields() {
        val overrides =
            TuningOverrides.NONE
                .with(TuningKey.DISPLAY_LEAD_ENABLED, 0f)
                .with(TuningKey.DISPLAY_LEAD_MAX_MS, 200f)
                .with(TuningKey.DISPLAY_LEAD_MAX_RATIO, 0.5f)
                .with(TuningKey.DISPLAY_LEAD_SPEED_LOW_HPS, 0.2f)

        val result = overrides.applyTo(TrackerConfig())

        assertThat(result.applied).isEqualTo(4)
        assertThat(result.config.displayLeadEnabled).isFalse()
        assertThat(result.config.displayLeadMaxMs).isEqualTo(200f)
        assertThat(result.config.displayLeadMaxRatio).isEqualTo(0.5f)
        assertThat(result.config.displayLeadSpeedLowHps).isEqualTo(0.2f)
    }

    @Test
    fun leadSwitch_clampsToZeroOrOneAndIsOnByDefault() {
        assertThat(TuningOverrides.NONE.with(TuningKey.DISPLAY_LEAD_ENABLED, 7f).count).isEqualTo(0)
        assertThat(
            TuningOverrides.NONE
                .with(TuningKey.DISPLAY_LEAD_ENABLED, -3f)
                .applyTo()
                .config.displayLeadEnabled,
        ).isFalse()
        assertThat(
            TuningOverrides.NONE
                .applyTo()
                .config.displayLeadEnabled,
        ).isTrue()
    }

    @Test
    fun leadValues_clampToTheirBounds() {
        val high = TuningOverrides.NONE.with(TuningKey.DISPLAY_LEAD_MAX_MS, 9999f).with(TuningKey.DISPLAY_LEAD_MAX_RATIO, 9f)

        assertThat(high.valueOf(TuningKey.DISPLAY_LEAD_MAX_MS)).isEqualTo(TrackerConfig.MAX_DISPLAY_LEAD_MAX_MS)
        assertThat(high.valueOf(TuningKey.DISPLAY_LEAD_MAX_RATIO)).isEqualTo(TrackerConfig.MAX_DISPLAY_LEAD_MAX_RATIO)
    }

    @Test
    fun leadSpeedLow_aboveTheHighSpeedRaisesTheHighSpeed() {
        val config =
            TuningOverrides.NONE
                .with(TuningKey.DISPLAY_LEAD_SPEED_LOW_HPS, 3f)
                .applyTo()
                .config

        assertThat(config.displayLeadSpeedLowHps).isEqualTo(3f)
        assertThat(config.displayLeadSpeedHighHps).isEqualTo(3f)
    }

    @Test
    fun leadKeys_roundTripAndIgnoreOutOfRange() {
        val overrides =
            TuningOverrides.NONE
                .with(TuningKey.DISPLAY_LEAD_ENABLED, 0f)
                .with(TuningKey.DISPLAY_LEAD_MAX_MS, 250f)
                .with(TuningKey.DISPLAY_LEAD_SPEED_LOW_HPS, 0.25f)

        assertThat(TuningOverrides.decode(overrides.encode())).isEqualTo(overrides)
        val decoded = TuningOverrides.decode(HEADER + "\ndisplayLeadMaxMs=5000\ndisplayLeadEnabled=2\n")
        assertThat(decoded).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun sheetBounds_areInsideTheRangeTheConfigAccepts() {
        assertThat(TrackerConfig.TUNING_MAX_RELOCK_MAX_DISTANCE).isAtMost(TrackerConfig.MAX_RELOCK_MAX_DISTANCE)
        assertThat(TrackerConfig.TUNING_MIN_RELOCK_MIN_MARGIN).isAtLeast(TrackerConfig.MIN_RELOCK_MIN_MARGIN)
        assertThat(TrackerConfig.TUNING_MIN_SMOOTH_MIN_CUTOFF_HZ).isAtLeast(TrackerConfig.MIN_SMOOTH_MIN_CUTOFF_HZ)
        assertThat(TrackerConfig.TUNING_MAX_SMOOTH_MIN_CUTOFF_HZ).isAtMost(TrackerConfig.MAX_SMOOTH_MIN_CUTOFF_HZ)
        assertThat(TrackerConfig.TUNING_MIN_SMOOTH_BETA).isAtLeast(TrackerConfig.MIN_SMOOTH_BETA)
        assertThat(TrackerConfig.TUNING_MAX_SMOOTH_BETA).isAtMost(TrackerConfig.MAX_SMOOTH_BETA)
        assertThat(TrackerConfig.TUNING_MIN_TRACK_MAX_LOST_MS).isAtLeast(TrackerConfig.MIN_TRACK_DURATION_MS)
        assertThat(TrackerConfig.TUNING_MAX_TRACK_MAX_LOST_MS).isAtMost(TrackerConfig.MAX_TRACK_DURATION_MS)
    }

    @Test
    fun decode_aValueInsideTheConfigRangeButOutsideTheSheetIsIgnored() {
        val text = HEADER + "\nrelockMaxDistance=0.9\nrelockMinMargin=0.01\ntrackMaxLostMs=5000\nsmoothBeta=50\nsmoothMinCutoffHz=20\n"

        assertThat(TuningOverrides.decode(text)).isEqualTo(TuningOverrides.NONE)
    }

    @Test
    fun applyTo_everyKeyAtItsSheetMinimumAndMaximumTogether_appliesAllInEitherOrder() {
        val orders = listOf(TuningKey.entries, TuningKey.entries.reversed())
        for (order in orders) {
            for (pick in listOf<(TuningKey) -> Float>({ it.min }, { it.max })) {
                val overrides = order.fold(TuningOverrides.NONE) { acc, key -> acc.with(key, pick(key)) }

                val result = overrides.applyTo(TrackerConfig())

                assertThat(result.skipped).isEmpty()
                assertThat(result.applied).isEqualTo(overrides.count)
            }
        }
    }

    @Test
    fun keys_aSwitchKeyIsOnlyTheLeadSwitchAndTheRestAreSliders() {
        assertThat(TuningKey.entries.filter { it.kind == TuningKind.SWITCH }).containsExactly(TuningKey.DISPLAY_LEAD_ENABLED)
    }

    @Test
    fun summaryText_listsOnlyOverriddenKeys() {
        val overrides = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.3f)

        assertThat(overrides.summaryText()).isEqualTo("scoreThreshold=0.3")
        assertThat(TuningOverrides.NONE.summaryText()).isEqualTo("")
    }

    private companion object {
        const val HEADER = TuningOverrides.HEADER
    }
}
