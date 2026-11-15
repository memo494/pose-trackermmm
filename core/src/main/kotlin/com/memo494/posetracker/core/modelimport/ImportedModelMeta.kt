package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/**
 * What the app remembers about the one imported model (M0c D1), stored as one small text value next to
 * the copied file.
 *
 * Callers build [displayName] with [sanitiseName]; [encode] does not repair a name, and [decode] refuses
 * any text whose name [sanitiseName] would change.
 *
 * @property displayName shown to the owner; a clean bare file name, see [sanitiseName].
 * @property sha256 SHA-256 of the copied file, 64 lowercase hex characters.
 * @property bytes size of the copied file, in bytes.
 * @property inputSize side of the model's square input, in pixels (Mdl space).
 * @property outputRows rows `N` of the model's `[1, N, 57]` output, unitless.
 */
data class ImportedModelMeta(
    val displayName: String,
    val sha256: String,
    val bytes: Long,
    val inputSize: Int,
    val outputRows: Int,
) {
    /**
     * The versioned text form: [HEADER] then `name=`, `sha256=`, `bytes=`, `inputSize=`, `outputRows=`, one per
     * line in that order, joined by `\n` with no trailing newline.
     */
    fun encode(): String =
        listOf(
            HEADER,
            "$KEY_NAME$displayName",
            "$KEY_SHA256$sha256",
            "$KEY_BYTES$bytes",
            "$KEY_INPUT_SIZE$inputSize",
            "$KEY_OUTPUT_ROWS$outputRows",
        ).joinToString(LINE_SEPARATOR.toString())

    companion object {
        private const val HEADER = "posetracker-imported-model v1"
        private const val KEY_NAME = "name="
        private const val KEY_SHA256 = "sha256="
        private const val KEY_BYTES = "bytes="
        private const val KEY_INPUT_SIZE = "inputSize="
        private const val KEY_OUTPUT_ROWS = "outputRows="
        private const val LINE_SEPARATOR = '\n'
        private const val EXPECTED_LINES = 6
        private const val LINE_NAME = 1
        private const val LINE_SHA256 = 2
        private const val LINE_BYTES = 3
        private const val LINE_INPUT_SIZE = 4
        private const val LINE_OUTPUT_ROWS = 5
        private const val DECIMAL_DIGITS_MAX = 19

        /**
         * Reads the text written by [encode]. Strict: returns `null` for anything unexpected (a wrong or
         * missing version line, a missing, extra, renamed or reordered line, `\r`, a trailing newline, a
         * bad number or one not in canonical decimal form, a bad SHA-256, a name [sanitiseName] would change,
         * [bytes] outside 1..[TrackerConfig.MAX_IMPORT_BYTES_LIMIT], [inputSize] outside
         * [TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT]..[TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT] or not a multiple
         * of [TrackerConfig.IMPORT_INPUT_SIZE_STEP], [outputRows] outside 1..[TrackerConfig.IMPORT_MAX_OUTPUT_ROWS]).
         * These ranges are sanity checks independent of the configured import limits. Never throws.
         */
        fun decode(text: String): ImportedModelMeta? {
            val lines = text.split(LINE_SEPARATOR)
            if (lines.size != EXPECTED_LINES || lines[0] != HEADER) return null
            val name = lines[LINE_NAME].removeIfStarts(KEY_NAME) ?: return null
            val sha256 = lines[LINE_SHA256].removeIfStarts(KEY_SHA256) ?: return null
            val bytes = lines[LINE_BYTES].removeIfStarts(KEY_BYTES)?.let(::parseCanonical) ?: return null
            val inputSize = lines[LINE_INPUT_SIZE].removeIfStarts(KEY_INPUT_SIZE)?.let(::parseCanonical) ?: return null
            val outputRows = lines[LINE_OUTPUT_ROWS].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null

            val valid =
                sanitiseName(name) == name &&
                    isLowercaseHexDigest(sha256) &&
                    bytes in 1L..TrackerConfig.MAX_IMPORT_BYTES_LIMIT &&
                    inputSize in TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT.toLong()..TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT.toLong() &&
                    inputSize % TrackerConfig.IMPORT_INPUT_SIZE_STEP == 0L &&
                    outputRows in 1L..TrackerConfig.IMPORT_MAX_OUTPUT_ROWS.toLong()
            if (!valid) return null
            return ImportedModelMeta(name, sha256, bytes, inputSize.toInt(), outputRows.toInt())
        }

        /**
         * A safe display name from a picked file's raw name (which may be `null`, a path, or contain anything):
         * keeps only the part after the last `/` or `\`; keeps only ASCII letters, digits, `.`, `_`, `-` and the
         * space (everything else, control and non-ASCII characters included, is dropped); strips leading dots
         * and spaces and trailing spaces; cuts at [TrackerConfig.IMPORT_NAME_MAX_CHARS] characters. When nothing
         * with a letter or digit is left the result is [TrackerConfig.IMPORT_FALLBACK_NAME]; never empty.
         * Idempotent.
         */
        fun sanitiseName(raw: String?): String {
            if (raw == null) return TrackerConfig.IMPORT_FALLBACK_NAME
            val last = raw.substring(maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1)
            val kept = last.filter(::isAllowedNameChar).trimStart('.', ' ')
            val capped = kept.take(TrackerConfig.IMPORT_NAME_MAX_CHARS).trimEnd(' ')
            return if (capped.any { it.isAsciiLetterOrDigit() }) capped else TrackerConfig.IMPORT_FALLBACK_NAME
        }

        private fun isAllowedNameChar(c: Char): Boolean = c.isAsciiLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == ' '

        private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

        private fun String.removeIfStarts(prefix: String): String? = if (startsWith(prefix)) substring(prefix.length) else null

        /** Digits only, no sign, no leading zero (except `0` itself), at most 19 digits and fitting a Long; else `null`. */
        private fun parseCanonical(text: String): Long? {
            if (text.isEmpty() || text.length > DECIMAL_DIGITS_MAX) return null
            if (text.length > 1 && text[0] == '0') return null
            if (!text.all { it in '0'..'9' }) return null
            return text.toLongOrNull()
        }

        private fun isLowercaseHexDigest(text: String): Boolean =
            text.length == TrackerConfig.SHA256_HEX_LENGTH && text.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
