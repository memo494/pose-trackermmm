package com.memo494.posetracker.tuning

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import org.junit.Test

class TuningSessionTest {
    private val base = TrackerConfig(debugMarks = true)

    @Test
    fun resolve_aMissingExtraIsTheBaseConfig() {
        val result = TuningSession.resolve(base, null)

        assertThat(result.config).isEqualTo(base)
        assertThat(result.applied).isEqualTo(0)
    }

    @Test
    fun resolve_aGarbageExtraIsTheBaseConfig() {
        for (garbage in listOf("", "garbage", "\u0000\u0001", "scoreThreshold=0.9", "posetracker-tuning v9\nscoreThreshold=0.9")) {
            val result = TuningSession.resolve(base, garbage)

            assertThat(result.config).isEqualTo(base)
            assertThat(result.applied).isEqualTo(0)
        }
    }

    @Test
    fun resolve_aValidExtraIsAppliedOverTheBaseConfig() {
        val extra = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.3f).encode()

        val result = TuningSession.resolve(base, extra)

        assertThat(result.config.scoreThreshold).isEqualTo(0.3f)
        assertThat(result.config.debugMarks).isTrue()
        assertThat(result.applied).isEqualTo(1)
        assertThat(result.skipped).isEmpty()
    }

    @Test
    fun resolve_aValueOutsideTheSheetBoundsIsIgnored() {
        val extra = TuningOverrides.HEADER + "\nrelockMaxDistance=0.95\nscoreThreshold=0.3\n"

        val result = TuningSession.resolve(base, extra)

        assertThat(result.config.relockMaxDistance).isEqualTo(TrackerConfig().relockMaxDistance)
        assertThat(result.applied).isEqualTo(1)
    }
}
