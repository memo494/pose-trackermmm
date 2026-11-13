package com.memo494.posetracker.core.modelpack

import com.memo494.posetracker.core.TrackerConfig

/**
 * The one definition of a valid model file name and SHA-256, shared by [ModelInfo] (the document) and
 * [ModelStager] (the disk), so neither accepts what the other would refuse.
 */
internal object ModelFileRules {
    /**
     * Fails with [IllegalArgumentException] unless [fileName] is a bare file name with an extension: not
     * empty, no `/` or `\`, no `..`, no leading dot, and a dot that is neither first nor last.
     */
    fun requireFileName(fileName: String) {
        require(isBareFileName(fileName)) {
            "model file name \"$fileName\" must be a bare name with an extension (no path, no leading dot)"
        }
    }

    /** Fails with [IllegalArgumentException] unless [sha256] is exactly 64 characters of `0-9a-f`. */
    fun requireSha256(sha256: String) {
        require(isLowercaseHexDigest(sha256)) {
            "sha256 \"$sha256\" must be ${TrackerConfig.SHA256_HEX_LENGTH} lowercase hex characters (0-9, a-f)"
        }
    }

    /** The part of the valid [fileName] before its last dot. */
    fun baseOf(fileName: String): String = fileName.substring(0, fileName.lastIndexOf('.'))

    /** The part of the valid [fileName] after its last dot, without the dot. */
    fun extensionOf(fileName: String): String = fileName.substring(fileName.lastIndexOf('.') + 1)

    private fun isBareFileName(fileName: String): Boolean {
        if (fileName.isEmpty() || fileName.startsWith('.') || ".." in fileName) return false
        if ('/' in fileName || '\\' in fileName) return false
        val lastDot = fileName.lastIndexOf('.')
        return lastDot > 0 && lastDot < fileName.length - 1
    }

    private fun isLowercaseHexDigest(sha256: String): Boolean =
        sha256.length == TrackerConfig.SHA256_HEX_LENGTH && sha256.all { it in '0'..'9' || it in 'a'..'f' }
}
