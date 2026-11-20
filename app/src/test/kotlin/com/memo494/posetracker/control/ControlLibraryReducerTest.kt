package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ModelOrientation
import org.junit.Test

class ControlLibraryReducerTest {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)
    private val itemA = LibraryItem(shaA, "a.tflite", "640")
    private val itemB = LibraryItem(shaB, "b.tflite", "832x384")
    private val loaded =
        ControlState(
            overlayPermission = true,
            library = listOf(itemA, itemB),
            portraitSha = shaA,
            landscapeSha = shaB,
        )

    private fun reduce(
        state: ControlState,
        event: ControlEvent,
    ): ControlStep = ControlReducer.reduce(state, event, false)

    // ---- deleting ----

    @Test
    fun deleteClicked_removesTheModelAndTheChoicesThatPointedToIt_andAsksTheActivityToDelete() {
        val step = reduce(loaded, ControlEvent.DeleteModelClicked(shaA))

        assertThat(step.state.library).containsExactly(itemB)
        assertThat(step.state.portraitSha).isNull()
        assertThat(step.state.landscapeSha).isEqualTo(shaB)
        assertThat(step.effect).isEqualTo(ControlEffect.DELETE_MODEL)
        assertThat(step.modelSha).isEqualTo(shaA)
    }

    @Test
    fun deleteClicked_makesRoomInAFullLibrary() {
        val full = loaded.copy(libraryFull = true)

        assertThat(reduce(full, ControlEvent.DeleteModelClicked(shaB)).state.libraryFull).isFalse()
    }

    @Test
    fun deleteClicked_isIgnoredWhileRunningAwaitingConsentOrImporting() {
        for (busy in listOf(loaded.copy(running = true), loaded.copy(awaitingConsent = true), loaded.copy(importing = true))) {
            val step = reduce(busy, ControlEvent.DeleteModelClicked(shaA))

            assertThat(step.state).isEqualTo(busy)
            assertThat(step.effect).isNull()
        }
    }

    @Test
    fun deleteClicked_forAModelThatIsNotListed_isIgnored() {
        val step = reduce(loaded, ControlEvent.DeleteModelClicked("c".repeat(64)))

        assertThat(step.state).isEqualTo(loaded)
        assertThat(step.effect).isNull()
    }

    // ---- choosing ----

    @Test
    fun slotPicked_setsOnlyThatOrientation_andAsksTheActivityToStoreIt() {
        val step = reduce(loaded, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, shaB))

        assertThat(step.state.portraitSha).isEqualTo(shaB)
        assertThat(step.state.landscapeSha).isEqualTo(shaB)
        assertThat(step.effect).isEqualTo(ControlEffect.SET_SLOT)
        assertThat(step.slotOrientation).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(step.modelSha).isEqualTo(shaB)
    }

    @Test
    fun slotPicked_builtIn_isANullSha() {
        val step = reduce(loaded, ControlEvent.SlotPicked(ModelOrientation.LANDSCAPE, null))

        assertThat(step.state.landscapeSha).isNull()
        assertThat(step.state.portraitSha).isEqualTo(shaA)
        assertThat(step.effect).isEqualTo(ControlEffect.SET_SLOT)
        assertThat(step.modelSha).isNull()
    }

    @Test
    fun slotPicked_isIgnoredWhileBusy() {
        for (busy in listOf(loaded.copy(running = true), loaded.copy(awaitingConsent = true), loaded.copy(importing = true))) {
            val step = reduce(busy, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, null))

            assertThat(step.state).isEqualTo(busy)
            assertThat(step.effect).isNull()
        }
    }

    @Test
    fun slotPicked_aModelThatIsNotListedOrIsSwitchedOff_isIgnored() {
        val withBlocked = loaded.copy(library = listOf(itemA, itemB.copy(blocked = true)))

        val unknown = reduce(loaded, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, "c".repeat(64)))
        val blocked = reduce(withBlocked, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, shaB))

        assertThat(unknown.effect).isNull()
        assertThat(unknown.state).isEqualTo(loaded)
        assertThat(blocked.effect).isNull()
        assertThat(blocked.state).isEqualTo(withBlocked)
    }

    // ---- import into a full library ----

    @Test
    fun importClicked_whenTheLibraryIsFull_doesNotOpenThePicker() {
        val full = loaded.copy(libraryFull = true)

        val step = reduce(full, ControlEvent.ImportClicked)

        assertThat(step.effect).isNull()
        assertThat(reduce(loaded, ControlEvent.ImportClicked).effect).isEqualTo(ControlEffect.OPEN_MODEL_PICKER)
    }

    @Test
    fun importPicked_whenTheLibraryIsFull_isIgnored() {
        val full = loaded.copy(libraryFull = true)

        val step = reduce(full, ControlEvent.ImportPicked("content://x"))

        assertThat(step.effect).isNull()
        assertThat(step.state.importing).isFalse()
    }

    // ---- loading and removing everything ----

    @Test
    fun modelStateLoaded_carriesTheLibraryAndBothChoices() {
        val event =
            ControlEvent.ModelStateLoaded(
                ActiveModel.BuiltIn,
                null,
                fileStored = true,
                library = listOf(itemA, itemB),
                portraitSha = shaA,
                landscapeSha = null,
                libraryFull = true,
            )

        val state = reduce(ControlState(), event).state

        assertThat(state.library).containsExactly(itemA, itemB).inOrder()
        assertThat(state.portraitSha).isEqualTo(shaA)
        assertThat(state.landscapeSha).isNull()
        assertThat(state.libraryFull).isTrue()
    }

    @Test
    fun useBuiltIn_emptiesTheLibraryAndBothChoices() {
        val step = reduce(loaded.copy(libraryFull = true), ControlEvent.UseBuiltInClicked)

        assertThat(step.state.library).isEmpty()
        assertThat(step.state.portraitSha).isNull()
        assertThat(step.state.landscapeSha).isNull()
        assertThat(step.state.libraryFull).isFalse()
        assertThat(step.effect).isEqualTo(ControlEffect.CLEAR_IMPORTED)
    }
}
