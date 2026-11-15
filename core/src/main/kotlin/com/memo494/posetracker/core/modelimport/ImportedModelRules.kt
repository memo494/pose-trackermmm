package com.memo494.posetracker.core.modelimport

import com.memo494.posetracker.core.TrackerConfig

/**
 * One tensor of a model file, as the detector module reads it from LiteRT.
 *
 * @property shape dimension sizes in order; a dynamic or unknown dimension is passed as it is read
 *   (zero or negative) and never matches a rule.
 * @property elementType lower-case element type name such as `float32`, `uint8`, `int8`, `float16`.
 *   The detector module maps LiteRT's element type to this name; only the exact text `float32` is accepted.
 */
data class TensorDescription(
    val shape: List<Int>,
    val elementType: String,
)

/**
 * Why [ImportedModelRules] refused a model: one value per rule of layout version 2 (M0c D2, M1a T1). The [key] is a stable
 * identifier for messages and logs (the app maps it to text); it never changes once released.
 */
enum class ImportRejection(
    val key: String,
) {
    /** The model does not have exactly one input tensor. */
    INPUT_COUNT("input_count"),

    /** The input is not `float32`. */
    INPUT_TYPE("input_type"),

    /** The input is not 4-dimensional with batch 1 and 3 channels first, i.e. not NCHW `[1, 3, H, W]`. */
    INPUT_NOT_NCHW("input_not_nchw"),

    /** The input is NHWC `[1, H, W, 3]` (the common TensorFlow layout); recognised so the message can say so. */
    INPUT_NHWC("input_nhwc"),

    /** The input height or width is not a multiple of [TrackerConfig.IMPORT_INPUT_SIZE_STEP]. */
    INPUT_SIZE_NOT_MULTIPLE("input_size_not_multiple"),

    /** The input height or width is outside [TrackerConfig.importMinInputSize]..[TrackerConfig.importMaxInputSize]. */
    INPUT_SIZE_OUT_OF_RANGE("input_size_out_of_range"),

    /** The long side of the input is more than [TrackerConfig.importMaxInputAspect] times the short side. */
    INPUT_ASPECT("input_aspect"),

    /** The model does not have exactly one output tensor. */
    OUTPUT_COUNT("output_count"),

    /** The output is not `float32`. */
    OUTPUT_TYPE("output_type"),

    /**
     * The output is the classic channels-first head `[1, C, N]` (for example `[1, 56, 8400]`) instead of
     * the end-to-end `[1, N, 57]` pose head; recognised so the message can say so.
     */
    OUTPUT_CLASSIC_HEAD("output_classic_head"),

    /**
     * The output is `[1, N, 57]` but `N` is above [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS]: the vendored
     * `PoseEstimator` treats 57 columns as end-to-end only up to `END_TO_END_MAX_ROWS` rows, so the model would
     * fail at every Start.
     */
    OUTPUT_ROWS("output_rows"),

    /** Any other output that is not `[1, N, 57]` with `0 < N <= 300` (other keypoint count, detect model, wrong rank). */
    OUTPUT_SHAPE("output_shape"),
}

/** Result of [ImportedModelRules.check]. */
sealed interface ImportCheck {
    /**
     * The model fits layout version 2.
     *
     * @property inputWidth width `W` of the input tensor `[1, 3, H, W]`, in pixels (Mdl space).
     * @property inputHeight height `H` of the input tensor `[1, 3, H, W]`, in pixels (Mdl space).
     * @property outputRows rows `N` of the output (candidate detections per frame), unitless.
     */
    data class Accepted(
        val inputWidth: Int,
        val inputHeight: Int,
        val outputRows: Int,
    ) : ImportCheck

    /**
     * The model does not fit.
     *
     * @property reason the rule that failed (the first one, in the order documented at [ImportedModelRules.check]).
     * @property details the shapes and types found, always present, for example
     *   `input [1,640,640,3] float32; output [1,56,8400] float32`.
     */
    data class Rejected(
        val reason: ImportRejection,
        val details: String,
    ) : ImportCheck
}

/**
 * The rules of M0c D2 for an imported pose model, applied to the tensor descriptions LiteRT reports. Pure;
 * never throws for bad data and accepts nothing it does not positively recognise (fail closed).
 */
object ImportedModelRules {
    /** The only accepted element type name. */
    const val FLOAT32 = "float32"

    private const val INPUT_RANK = 4
    private const val OUTPUT_RANK = 3
    private const val BATCH = 1
    private const val INPUT_CHANNELS = 3
    private const val AXIS_BATCH = 0
    private const val AXIS_1 = 1
    private const val AXIS_2 = 2
    private const val AXIS_3 = 3

