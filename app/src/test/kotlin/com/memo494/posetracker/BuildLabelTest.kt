package com.memo494.posetracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuildLabelTest {
    @Test
    fun line_formatsVersionAndSha() {
        assertThat(BuildLabel.line("0.1.1", "abc1234")).isEqualTo("v0.1.1 · abc1234")
    }
}
