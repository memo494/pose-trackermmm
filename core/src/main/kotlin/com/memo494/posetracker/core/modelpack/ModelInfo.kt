package com.memo494.posetracker.core.modelpack

import com.memo494.posetracker.core.json.Json
import com.memo494.posetracker.core.json.JsonArray
import com.memo494.posetracker.core.json.JsonObject
import com.memo494.posetracker.core.model.ModelInputText

/**
 * One model file listed in `model_info.json`.
 *
 * @property inputWidth width `W` of the network input, in model pixels (Mdl); the key under `sizes` is `S` for a
 *   square input and `WxH` for any other.
 * @property inputHeight height `H` of the network input, in model pixels (Mdl).
 * @property fileName bare file name of the `.tflite` file (no directory), e.g. `yolo26n-pose_416.tflite`.
 * @property sha256 SHA-256 of the file, 64 lowercase hex characters.
 * @property inputShape tensor shape of the network input, always `[1, 3, inputHeight, inputWidth]` (NCHW).
 * @property outputShape tensor shape of the network output, every dimension positive (e.g. `[1, 300, 57]`).
 */
class ModelEntry(
    val inputWidth: Int,
    val inputHeight: Int,
    val fileName: String,
    val sha256: String,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
)

/**
 * The parsed `model_info.json` that ships next to the model files (assets and `model/`).
 *
 * @property entries one entry per model input size, ascending by width, then height; never empty.
 */
class ModelInfo(
    val entries: List<ModelEntry>,
) {
    /**
     * The entry for the square model input of side [inputSize] (model pixels).
     *
     * @throws IllegalArgumentException naming the known sizes when there is none.
     */
    fun entryFor(inputSize: Int): ModelEntry = entryFor(inputSize, inputSize)

    /**
     * The entry for the model input [inputWidth] x [inputHeight] (model pixels).
     *
     * @throws IllegalArgumentException naming the known sizes when there is none.
     */
    fun entryFor(
        inputWidth: Int,
        inputHeight: Int,
    ): ModelEntry {
        val entry = entries.firstOrNull { it.inputWidth == inputWidth && it.inputHeight == inputHeight }
        require(entry != null) {
            val known = entries.joinToString { ModelInputText.text(it.inputWidth, it.inputHeight) }
            "no model for input size ${ModelInputText.text(inputWidth, inputHeight)}, known sizes: $known"
        }
        return entry
    }

    companion object {
        /** Name of the document inside the assets and next to the model files. */
        const val ASSET_NAME = "model_info.json"

        private const val EXPECTED_BATCH = 1
        private const val EXPECTED_CHANNELS = 3
        private const val SIZE_SEPARATOR = "x"

        /**
         * Parses the text of `model_info.json`. Fails closed: anything unexpected throws
         * [IllegalArgumentException] (a `JsonParseException` for malformed JSON or a missing or mistyped
         * member) and no default is ever substituted. Rejected: `sizes` missing or empty; a size key that is
         * neither a positive integer `S` nor `WxH` (two different positive integers, no leading zeros, a lower case
         * `x`); `input` or `output` without exactly one entry; an input shape other than `[1, 3, S, S]` (key `S`)
         * or `[1, 3, H, W]` (key `WxH`); an output dimension that is not positive; a
         * `file` that is not a bare file name with an extension; a `sha256` that is not 64 lowercase hex
         * characters.
         */
        fun parse(text: String): ModelInfo {
            val root = Json.parse(text)
            require(root is JsonObject) { "model_info root must be an object" }
            val sizes = root.obj("sizes")
            require(sizes.members.isNotEmpty()) { "\"sizes\" must list at least one model" }
            val entries =
                sizes.members.map { (key, value) ->
                    require(value is JsonObject) { "size \"$key\" must be an object" }
                    readEntry(key, value)
                }
            return ModelInfo(entries.sortedWith(compareBy({ it.inputWidth }, { it.inputHeight })))
        }

        private fun readEntry(
            key: String,
            value: JsonObject,
        ): ModelEntry {
            val (inputWidth, inputHeight) = readSize(key)
            val fileName = value.string("file")
            ModelFileRules.requireFileName(fileName)
            val sha256 = value.string("sha256")
            ModelFileRules.requireSha256(sha256)
            val inputShape = readShape(value.array("input"), "size $key input")
            require(inputShape == listOf(EXPECTED_BATCH, EXPECTED_CHANNELS, inputHeight, inputWidth)) {
                "size $key: input shape $inputShape must be [$EXPECTED_BATCH, $EXPECTED_CHANNELS, $inputHeight, $inputWidth]"
            }
            val outputShape = readShape(value.array("output"), "size $key output")
            require(outputShape.isNotEmpty() && outputShape.all { it > 0 }) {
                "size $key: output shape $outputShape must be non-empty with positive dimensions"
            }
            return ModelEntry(inputWidth, inputHeight, fileName, sha256, inputShape, outputShape)
        }

        /** The width and height a size [key] stands for: `S` is `S` x `S`, `WxH` (W and H different) is that size. */
        private fun readSize(key: String): Pair<Int, Int> {
            val parts = key.split(SIZE_SEPARATOR)
            val sides = parts.map { positiveCanonical(it) }
            val valid = sides.none { it == null } && (parts.size == 1 || (parts.size == 2 && sides[0] != sides[1]))
            require(valid) { "size key \"$key\" must be a positive integer or WxH of two different positive integers" }
            val width = checkNotNull(sides[0])
            return width to (sides.getOrNull(1) ?: width)
        }

        /** [text] as a positive integer without a sign or leading zeros, or `null`. */
        private fun positiveCanonical(text: String): Int? {
            val number = text.toIntOrNull()
            return if (number != null && number > 0 && number.toString() == text) number else null
        }

        /** The `shape` of the single tensor in [tensors]; [label] names the tensor list in errors. */
        private fun readShape(
            tensors: JsonArray,
            label: String,
        ): List<Int> {
            require(tensors.size == 1) { "$label must have exactly one tensor but has ${tensors.size}" }
            val shape = tensors.obj(0).array("shape")
            return List(shape.size) { index -> shape.int(index) }
        }
    }
}