    /**
     * Applies the rules in this order and returns the first that fails: input count, output count, input
     * type, input layout (NHWC recognised, otherwise NCHW rank/batch/channels, then height and width each
     * inside the size range, then each a multiple of [TrackerConfig.IMPORT_INPUT_SIZE_STEP], then the
     * long side at most [TrackerConfig.importMaxInputAspect] times the short side), output type, output
     * shape (classic head recognised). Accepts exactly one `float32` input `[1, 3, H, W]` (not necessarily
     * square) and one `float32` output
     * `[1, N, 57]` with `0 < N <=` [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS] ([TrackerConfig.POSE_OUTPUT_COLUMNS]
     * columns; the vendored `PoseEstimator` treats 57 columns as end-to-end only up to its
     * `END_TO_END_MAX_ROWS`, so a larger `N` is refused as [ImportRejection.OUTPUT_ROWS]).
     */
    fun check(
        inputs: List<TensorDescription>,
        outputs: List<TensorDescription>,
        config: TrackerConfig,
    ): ImportCheck {
        val details = describe(inputs, outputs)

        fun reject(reason: ImportRejection) = ImportCheck.Rejected(reason, details)

        if (inputs.size != 1) return reject(ImportRejection.INPUT_COUNT)
        if (outputs.size != 1) return reject(ImportRejection.OUTPUT_COUNT)
        val input = inputs[0]
        val output = outputs[0]
        if (input.elementType != FLOAT32) return reject(ImportRejection.INPUT_TYPE)

        val inputShape = input.shape
        val height: Int
        val width: Int
        if (isNchwPrefix(inputShape)) {
            height = inputShape[AXIS_2]
            width = inputShape[AXIS_3]
            if (!inRange(height, config) || !inRange(width, config)) return reject(ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
            if (!isStepMultiple(height) || !isStepMultiple(width)) return reject(ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
            if (tooElongated(height, width, config)) return reject(ImportRejection.INPUT_ASPECT)
        } else if (isNhwc(inputShape)) {
            return reject(ImportRejection.INPUT_NHWC)
        } else {
            return reject(ImportRejection.INPUT_NOT_NCHW)
        }

        if (output.elementType != FLOAT32) return reject(ImportRejection.OUTPUT_TYPE)
        val outputShape = output.shape
        if (isPoseHead(outputShape)) {
            val rows = outputShape[AXIS_1]
            if (rows > TrackerConfig.IMPORT_MAX_OUTPUT_ROWS) return reject(ImportRejection.OUTPUT_ROWS)
            return ImportCheck.Accepted(inputWidth = width, inputHeight = height, outputRows = rows)
        }
        return reject(if (isClassicHead(outputShape)) ImportRejection.OUTPUT_CLASSIC_HEAD else ImportRejection.OUTPUT_SHAPE)
    }

    private fun inRange(
        side: Int,
        config: TrackerConfig,
    ): Boolean = side >= config.importMinInputSize && side <= config.importMaxInputSize

    private fun isStepMultiple(side: Int): Boolean = side % TrackerConfig.IMPORT_INPUT_SIZE_STEP == 0

    /**
     * Both sides are positive here (range already checked); compares in `Double`, so 4.0 is exact. Also used by
     * [ImportedModelMeta.decode], so that the stored form obeys the same aspect rule as the importer.
     */
    internal fun tooElongated(
        height: Int,
        width: Int,
        config: TrackerConfig,
    ): Boolean = maxOf(height, width).toDouble() / minOf(height, width).toDouble() > config.importMaxInputAspect.toDouble()

    private fun isNchwPrefix(shape: List<Int>): Boolean =
        shape.size == INPUT_RANK && shape[AXIS_BATCH] == BATCH && shape[AXIS_1] == INPUT_CHANNELS

    private fun isNhwc(shape: List<Int>): Boolean =
        shape.size == INPUT_RANK && shape[AXIS_BATCH] == BATCH && shape[AXIS_3] == INPUT_CHANNELS

    private fun isPoseHead(shape: List<Int>): Boolean =
        shape.size == OUTPUT_RANK && shape[AXIS_BATCH] == BATCH && shape[AXIS_1] > 0 &&
            shape[AXIS_2] == TrackerConfig.POSE_OUTPUT_COLUMNS

    /**
     * `[1, C, N]` with channels first: batch 1, a positive channel count `C` smaller than the row count
     * `N`, and neither equal to the 57 columns of the accepted head.
     */
    private fun isClassicHead(shape: List<Int>): Boolean =
        shape.size == OUTPUT_RANK && shape[AXIS_BATCH] == BATCH && shape[AXIS_1] > 0 &&
            shape[AXIS_1] < shape[AXIS_2] && shape[AXIS_1] != TrackerConfig.POSE_OUTPUT_COLUMNS &&
            shape[AXIS_2] != TrackerConfig.POSE_OUTPUT_COLUMNS

    private fun describe(
        inputs: List<TensorDescription>,
        outputs: List<TensorDescription>,
    ): String = "${describeList("input", inputs)}; ${describeList("output", outputs)}"

    private fun describeList(
        label: String,
        tensors: List<TensorDescription>,
    ): String =
        when (tensors.size) {
            1 -> "$label ${describe(tensors[0])}"
            0 -> "${label}s(0)"
            else -> "${label}s(${tensors.size}) " + tensors.joinToString(", ") { describe(it) }
        }

    private fun describe(tensor: TensorDescription): String = "${tensor.shape.joinToString(",", "[", "]")} ${tensor.elementType}"
}
