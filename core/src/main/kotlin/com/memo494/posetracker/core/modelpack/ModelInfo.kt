package com.memo494.posetracker.core.modelpack

import com.memo494.posetracker.core.json.Json
import com.memo494.posetracker.core.json.JsonArray
import com.memo494.posetracker.core.json.JsonObject

/**
 * One model file listed in `model_info.json`.
 *
 * @property inputSize side of the square network input, in model pixels (the key under `sizes`).
 * @property fileName bare file name of the `.tflite` file (no directory), e.g. `yolo26n-pose_416.tflite`.
 * @property sha256 SHA-256 of the file, 64 lowercase hex characters.
 * @property inputShape tensor shape of the network input, always `[1, 3, inputSize, inputSize]`.
 * @property outputShape tensor shape of the network output, every dimension positive (e.g. `[1, 300, 57]`).
 */
class ModelEntry(
    val inputSize: Int,
    val fileName: String,
    val sha256: String,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
)

/**
 * The parsed `model_info.json` that ships next to the model files (assets and `model/`).
 *
 * @property entries one entry per model input size, ascending by [ModelEntry.inputSize]; never empty.
 */
class ModelInfo(
    val entries: List<ModelEntry>,
) {
    /**
     * The entry for the model input size [inputSize] (model pixels).
     *
     * @throws IllegalArgumentException naming the known sizes when there is none.
     */
    fun entryFor(inputSize: Int): ModelEntry {
        val entry = entries.firstOrNull { it.inputSize == inputSize }
        require(entry != null) {
            val known = entries.joinToString { it.inputSize.toString() }
            "no model for input size $inputSize, known sizes: $known"
        }
        return entry
    }

    companion object {
        /** Name of the document inside the assets and next to the model files. */
        const val ASSET_NAME = "model_info.json"

        private const val EXPECTED_BATCH = 1
        private const val EXPECTED_CHANNELS = 3

        /**
         * Parses the text of `model_info.json`. Fails closed: anything unexpected throws
         * [IllegalArgumentException] (a `JsonParseException` for malformed JSON or a missing or mistyped
         * member) and no default is ever substituted. Rejected: `sizes` missing or empty; a size key that is
         * not a positive integer; `input` or `output` without exactly one entry; an input shape other than
         * `[1, 3, S, S]` with `S` equal to the size key; an output dimension that is not positive; a
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
            return ModelInfo(entries.sortedBy { it.inputSize })
        }

        private fun readEntry(
            key: String,
            value: JsonObject,
        ): ModelEntry {
            val inputSize = key.toIntOrNull()
            require(inputSize != null && inputSize > 0 && inputSize.toString() == key) {
                "size key \"$key\" must be a positive integer"
            }
            val fileName = value.string("file")
            ModelFileRules.requireFileName(fileName)
            val sha256 = value.string("sha256")
            ModelFileRules.requireSha256(sha256)
            val inputShape = readShape(value.array("input"), "size $key input")
            require(inputShape == listOf(EXPECTED_BATCH, EXPECTED_CHANNELS, inputSize, inputSize)) {
                "size $key: input shape $inputShape must be [$EXPECTED_BATCH, $EXPECTED_CHANNELS, $key, $key]"
            }
            val outputShape = readShape(value.array("output"), "size $key output")
            require(outputShape.isNotEmpty() && outputShape.all { it > 0 }) {
                "size $key: output shape $outputShape must be non-empty with positive dimensions"
            }
            return ModelEntry(inputSize, fileName, sha256, inputShape, outputShape)
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
