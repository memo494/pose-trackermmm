package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LockArmingTest {
    private val arming = LockArming(TIMEOUT_NS)

    @Test
    fun aNewStateMachine_isNotArmed() {
        assertThat(arming.isArmed(START_NS)).isFalse()
    }

    @Test
    fun arm_makesItArmed() {
        arming.arm(START_NS)

        assertThat(arming.isArmed(START_NS)).isTrue()
    }

    @Test
    fun isArmed_untilJustBeforeTheTimeout() {
        arming.arm(START_NS)

        assertThat(arming.isArmed(START_NS + TIMEOUT_NS - 1L)).isTrue()
    }

    @Test
    fun isArmed_isFalseFromTheTimeoutOn() {
        arming.arm(START_NS)

        assertThat(arming.isArmed(START_NS + TIMEOUT_NS)).isFalse()
    }

    @Test
    fun anExpiredArm_staysDisarmed_andConsumesNothing() {
        arming.arm(START_NS)
        assertThat(arming.isArmed(START_NS + TIMEOUT_NS + 1L)).isFalse()

        assertThat(arming.consumeTap()).isFalse()
        assertThat(arming.isArmed(START_NS + 1L)).isFalse()
    }

    @Test
    fun consumeTap_whileArmed_returnsTrueOnceAndDisarms() {
        arming.arm(START_NS)

        assertThat(arming.consumeTap()).isTrue()
        assertThat(arming.isArmed(START_NS + 1L)).isFalse()
        assertThat(arming.consumeTap()).isFalse()
    }

    @Test
    fun consumeTap_whenNeverArmed_returnsFalse() {
        assertThat(arming.consumeTap()).isFalse()
    }

    @Test
    fun disarm_endsTheArmedState() {
        arming.arm(START_NS)

        arming.disarm()

        assertThat(arming.isArmed(START_NS + 1L)).isFalse()
        assertThat(arming.consumeTap()).isFalse()
    }

    @Test
    fun disarm_whenNotArmed_changesNothing() {
        arming.disarm()

        assertThat(arming.isArmed(START_NS)).isFalse()
    }

    @Test
    fun armingAgain_restartsTheTimeout() {
        arming.arm(START_NS)

        arming.arm(START_NS + TIMEOUT_NS - 1L)

        assertThat(arming.isArmed(START_NS + TIMEOUT_NS + 1L)).isTrue()
        assertThat(arming.isArmed(START_NS + 2L * TIMEOUT_NS - 1L)).isFalse()
    }

    @Test
    fun armingAfterAnExpiry_worksAgain() {
        arming.arm(START_NS)
        assertThat(arming.isArmed(START_NS + TIMEOUT_NS)).isFalse()

        arming.arm(START_NS + 2L * TIMEOUT_NS)

        assertThat(arming.isArmed(START_NS + 2L * TIMEOUT_NS + 1L)).isTrue()
    }

    @Test
    fun aTimeoutThatIsNotPositive_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { LockArming(0L) }
        assertThrows(IllegalArgumentException::class.java) { LockArming(-1L) }
    }

    private companion object {
        const val START_NS = 7_000_000_000L
        const val TIMEOUT_NS = 5_000_000_000L
    }
}
