package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.OrientationSlots
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.core.modelimport.fitsSlot
import com.memo494.posetracker.modelimport.LibraryEntry
import com.memo494.posetracker.modelimport.LibraryState
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus
import org.junit.Test

class ControlLibraryMapperTest {
    private val metaA = ImportedModelMeta("a.tflite", "a".repeat(64), 1_000L, 640, 640, 300)
    private val metaB = ImportedModelMeta("b.tflite", "b".repeat(64), 1_000L, 832, 384, 300)
    private val metaT = ImportedModelMeta("t.tflite", "f".repeat(64), 1_000L, 384, 832, 300)

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
                LibraryItem(metaB.sha256, "b.tflite", "832x384", fitsPortrait = false),
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

    @Test
    fun eachItemSaysWhichOrientationItFits() {
        val tall = ImportedModelMeta("t.tflite", "c".repeat(64), 1_000L, 448, 960, 300)
        val entries = listOf(LibraryEntry(metaA, true), LibraryEntry(metaB, true), LibraryEntry(tall, true))

        val items = load(library(entries = entries)).library.associateBy { it.displayName }

        assertThat(items.getValue("a.tflite").fits(ModelOrientation.PORTRAIT)).isTrue()
        assertThat(items.getValue("a.tflite").fits(ModelOrientation.LANDSCAPE)).isTrue()
        assertThat(items.getValue("b.tflite").fits(ModelOrientation.PORTRAIT)).isFalse()
        assertThat(items.getValue("b.tflite").fits(ModelOrientation.LANDSCAPE)).isTrue()
        assertThat(items.getValue("t.tflite").fits(ModelOrientation.PORTRAIT)).isTrue()
        assertThat(items.getValue("t.tflite").fits(ModelOrientation.LANDSCAPE)).isFalse()
    }

    @Test
    fun aWideModelInThePortraitSlot_showsTheBuiltInModelAndTheWrongShapeNotice() {
        val slots = OrientationSlots(SlotChoice.Library(metaB.sha256), SlotChoice.Library(metaB.sha256))
        val state = library(slots)

        val portrait = load(state, ModelOrientation.PORTRAIT)
        val landscape = load(state, ModelOrientation.LANDSCAPE)

        assertThat(portrait.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(portrait.notice).isEqualTo(ModelNotice(ModelNoticeKind.WRONG_SHAPE, "b.tflite 832x384 (wide)"))
        assertThat(portrait.portraitSha).isEqualTo(metaB.sha256)
        assertThat(landscape.active).isEqualTo(ActiveModel.Imported("b.tflite", "832x384"))
        assertThat(landscape.notice).isNull()
    }

    @Test
    fun eachItemStoresTheSlotRuleForBothOrientations() {
        val entries = listOf(LibraryEntry(metaA, true), LibraryEntry(metaB, true), LibraryEntry(metaT, true))

        val items = load(library(entries = entries)).library

        for ((item, entry) in items.zip(entries)) {
            assertThat(item.fitsPortrait).isEqualTo(fitsSlot(entry.meta, ModelOrientation.PORTRAIT))
            assertThat(item.fitsLandscape).isEqualTo(fitsSlot(entry.meta, ModelOrientation.LANDSCAPE))
        }
        assertThat(items.map { it.fitsPortrait }).containsExactly(true, false, true).inOrder()
        assertThat(items.map { it.fitsLandscape }).containsExactly(true, true, false).inOrder()
    }

    @Test
    fun incident_setUpUpright_theWrongLandscapeSlotIsNoticedToo() {
        // The owner set the slots up holding the phone upright; Landscape got a tall model and nothing said so.
        val entries = listOf(LibraryEntry(metaA, true), LibraryEntry(metaT, true))
        val slots = OrientationSlots(SlotChoice.Library(metaA.sha256), SlotChoice.Library(metaT.sha256))

        val portrait = load(library(slots, entries), ModelOrientation.PORTRAIT)

        assertThat(portrait.active).isEqualTo(ActiveModel.Imported("a.tflite", "640"))
        assertThat(portrait.notice).isNull()
        assertThat(portrait.otherSlotNotice)
            .isEqualTo(
                ModelNotice(
                    ModelNoticeKind.OTHER_SLOT_WRONG_SHAPE,
                    "Landscape: t.tflite (tall) is not used \u2014 pick a wide or square model",
                ),
            )
    }

    @Test
    fun aWidePortraitSlot_isNoticedWhileTheScreenIsInLandscape() {
        val slots = OrientationSlots(SlotChoice.Library(metaB.sha256), SlotChoice.Library(metaB.sha256))

        val landscape = load(library(slots), ModelOrientation.LANDSCAPE)

        assertThat(landscape.notice).isNull()
        assertThat(landscape.otherSlotNotice)
            .isEqualTo(
                ModelNotice(
                    ModelNoticeKind.OTHER_SLOT_WRONG_SHAPE,
                    "Portrait: b.tflite (wide) is not used \u2014 pick a tall or square model",
                ),
            )
    }

    @Test
    fun bothSlotsWrong_eachOrientationShowsItsOwnNoticeAndTheOtherSlotsNotice() {
        val entries = listOf(LibraryEntry(metaB, true), LibraryEntry(metaT, true))
        val slots = OrientationSlots(SlotChoice.Library(metaB.sha256), SlotChoice.Library(metaT.sha256))

        val portrait = load(library(slots, entries), ModelOrientation.PORTRAIT)

        assertThat(portrait.notice).isEqualTo(ModelNotice(ModelNoticeKind.WRONG_SHAPE, "b.tflite 832x384 (wide)"))
        assertThat(portrait.otherSlotNotice?.kind).isEqualTo(ModelNoticeKind.OTHER_SLOT_WRONG_SHAPE)
        assertThat(portrait.otherSlotNotice?.message).startsWith("Landscape: t.tflite (tall)")
    }

    @Test
    fun fittingSlots_haveNoOtherSlotNotice() {
        val slots = OrientationSlots(SlotChoice.Library(metaA.sha256), SlotChoice.Library(metaB.sha256))

        assertThat(load(library(slots), ModelOrientation.PORTRAIT).otherSlotNotice).isNull()
        assertThat(load(library(slots), ModelOrientation.LANDSCAPE).otherSlotNotice).isNull()
        assertThat(load(library(), ModelOrientation.PORTRAIT).otherSlotNotice).isNull()
    }

    @Test
    fun aWrongShapeSlot_doesNotHideTheLoadTripOfItsModel() {
        val slots = OrientationSlots(SlotChoice.Library(metaB.sha256), SlotChoice.BuiltIn)

        val portrait = load(library(slots, blocked = setOf(metaB.sha256)), ModelOrientation.PORTRAIT)

        assertThat(portrait.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(portrait.notice).isEqualTo(ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD))
    }

    @Test
    fun aWrongShapeSlot_doesNotHideABlockedBundledModel() {
        val slots = OrientationSlots(SlotChoice.Library(metaB.sha256), SlotChoice.BuiltIn)
        val last =
            ModelStatus(ModelSource.BUILT_IN, metaB, ModelFallback.WRONG_SHAPE, "built-in model skipped: it crashed earlier", 416, 416)
                .copy(builtInFailed = true, builtInBlocked = true)

        val portrait = ControlModelMapper.libraryLoaded(library(slots), last, ModelOrientation.PORTRAIT, 4)

        assertThat(portrait.notice).isEqualTo(ModelNotice(ModelNoticeKind.BUILT_IN_BLOCKED, "built-in model skipped: it crashed earlier"))
    }
}
