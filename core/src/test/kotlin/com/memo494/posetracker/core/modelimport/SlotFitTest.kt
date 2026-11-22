package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SlotFitTest {
    private fun meta(
        width: Int,
        height: Int,
    ) = ImportedModelMeta("m.tflite", "a".repeat(64), 1_000_000L, width, height, 300)

    @Test
    fun tallModel_fitsPortraitOnly() {
        val tall = meta(448, 960)

        assertThat(fitsSlot(tall, ModelOrientation.PORTRAIT)).isTrue()
        assertThat(fitsSlot(tall, ModelOrientation.LANDSCAPE)).isFalse()
    }

    @Test
    fun wideModel_fitsLandscapeOnly() {
        val wide = meta(960, 448)

        assertThat(fitsSlot(wide, ModelOrientation.LANDSCAPE)).isTrue()
        assertThat(fitsSlot(wide, ModelOrientation.PORTRAIT)).isFalse()
    }

    @Test
    fun squareModel_fitsBoth() {
        val square = meta(640, 640)

        assertThat(fitsSlot(square, ModelOrientation.PORTRAIT)).isTrue()
        assertThat(fitsSlot(square, ModelOrientation.LANDSCAPE)).isTrue()
    }

    @Test
    fun orientationOfShape_isReadFromTheTensorOnly() {
        assertThat(orientationOfShape(meta(960, 448))).isEqualTo(ModelOrientation.LANDSCAPE)
        assertThat(orientationOfShape(meta(448, 960))).isEqualTo(ModelOrientation.PORTRAIT)
        assertThat(orientationOfShape(meta(640, 640))).isNull()
    }

    @Test
    fun shapeWord_namesTheShape() {
        assertThat(shapeWord(meta(960, 448))).isEqualTo("wide")
        assertThat(shapeWord(meta(448, 960))).isEqualTo("tall")
        assertThat(shapeWord(meta(640, 640))).isEqualTo("square")
    }
}
