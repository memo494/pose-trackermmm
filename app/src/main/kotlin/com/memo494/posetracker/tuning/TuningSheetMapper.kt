package com.memo494.posetracker.tuning

import com.memo494.posetracker.core.tuning.TuningGroup
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningKind
import com.memo494.posetracker.core.tuning.TuningOverrides
import java.math.BigDecimal
import java.util.Locale

/** One slider of the tuning sheet as the screen draws it. */
internal data class TuningRow(
    val key: TuningKey,
    val sliderValue: Float,
    val valueText: String,
    val defaultText: String,
    val overridden: Boolean,
)

/** Pure bridge from [TuningOverrides] to the rows of the tuning sheet: texts, snapping and the safety warning. */
internal object TuningSheetMapper {
    /** One row per [TuningKey], in the order of the keys (which are listed group by group). */
    fun rows(overrides: TuningOverrides): List<TuningRow> =
        TuningKey.entries.map { key ->
            val value = overrides.valueOf(key)
            TuningRow(
                key = key,
                sliderValue = value,
                valueText = valueText(key, value),
                defaultText = defaultText(key),
                overridden = value != key.defaultValue,
            )
        }

    /** [value] with the decimals of the key's step and its unit: `0.15`, `1.00 Hz`, `800 ms`. */
    fun valueText(
        key: TuningKey,
        value: Float,
    ): String {
        if (key.kind == TuningKind.SWITCH) return if (value >= key.max) "on" else "off"
        val number = String.format(Locale.ROOT, "%.${decimalsOf(key)}f", value)
        return if (key.unit.isEmpty()) number else "$number ${key.unit}"
    }

    /** `default 0.15` (with the unit, if any). */
    fun defaultText(key: TuningKey): String = "default " + valueText(key, key.defaultValue)

    /**
     * [raw] (a slider position) moved to the nearest multiple of the key's step and limited to its bounds. The product is
     * made in decimal so that a default on the grid comes back equal to the default (no `0.15000001`).
     */
    fun snap(
        key: TuningKey,
        raw: Float,
    ): Float {
        if (!raw.isFinite()) return key.defaultValue
        val steps = Math.round(raw / key.step).toLong()
        val snapped = BigDecimal.valueOf(steps).multiply(BigDecimal(key.step.toString())).toFloat()
        return key.clamp(snapped)
    }

    /** `true` for a group whose sliders can make the ring go to the wrong person: its note is shown under the group. */
    fun hasSafetyWarning(group: TuningGroup): Boolean = group == TuningGroup.LOCK

    private fun decimalsOf(key: TuningKey): Int = BigDecimal(key.step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
}
