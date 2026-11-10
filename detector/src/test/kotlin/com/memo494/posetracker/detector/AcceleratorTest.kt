package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AcceleratorTest {
    @Test
    fun hasExactlyGpuAndCpu() {
        assertThat(Accelerator.entries.toList())
            .containsExactly(Accelerator.GPU, Accelerator.CPU)
            .inOrder()
    }
}
