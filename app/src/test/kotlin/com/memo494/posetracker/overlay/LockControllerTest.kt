package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.overlay.LockArming
import org.junit.Test

private class FakeLockTarget : LockTarget {
    override var hasLock = false
    val locked = mutableListOf<Int>()
    var unlocks = 0

    override fun lock(id: Int) {
        locked.add(id)
        hasLock = true
    }

    override fun unlock() {
        unlocks++
        hasLock = false
    }
}

private class FakeLockUi : LockUi {
    var touchable = false
    var armedShown = false
    var scheduledMs = -1L
    var cancels = 0
    val touchableChanges = mutableListOf<Boolean>()

    override fun setOverlayTouchable(touchable: Boolean) {
        this.touchable = touchable
        touchableChanges.add(touchable)
    }

    override fun showArmed(armed: Boolean) {
        armedShown = armed
    }

    override fun scheduleTimeout(delayMs: Long) {
        scheduledMs = delayMs
    }

    override fun cancelTimeout() {
        cancels++
        scheduledMs = -1L
    }
}

class LockControllerTest {
    private val config = TrackerConfig()
    private var nowNs = 1_000_000_000L
    private val target = FakeLockTarget()
    private val ui = FakeLockUi()
    private val controller = LockController(LockArming(config.lockArmTimeoutNs), config.lockArmTimeoutMs, { nowNs }, target, ui)

    @Test
    fun theHandleTap_withoutALock_armsTheOverlayAndStartsTheTimeout() {
        controller.onHandleTap()

        assertThat(ui.touchable).isTrue()
        assertThat(ui.armedShown).isTrue()
        assertThat(ui.scheduledMs).isEqualTo(config.lockArmTimeoutMs)
        assertThat(target.locked).isEmpty()
    }

    @Test
    fun theHandleTap_withALock_unlocksAndDoesNotArm() {
        target.hasLock = true

        controller.onHandleTap()

        assertThat(target.unlocks).isEqualTo(1)
        assertThat(target.hasLock).isFalse()
        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
        assertThat(ui.touchableChanges).isEmpty()
    }

    @Test
    fun theHandleTap_whileArmed_cancelsTheArming() {
        controller.onHandleTap()

        controller.onHandleTap()

        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
        assertThat(ui.scheduledMs).isEqualTo(-1L)
        assertThat(target.locked).isEmpty()
    }

    @Test
    fun theTapOnAPerson_locksThatPerson_andTheOverlayIsNotTouchableAgain() {
        controller.onHandleTap()

        controller.onArmedTap(7)

        assertThat(target.locked).containsExactly(7)
        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
        assertThat(ui.scheduledMs).isEqualTo(-1L)
    }

    @Test
    fun theTapOnNobody_locksNothing_butStillEndsTheArming() {
        controller.onHandleTap()

        controller.onArmedTap(NO_PERSON)

        assertThat(target.locked).isEmpty()
        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
    }

    @Test
    fun aSecondTapAfterTheOne_isIgnored() {
        controller.onHandleTap()
        controller.onArmedTap(7)

        controller.onArmedTap(8)

        assertThat(target.locked).containsExactly(7)
    }

    @Test
    fun aTapWhenNeverArmed_locksNothing() {
        controller.onArmedTap(7)

        assertThat(target.locked).isEmpty()
        assertThat(ui.touchableChanges).isEmpty()
    }

    @Test
    fun theTimeout_endsTheArming() {
        controller.onHandleTap()

        controller.onTimeout()

        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
        assertThat(target.locked).isEmpty()
    }

    @Test
    fun aTapAfterTheTimeoutElapsedButBeforeItsRunnableRan_locksNothingAndEndsTheArming() {
        controller.onHandleTap()
        nowNs += config.lockArmTimeoutNs

        controller.onArmedTap(7)

        assertThat(target.locked).isEmpty()
        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
    }

    @Test
    fun aTapJustBeforeTheTimeout_stillLocks() {
        controller.onHandleTap()
        nowNs += config.lockArmTimeoutNs - 1L

        controller.onArmedTap(7)

        assertThat(target.locked).containsExactly(7)
    }

    @Test
    fun stop_whileArmed_endsTheArmingAndCancelsTheTimeout() {
        controller.onHandleTap()

        controller.stop()

        assertThat(ui.touchable).isFalse()
        assertThat(ui.armedShown).isFalse()
        assertThat(ui.scheduledMs).isEqualTo(-1L)
    }

    @Test
    fun stop_whenNotArmed_doesNotTouchTheWindow() {
        controller.stop()

        assertThat(ui.touchableChanges).isEmpty()
    }

    @Test
    fun afterAnUnlock_theNextHandleTapArmsAgain() {
        target.hasLock = true
        controller.onHandleTap()

        controller.onHandleTap()

        assertThat(ui.touchable).isTrue()
        assertThat(ui.armedShown).isTrue()
    }

    private companion object {
        const val NO_PERSON = -1
    }
}
