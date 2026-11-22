package com.memo494.posetracker.core.stats

import com.memo494.posetracker.core.TrackerConfig
import java.util.Locale

/**
 * The thermal step-down policy (ARCHITECTURE §7.2): decides from the sampled thermal readings whether the device is
 * hot enough to run the small model. Pure and allocation-free; thread-confined (the capture thread, once per new
 * [ThermalSampler] reading).
 *
 * - NORMAL to HOT: the platform status is at least `MODERATE` ([TrackerConfig.THERMAL_STATUS_HOT_MIN]) or the headroom
 *   is at least [TrackerConfig.thermalHotHeadroom], and it stays so for [TrackerConfig.thermalHotConfirmMs].
 * - HOT to NORMAL: every reading that is known is cool (status at most `LIGHT`, headroom at most
 *   [TrackerConfig.thermalCoolHeadroom]), at least one is known, and it stays so for [TrackerConfig.thermalCoolDownMs].
 * - Any reading that is neither (and an unavailable one) restarts the running timer, so the state never flaps and an
 *   unreadable device keeps the current model (fail closed): unavailable readings never trigger HOT and never end it.
 *
 * @param config the thresholds and times.
 */
class ThermalStepDown(
    private val config: TrackerConfig,
) {
    /** The two states of the policy. */
    enum class State { NORMAL, HOT }

    /** The current state; starts as [State.NORMAL]. */
    var state: State = State.NORMAL
        private set

    /** How many times the policy went from NORMAL to HOT. */
    var stepDowns: Int = 0
        private set

    private var candidateSinceNs = NONE
    private var hotTotalNs = 0L
    private var hotSinceNs = 0L

    /**
     * Feeds one new reading taken at [nowNs] (monotonic nanoseconds): [statusCode] is `PowerManager.THERMAL_STATUS_*`
     * or [ThermalReader.UNAVAILABLE], [headroom] is a finite non-negative ratio or NaN. Returns `true` when [state]
     * changed with this reading.
     */
    fun update(
        statusCode: Int,
        headroom: Float,
        nowNs: Long,
    ): Boolean {
        val statusKnown = statusCode in 0..TrackerConfig.THERMAL_STATUS_MAX
        val headroomKnown = headroom.isFinite() && headroom >= 0f
        return if (state == State.NORMAL) {
            val hot =
                (statusKnown && statusCode >= TrackerConfig.THERMAL_STATUS_HOT_MIN) ||
                    (headroomKnown && headroom >= config.thermalHotHeadroom)
            held(hot, nowNs, config.thermalHotConfirmNs) && change(State.HOT, nowNs)
        } else {
            val cool =
                (statusKnown || headroomKnown) &&
                    (!statusKnown || statusCode <= TrackerConfig.THERMAL_STATUS_COOL_MAX) &&
                    (!headroomKnown || headroom <= config.thermalCoolHeadroom)
            held(cool, nowNs, config.thermalCoolDownNs) && change(State.NORMAL, nowNs)
        }
    }

    /** The time spent in HOT so far, in milliseconds, up to [nowNs] (the running stretch included). */
    fun hotMs(nowNs: Long): Long {
        val running = if (state == State.HOT) nowNs - hotSinceNs else 0L
        return (hotTotalNs + running) / TrackerConfig.NANOS_PER_MILLI
    }

    /** Whether [condition] has held for [neededNs] up to [nowNs]; a false condition restarts the timer. */
    private fun held(
        condition: Boolean,
        nowNs: Long,
        neededNs: Long,
    ): Boolean {
        if (!condition) {
            candidateSinceNs = NONE
            return false
        }
        if (candidateSinceNs == NONE) candidateSinceNs = nowNs
        return nowNs - candidateSinceNs >= neededNs
    }

    private fun change(
        to: State,
        nowNs: Long,
    ): Boolean {
        candidateSinceNs = NONE
        if (to == State.HOT) {
            stepDowns++
            hotSinceNs = nowNs
        } else {
            hotTotalNs += nowNs - hotSinceNs
        }
        state = to
        return true
    }

    companion object {
        private const val HEADROOM_DECIMALS = 2

        /**
         * The one log line of a transition into [state], with the [statusName] (`ThermalSampler.status`) and the
         * [headroom] (NaN = `n/a`) that caused it. Allocates; called on a transition only.
         */
        fun transitionLine(
            state: State,
            statusName: String,
            headroom: Float,
        ): String {
            val headroomText =
                if (headroom.isFinite()) {
                    String.format(
                        Locale.ROOT,
                        "%.${HEADROOM_DECIMALS}f",
                        headroom,
                    )
                } else {
                    ThermalStatusText.NOT_AVAILABLE
                }
            val action = if (state == State.HOT) "using the small model" else "using the normal model again"
            return "thermal: $state (status $statusName, headroom $headroomText), $action"
        }

        /** No timer is running. A clock value is never this (monotonic nanoseconds do not go negative). */
        const val NONE = Long.MIN_VALUE
    }
}
