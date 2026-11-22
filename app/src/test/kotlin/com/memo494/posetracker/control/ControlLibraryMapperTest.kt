package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.OrientationSlots
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.modelimport.LibraryEntry
import com.memo494.posetracker.modelimport.LibraryState
import org.junit.Test

class ControlLibraryMapperTest {
    private val metaA = ImportedModelMeta("a.tflite", "a".repeat(64), 1_000L, 640, 640, 300)
    private val metaB = ImportedModelMeta("b.tflite", "b".repeat(64), 1_000L, 832, 384, 300)

    private fun library(
        slots: OrientationSlots = OrientationSlots(),
        entries: List<LibraryEntry> = listOf(LibraryEntry(metaA, true), LibraryEntry(metaB, true)),
        unreadable: Set<ModelOrientation> = emptySet(),
        blocked: Set<String> = emptySet(),
        guard: ImportGuardState = ImportGuardState.Idle,
        blockedUnreadable: Boolean = false,
    ) = LibraryState(entries, slots, unreadable, blocked, blockedUnreadable, guard, anyFileStored = entries.isNotEmpty())

    private fun load(
        state: LibraryState,
        orientation: ModelOrientation = ModelOrientation.PORTRAIT,
        max: Int = 4,
    ) = ControlModelMapper.libraryLoaded(state, null, orientation, max)

    @Test
    fun theLibraryIsListedWithNamesAndSizes() {
        val event = load(library())

        assertThat(event.library)
            .containsExactly(
                LibraryItem(metaA.sha256, "a.tflite", "640"),
                LibraryItem(metaB.sha256, "b.tflite", "832x384"),
            ).inOrder()
    }

    @Test
    fun bothChoicesAreCarried_builtInIsNull() {
        val slots = OrientationSlots(SlotChoice.BuiltIn, SlotChoice.Library(metaB.sha256))

        val event = load(library(slots))

        assertThat(event.portraitSha).isNull()
        assertThat(event.landscapeSha).isEqualTo(metaB.sha256)
    }

    @Test
    fun theActiveModelIsTheOneOfTheScreensOrientation() {
        val slots = OrientationSlots(SlotChoice.Library(metaA.sha256), SlotChoice.Library(metaB.sha256))
        val state = library(slots)

        assertThat(load(state, ModelOrientation.PORTRAIT).active).isEqualTo(ActiveModel.Imported("a.tflite", "640"))
        assertThat(load(state, ModelOrientation.LANDSCAPE).active).isEqualTo(ActiveModel.Imported("b.tflite", "832x384"))
    }

    @Test
    fun aBuiltInSlot_showsTheBuiltInModelAndNoNotice() {
        val event = load(library())

        assertThat(event.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(event.notice).isNull()
    }

    @Test
    fun aModelThatCrashedItsLoad_isMarkedBlocked_andItsSlotShowsTheLoadNotice() {
        val slots = OrientationSlots(SlotChoice.Library(metaA.sha256), SlotChoice.Library(metaB.sha256))
        val state = library(slots, blocked = setOf(metaA.sha256))

        val portrait = load(state, ModelOrientation.PORTRAIT)
        val landscape = load(state, ModelOrientation.LANDSCAPE)

        assertThat(portrait.library.map { it.blocked }).containsExactly(true, false).inOrder()
        assertThat(portrait.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(portrait.notice).isEqualTo(ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD))
        assertThat(landscape.active).isEqualTo(ActiveModel.Imported("b.tflite", "832x384"))
        assertThat(landscape.notice).isNull()
    }

    @Test
    fun aMarkerWithoutAModel_marksEveryModelBlocked() {
        val state = library(guard = ImportGuardState.Tripped(ImportStage.LOAD))

        assertThat(load(state).library.map { it.blocked }).containsExactly(true, true)
    }

    @Test
    fun aChoiceThatCouldNotBeRead_showsTheBuiltInModelAndAnUnreadableNotice() {
        val state = library(unreadable = setOf(ModelOrientation.LANDSCAPE))

        val landscape = load(state, ModelOrientation.LANDSCAPE)
        val portrait = load(state, ModelOrientation.PORTRAIT)

        assertThat(landscape.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(landscape.notice?.kind).isEqualTo(ModelNoticeKind.STATE_UNREADABLE)
        assertThat(portrait.notice).isNull()
    }

    @Test
    fun theLibraryIsFullAtTheLimit() {
        assertThat(load(library(), max = 2).libraryFull).isTrue()
        assertThat(load(library(), max = 3).libraryFull).isFalse()
    }

    @Test
    fun aModelWithoutItsFile_showsTheMissingFileNoticeForItsSlot() {
        val slots = OrientationSlots(SlotChoice.Library(metaA.sha256), SlotChoice.BuiltIn)
        val state = library(slots, entries = listOf(LibraryEntry(metaA, false)))

        val event = load(state)

        assertThat(event.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(event.notice).isEqualTo(ModelNotice(ModelNoticeKind.FILE_MISSING))
    }

    @Test
    fun theEmptyLibrary_isEmptyWithBuiltInChoices() {
        val event = load(library(entries = emptyList()))

        assertThat(event.library).isEmpty()
        assertThat(event.portraitSha).isNull()
        assertThat(event.landscapeSha).isNull()
        assertThat(event.libraryFull).isFalse()
        assertThat(event.fileStored).isFalse()
    }

    @Test
    fun anUnreadableBlockedList_showsItsNotice_andEveryModelIsSwitchedOff() {
        val event = load(library(blockedUnreadable = true))

        assertThat(event.notice!!.kind).isEqualTo(ModelNoticeKind.BLOCKED_UNREADABLE)
        assertThat(event.library.all { it.blocked }).isTrue()
    }

    @Test
    fun aReadableBlockedList_showsNoSuchNotice() {
        assertThat(load(library()).notice).isNull()
    }

    @Test
    fun builtInLabel_isThePortraitModelSizeInPortraitAndTheLandscapeOneInLandscape() {
        assertThat(ControlModelMapper.builtInInputText(ModelOrientation.PORTRAIT)).isEqualTo("448x960")
        assertThat(ControlModelMapper.builtInInputText(ModelOrientation.LANDSCAPE)).isEqualTo("960x448")
    }

    @Test
    fun theEventCarriesTheScreensOrientation_soTheBuiltInLineNamesTheRightModel() {
        assertThat(load(library(), ModelOrientation.PORTRAIT).orientation).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(load(library(), ModelOrientation.LANDSCAPE).orientation).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun anUnreadableChoice_stillCarriesTheOrientationOfTheBuiltInLine() {
        val event = load(library(unreadable = setOf(ModelOrientation.LANDSCAPE)), ModelOrientation.LANDSCAPE)

        assertThat(event.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(event.orientation).isEqualTo(ModelOrientation.LANDSCAPE)
    }
}
