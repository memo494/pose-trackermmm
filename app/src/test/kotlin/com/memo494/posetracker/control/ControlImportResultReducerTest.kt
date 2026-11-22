package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.modelimport.ImportOutcome
import org.junit.Test

/** M1b-fix-import T2: what the reducer and the mapper do with an import that was assigned to a slot (or not). */
class ControlImportResultReducerTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val itemA = LibraryItem(shaA, "a.tflite", "384x832")
    private val itemB = LibraryItem(shaB, "b.tflite", "832x384")
    private val oldModel = ActiveModel.Imported("a.tflite", "384x832")
    private val ready = ControlState(overlayPermission = true, library = listOf(itemA, itemB), portraitSha = shaA, landscapeSha = shaA)
    private val accepted = ImportSummary.Accepted("b.tflite", "832x384", ModelOrientation.LANDSCAPE)
    private val acceptedNoSlot = ImportSummary.Accepted("b.tflite", "832x384", null)

    private fun reduce(
        state: ControlState,
        event: ControlEvent,
    ): ControlStep = ControlReducer.reduce(state, event, false)

    @Test
    fun importFinished_accepted_keepsTheActiveModelAndAsksForAReload() {
        val state = ready.copy(importing = true, activeModel = oldModel)

        for (summary in listOf(accepted, acceptedNoSlot, accepted.copy(slot = ModelOrientation.PORTRAIT))) {
            val step = reduce(state, ControlEvent.ImportFinished(summary))

            assertThat(step.state.activeModel).isEqualTo(oldModel)
            assertThat(step.state.importResult).isEqualTo(summary)
            assertThat(step.state.importing).isFalse()
            assertThat(step.effect).isEqualTo(ControlEffect.LOAD_MODEL_STATE)
        }
    }

    @Test
    fun importFinished_accepted_fromTheBuiltInModel_staysBuiltInUntilTheReloadAnswers() {
        val step = reduce(ready.copy(importing = true), ControlEvent.ImportFinished(accepted))

        assertThat(step.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
    }

    @Test
    fun slotPicked_clearsTheLastImportResult() {
        val state = ready.copy(importResult = accepted)

        val step = reduce(state, ControlEvent.SlotPicked(ModelOrientation.LANDSCAPE, shaB))

        assertThat(step.state.importResult).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.SET_SLOT)
    }

    @Test
    fun slotPicked_ignored_keepsTheLastImportResult() {
        val busy = ready.copy(importResult = accepted, running = true)

        val step = reduce(busy, ControlEvent.SlotPicked(ModelOrientation.LANDSCAPE, shaB))

        assertThat(step.state.importResult).isEqualTo(accepted)
    }

    @Test
    fun deleteClicked_clearsTheLastImportResult() {
        val state = ready.copy(importResult = acceptedNoSlot)

        val step = reduce(state, ControlEvent.DeleteModelClicked(shaB))

        assertThat(step.state.importResult).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.DELETE_MODEL)
    }

    @Test
    fun deleteClicked_ignored_keepsTheLastImportResult() {
        val state = ready.copy(importResult = accepted)

        val step = reduce(state, ControlEvent.DeleteModelClicked("c".repeat(64)))

        assertThat(step.state.importResult).isEqualTo(accepted)
    }

    @Test
    fun summary_carriesTheAssignedSlot() {
        val meta = ImportedModelMeta("b.tflite", shaB, 1000L, 832, 384, 300)

        assertThat(ControlModelMapper.summary(ImportOutcome.Accepted(meta, ModelOrientation.LANDSCAPE)))
            .isEqualTo(ImportSummary.Accepted("b.tflite", "832x384", ModelOrientation.LANDSCAPE))
        assertThat(ControlModelMapper.summary(ImportOutcome.Accepted(meta, null)))
            .isEqualTo(ImportSummary.Accepted("b.tflite", "832x384", null))
    }
}
