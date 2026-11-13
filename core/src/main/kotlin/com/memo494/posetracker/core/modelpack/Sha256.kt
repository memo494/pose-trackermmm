package com.memo494.posetracker.core.modelpack

import com.memo494.posetracker.core.TrackerConfig
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 of a byte stream, written as lowercase hex. Load-time only (model files), never per frame. */
object Sha256 {
    private const val ALGORITHM = "SHA-256"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BITS_PER_HEX_DIGIT = 4
    private const val HEX_DIGIT_MASK = 0x0F
    private const val BYTE_MASK = 0xFF
    private const val HEX_CHARS_PER_BYTE = 2

    /**
     * Reads [input] to its end through one [TrackerConfig.MODEL_IO_BUFFER_BYTES] buffer (so a model of
     * any size never sits in memory) and returns the digest as 64 lowercase hex characters.
     * Does not close [input]; an [java.io.IOException] from the stream is passed on.
     */
    fun hex(input: InputStream): String {
        val digest = newDigest()
        val buffer = ByteArray(TrackerConfig.MODEL_IO_BUFFER_BYTES)
        var count = input.read(buffer)
        while (count >= 0) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        return toHex(digest.digest())
    }

    /** Creates the [MessageDigest] [hex] and [ModelStager] feed; one place for the algorithm name. */
    internal fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    /** [digest] as lowercase hex, two characters per byte. */
    internal fun toHex(digest: ByteArray): String {
        val out = StringBuilder(digest.size * HEX_CHARS_PER_BYTE)
        for (byte in digest) {
            val value = byte.toInt() and BYTE_MASK
            out.append(HEX_DIGITS[value shr BITS_PER_HEX_DIGIT])
            out.append(HEX_DIGITS[value and HEX_DIGIT_MASK])
        }
        return out.toString()
    }
}
