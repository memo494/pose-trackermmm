package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelpack.ModelIntegrityException
import com.memo494.posetracker.core.modelpack.Sha256
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class ImportedFileCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val content = ByteArray(CONTENT_BYTES) { it.toByte() }

    private fun metaFor(file: File): ImportedModelMeta =
        ImportedModelMeta(
            displayName = "model.tflite",
            sha256 = file.inputStream().use { Sha256.hex(it) },
            bytes = file.length(),
            inputWidth = 640,
            inputHeight = 640,
            outputRows = 300,
        )

    @Test
    fun matchingFilePasses() {
        val file = folder.newFile("model.tflite").apply { writeBytes(content) }

        PoseDetectors.verifyImportedFile(file, metaFor(file))
    }

    @Test
    fun missingFileThrowsFileNotFound() {
        val file = folder.newFile("model.tflite").apply { writeBytes(content) }
        val meta = metaFor(file)
        file.delete()

        assertThrows(FileNotFoundException::class.java) { PoseDetectors.verifyImportedFile(file, meta) }
    }

    @Test
    fun directoryInsteadOfFileThrowsFileNotFound() {
        val file = folder.newFile("model.tflite").apply { writeBytes(content) }
        val meta = metaFor(file)

        assertThrows(FileNotFoundException::class.java) { PoseDetectors.verifyImportedFile(folder.newFolder("dir"), meta) }
    }

    @Test
    fun wrongLengthThrowsIntegrityBeforeHashing() {
        val file = folder.newFile("model.tflite").apply { writeBytes(content) }
        val meta = metaFor(file).copy(bytes = file.length() + 1)

        val thrown = assertThrows(ModelIntegrityException::class.java) { PoseDetectors.verifyImportedFile(file, meta) }

        assertThat(thrown).hasMessageThat().contains("bytes")
    }

    @Test
    fun wrongHashThrowsIntegrity() {
        val file = folder.newFile("model.tflite").apply { writeBytes(content) }
        val meta = metaFor(file)
        file.writeBytes(ByteArray(CONTENT_BYTES) { 7 })

        val thrown = assertThrows(ModelIntegrityException::class.java) { PoseDetectors.verifyImportedFile(file, meta) }

        assertThat(thrown).hasMessageThat().contains("sha256")
    }

    private companion object {
        const val CONTENT_BYTES = 4096
    }
}
