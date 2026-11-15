package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ModelFileSnifferTest {
    private val config = TrackerConfig()
    private val okBytes = config.importMinBytes + 1L

    private fun header(
        identifier: String = "TFL3",
        length: Int = ModelFileSniffer.HEADER_BYTES,
    ): ByteArray {
        val bytes = ByteArray(length)
        val id = identifier.toByteArray(Charsets.US_ASCII)
        for (i in id.indices) {
            if (ID_OFFSET + i < length) bytes[ID_OFFSET + i] = id[i]
        }
        return bytes
    }

    @Test
    fun headerBytes_isEight() {
        assertThat(ModelFileSniffer.HEADER_BYTES).isEqualTo(8)
    }

    @Test
    fun validHeaderAndSize_isOk() {
        assertThat(ModelFileSniffer.looksLikeTflite(header(), okBytes, config)).isEqualTo(SniffResult.Ok)
    }

    @Test
    fun longerHeader_isOk() {
        val result = ModelFileSniffer.looksLikeTflite(header(length = 64), okBytes, config)

        assertThat(result).isEqualTo(SniffResult.Ok)
    }

    @Test
    fun sizeAtBothLimits_isOk() {
        assertThat(ModelFileSniffer.looksLikeTflite(header(), config.importMinBytes, config)).isEqualTo(SniffResult.Ok)
        assertThat(ModelFileSniffer.looksLikeTflite(header(), config.importMaxBytes, config)).isEqualTo(SniffResult.Ok)
    }

    @Test
    fun sizeJustBelowTheMinimum_isTooSmall() {
        val result = ModelFileSniffer.looksLikeTflite(header(), config.importMinBytes - 1L, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.TOO_SMALL))
    }

    @Test
    fun zeroAndNegativeSizes_areTooSmall() {
        for (size in longArrayOf(0L, -1L, Long.MIN_VALUE)) {
            val result = ModelFileSniffer.looksLikeTflite(header(), size, config)

            assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.TOO_SMALL))
        }
    }

    @Test
    fun sizeJustAboveTheMaximum_isTooLarge() {
        val result = ModelFileSniffer.looksLikeTflite(header(), config.importMaxBytes + 1L, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.TOO_LARGE))
    }

    @Test
    fun hugeSize_isTooLarge() {
        val result = ModelFileSniffer.looksLikeTflite(header(), Long.MAX_VALUE, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.TOO_LARGE))
    }

    @Test
    fun headerShorterThanEightBytes_isRejectedAsTooShort() {
        for (length in 0 until ModelFileSniffer.HEADER_BYTES) {
            val result = ModelFileSniffer.looksLikeTflite(header(length = length), okBytes, config)

            assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.HEADER_TOO_SHORT))
        }
    }

    @Test
    fun wrongIdentifier_isNotTflite() {
        for (identifier in listOf("TFL2", "tfl3", "TFL4", "\u0000\u0000\u0000\u0000", "PK\u0003\u0004")) {
            val result = ModelFileSniffer.looksLikeTflite(header(identifier), okBytes, config)

            assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.NOT_TFLITE))
        }
    }

    @Test
    fun identifierAtTheWrongOffset_isNotTflite() {
        val bytes = ByteArray(ModelFileSniffer.HEADER_BYTES)
        "TFL3".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 0)

        val result = ModelFileSniffer.looksLikeTflite(bytes, okBytes, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.NOT_TFLITE))
    }

    @Test
    fun jpegHeader_isNotTflite() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte())

        val result = ModelFileSniffer.looksLikeTflite(jpeg, okBytes, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.NOT_TFLITE))
    }

    @Test
    fun sizeIsCheckedBeforeTheHeader() {
        val result = ModelFileSniffer.looksLikeTflite(ByteArray(0), 0L, config)

        assertThat(result).isEqualTo(SniffResult.Rejected(SniffRejection.TOO_SMALL))
    }

    @Test
    fun limitsComeFromTheConfig() {
        val custom = TrackerConfig(importMinBytes = 10L, importMaxBytes = 20L)

        assertThat(ModelFileSniffer.looksLikeTflite(header(), 10L, custom)).isEqualTo(SniffResult.Ok)
        assertThat(ModelFileSniffer.looksLikeTflite(header(), 21L, custom))
            .isEqualTo(SniffResult.Rejected(SniffRejection.TOO_LARGE))
    }

    private companion object {
        const val ID_OFFSET = 4
    }
}
