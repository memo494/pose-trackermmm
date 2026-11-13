package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkerThreadCheckTest {
    @Test
    fun onMainThread_throwsIllegalState() {
        assertThrows(IllegalStateException::class.java) { WorkerThreadCheck.require(onMainThread = true) }
    }

    @Test
    fun offMainThread_passes() {
        WorkerThreadCheck.require(onMainThread = false)
    }

    @Test
    fun theMessage_saysWhatMustNotRunOnTheMainThread() {
        val error = assertThrows(IllegalStateException::class.java) { WorkerThreadCheck.require(onMainThread = true) }

        assertThat(error).hasMessageThat().contains("off the main thread")
    }

    @Test
    fun theInjectedFake_followsItsFlag() {
        val thread = FakeMainThread()
        thread.check()

        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) { thread.check() }
    }
}
