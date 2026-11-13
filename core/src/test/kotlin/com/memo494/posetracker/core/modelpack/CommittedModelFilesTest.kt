package com.memo494.posetracker.core.modelpack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the committed `model/` directory: Gradle runs tests with the module directory as working
 * directory, so the root `model/` is `../model`. `core/build.gradle.kts` declares it as a test input, so a
 * changed model file always re-runs this test.
 */
class CommittedModelFilesTest {
    private val modelDirectory = File("../model")

    @Test
    fun everyListedModelFile_existsAndMatchesItsSha256() {
        val info = ModelInfo.parse(File(modelDirectory, ModelInfo.ASSET_NAME).readText())

        assertThat(info.entries).isNotEmpty()
        for (entry in info.entries) {
            val file = File(modelDirectory, entry.fileName)
            assertThat(file.isFile).isTrue()
            val actual = file.inputStream().use { Sha256.hex(it) }
            assertThat(actual).isEqualTo(entry.sha256)
        }
    }
}
