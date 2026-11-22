package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AutoSlotTest {
    private fun meta(
        digit: Char,
        width: Int,
        height: Int,
    ) = ImportedModelMeta("m$digit.tflite", digit.toString().repeat(64), 1_000_000L, width, height, 300)

    private val tall = meta('a', 448, 960)
    private val wide = meta('b', 960, 448)
    private val square = meta('c', 640, 640)

    private fun lib(vararg metas: ImportedModelMeta) = ModelLibrary.of(metas.toList())

    private fun slot(meta: ImportedModelMeta) = SlotChoice.Library(meta.sha256)

    @Test
    fun tallModel_goesToPortrait_whenPortraitIsBuiltIn() {
        assertThat(autoSlotFor(tall, OrientationSlots(), ModelLibrary.EMPTY)).isEqualTo(ModelOrientation.PORTRAIT)
    }

    @Test
    fun wideModel_goesToLandscape_whenLandscapeIsBuiltIn() {
        assertThat(autoSlotFor(wide, OrientationSlots(), ModelLibrary.EMPTY)).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun bothSlotsBuiltIn_onlyTheMatchingSlotIsChosen() {
        val slots = OrientationSlots(SlotChoice.BuiltIn, SlotChoice.BuiltIn)

        assertThat(autoSlotFor(tall, slots, ModelLibrary.EMPTY)).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(autoSlotFor(wide, slots, ModelLibrary.EMPTY)).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun wideModel_replacesATallModelInLandscape_theMigrationState() {
        val slots = OrientationSlots(slot(tall), slot(tall))

        assertThat(autoSlotFor(wide, slots, lib(tall))).isEqualTo(ModelOrientation.LANDSCAPE)
    }

    @Test
    fun tallModel_replacesAWideModelInPortrait() {
        val slots = OrientationSlots(slot(wide), slot(wide))

        assertThat(autoSlotFor(tall, slots, lib(wide))).isEqualTo(ModelOrientation.PORTRAIT)
    }

    @Test
    fun slotThatAlreadyFits_isNeverOverwritten() {
        val otherTall = meta('d', 384, 832)
        val slots = OrientationSlots(slot(otherTall), SlotChoice.BuiltIn)

        assertThat(autoSlotFor(tall, slots, lib(otherTall))).isNull()
    }

    @Test
    fun slotHoldingASquareModel_isTheOwnersChoiceAndIsKept() {
        val slots = OrientationSlots(slot(square), slot(square))

        assertThat(autoSlotFor(tall, slots, lib(square))).isNull()
        assertThat(autoSlotFor(wide, slots, lib(square))).isNull()
    }

    @Test
    fun squareModel_assignsNothing() {
        assertThat(autoSlotFor(square, OrientationSlots(), ModelLibrary.EMPTY)).isNull()
    }

    @Test
    fun duplicate_assignsNothing_evenWhenTheSlotIsBuiltIn() {
        assertThat(autoSlotFor(tall, OrientationSlots(), lib(tall))).isNull()
    }

    @Test
    fun slotPointingAtAModelMissingFromTheLibrary_failsClosed() {
        val slots = OrientationSlots(SlotChoice.BuiltIn, slot(tall))

        assertThat(autoSlotFor(wide, slots, ModelLibrary.EMPTY)).isNull()
    }

    @Test
    fun onlyTheMatchingSlotIsLookedAt() {
        val slots = OrientationSlots(portrait = slot(tall), landscape = SlotChoice.BuiltIn)

        assertThat(autoSlotFor(wide, slots, lib(tall))).isEqualTo(ModelOrientation.LANDSCAPE)
    }
}
