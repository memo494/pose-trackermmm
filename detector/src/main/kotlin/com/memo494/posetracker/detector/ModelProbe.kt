package com.memo494.posetracker.detector

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import com.memo494.posetracker.core.modelimport.TensorDescription
import java.io.File
import com.google.ai.edge.litert.Accelerator as LiteRtAccelerator

/** Result of [ModelProbe.probe]. */
sealed interface ProbeResult {
    /**
     * The tensors the model file declares, ready for `ImportedModelRules.check`.
     *
     * @property inputs one entry per input tensor, in the model's order.
     * @property outputs one entry per output tensor, in the model's order.
     */
    data class Tensors(
        val inputs: List<TensorDescription>,
        val outputs: List<TensorDescription>,
    ) : ProbeResult

    /**
     * LiteRT could not open or read the file; nothing was rethrown.
     *
     * @property message class name and message of the failure, one line, at most [ModelProbe.MAX_MESSAGE_CHARS] characters.
     */
    data class Failed(
        val message: String,
    ) : ProbeResult
}

/**
 * Reads the input and output tensor descriptions of a `.tflite` file through LiteRT, without running it
 * (M0c D3: the real tensors decide, never metadata inside the file).
 *
 * Thread: call on a worker thread, never on the main thread (it maps the file and compiles it). It uses its own
 * `CompiledModel` on the CPU, so it may run while a session uses LiteRT on the capture thread. The GPU is never
 * used: the probe must not depend on a driver. A native crash cannot be caught here; the caller guards the probe
 * with `ImportGuard` (D4).
 *
 * LiteRT has no call that lists tensor names, and reads a tensor type only by name. The probe therefore takes
 * the tensor counts from the buffers `CompiledModel` creates (once, closed in `finally`) and looks the types up
 * under the names Ultralytics and TFLite exporters use (the same spellings as the vendored `LiteRtModel`). A tensor
 * under any other name is reported with an empty shape and element type `unknown`, which the import rules refuse.
 */
object ModelProbe {
    /** Element type name for a tensor whose type could not be read. */
    const val UNKNOWN_ELEMENT_TYPE = "unknown"

    /** Longest message a [ProbeResult.Failed] carries, in characters. */
    const val MAX_MESSAGE_CHARS = 200

    private const val ELLIPSIS = "..."
    private val WHITESPACE_RUN = Regex("\\s+")

    private val INPUT_NAMES = listOf("images", "args_0", "input", "input_1", "serving_default_input")

    /** Output name spellings for output [index], in the order the vendored loader tries them. */
    private fun outputNames(index: Int): List<String> =
        listOf("output_$index", "output$index", if (index == 0) "Identity" else "Identity_$index")

    /**
     * Opens [file] with `CompiledModel` on the CPU, reads every input and output tensor and closes everything
     * in `finally`. Never throws: any [Throwable] (`OutOfMemoryError`, `UnsatisfiedLinkError` and other `Error`s
     * included) becomes [ProbeResult.Failed] with a short message.
     */
    fun probe(file: File): ProbeResult {
        var model: CompiledModel? = null
        var inputBuffers: List<TensorBuffer> = emptyList()
        var outputBuffers: List<TensorBuffer> = emptyList()
        return try {
            val compiled = CompiledModel.create(file.absolutePath, CompiledModel.Options(LiteRtAccelerator.CPU))
            model = compiled
            inputBuffers = compiled.createInputBuffers()
            outputBuffers = compiled.createOutputBuffers()
            ProbeResult.Tensors(
                inputs = describeInputs(compiled, inputBuffers.size),
                outputs = List(outputBuffers.size) { index -> describeOutput(compiled, index) },
            )
        } catch (t: Throwable) {
            ProbeResult.Failed(failureMessage(t))
        } finally {
            closeQuietly(inputBuffers, outputBuffers, model)
        }
    }

    /**
     * One [TensorDescription] from raw values: [dimensions] as LiteRT reports them (`null` or empty when they
     * cannot be read becomes an empty shape, which the rules refuse) and [elementTypeName], the name of the LiteRT
     * element type enum (`null` when unreadable). Pure.
     */
    fun describe(
        dimensions: IntArray?,
        elementTypeName: String?,
    ): TensorDescription = TensorDescription(dimensions?.toList() ?: emptyList(), elementTypeName(elementTypeName))

    /**
     * The conventional lower-case name of LiteRT's element type [liteRtName] (`FLOAT` becomes exactly `float32`,
     * `INT` `int32`, `INT8` `int8`, `UINT8` `uint8`, `INT64` `int64`, `HALF`/`FLOAT16` `float16`, `BOOLEAN`/`BOOL`
     * `bool`); any other name is returned lower-cased, `null` becomes [UNKNOWN_ELEMENT_TYPE]. Pure.
     */
    fun elementTypeName(liteRtName: String?): String =
        when (liteRtName) {
            null -> UNKNOWN_ELEMENT_TYPE
            "FLOAT" -> "float32"
            "INT" -> "int32"
            "INT8" -> "int8"
            "UINT8" -> "uint8"
            "INT64" -> "int64"
            "HALF", "FLOAT16" -> "float16"
            "BOOLEAN", "BOOL" -> "bool"
            else -> liteRtName.lowercase()
        }

    /**
     * The one-line text for a failed probe: class simple name, then `: ` and the message when there is one,
     * line breaks and runs of whitespace collapsed to one space, cut to [MAX_MESSAGE_CHARS] characters. Pure.
     */
    fun failureMessage(error: Throwable): String {
        val name = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
        val text = error.message?.trim().orEmpty()
        val full = if (text.isEmpty()) name else "$name: $text"
        val oneLine = full.replace(WHITESPACE_RUN, " ")
        return if (oneLine.length <= MAX_MESSAGE_CHARS) oneLine else oneLine.take(MAX_MESSAGE_CHARS - ELLIPSIS.length) + ELLIPSIS
    }

    /**
     * A model with one input is looked up under the known input names; with any other count the names cannot be
     * told apart, so every input is reported unreadable (the rules refuse the count first anyway).
     */
    private fun describeInputs(
        model: CompiledModel,
        count: Int,
    ): List<TensorDescription> {
        val first = if (count == 1) firstReadable(INPUT_NAMES) { model.getInputTensorType(inputName = it) } else null
        return List(count) { describeType(first) }
    }

    private fun describeOutput(
        model: CompiledModel,
        index: Int,
    ): TensorDescription = describeType(firstReadable(outputNames(index)) { model.getOutputTensorType(outputName = it) })

    private fun describeType(type: TensorType?): TensorDescription =
        describe(type?.layout?.dimensions?.toIntArray(), type?.elementType?.name)

    /** The first of [names] for which [read] returns a type; a name LiteRT refuses (or cannot read) is skipped. */
    private fun firstReadable(
        names: List<String>,
        read: (String) -> TensorType,
    ): TensorType? {
        for (name in names) {
            try {
                return read(name)
            } catch (_: Throwable) {
                // Unknown name, or an element type the Kotlin API cannot read: try the next spelling.
            }
        }
        return null
    }

    private fun closeQuietly(
        inputs: List<TensorBuffer>,
        outputs: List<TensorBuffer>,
        model: CompiledModel?,
    ) {
        for (buffer in inputs + outputs) {
            try {
                buffer.close()
            } catch (_: Throwable) {
                // best effort: the probe result is already decided
            }
        }
        try {
            model?.close()
        } catch (_: Throwable) {
            // best effort
        }
    }
}
