package com.memo494.posetracker.tuning

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.core.tuning.TuningResult

/** Pure: the session config from the text the Start Intent carries (no Android types, so it is tested on the JVM). */
internal object TuningSession {
    /** The overrides in [extra]; a missing or unreadable text (garbage, wrong header) is no override. */
    fun overridesOf(extra: String?): TuningOverrides = extra?.let(TuningOverrides::decode) ?: TuningOverrides.NONE

    /** [base] with the overrides in [extra] applied. */
    fun resolve(
        base: TrackerConfig,
        extra: String?,
    ): TuningResult = overridesOf(extra).applyTo(base)
}
