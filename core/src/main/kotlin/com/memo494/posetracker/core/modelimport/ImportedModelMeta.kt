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
 * @property inputWidth real width `W` of the model's input tensor (`[1, 3, H, W]` or `[1, H, W, 3]`), in pixels (Mdl space).
 * @property inputHeight real height `H` of the model's input tensor, in pixels (Mdl space).
 * @property outputRows rows `N` of the model's output (`[1, N, 57]`, `[1, 56, N]` or `[1, N, 56]`), unitless.
 * @property outputLayout how the output is laid out; [PoseOutputLayout.END_TO_END] for every model imported before
 *   raw outputs were supported.
 * @property inputLayout how the input tensor is laid out; [InputLayout.NCHW] for every model imported before NHWC
 *   inputs were supported.
 * @property classes class score columns `C` of a raw detect-only output ([PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST] /
 *   [PoseOutputLayout.DETECT_RAW_CHANNELS_LAST]), count in 1..[TrackerConfig.DETECT_MAX_CLASSES]; 0 for every other
 *   layout.
 */
data class ImportedModelMeta(
    val displayName: String,
    val sha256: String,
    val bytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputRows: Int,
    val outputLayout: PoseOutputLayout = PoseOutputLayout.END_TO_END,
    val inputLayout: InputLayout = InputLayout.NCHW,
    val classes: Int = 0,
) {
    /**
     * The versioned text form: [HEADER] then `name=`, `sha256=`, `bytes=`, `inputWidth=`, `inputHeight=`,
     * `outputRows=`, one per line in that order, joined by `\n` with no trailing newline. Version 2 for an
     * [PoseOutputLayout.END_TO_END] model (the text is what it always was); version 3 for a raw one, which adds a
     * last `outputLayout=` line (`raw_channels_first` or `raw_channels_last`); version 4 for an [InputLayout.NHWC]
     * model, which has both a `outputLayout=` line (`end_to_end`, `raw_channels_first` or `raw_channels_last`) and a last
     * `inputLayout=nhwc` line. Version 5 for a detect-only model (no keypoints): like version 4 but with the
     * `outputLayout=` value `detect_raw_channels_first`, `detect_raw_channels_last` or `detect_end_to_end`, `inputLayout=`
     * `nchw` or `nhwc`, and a last `classes=` line. Every other NCHW model keeps the text of versions 2 and 3.
     */
    fun encode(): String {
        if (outputLayout.isDetectOnly) return encodeV5()
        if (inputLayout == InputLayout.NHWC) return encodeV4()
        val lines =
            listOf(
                if (outputLayout.isRaw) HEADER_V3 else HEADER,
                "$KEY_NAME$displayName",
                "$KEY_SHA256$sha256",
                "$KEY_BYTES$bytes",
                "$KEY_INPUT_WIDTH$inputWidth",
                "$KEY_INPUT_HEIGHT$inputHeight",
                "$KEY_OUTPUT_ROWS$outputRows",
            )
        val all = if (outputLayout.isRaw) lines + "$KEY_OUTPUT_LAYOUT${layoutText(outputLayout)}" else lines
        return all.joinToString(LINE_SEPARATOR.toString())
    }

    private fun encodeV4(): String =
        listOf(
            HEADER_V4,
            "$KEY_NAME$displayName",
            "$KEY_SHA256$sha256",
            "$KEY_BYTES$bytes",
            "$KEY_INPUT_WIDTH$inputWidth",
            "$KEY_INPUT_HEIGHT$inputHeight",
            "$KEY_OUTPUT_ROWS$outputRows",
            "$KEY_OUTPUT_LAYOUT${layoutTextV4(outputLayout)}",
            "$KEY_INPUT_LAYOUT$LAYOUT_NHWC",
        ).joinToString(LINE_SEPARATOR.toString())

    private fun encodeV5(): String =
        listOf(
            HEADER_V5,
            "$KEY_NAME$displayName",
            "$KEY_SHA256$sha256",
            "$KEY_BYTES$bytes",
            "$KEY_INPUT_WIDTH$inputWidth",
            "$KEY_INPUT_HEIGHT$inputHeight",
            "$KEY_OUTPUT_ROWS$outputRows",
            "$KEY_OUTPUT_LAYOUT${layoutTextV5(outputLayout)}",
            "$KEY_INPUT_LAYOUT${if (inputLayout == InputLayout.NHWC) LAYOUT_NHWC else LAYOUT_NCHW}",
            "$KEY_CLASSES$classes",
        ).joinToString(LINE_SEPARATOR.toString())

    companion object {
        private const val HEADER = "posetracker-imported-model v2"
        private const val HEADER_V5 = "posetracker-imported-model v5"
        private const val KEY_CLASSES = "classes="
        private const val LAYOUT_NCHW = "nchw"
        private const val LAYOUT_DETECT_RAW_FIRST = "detect_raw_channels_first"
        private const val LAYOUT_DETECT_RAW_LAST = "detect_raw_channels_last"
        private const val LAYOUT_DETECT_END_TO_END = "detect_end_to_end"
        private const val EXPECTED_LINES_V5 = 10
        private const val LINE_CLASSES_V5 = 9
        private const val HEADER_V3 = "posetracker-imported-model v3"
        private const val HEADER_V4 = "posetracker-imported-model v4"
        private const val KEY_OUTPUT_LAYOUT = "outputLayout="
        private const val KEY_INPUT_LAYOUT = "inputLayout="
        private const val LAYOUT_END_TO_END = "end_to_end"
        private const val LAYOUT_NHWC = "nhwc"
        private const val EXPECTED_LINES_V4 = 9
        private const val LINE_OUTPUT_LAYOUT_V4 = 7
        private const val LINE_INPUT_LAYOUT_V4 = 8
        private const val LAYOUT_RAW_FIRST = "raw_channels_first"
        private const val LAYOUT_RAW_LAST = "raw_channels_last"
        private const val EXPECTED_LINES_V3 = 8
        private const val LINE_OUTPUT_LAYOUT = 7
        private const val HEADER_V1 = "posetracker-imported-model v1"
        private const val KEY_NAME = "name="
        private const val KEY_SHA256 = "sha256="
        private const val KEY_BYTES = "bytes="
        private const val KEY_INPUT_SIZE_V1 = "inputSize="
        private const val KEY_INPUT_WIDTH = "inputWidth="
        private const val KEY_INPUT_HEIGHT = "inputHeight="
        private const val KEY_OUTPUT_ROWS = "outputRows="
        private const val LINE_SEPARATOR = '\n'
        private const val EXPECTED_LINES_V1 = 6
        private const val EXPECTED_LINES = 7
        private const val LINE_NAME = 1
        private const val LINE_SHA256 = 2
        private const val LINE_BYTES = 3
        private const val LINE_INPUT_SIZE_V1 = 4
        private const val LINE_OUTPUT_ROWS_V1 = 5
        private const val LINE_INPUT_WIDTH = 4
        private const val LINE_INPUT_HEIGHT = 5
        private const val LINE_OUTPUT_ROWS = 6
        private const val DECIMAL_DIGITS_MAX = 19

        /**
         * Reads the text written by [encode] (versions 2 to 5; versions 1 to 3 are [InputLayout.NCHW]) or by the first
         * release (version 1, one `inputSize=` line, which becomes both [inputWidth] and [inputHeight]). Version 4
         * accepts only `inputLayout=nhwc` (an NCHW model is never written as version 4). Strict: returns `null` for anything
         * unexpected (a wrong or missing version line, a missing, extra, renamed or reordered line for that
         * version, `\r`, a trailing newline, a bad number or one not in canonical decimal form, a bad SHA-256,
         * a name [sanitiseName] would change, [bytes] outside 1..[TrackerConfig.MAX_IMPORT_BYTES_LIMIT],
         * [inputWidth] or [inputHeight] outside
         * [TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT]..[TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT] or not a multiple
         * of [TrackerConfig.IMPORT_INPUT_SIZE_STEP], [outputRows] outside 1..[TrackerConfig.IMPORT_MAX_OUTPUT_ROWS] (1..[TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS] for a version 3 raw layout),
         * or a long side more than [TrackerConfig.importMaxInputAspect] of [config] times the short side, so that
         * a stored text the importer could never have written is refused, for both versions). The size ranges are
         * sanity checks independent of the configured import size limits; the aspect limit is the configured one.
         * Never throws.
         *
         * @param config supplies the aspect limit; the importer passes its own, a caller without one gets the default.
         */
        fun decode(
            text: String,
            config: TrackerConfig = TrackerConfig(),
        ): ImportedModelMeta? {
            val lines = text.split(LINE_SEPARATOR)
            return when {
                lines.size == EXPECTED_LINES_V5 && lines[0] == HEADER_V5 -> decodeV5(lines, config)
                lines.size == EXPECTED_LINES_V4 && lines[0] == HEADER_V4 -> decodeV4(lines, config)
                lines.size == EXPECTED_LINES_V3 && lines[0] == HEADER_V3 -> decodeV3(lines, config)
                lines.size == EXPECTED_LINES && lines[0] == HEADER -> decodeV2(lines, config)
                lines.size == EXPECTED_LINES_V1 && lines[0] == HEADER_V1 -> decodeV1(lines, config)
                else -> null
            }
        }

        private fun decodeV2(
            lines: List<String>,
            config: TrackerConfig,
        ): ImportedModelMeta? {
            val width = lines[LINE_INPUT_WIDTH].removeIfStarts(KEY_INPUT_WIDTH)?.let(::parseCanonical) ?: return null
            val height = lines[LINE_INPUT_HEIGHT].removeIfStarts(KEY_INPUT_HEIGHT)?.let(::parseCanonical) ?: return null
            val rows = lines[LINE_OUTPUT_ROWS].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null
            return build(lines, width, height, rows, PoseOutputLayout.END_TO_END, config)
        }

        /** Version 3: version 2 plus the last `outputLayout=` line, which names a raw layout (never `end_to_end`). */
        private fun decodeV3(
            lines: List<String>,
            config: TrackerConfig,
        ): ImportedModelMeta? {
            val width = lines[LINE_INPUT_WIDTH].removeIfStarts(KEY_INPUT_WIDTH)?.let(::parseCanonical) ?: return null
            val height = lines[LINE_INPUT_HEIGHT].removeIfStarts(KEY_INPUT_HEIGHT)?.let(::parseCanonical) ?: return null
            val rows = lines[LINE_OUTPUT_ROWS].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null
            val layout =
                when (lines[LINE_OUTPUT_LAYOUT].removeIfStarts(KEY_OUTPUT_LAYOUT)) {
                    LAYOUT_RAW_FIRST -> PoseOutputLayout.RAW_CHANNELS_FIRST
                    LAYOUT_RAW_LAST -> PoseOutputLayout.RAW_CHANNELS_LAST
                    else -> return null
                }
            return build(lines, width, height, rows, layout, config)
        }

        /** Version 4: version 2 plus `outputLayout=` (any of the three) and `inputLayout=nhwc`. */
        private fun decodeV4(
            lines: List<String>,
            config: TrackerConfig,
        ): ImportedModelMeta? {
            val width = lines[LINE_INPUT_WIDTH].removeIfStarts(KEY_INPUT_WIDTH)?.let(::parseCanonical) ?: return null
            val height = lines[LINE_INPUT_HEIGHT].removeIfStarts(KEY_INPUT_HEIGHT)?.let(::parseCanonical) ?: return null
            val rows = lines[LINE_OUTPUT_ROWS].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null
            val outputLayout =
                when (lines[LINE_OUTPUT_LAYOUT_V4].removeIfStarts(KEY_OUTPUT_LAYOUT)) {
                    LAYOUT_END_TO_END -> PoseOutputLayout.END_TO_END
                    LAYOUT_RAW_FIRST -> PoseOutputLayout.RAW_CHANNELS_FIRST
                    LAYOUT_RAW_LAST -> PoseOutputLayout.RAW_CHANNELS_LAST
                    else -> return null
                }
            if (lines[LINE_INPUT_LAYOUT_V4].removeIfStarts(KEY_INPUT_LAYOUT) != LAYOUT_NHWC) return null
            return build(lines, width, height, rows, outputLayout, config, InputLayout.NHWC)
        }

        /** Version 5: a detect-only model; `outputLayout=` names a detect layout, `inputLayout=` is `nchw` or `nhwc`, then `classes=`. */
        private fun decodeV5(
            lines: List<String>,
            config: TrackerConfig,
        ): ImportedModelMeta? {
            val width = lines[LINE_INPUT_WIDTH].removeIfStarts(KEY_INPUT_WIDTH)?.let(::parseCanonical) ?: return null
            val height = lines[LINE_INPUT_HEIGHT].removeIfStarts(KEY_INPUT_HEIGHT)?.let(::parseCanonical) ?: return null
            val rows = lines[LINE_OUTPUT_ROWS].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null
            val outputLayout =
                when (lines[LINE_OUTPUT_LAYOUT_V4].removeIfStarts(KEY_OUTPUT_LAYOUT)) {
                    LAYOUT_DETECT_RAW_FIRST -> PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST
                    LAYOUT_DETECT_RAW_LAST -> PoseOutputLayout.DETECT_RAW_CHANNELS_LAST
                    LAYOUT_DETECT_END_TO_END -> PoseOutputLayout.DETECT_END_TO_END
                    else -> return null
                }
            val inputLayout =
                when (lines[LINE_INPUT_LAYOUT_V4].removeIfStarts(KEY_INPUT_LAYOUT)) {
                    LAYOUT_NCHW -> InputLayout.NCHW
                    LAYOUT_NHWC -> InputLayout.NHWC
                    else -> return null
                }
            val classes = lines[LINE_CLASSES_V5].removeIfStarts(KEY_CLASSES)?.let(::parseCanonical) ?: return null
            val classesFit =
                if (outputLayout == PoseOutputLayout.DETECT_END_TO_END) {
                    classes == 0L
                } else {
                    classes in TrackerConfig.DETECT_MIN_CLASSES.toLong()..TrackerConfig.DETECT_MAX_CLASSES.toLong()
                }
            if (!classesFit) return null
            return build(lines, width, height, rows, outputLayout, config, inputLayout, classes.toInt())
        }

        private fun layoutTextV5(layout: PoseOutputLayout): String =
            when (layout) {
                PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST -> LAYOUT_DETECT_RAW_FIRST
                PoseOutputLayout.DETECT_RAW_CHANNELS_LAST -> LAYOUT_DETECT_RAW_LAST
                PoseOutputLayout.DETECT_END_TO_END -> LAYOUT_DETECT_END_TO_END
                else -> error("only a detect-only meta has a version 5 outputLayout line")
            }

        private fun layoutTextV4(layout: PoseOutputLayout): String =
            if (layout == PoseOutputLayout.END_TO_END) LAYOUT_END_TO_END else layoutText(layout)

        private fun layoutText(layout: PoseOutputLayout): String =
            when (layout) {
                PoseOutputLayout.RAW_CHANNELS_FIRST -> LAYOUT_RAW_FIRST
                PoseOutputLayout.RAW_CHANNELS_LAST -> LAYOUT_RAW_LAST
                else -> error("a $layout meta has no version 3 outputLayout line")
            }

        private fun decodeV1(
            lines: List<String>,
            config: TrackerConfig,
        ): ImportedModelMeta? {
            val size = lines[LINE_INPUT_SIZE_V1].removeIfStarts(KEY_INPUT_SIZE_V1)?.let(::parseCanonical) ?: return null
            val rows = lines[LINE_OUTPUT_ROWS_V1].removeIfStarts(KEY_OUTPUT_ROWS)?.let(::parseCanonical) ?: return null
            return build(lines, size, size, rows, PoseOutputLayout.END_TO_END, config)
        }

        /**
         * The version-independent lines (name, sha256, bytes) and the range and aspect checks of the numbers already
         * read.
         */
        private fun build(
            lines: List<String>,
            width: Long,
            height: Long,
            outputRows: Long,
            outputLayout: PoseOutputLayout,
            config: TrackerConfig,
            inputLayout: InputLayout = InputLayout.NCHW,
            classes: Int = 0,
        ): ImportedModelMeta? {
            val name = lines[LINE_NAME].removeIfStarts(KEY_NAME) ?: return null
            val sha256 = lines[LINE_SHA256].removeIfStarts(KEY_SHA256) ?: return null
            val bytes = lines[LINE_BYTES].removeIfStarts(KEY_BYTES)?.let(::parseCanonical) ?: return null

            val valid =
                sanitiseName(name) == name &&
                    isLowercaseHexDigest(sha256) &&
                    bytes in 1L..TrackerConfig.MAX_IMPORT_BYTES_LIMIT &&
                    isSaneInputSide(width) &&
                    isSaneInputSide(height) &&
                    !ImportedModelRules.tooElongated(height.toInt(), width.toInt(), config) &&
                    outputRows in 1L..maxRows(outputLayout)
            if (!valid) return null
            return ImportedModelMeta(
                name,
                sha256,
                bytes,
                width.toInt(),
                height.toInt(),
                outputRows.toInt(),
                outputLayout,
                inputLayout,
                classes,
            )
        }

        private fun maxRows(layout: PoseOutputLayout): Long =
            (
                when (layout) {
                    PoseOutputLayout.END_TO_END, PoseOutputLayout.DETECT_END_TO_END -> TrackerConfig.IMPORT_MAX_OUTPUT_ROWS
                    else -> TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS
                }
            ).toLong()

        private fun isSaneInputSide(side: Long): Boolean =
            side in TrackerConfig.MIN_IMPORT_INPUT_SIZE_LIMIT.toLong()..TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT.toLong() &&
                side % TrackerConfig.IMPORT_INPUT_SIZE_STEP == 0L

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
