package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import org.junit.Test

class ModelChoiceTest {
    private val meta = ImportedModelMeta("model.tflite", "a".repeat(64), 1_000L, 640, 640, 300)

    private val idle = ImportGuardState.Idle
    private val pendingProbe = ImportGuardState.Pending(ImportStage.PROBE)
    private val pendingLoad = ImportGuardState.Pending(ImportStage.LOAD)
    private val trippedProbe = ImportGuardState.Tripped(ImportStage.PROBE)
    private val trippedLoad = ImportGuardState.Tripped(ImportStage.LOAD)

    private fun resolve(
        hasMeta: Boolean,
        fileExists: Boolean,
        guard: ImportGuardState,
    ): ModelChoice = ModelChoice.resolve(StoredModelState(if (hasMeta) meta else null, fileExists, guard))

    @Test
    fun nothingImported_isBuiltInWithoutReason() {
        assertThat(resolve(hasMeta = false, fileExists = false, guard = idle)).isEqualTo(ModelChoice.BuiltIn(null))
    }

    @Test
    fun fileWithoutMeta_isBuiltInWithoutReason() {
        assertThat(resolve(hasMeta = false, fileExists = true, guard = idle)).isEqualTo(ModelChoice.BuiltIn(null))
    }

    @Test
    fun metaAndFile_withIdleGuard_isImported() {
        assertThat(resolve(hasMeta = true, fileExists = true, guard = idle)).isEqualTo(ModelChoice.Imported(meta))
    }

    @Test
    fun metaWithoutFile_isBuiltInFileMissing() {
        assertThat(resolve(hasMeta = true, fileExists = false, guard = idle))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.FILE_MISSING))
    }

    @Test
    fun pendingGuard_isNotATrip() {
        assertThat(resolve(hasMeta = true, fileExists = true, guard = pendingProbe)).isEqualTo(ModelChoice.Imported(meta))
        assertThat(resolve(hasMeta = true, fileExists = true, guard = pendingLoad)).isEqualTo(ModelChoice.Imported(meta))
        assertThat(resolve(hasMeta = false, fileExists = false, guard = pendingLoad)).isEqualTo(ModelChoice.BuiltIn(null))
    }

    @Test
    fun trippedGuard_winsOverAGoodModel() {
        assertThat(resolve(hasMeta = true, fileExists = true, guard = trippedProbe))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
        assertThat(resolve(hasMeta = true, fileExists = true, guard = trippedLoad))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
    }

    @Test
    fun trippedGuard_winsOverAMissingFile() {
        assertThat(resolve(hasMeta = true, fileExists = false, guard = trippedLoad))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
        assertThat(resolve(hasMeta = true, fileExists = false, guard = trippedProbe))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
    }

    @Test
    fun trippedGuard_winsEvenWhenNothingIsStored() {
        assertThat(resolve(hasMeta = false, fileExists = false, guard = trippedProbe))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
        assertThat(resolve(hasMeta = false, fileExists = true, guard = trippedLoad))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
    }

    // ---- a probe trip keeps a stored model that still matches its meta (T9) ----

    private fun resolveVerified(
        hasMeta: Boolean,
        fileExists: Boolean,
        guard: ImportGuardState,
        intact: Boolean,
    ): ModelChoice = ModelChoice.resolve(StoredModelState(if (hasMeta) meta else null, fileExists, guard)) { intact }

    @Test
    fun probeTrip_withAStoredModelThatStillMatches_keepsTheStoredModel() {
        assertThat(resolveVerified(hasMeta = true, fileExists = true, guard = trippedProbe, intact = true))
            .isEqualTo(ModelChoice.Imported(meta))
    }

    @Test
    fun probeTrip_withAStoredModelThatNoLongerMatches_isBuiltIn() {
        assertThat(resolveVerified(hasMeta = true, fileExists = true, guard = trippedProbe, intact = false))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
    }

    @Test
    fun probeTrip_withNothingStored_isBuiltInAndTheCheckIsNeverAsked() {
        var asked = 0

        val choice = ModelChoice.resolve(StoredModelState(null, fileExists = false, guard = trippedProbe)) { asked++ > 0 }

        assertThat(choice).isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
        assertThat(asked).isEqualTo(0)
    }

    @Test
    fun probeTrip_withAMetaButNoFile_isBuiltInEvenIfTheCheckSaysYes() {
        assertThat(resolveVerified(hasMeta = true, fileExists = false, guard = trippedProbe, intact = true))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
    }

    @Test
    fun probeTrip_whenTheCheckThrows_isBuiltIn() {
        val state = StoredModelState(meta, fileExists = true, guard = trippedProbe)

        val choice = ModelChoice.resolve(state) { throw OutOfMemoryError("pretend") }

        assertThat(choice).isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
    }

    @Test
    fun probeTrip_withoutACheck_failsClosedToBuiltIn() {
        val state = StoredModelState(meta, fileExists = true, guard = trippedProbe)

        assertThat(ModelChoice.resolve(state)).isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE))
    }

    @Test
    fun loadTrip_withAStoredModelThatMatches_staysBuiltIn() {
        assertThat(resolveVerified(hasMeta = true, fileExists = true, guard = trippedLoad, intact = true))
            .isEqualTo(ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD))
    }

    @Test
    fun otherGuardStates_neverAskTheCheck() {
        var asked = 0
        for (guard in listOf(idle, pendingProbe, pendingLoad, trippedLoad)) {
            ModelChoice.resolve(StoredModelState(meta, fileExists = true, guard = guard)) { asked++ > 0 }
        }

        assertThat(asked).isEqualTo(0)
    }

    @Test
    fun everyCombination_matchesTheTable() {
        val imported = ModelChoice.Imported(meta)
        val none = ModelChoice.BuiltIn(null)
        val missing = ModelChoice.BuiltIn(BuiltInReason.FILE_MISSING)
        val probe = ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_PROBE)
        val load = ModelChoice.BuiltIn(BuiltInReason.GUARD_TRIPPED_LOAD)
        // (hasMeta, fileExists) -> expected for guards [idle, pendingProbe, pendingLoad, trippedProbe, trippedLoad]
        val table =
            listOf(
                Triple(false, false, listOf(none, none, none, probe, load)),
                Triple(false, true, listOf(none, none, none, probe, load)),
                Triple(true, false, listOf(missing, missing, missing, probe, load)),
                Triple(true, true, listOf(imported, imported, imported, probe, load)),
            )
        val guards = listOf(idle, pendingProbe, pendingLoad, trippedProbe, trippedLoad)

        for ((hasMeta, fileExists, expected) in table) {
            for ((index, guard) in guards.withIndex()) {
                assertThat(resolve(hasMeta, fileExists, guard)).isEqualTo(expected[index])
            }
        }
    }

    @Test
    fun wrongShape_winsOverEveryGuardAndFileCheck_andNamesTheRefusedModel() {
        val wide = ImportedModelMeta("wide.tflite", "b".repeat(64), 1_000L, 960, 448, 300)
        var asked = 0
        for (guard in listOf(idle, pendingProbe, pendingLoad, trippedProbe, trippedLoad)) {
            for (fileExists in listOf(true, false)) {
                val state = StoredModelState(wide, fileExists, guard, wrongShape = true)

                val choice = ModelChoice.resolve(state) { asked++ >= 0 }

                assertThat(choice).isEqualTo(ModelChoice.BuiltIn(BuiltInReason.WRONG_SHAPE, refused = wide))
            }
        }
        assertThat(asked).isEqualTo(0)
    }
}
