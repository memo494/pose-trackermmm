package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.overlay.LockArming

/** What [LockController] changes on screen; implemented by [OverlayWindow]. Main thread. */
internal interface LockUi {
    /** Makes the overlay window touchable (armed) or not touchable (the normal state: taps reach the app below). */
    fun setOverlayTouchable(touchable: Boolean)

    /** Shows or hides the "tap a person" hint and the armed look of the handle. */
    fun showArmed(armed: Boolean)

    /** Calls [LockController.onTimeout] after [delayMs] milliseconds, replacing an earlier schedule. */
    fun scheduleTimeout(delayMs: Long)

    /** Cancels the scheduled timeout, if any. */
    fun cancelTimeout()
}

/**
 * The flow of tap-to-lock (docs/plans/M4.md, M4b T2; ARCHITECTURE §6.6), free of `android.*` so it can be tested:
 * a tap on the handle unlocks when a person is locked, else arms the overlay for one tap (it becomes touchable, the
 * hint shows, a timeout starts); a second tap on the handle while armed cancels. The next tap on the overlay,
 * mapped to a track id by the view (`LockPicker`), locks that track (an id of -1 locks nothing), and the overlay is
 * not touchable again, as it is after the timeout and when the session stops. Main thread only.
 *
 * @param arming the armed state with its timeout.
 * @param timeoutMs the timeout handed to [LockUi.scheduleTimeout] (`TrackerConfig.lockArmTimeoutMs`).
 * @param clockNs `System.nanoTime()` source, the clock of [arming].
 * @param target where a lock or unlock goes.
 * @param ui what the flow changes on screen.
 */
internal class LockController(
    private val arming: LockArming,
    private val timeoutMs: Long,
    private val clockNs: () -> Long,
    private val target: LockTarget,
    private val ui: LockUi,
) {
    private var uiArmed = false

    /** The tap on the handle. */
    fun onHandleTap() {
        if (target.hasLock) {
            endArming()
            target.unlock()
            return
        }
        if (uiArmed) {
            endArming()
            return
        }
        arming.arm(clockNs())
        uiArmed = true
        ui.setOverlayTouchable(true)
        ui.showArmed(true)
        ui.scheduleTimeout(timeoutMs)
    }

    /**
     * The one tap on the overlay while armed; [id] is the track the view picked at the tap, or -1 for nobody. A tap
     * that arrives after the timeout elapsed (its runnable did not run yet) or when not armed locks nothing.
     */
    fun onArmedTap(id: Int) {
        if (!uiArmed) return
        val counts = arming.isArmed(clockNs()) && arming.consumeTap()
        endArming()
        if (counts && id >= 0) target.lock(id)
    }

    /** The scheduled timeout ran: the overlay is not touchable again. */
    fun onTimeout() {
        endArming()
    }

    /** The session ends: leave nothing armed or scheduled. */
    fun stop() {
        endArming()
    }

    private fun endArming() {
        arming.disarm()
        if (!uiArmed) return
        uiArmed = false
        ui.cancelTimeout()
        ui.setOverlayTouchable(false)
        ui.showArmed(false)
    }
}
