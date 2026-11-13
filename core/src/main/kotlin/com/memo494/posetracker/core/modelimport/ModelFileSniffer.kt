package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/** Why [ModelFileSniffer] declined a file; one value per rule, so a message can say which rule failed. */
enum class SniffRejection {
    /** The file is smaller than [TrackerConfig.importMinBytes] (or its size is unknown or negative). */
    TOO_SMALL,

    /** The file is larger than [TrackerConfig.importMaxBytes]. */
    TOO_LARGE,

    /** Fewer than [ModelFileSniffer.HEADER_BYTES] bytes were read, so the identifier cannot be checked. */
    HEADER_TOO_SHORT,

    /** Bytes 4 to 7 are not the ASCII text `TFL3`, the identifier of a TensorFlow Lite FlatBuffer. */
    NOT_TFLITE,
}

/** Result of [ModelFileSniffer.looksLikeTflite]. */
sealed interface SniffResult {
    /** The file passed every cheap check; it may still not be a usable model (the probe decides). */
    object Ok : SniffResult {
        override fun toString(): String = "Ok"
    }

    /** The file failed the rule [reason]. */
    data class Rejected(
        val reason: SniffRejection,
    ) : SniffResult
}

/**
 * The cheap first look at a picked file, before anything is copied or opened by LiteRT: its size and the
 * FlatBuffers file identifier. Pure; never throws for bad data and never guesses (a file it is not sure
 * about is rejected, a later step answers).
 */
object ModelFileSniffer {
    /** Number of leading bytes the caller must read: 4 bytes of root offset, then the 4-byte identifier. */
    const val HEADER_BYTES = 8

    /** Offset of the identifier inside the header, in bytes. */
    private const val IDENTIFIER_OFFSET = 4

    private val IDENTIFIER = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    /**
     * Checks [fileBytes] against [TrackerConfig.importMinBytes]..[TrackerConfig.importMaxBytes] (bytes), then
     * [header] (the first bytes of the file, at least [HEADER_BYTES]) for `TFL3` at bytes 4 to 7. Rules are
     * checked in that order: too small, too large, header too short, not TFLite.
     *
     * @param header the leading bytes of the file; only the first [HEADER_BYTES] are looked at.
     * @param fileBytes the size of the whole file, in bytes; a negative value (size unknown) counts as too small.
     */
    fun looksLikeTflite(
        header: ByteArray,
        fileBytes: Long,
        config: TrackerConfig,
    ): SniffResult =
        when {
            fileBytes < config.importMinBytes -> SniffResult.Rejected(SniffRejection.TOO_SMALL)
            fileBytes > config.importMaxBytes -> SniffResult.Rejected(SniffRejection.TOO_LARGE)
            header.size < HEADER_BYTES -> SniffResult.Rejected(SniffRejection.HEADER_TOO_SHORT)
            !hasIdentifier(header) -> SniffResult.Rejected(SniffRejection.NOT_TFLITE)
            else -> SniffResult.Ok
        }

    private fun hasIdentifier(header: ByteArray): Boolean {
        for (i in IDENTIFIER.indices) {
            if (header[IDENTIFIER_OFFSET + i] != IDENTIFIER[i]) return false
        }
        return true
    }
}
