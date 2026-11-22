package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelpack.ModelInfo
import org.junit.jupiter.api.Test
import java.io.File

class BuiltInModelsTest {
    @Test
    fun portrait_isTheTall448x960Model() {
        assertThat(BuiltInModels.widthFor(ModelOrientation.PORTRAIT)).isEqualTo(448)
        assertThat(BuiltInModels.heightFor(ModelOrientation.PORTRAIT)).isEqualTo(960)
        assertThat(BuiltInModels.inputText(ModelOrientation.PORTRAIT)).isEqualTo("448x960")
    }

    @Test
    fun landscape_isTheWide960x448Model() {
        assertThat(BuiltInModels.widthFor(ModelOrientation.LANDSCAPE)).isEqualTo(960)
        assertThat(BuiltInModels.heightFor(ModelOrientation.LANDSCAPE)).isEqualTo(448)
        assertThat(BuiltInModels.inputText(ModelOrientation.LANDSCAPE)).isEqualTo("960x448")
    }

    @Test
    fun everyBuiltInModel_andTheSquareFallback_isListedInTheCommittedModelInfo() {
        val info = ModelInfo.parse(File("../model/${ModelInfo.ASSET_NAME}").readText())

        for (orientation in ModelOrientation.entries) {
            val entry = info.entryFor(BuiltInModels.widthFor(orientation), BuiltInModels.heightFor(orientation))
            assertThat(entry.fileName).isEqualTo("yolo26n-pose_${BuiltInModels.inputText(orientation)}.tflite")
        }
        assertThat(info.entryFor(TrackerConfig().modelInputSize).fileName).isEqualTo("yolo26n-pose_416.tflite")
    }
}
