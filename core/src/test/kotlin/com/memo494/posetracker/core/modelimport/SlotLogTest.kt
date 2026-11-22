package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SlotLogTest {
    private val wide = ImportedModelMeta("wide.tflite", "0123456789abcdef".repeat(4), 1_000_000L, 960, 448, 300)
    private val tall = ImportedModelMeta("tall.tflite", "fedcba9876543210".repeat(4), 1_000_000L, 448, 960, 300)

    @Test
    fun picked_libraryModel_namesItsShaAndInput() {
        val line = SlotLog.afterSetSlot(ModelOrientation.LANDSCAPE, wide.sha256, wide, stored = true)

        assertThat(line).isEqualTo("slot: landscape = wide.tflite 0123456789abcdef 960x448")
    }

    @Test
    fun picked_builtIn() {
        val line = SlotLog.afterSetSlot(ModelOrientation.LANDSCAPE, null, null, stored = true)

        assertThat(line).isEqualTo("slot: landscape = built-in")
    }

    @Test
    fun refused_wrongShape_namesTheShape() {
        val line = SlotLog.afterSetSlot(ModelOrientation.PORTRAIT, wide.sha256, wide, stored = false)

        assertThat(line).isEqualTo("slot: portrait refused wide.tflite 0123456789abcdef 960x448 (wide)")
    }

    @Test
    fun notStored_forAnotherReason_saysTheChoiceFailed() {
        assertThat(SlotLog.afterSetSlot(ModelOrientation.PORTRAIT, tall.sha256, tall, stored = false))
            .isEqualTo("storing the model choice failed")
        assertThat(SlotLog.afterSetSlot(ModelOrientation.PORTRAIT, null, null, stored = false))
            .isEqualTo("storing the model choice failed")
    }

    @Test
    fun aShaTheLibraryDoesNotHold_isAnUnknownModel_withItsShortSha() {
        assertThat(SlotLog.afterSetSlot(ModelOrientation.PORTRAIT, tall.sha256, null, stored = false))
            .isEqualTo("slot: portrait unknown model fedcba9876543210")
        assertThat(SlotLog.afterSetSlot(ModelOrientation.LANDSCAPE, wide.sha256, null, stored = true))
            .isEqualTo("slot: landscape unknown model 0123456789abcdef")
    }

    @Test
    fun notUsed_atLoad_namesSlotModelInputAndShape() {
        assertThat(SlotLog.notUsed(ModelOrientation.LANDSCAPE, tall))
            .isEqualTo("model: landscape slot holds tall.tflite 448x960 (tall), not used; built-in runs")
    }
}
