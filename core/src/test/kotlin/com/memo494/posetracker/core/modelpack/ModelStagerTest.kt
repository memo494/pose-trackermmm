package com.memo494.posetracker.core.modelpack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class ModelStagerTest {
    @TempDir
    lateinit var tempDir: File

    private val fileName = "model_a.tflite"
    private val content = ByteArray(200_000) { index -> (index * 7).toByte() }
    private val sha = Sha256.hex(ByteArrayInputStream(content))
    private val stagedName = "model_a-${sha.take(16)}.tflite"

    private fun stagingDirectory(): File = File(tempDir, "staged")

    private fun namesIn(directory: File): List<String> {
        val files = directory.listFiles().orEmpty()
        return files.map { it.name }.sorted()
    }

    /** Opens [bytes] and counts how often and whether the stream was closed. */
    private class CountingSource(
        private val bytes: ByteArray,
    ) {
        var opens = 0
        var closes = 0

        fun open(): InputStream {
            opens++
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closes++
                    super.close()
                }
            }
        }
    }

    /** Delivers [goodBytes] bytes and then fails with an [IOException]. */
    private class FailingStream(
        private val goodBytes: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= goodBytes) throw IOException("source failed")
            position++
            return 1
        }
    }

    @Test
    fun stage_freshFile_isCopiedAndNamedWithTheShaPrefix() {
        val source = CountingSource(content)

        val staged = ModelStager(stagingDirectory()).stage(fileName, sha) { source.open() }

        assertThat(staged.name).isEqualTo(stagedName)
        assertThat(staged.parentFile).isEqualTo(stagingDirectory())
        assertThat(staged.readBytes()).isEqualTo(content)
        assertThat(namesIn(stagingDirectory())).containsExactly(stagedName)
        assertThat(source.opens).isEqualTo(1)
    }

    @Test
    fun stage_closesTheSourceStream() {
        val source = CountingSource(content)

        ModelStager(stagingDirectory()).stage(fileName, sha) { source.open() }

        assertThat(source.closes).isEqualTo(1)
    }

    @Test
    fun stage_validExistingFile_isReusedWithoutOpeningTheSource() {
        val stager = ModelStager(stagingDirectory())
        val first = stager.stage(fileName, sha) { ByteArrayInputStream(content) }
        val source = CountingSource(content)

        val second = stager.stage(fileName, sha) { source.open() }

        assertThat(second).isEqualTo(first)
        assertThat(source.opens).isEqualTo(0)
        assertThat(second.readBytes()).isEqualTo(content)
    }

    @Test
    fun stage_tamperedExistingFile_isReplacedFromTheSource() {
        val stager = ModelStager(stagingDirectory())
        val first = stager.stage(fileName, sha) { ByteArrayInputStream(content) }
        first.writeBytes(ByteArray(content.size))
        val source = CountingSource(content)

        val second = stager.stage(fileName, sha) { source.open() }

        assertThat(source.opens).isEqualTo(1)
        assertThat(second.name).isEqualTo(stagedName)
        assertThat(second.readBytes()).isEqualTo(content)
        assertThat(namesIn(stagingDirectory())).containsExactly(stagedName)
    }

    @Test
    fun stage_tamperedSource_isRejectedAndLeavesNoFile() {
        val tampered = content.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val actualSha = Sha256.hex(ByteArrayInputStream(tampered))

        val error =
            assertThrows(ModelIntegrityException::class.java) {
                ModelStager(stagingDirectory()).stage(fileName, sha) { ByteArrayInputStream(tampered) }
            }

        assertThat(error.message).contains(fileName)
        assertThat(error.message).contains(sha)
        assertThat(error.message).contains(actualSha)
        assertThat(namesIn(stagingDirectory())).isEmpty()
    }

    @Test
    fun modelIntegrityException_isAnIOException() {
        assertThat(ModelIntegrityException("x")).isInstanceOf(IOException::class.java)
    }

    @Test
    fun stage_tamperedExistingFileAndTamperedSource_leavesNoFile() {
        val stager = ModelStager(stagingDirectory())
        val first = stager.stage(fileName, sha) { ByteArrayInputStream(content) }
        first.writeBytes(ByteArray(content.size))

        assertThrows(ModelIntegrityException::class.java) {
            stager.stage(fileName, sha) { ByteArrayInputStream(ByteArray(content.size)) }
        }

        assertThat(namesIn(stagingDirectory())).isEmpty()
    }

    @Test
    fun stage_sourceThatFailsMidCopy_leavesNoFile() {
        val error =
            assertThrows(IOException::class.java) {
                ModelStager(stagingDirectory()).stage(fileName, sha) { FailingStream(goodBytes = 100_000) }
            }

        assertThat(error.message).isEqualTo("source failed")
        assertThat(namesIn(stagingDirectory())).isEmpty()
    }

    @Test
    fun stage_sourceThatCannotBeOpened_leavesNoFile() {
        assertThrows(IOException::class.java) {
            ModelStager(stagingDirectory()).stage(fileName, sha) { throw IOException("no such asset") }
        }

        assertThat(namesIn(stagingDirectory())).isEmpty()
    }

    @Test
    fun stage_olderVersionsAndTmpLeftovers_areDeletedButOtherModelsStay() {
        val directory = stagingDirectory()
        assertThat(directory.mkdirs()).isTrue()
        val oldSha = "0123456789abcdef"
        val removed =
            listOf(
                "model_a-$oldSha.tflite",
                "model_a-$oldSha.tflite.tmp",
                "model_a-${sha.take(16)}.tflite.tmp",
            )
        val kept =
            listOf(
                "model_b-$oldSha.tflite",
                "model_b-$oldSha.tflite.tmp",
                "model_a-extra-$oldSha.tflite",
                "model_a-$oldSha.bin",
                "notes.txt",
            )
        (removed + kept).forEach { File(directory, it).writeText("old") }

        val staged = ModelStager(directory).stage(fileName, sha) { ByteArrayInputStream(content) }

        assertThat(namesIn(directory)).containsExactlyElementsIn(kept + stagedName)
        assertThat(staged.readBytes()).isEqualTo(content)
    }

    @Test
    fun stage_reusingAValidFile_alsoDeletesOlderVersions() {
        val directory = stagingDirectory()
        val stager = ModelStager(directory)
        stager.stage(fileName, sha) { ByteArrayInputStream(content) }
        File(directory, "model_a-0123456789abcdef.tflite").writeText("old")
        File(directory, "model_b-0123456789abcdef.tflite").writeText("other")

        stager.stage(fileName, sha) { throw AssertionError("the source must not be opened") }

        assertThat(namesIn(directory)).containsExactly(stagedName, "model_b-0123456789abcdef.tflite")
    }

    @Test
    fun stage_invalidFileName_isRejectedBeforeTouchingTheDisk() {
        val badNames =
            listOf("", "a/b.tflite", "a\\b.tflite", "..", "../a.tflite", "a..b.tflite", ".hidden.tflite", "noext", "a.")
        for (badName in badNames) {
            assertThrows(IllegalArgumentException::class.java) {
                ModelStager(stagingDirectory()).stage(badName, sha) { throw AssertionError("must not open") }
            }
        }
        assertThat(stagingDirectory().exists()).isFalse()
    }

    @Test
    fun stage_invalidSha256_isRejectedBeforeTouchingTheDisk() {
        val badShas =
            listOf("", sha.take(63), sha + "0", sha.uppercase(), "g" + sha.drop(1), sha.take(63) + " ")
        for (badSha in badShas) {
            assertThrows(IllegalArgumentException::class.java) {
                ModelStager(stagingDirectory()).stage(fileName, badSha) { throw AssertionError("must not open") }
            }
        }
        assertThat(stagingDirectory().exists()).isFalse()
    }

    @Test
    fun stage_directoryThatCannotBeCreated_isAnIOException() {
        val blocker = File(tempDir, "blocker")
        blocker.writeText("a regular file where the directory should go")

        assertThrows(IOException::class.java) {
            ModelStager(File(blocker, "staged")).stage(fileName, sha) { ByteArrayInputStream(content) }
        }
    }

    @Test
    fun stage_missingNestedDirectory_isCreated() {
        val directory = File(tempDir, "a/b/c")

        val staged = ModelStager(directory).stage(fileName, sha) { ByteArrayInputStream(content) }

        assertThat(staged.parentFile).isEqualTo(directory)
        assertThat(staged.isFile).isTrue()
    }

    @Test
    fun stage_nameWithSeveralDots_splitsAtTheLastDot() {
        val staged = ModelStager(stagingDirectory()).stage("yolo.v2.tflite", sha) { ByteArrayInputStream(content) }

        assertThat(staged.name).isEqualTo("yolo.v2-${sha.take(16)}.tflite")
    }
}
