package com.memo494.posetracker.core.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ThermalStatusTextTest {
    @Test
    fun platformStatuses_mapToTheirNames() {
        // android.os.PowerManager.THERMAL_STATUS_NONE .. THERMAL_STATUS_SHUTDOWN are 0..6
        val names = (0..6).map { ThermalStatusText.of(it) }

        assertThat(names)
            .containsExactly("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
            .inOrder()
    }

    @Test
    fun anythingElse_isNotAvailable() {
        for (status in listOf(-1, 7, 100, Int.MIN_VALUE, Int.MAX_VALUE, ThermalReader.UNAVAILABLE)) {
            assertThat(ThermalStatusText.of(status)).isEqualTo("n/a")
        }
    }
}
