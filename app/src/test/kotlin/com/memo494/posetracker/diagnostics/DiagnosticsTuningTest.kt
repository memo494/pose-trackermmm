package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import org.junit.Test

class DiagnosticsTuningTest {
    private fun summary(
        tuningOverrides: Int,
        stats: CaptureStats? = null,
    ): String =
        DiagnosticsReport
            .entries(
                appInfo = linkedMapOf("versionName" to "x"),
                deviceInfo = linkedMapOf("model" to "y"),
                config = "TrackerConfig()",
                stats = stats,
                logLines = emptyList(),
                modelInfo = null,
                lastCrash = null,
                tuningOverrides = tuningOverrides,
            ).first { it.name == "summary.txt" }
            .text

    @Test
    fun summary_hasTheNumberOfTuningOverrides() {
        assertThat(summary(tuningOverrides = 3)).contains("tuningOverrides=3\n")
    }

    @Test
    fun summary_hasTuningOverridesZeroWithoutASession() {
        assertThat(summary(tuningOverrides = 0, stats = null)).contains("tuningOverrides=0\n")
    }

    @Test
    fun holder_remembersTheEffectiveConfigAndTheOverrideCountOfTheSession() {
        val result = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.3f).applyTo(TrackerConfig())

        CaptureStatsHolder.setSession(result.config, result.applied)

        assertThat(CaptureStatsHolder.sessionConfig.scoreThreshold).isEqualTo(0.3f)
        assertThat(CaptureStatsHolder.tuningOverrides).isEqualTo(1)
        assertThat(CaptureStatsHolder.sessionConfig.toString()).contains("scoreThreshold=0.3")
        CaptureStatsHolder.setSession(TrackerConfig(), 0)
    }
}
