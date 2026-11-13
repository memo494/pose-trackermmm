package com.memo494.posetracker.core.modelpack

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class Sha256Test {
    private fun hexOf(bytes: ByteArray): String = Sha256.hex(ByteArrayInputStream(bytes))

    @Test
    fun hex_ofEmptyInput_isTheWellKnownDigest() {
        assertThat(hexOf(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun hex_ofAbc_isTheWellKnownDigest() {
        assertThat(hexOf("abc".toByteArray(Charsets.US_ASCII)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun hex_ofInputLargerThanTheBuffer_matchesTheJdkDigest() {
        val bytes = ByteArray(TrackerConfig.MODEL_IO_BUFFER_BYTES * 3 + 7) { index -> (index * 31).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)

        val actual = hexOf(bytes)

        assertThat(actual).hasLength(TrackerConfig.SHA256_HEX_LENGTH)
        assertThat(actual).isEqualTo(expected.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun hex_doesNotCloseTheStream() {
        var closed = false
        val stream =
            object : InputStream() {
                override fun read(): Int = -1

                override fun close() {
                    closed = true
                }
            }

        Sha256.hex(stream)

        assertThat(closed).isFalse()
    }
}
