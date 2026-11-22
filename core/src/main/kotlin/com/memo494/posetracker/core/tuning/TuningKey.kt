package com.memo494.posetracker.core.tuning

import com.memo494.posetracker.core.TrackerConfig

/** How the sheet draws a [TuningKey]: a slider over its range, or an on/off switch (the values `min` = off and `max` = on). */
enum class TuningKind {
    SLIDER,
    SWITCH,
}

/** Where a [TuningKey] is shown on the tuning sheet. */
enum class TuningGroup {
    DETECTION,
    SMOOTHING,
    TRACKING,
    LOCK,
    LEAD,
}

/**
 * One slider of the tuning sheet (ARCHITECTURE §7.1): the number it changes in [TrackerConfig], its bounds (the
 * `MIN_*` / `MAX_*` or the sheet-only `TUNING_*` constants of [TrackerConfig], inside what the config accepts), its
 * step, and how to read and write it. A write may move a coupled number with it (`relockJudgeFrames` 1..100 raises
 * `relockJudgeMaxFrames`; `displayLeadSpeedLowHps` raises `displayLeadSpeedHighHps`) so the config's own rules hold. **Adding a slider is adding one entry here** (and, for a new [group], its title
 * in the strings).
 *
 * @property id the key in the saved file; never reused for another meaning (an old file may still hold it).
 * @property label the English name shown on the sheet.
 * @property unit the unit shown after the value (empty = unitless).
 * @property kind how the sheet draws it ([TuningKind.SWITCH]: off at `min`, on at `max`).
 * @property integer `true` for a whole-number setting (count): the value is rounded to a whole number.
 */
