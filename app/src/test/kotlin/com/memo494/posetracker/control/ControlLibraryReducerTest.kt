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

    @Test
    fun slotPicked_aWideModelForPortrait_isRefusedWithoutAnEffect() {
        val wide = itemB.copy(onlyFor = ModelOrientation.LANDSCAPE)
        val state = loaded.copy(library = listOf(itemA, wide), landscapeSha = null)

        val step = reduce(state, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, shaB))

        assertThat(step.effect).isNull()
        assertThat(step.state).isEqualTo(state)
    }

    @Test
    fun slotPicked_aWideModelForLandscape_isStored() {
        val wide = itemB.copy(onlyFor = ModelOrientation.LANDSCAPE)
        val state = loaded.copy(library = listOf(itemA, wide), landscapeSha = null)

        val step = reduce(state, ControlEvent.SlotPicked(ModelOrientation.LANDSCAPE, shaB))

        assertThat(step.effect).isEqualTo(ControlEffect.SET_SLOT)
        assertThat(step.state.landscapeSha).isEqualTo(shaB)
        assertThat(step.slotOrientation).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun slotPicked_aTallModelForLandscape_isRefused() {
        val tall = itemA.copy(inputText = "448x960", onlyFor = ModelOrientation.PORTRAIT)
        val state = loaded.copy(library = listOf(tall, itemB))

        val step = reduce(state, ControlEvent.SlotPicked(ModelOrientation.LANDSCAPE, shaA))

        assertThat(step.effect).isNull()
        assertThat(step.state).isEqualTo(state)
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

    // ---- remove all: the second tap confirms ----

    @Test
    fun removeAll_firstTap_armsAndSchedulesTheDisarm_withoutRemovingAnything() {
        val step = reduce(loaded, ControlEvent.RemoveAllClicked)

        assertThat(step.state.removeAllArmed).isTrue()
        assertThat(step.state.library).containsExactly(itemA, itemB)
        assertThat(step.effect).isEqualTo(ControlEffect.SCHEDULE_REMOVE_ALL_DISARM)
    }

    @Test
    fun removeAll_secondTap_removesEverythingAndDisarms() {
        val armed = reduce(loaded, ControlEvent.RemoveAllClicked).state

        val step = reduce(armed, ControlEvent.RemoveAllClicked)

        assertThat(step.effect).isEqualTo(ControlEffect.CLEAR_IMPORTED)
        assertThat(step.state.removeAllArmed).isFalse()
        assertThat(step.state.library).isEmpty()
    }

    @Test
    fun removeAll_theTimeout_disarmsWithoutAnEffect() {
        val armed = reduce(loaded, ControlEvent.RemoveAllClicked).state

        val step = reduce(armed, ControlEvent.RemoveAllDisarm)

        assertThat(step.state.removeAllArmed).isFalse()
        assertThat(step.effect).isNull()
        assertThat(reduce(step.state, ControlEvent.RemoveAllClicked).effect).isEqualTo(ControlEffect.SCHEDULE_REMOVE_ALL_DISARM)
    }

    @Test
    fun removeAll_isIgnoredWhileRunningAwaitingConsentOrImporting() {
        for (busy in listOf(loaded.copy(running = true), loaded.copy(awaitingConsent = true), loaded.copy(importing = true))) {
            val step = reduce(busy, ControlEvent.RemoveAllClicked)

            assertThat(step.state).isEqualTo(busy)
            assertThat(step.effect).isNull()
        }
    }

    @Test
    fun removeAll_armedState_endsWhenAnotherModelActionStarts() {
        val armed = reduce(loaded, ControlEvent.RemoveAllClicked).state

        assertThat(reduce(armed, ControlEvent.DeleteModelClicked(shaA)).state.removeAllArmed).isFalse()
        assertThat(reduce(armed, ControlEvent.ImportClicked).state.removeAllArmed).isFalse()
        assertThat(reduce(armed, ControlEvent.SlotPicked(ModelOrientation.PORTRAIT, shaB)).state.removeAllArmed).isFalse()
    }
}
