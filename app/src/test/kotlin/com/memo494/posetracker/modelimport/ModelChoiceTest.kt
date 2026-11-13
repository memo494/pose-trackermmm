package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import org.junit.Test

class ModelChoiceTest {
    private val meta = ImportedModelMeta("model.tflite", "a".repeat(64), 1_000L, 640, 300)

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
}