enum class TuningKey(
    val id: String,
    val group: TuningGroup,
    val label: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val integer: Boolean,
    private val read: (TrackerConfig) -> Float,
    internal val write: (TrackerConfig, Float) -> TrackerConfig,
    val kind: TuningKind = TuningKind.SLIDER,
) {
    SCORE_THRESHOLD(
        "scoreThreshold",
        TuningGroup.DETECTION,
        "Score threshold",
        "",
        TrackerConfig.MIN_SCORE_THRESHOLD,
        TrackerConfig.MAX_SCORE_THRESHOLD,
        TrackerConfig.TUNING_STEP_SCORE,
        false,
        { it.scoreThreshold },
        { c, v -> c.copy(scoreThreshold = v) },
    ),
    SMOOTH_MIN_CUTOFF_HZ(
        "smoothMinCutoffHz",
        TuningGroup.SMOOTHING,
        "Smoothing at rest (min cutoff)",
        "Hz",
        TrackerConfig.TUNING_MIN_SMOOTH_MIN_CUTOFF_HZ,
        TrackerConfig.TUNING_MAX_SMOOTH_MIN_CUTOFF_HZ,
        TrackerConfig.TUNING_STEP_CUTOFF_HZ,
        false,
        { it.smoothMinCutoffHz },
        { c, v -> c.copy(smoothMinCutoffHz = v) },
    ),
    SMOOTH_BETA(
        "smoothBeta",
        TuningGroup.SMOOTHING,
        "Smoothing speed gain (beta)",
        "",
        TrackerConfig.TUNING_MIN_SMOOTH_BETA,
        TrackerConfig.TUNING_MAX_SMOOTH_BETA,
        TrackerConfig.TUNING_STEP_BETA,
        false,
        { it.smoothBeta },
        { c, v -> c.copy(smoothBeta = v) },
    ),
    TRACK_MAX_LOST_MS(
        "trackMaxLostMs",
        TuningGroup.TRACKING,
        "Longest time a person may be lost",
        "ms",
        TrackerConfig.TUNING_MIN_TRACK_MAX_LOST_MS,
        TrackerConfig.TUNING_MAX_TRACK_MAX_LOST_MS,
        TrackerConfig.TUNING_STEP_LOST_MS,
        false,
        { it.trackMaxLostMs },
        { c, v -> c.copy(trackMaxLostMs = v) },
    ),
    TRACK_MATCH_MAX_COST(
        "trackMatchMaxCost",
        TuningGroup.TRACKING,
        "Match cost limit",
        "",
        TrackerConfig.MIN_TRACK_MATCH_MAX_COST,
        TrackerConfig.MAX_TRACK_MATCH_MAX_COST,
        TrackerConfig.TUNING_STEP_COST,
        false,
        { it.trackMatchMaxCost },
        { c, v -> c.copy(trackMatchMaxCost = v) },
    ),
    TRACK_LOW_MATCH_MAX_COST(
        "trackLowMatchMaxCost",
        TuningGroup.TRACKING,
        "Match cost limit, low-score detections",
        "",
        TrackerConfig.MIN_TRACK_MATCH_MAX_COST,
        TrackerConfig.MAX_TRACK_MATCH_MAX_COST,
        TrackerConfig.TUNING_STEP_COST,
        false,
        { it.trackLowMatchMaxCost },
        { c, v -> c.copy(trackLowMatchMaxCost = v) },
    ),
    RELOCK_MAX_DISTANCE(
        "relockMaxDistance",
        TuningGroup.LOCK,
        "Re-lock colour distance limit",
        "",
        TrackerConfig.MIN_RELOCK_MAX_DISTANCE,
        TrackerConfig.TUNING_MAX_RELOCK_MAX_DISTANCE,
        TrackerConfig.TUNING_STEP_RELOCK,
        false,
        { it.relockMaxDistance },
        { c, v -> c.copy(relockMaxDistance = v) },
    ),
    RELOCK_MIN_MARGIN(
        "relockMinMargin",
        TuningGroup.LOCK,
        "Re-lock margin over other people",
        "",
        TrackerConfig.TUNING_MIN_RELOCK_MIN_MARGIN,
        TrackerConfig.MAX_RELOCK_MIN_MARGIN,
        TrackerConfig.TUNING_STEP_RELOCK,
        false,
        { it.relockMinMargin },
        { c, v -> c.copy(relockMinMargin = v) },
    ),
    RELOCK_JUDGE_FRAMES(
        "relockJudgeFrames",
        TuningGroup.LOCK,
        "Frames judged before a re-lock",
        "frames",
        TrackerConfig.MIN_RELOCK_JUDGE_FRAMES.toFloat(),
        TrackerConfig.MAX_RELOCK_JUDGE_FRAMES.toFloat(),
        TrackerConfig.TUNING_STEP_FRAMES,
        true,
        { it.relockJudgeFrames.toFloat() },
        // The frames needed may not exceed the frames allowed in all (a TrackerConfig rule): raise the latter with it.
        { c, v ->
            val frames = v.toInt()
            c.copy(relockJudgeFrames = frames, relockJudgeMaxFrames = maxOf(c.relockJudgeMaxFrames, frames))
        },
    ),
    DISPLAY_LEAD_ENABLED(
        "displayLeadEnabled",
        TuningGroup.LEAD,
        "Ring lead",
        "",
        TrackerConfig.TUNING_SWITCH_OFF,
        TrackerConfig.TUNING_SWITCH_ON,
        TrackerConfig.TUNING_SWITCH_ON,
        true,
        { if (it.displayLeadEnabled) TrackerConfig.TUNING_SWITCH_ON else TrackerConfig.TUNING_SWITCH_OFF },
        { c, v -> c.copy(displayLeadEnabled = v >= TrackerConfig.TUNING_SWITCH_ON) },
        kind = TuningKind.SWITCH,
    ),
    DISPLAY_LEAD_MAX_MS(
        "displayLeadMaxMs",
        TuningGroup.LEAD,
        "Longest lead time",
        "ms",
        TrackerConfig.MIN_DISPLAY_LEAD_MAX_MS,
        TrackerConfig.MAX_DISPLAY_LEAD_MAX_MS,
        TrackerConfig.TUNING_STEP_LEAD_MS,
        false,
        { it.displayLeadMaxMs },
        { c, v -> c.copy(displayLeadMaxMs = v) },
    ),
    DISPLAY_LEAD_MAX_RATIO(
        "displayLeadMaxRatio",
        TuningGroup.LEAD,
        "Longest lead (share of the person's height)",
        "",
        TrackerConfig.MIN_DISPLAY_LEAD_MAX_RATIO,
        TrackerConfig.MAX_DISPLAY_LEAD_MAX_RATIO,
        TrackerConfig.TUNING_STEP_LEAD_RATIO,
        false,
        { it.displayLeadMaxRatio },
        { c, v -> c.copy(displayLeadMaxRatio = v) },
    ),
    DISPLAY_LEAD_SPEED_LOW_HPS(
        "displayLeadSpeedLowHps",
        TuningGroup.LEAD,
        "Speed below which there is no lead",
        "heights/s",
        TrackerConfig.MIN_DISPLAY_LEAD_SPEED_HPS,
        TrackerConfig.MAX_DISPLAY_LEAD_SPEED_HPS,
        TrackerConfig.TUNING_STEP_LEAD_SPEED,
        false,
        { it.displayLeadSpeedLowHps },
        // The speed for a full lead may not be below the speed for none (a TrackerConfig rule): raise the former with it.
        { c, v -> c.copy(displayLeadSpeedLowHps = v, displayLeadSpeedHighHps = maxOf(c.displayLeadSpeedHighHps, v)) },
    ),
    ;

    /** The value [TrackerConfig] has without any override. */
    val defaultValue: Float = read(TrackerConfig())

    /** [value] limited to [min]..[max] (and rounded for an [integer] key). NaN and infinities are not valid: check [isValid] first. */
    fun clamp(value: Float): Float {
        val limited = value.coerceIn(min, max)
        return if (integer) Math.round(limited).toFloat() else limited
    }

    /** `true` when [value] is finite, inside [min]..[max] and whole for an [integer] key: a value a saved file may hold. */
    fun isValid(value: Float): Boolean =
        value.isFinite() && value in min..max && (!integer || value == Math.rint(value.toDouble()).toFloat())

    internal companion object {
        /** The key with this saved [id], or `null` (an unknown key is ignored, never an error). */
        fun byId(id: String): TuningKey? = entries.firstOrNull { it.id == id }
    }
}
