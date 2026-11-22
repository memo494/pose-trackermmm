package com.memo494.posetracker.core.tuning

import com.memo494.posetracker.core.TrackerConfig

/**
 * What [TuningOverrides.applyTo] made: the [config], how many overrides it applied ([applied]) and the keys the config
 * refused, which stay at their defaults ([skipped]; empty for values inside the sheet bounds).
 */
data class TuningResult(
    val config: TrackerConfig,
    val applied: Int,
    val skipped: List<TuningKey> = emptyList(),
)

/**
 * The values the owner moved on the tuning sheet, as an immutable map of key to value. A key at its default is not
 * an override. Every value is inside its key's bounds: [with] clamps and [decode] ignores what is outside (fail
 * closed: a file written by an older version, or by hand, can never put a value in the config that it refuses).
 *
 * The saved text is `posetracker-tuning v1`, then one `id=value` line per override. Pure: no files, no Android.
 */
class TuningOverrides private constructor(
    private val values: Map<TuningKey, Float>,
) {
    /** The number of overrides. */
    val count: Int get() = values.size

    /** The value of [key]: the override, or the [TuningKey.defaultValue] when there is none. */
    fun valueOf(key: TuningKey): Float = values[key] ?: key.defaultValue

    /**
     * These overrides with [key] set to [value], clamped to its bounds; a [value] equal to the default removes the
     * override; a value that is not a number is ignored (the overrides stay as they are).
     */
    fun with(
        key: TuningKey,
        value: Float,
    ): TuningOverrides {
        if (!value.isFinite()) return this
        val next = values.toMutableMap()
        val clamped = key.clamp(value)
        if (clamped == key.defaultValue) next.remove(key) else next[key] = clamped
        return TuningOverrides(next)
    }

    /** No override at all ("Reset to defaults"). */
    fun reset(): TuningOverrides = NONE

    /**
     * [base] with every override applied, and the number applied. An override the config would refuse is skipped
     * (it cannot happen for a value inside its bounds, but the config has the last word).
     */
    fun applyTo(base: TrackerConfig = TrackerConfig()): TuningResult {
        var config = base
        var applied = 0
        val skipped = mutableListOf<TuningKey>()
        for ((key, value) in values) {
            try {
                config = key.write(config, value)
                applied++
            } catch (ignored: IllegalArgumentException) {
                // Fail closed: this knob stays at its default.
                skipped += key
            }
        }
        return TuningResult(config, applied, skipped)
    }

    /** The saved text. */
    fun encode(): String {
        val out = StringBuilder(HEADER).append('\n')
        for (key in TuningKey.entries) {
            val value = values[key] ?: continue
            out
                .append(key.id)
                .append('=')
                .append(format(key, value))
                .append('\n')
        }
        return out.toString()
    }

    /** `id=value, id=value` for the log and the report; empty when there is no override. */
    fun summaryText(): String =
        TuningKey.entries.filter { it in values }.joinToString(", ") { "${it.id}=${format(it, values.getValue(it))}" }

    override fun equals(other: Any?): Boolean = other is TuningOverrides && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "TuningOverrides(${summaryText()})"

    companion object {
        /** First line of the saved text. */
        const val HEADER = "posetracker-tuning v1"

        private const val SEPARATOR = '='

        /** No override. */
        val NONE = TuningOverrides(emptyMap())

        /**
         * The overrides in [text], or `null` when [text] is not a tuning file (wrong first line): the caller uses the
         * defaults and logs it once. Inside a readable file an unknown key, a line that is not `id=value`, a value
         * that is not a number, outside the key's current bounds or fractional for a whole-number key is ignored, and
         * the last line of a key wins.
         */
        fun decode(text: String): TuningOverrides? {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != HEADER) return null
            var result = NONE
            for (line in lines.drop(1)) {
                val at = line.indexOf(SEPARATOR)
                if (at <= 0) continue
                val key = TuningKey.byId(line.substring(0, at).trim()) ?: continue
                val value = line.substring(at + 1).trim().toFloatOrNull() ?: continue
                if (key.isValid(value)) result = result.with(key, value)
            }
            return result
        }

        private fun format(
            key: TuningKey,
            value: Float,
        ): String = if (key.integer) value.toInt().toString() else value.toString()
    }
}
