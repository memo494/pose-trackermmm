package com.memo494.posetracker.modelimport

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
    fun withoutAndroidLooper_passesSoJvmTestsCanRun() {
        WorkerThreadCheck.require()
    }
}
