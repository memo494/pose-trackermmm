package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImportGuardTest {
    @Test
    fun begin_givesPendingForThatStage() {
        assertThat(ImportGuard.begin(ImportStage.PROBE)).isEqualTo(ImportGuardState.Pending(ImportStage.PROBE))
        assertThat(ImportGuard.begin(ImportStage.LOAD)).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD))
    }

    @Test
    fun succeed_givesIdle() {
        assertThat(ImportGuard.succeed()).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun clear_givesIdle() {
        assertThat(ImportGuard.clear()).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun onAppStart_pendingProbe_isTrippedAtProbe() {
        val state = ImportGuard.onAppStart(ImportGuardState.Pending(ImportStage.PROBE))

        assertThat(state).isEqualTo(ImportGuardState.Tripped(ImportStage.PROBE))
    }

    @Test
    fun onAppStart_pendingLoad_isTrippedAtLoad() {
        val state = ImportGuard.onAppStart(ImportGuardState.Pending(ImportStage.LOAD))

        assertThat(state).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))
    }

    @Test
    fun onAppStart_idleAndTripped_areUnchanged() {
        assertThat(ImportGuard.onAppStart(ImportGuardState.Idle)).isEqualTo(ImportGuardState.Idle)
        for (stage in ImportStage.entries) {
            val tripped = ImportGuardState.Tripped(stage)

            assertThat(ImportGuard.onAppStart(tripped)).isEqualTo(tripped)
        }
    }

    @Test
    fun onAppStart_twice_staysTripped() {
        val once = ImportGuard.onAppStart(ImportGuardState.Pending(ImportStage.LOAD))

        assertThat(ImportGuard.onAppStart(once)).isEqualTo(once)
    }

    @Test
    fun fullCycle_probeThenLoadThenSucceed_endsIdle() {
        assertThat(ImportGuard.begin(ImportStage.PROBE)).isEqualTo(ImportGuardState.Pending(ImportStage.PROBE))
        assertThat(ImportGuard.succeed()).isEqualTo(ImportGuardState.Idle)
        assertThat(ImportGuard.begin(ImportStage.LOAD)).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD))

        assertThat(ImportGuard.onAppStart(ImportGuard.succeed())).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun crashDuringLoad_thenRestartThenClear_endsIdle() {
        val crashed = ImportGuard.begin(ImportStage.LOAD)

        val afterRestart = ImportGuard.onAppStart(crashed)
        assertThat(afterRestart).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))

        assertThat(ImportGuard.clear()).isEqualTo(ImportGuardState.Idle)
    }

    @Test
    fun encode_usesTheDocumentedTexts() {
        assertThat(ImportGuard.encode(ImportGuardState.Idle)).isEqualTo("idle")
        assertThat(ImportGuard.encode(ImportGuardState.Pending(ImportStage.PROBE))).isEqualTo("pending:probe")
        assertThat(ImportGuard.encode(ImportGuardState.Pending(ImportStage.LOAD))).isEqualTo("pending:load")
        assertThat(ImportGuard.encode(ImportGuardState.Tripped(ImportStage.PROBE))).isEqualTo("tripped:probe")
        assertThat(ImportGuard.encode(ImportGuardState.Tripped(ImportStage.LOAD))).isEqualTo("tripped:load")
    }

    @Test
    fun decode_roundTripsEveryState() {
        val states =
            listOf(ImportGuardState.Idle) +
                ImportStage.entries.map { ImportGuardState.Pending(it) } +
                ImportStage.entries.map { ImportGuardState.Tripped(it) }

        for (state in states) {
            assertThat(ImportGuard.decode(ImportGuard.encode(state))).isEqualTo(state)
        }
    }

    @Test
    fun decode_nullAndUnknownText_isIdle() {
        val unknown =
            listOf(null, "", " ", "IDLE", "pending", "pending:", "pending:other", "tripped:LOAD", "pending:load ", "x", "idle:load")

        for (text in unknown) {
            assertThat(ImportGuard.decode(text)).isEqualTo(ImportGuardState.Idle)
        }
    }

    private val shaA = "a".repeat(64)

    @Test
    fun begin_withASha_isPendingForThatModel() {
        assertThat(ImportGuard.begin(ImportStage.LOAD, shaA)).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD, shaA))
    }

    @Test
    fun onAppStart_pendingLoadOfAModel_isTrippedForThatModel() {
        val state = ImportGuard.onAppStart(ImportGuardState.Pending(ImportStage.LOAD, shaA))

        assertThat(state).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD, shaA))
    }

    @Test
    fun encode_appendsTheShaAfterAColon() {
        assertThat(ImportGuard.encode(ImportGuardState.Pending(ImportStage.LOAD, shaA))).isEqualTo("pending:load:$shaA")
        assertThat(ImportGuard.encode(ImportGuardState.Tripped(ImportStage.LOAD, shaA))).isEqualTo("tripped:load:$shaA")
    }

    @Test
    fun decode_roundTripsStatesWithAModel() {
        val states = listOf(ImportGuardState.Pending(ImportStage.LOAD, shaA), ImportGuardState.Tripped(ImportStage.LOAD, shaA))

        for (state in states) {
            assertThat(ImportGuard.decode(ImportGuard.encode(state))).isEqualTo(state)
        }
    }

    @Test
    fun decode_aMarkerWithABadSha_isStillAMarkerOfAnUnknownModel() {
        assertThat(ImportGuard.decode("pending:load:xyz")).isEqualTo(ImportGuardState.Pending(ImportStage.LOAD))
        assertThat(ImportGuard.decode("tripped:load:" + "A".repeat(64))).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))
        assertThat(ImportGuard.decode("tripped:load:")).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))
    }

    @Test
    fun decode_aMarkerWithAnUnknownStageAndASha_isIdle() {
        assertThat(ImportGuard.decode("pending:other:$shaA")).isEqualTo(ImportGuardState.Idle)
    }
}
