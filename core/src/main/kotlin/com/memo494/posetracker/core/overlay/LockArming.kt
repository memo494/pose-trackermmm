package com.memo494.posetracker.core.overlay

/**
 * The "armed" state of tap-to-lock (docs/plans/M4.md, M4b T2; ARCHITECTURE §6.6): after the handle was tapped the
 * overlay waits for exactly one tap on a person, for at most [timeoutNs]. Pure; the clock is injected by the
 * caller (`System.nanoTime()` values). Main thread only, like the window code that uses it.
 *
 * @param timeoutNs how long an arm lasts, nanoseconds (`TrackerConfig.lockArmTimeoutNs`); positive.
 */
class LockArming(
    private val timeoutNs: Long,
) {
    private var armed = false
    private var armedAtNs = 0L

    init {
        require(timeoutNs > 0L) { "timeoutNs $timeoutNs must be positive" }
    }

    /** Starts waiting for a tap at [nowNs]; arming again restarts the timeout. */
    fun arm(nowNs: Long) {
        armed = true
        armedAtNs = nowNs
    }

    /**
     * Whether a tap at [nowNs] would still count: armed and less than the timeout since [arm]. Finding the arm
     * expired ends it (later calls and [consumeTap] then answer false without a new [arm]).
     */
    fun isArmed(nowNs: Long): Boolean {
        if (armed && nowNs - armedAtNs >= timeoutNs) armed = false
        return armed
    }

    /** Uses the one tap: `true` when it was armed (it is then not armed any more), `false` otherwise. */
    fun consumeTap(): Boolean {
        val wasArmed = armed
        armed = false
        return wasArmed
    }

    /** Ends the armed state without a tap (timeout of the window code, session stop, a second tap on the handle). */
    fun disarm() {
        armed = false
    }
}
