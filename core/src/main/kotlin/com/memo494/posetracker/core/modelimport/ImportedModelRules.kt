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
 * Why [ImportedModelRules] refused a model: one value per rule of the import layout (M0c D2, M1a T1, M-legacy T1). The [key] is a stable
 * identifier for messages and logs (the app maps it to text); it never changes once released.
 */
enum class ImportRejection(
    val key: String,
) {
    /** The model does not have exactly one input tensor. */
    INPUT_COUNT("input_count"),

    /** The input is not `float32` (and not `int8`/`uint8`, which are [QUANTIZED_IO]). */
    INPUT_TYPE("input_type"),

    /** The input or the output is `int8` or `uint8` (an INT8 export); only `float32` is accepted. */
    QUANTIZED_IO("quantized_io"),

    /** The input is not 4-dimensional with batch 1 and 3 channels, first (NCHW `[1, 3, H, W]`) or last (NHWC `[1, H, W, 3]`). */
    INPUT_NOT_NCHW("input_not_nchw"),

    /**
     * The input is NHWC `[1, H, W, 3]`. No longer returned: NHWC is accepted since M-legacy T1 (see [InputLayout.NHWC]);
     * the entry and its key stay because keys are never reused or removed.
     */
    INPUT_NHWC("input_nhwc"),

    /** The input height or width is not a multiple of [TrackerConfig.IMPORT_INPUT_SIZE_STEP]. */
    INPUT_SIZE_NOT_MULTIPLE("input_size_not_multiple"),

    /** The input height or width is outside [TrackerConfig.importMinInputSize]..[TrackerConfig.importMaxInputSize]. */
    INPUT_SIZE_OUT_OF_RANGE("input_size_out_of_range"),

    /** The long side of the input is more than [TrackerConfig.importMaxInputAspect] times the short side. */
    INPUT_ASPECT("input_aspect"),

    /** The model does not have exactly one output tensor. */
    OUTPUT_COUNT("output_count"),

    /** The output is not `float32` (and not `int8`/`uint8`, which are [QUANTIZED_IO]). */
    OUTPUT_TYPE("output_type"),

    /**
     * The output is a channels-first head `[1, C, N]` that is not the 17-keypoint raw pose head `[1, 56, N]`
     * (for example `[1, 84, 8400]`); recognised so the message can say so.
     */
    OUTPUT_CLASSIC_HEAD("output_classic_head"),

    /**
     * The output has channels `C` with `5 <= C < 56` (`[1, C, N]` or `[1, N, C]`): 4 box values and classes, too few
     * for 17 keypoints, so it is a detection model without body keypoints (for example `[1, 6, 2100]`); except
     * channel counts of a pose head with another keypoint count, see [OUTPUT_KEYPOINT_COUNT].
     */
    OUTPUT_DETECT_ONLY("output_detect_only"),

    /**
     * The output has `5 + 3k` channels (`[1, C, N]` or `[1, N, C]`, `8 <= C < 56`): a pose model with another number of
     * keypoints than 17 (for example 41 channels = 12 keypoints). Only 17 body keypoints are supported.
     */
    OUTPUT_KEYPOINT_COUNT("output_keypoint_count"),

    /**
     * The output has more rows than the layout allows: `[1, N, 57]` above [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS] (the
     * vendored `PoseEstimator` treats 57 columns as end-to-end only up to `END_TO_END_MAX_ROWS` rows, so the model
     * would fail at every Start), or a raw `[1, 56, N]` / `[1, N, 56]` above [TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS].
     */
    OUTPUT_ROWS("output_rows"),

    /**
     * Any other output that is not `[1, N, 57]` with `0 < N <= 300` and not a raw `[1, 56, N]` / `[1, N, 56]`
     * (other keypoint count, wrong rank, a `56 x 56` output that could be either raw layout).
     */
    OUTPUT_SHAPE("output_shape"),
}

/** Result of [ImportedModelRules.check]. */
sealed interface ImportCheck {
    /**
     * The model fits layout version 2 (end-to-end) or a raw pose layout.
     *
     * @property inputWidth real width `W` of the input tensor (`[1, 3, H, W]` or `[1, H, W, 3]`), in pixels (Mdl space).
     * @property inputHeight real height `H` of the input tensor, in pixels (Mdl space).
     * @property outputRows rows `N` of the output (candidate detections per frame), unitless.
     * @property outputLayout how the output is laid out.
     * @property inputLayout how the input tensor is laid out (channels first or last).
     */
    data class Accepted(
        val inputWidth: Int,
        val inputHeight: Int,
        val outputRows: Int,
        val outputLayout: PoseOutputLayout = PoseOutputLayout.END_TO_END,
        val inputLayout: InputLayout = InputLayout.NCHW,
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

    private const val INT8 = "int8"
    private const val UINT8 = "uint8"

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
     * type (`int8`/`uint8` is [ImportRejection.QUANTIZED_IO], any other non-float32 [ImportRejection.INPUT_TYPE]),
     * input layout (NCHW or NHWC by rank/batch/channels, otherwise [ImportRejection.INPUT_NOT_NCHW]; then height and
     * width each inside the size range, then each a multiple of [TrackerConfig.IMPORT_INPUT_SIZE_STEP], then the
     * long side at most [TrackerConfig.importMaxInputAspect] times the short side, the same for both layouts),
     * output type, output shape (detect-only and other channels-first heads recognised). Accepts exactly one
     * `float32` input `[1, 3, H, W]` ([InputLayout.NCHW]) or `[1, H, W, 3]` ([InputLayout.NHWC]), not necessarily
     * square, and one `float32` output that is one of:
     * - `[1, N, 57]` with `0 < N <=` [TrackerConfig.IMPORT_MAX_OUTPUT_ROWS] ([TrackerConfig.POSE_OUTPUT_COLUMNS]
     *   columns, [PoseOutputLayout.END_TO_END]; the vendored `PoseEstimator` treats 57 columns as end-to-end only up
     *   to its `END_TO_END_MAX_ROWS`, so a larger `N` is refused as [ImportRejection.OUTPUT_ROWS]);
     * - `[1, 56, N]` or `[1, N, 56]` ([TrackerConfig.POSE_RAW_CHANNELS], [PoseOutputLayout.RAW_CHANNELS_FIRST] /
     *   [PoseOutputLayout.RAW_CHANNELS_LAST]) with `56 < N <=` [TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS]. A shape
     *   with 56 on both axes is ambiguous and refused as [ImportRejection.OUTPUT_SHAPE].
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
        if (input.elementType != FLOAT32) {
            return reject(if (isQuantized(input)) ImportRejection.QUANTIZED_IO else ImportRejection.INPUT_TYPE)
        }

        val inputShape = input.shape
        val inputLayout =
            when {
                isNchwPrefix(inputShape) -> InputLayout.NCHW
                isNhwc(inputShape) -> InputLayout.NHWC
                else -> return reject(ImportRejection.INPUT_NOT_NCHW)
            }
        val height = if (inputLayout == InputLayout.NCHW) inputShape[AXIS_2] else inputShape[AXIS_1]
        val width = if (inputLayout == InputLayout.NCHW) inputShape[AXIS_3] else inputShape[AXIS_2]
        if (!inRange(height, config) || !inRange(width, config)) return reject(ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        if (!isStepMultiple(height) || !isStepMultiple(width)) return reject(ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
        if (tooElongated(height, width, config)) return reject(ImportRejection.INPUT_ASPECT)

        if (output.elementType != FLOAT32) {
            return reject(if (isQuantized(output)) ImportRejection.QUANTIZED_IO else ImportRejection.OUTPUT_TYPE)
        }
        val outputShape = output.shape
        if (isPoseHead(outputShape)) {
            val rows = outputShape[AXIS_1]
            if (rows > TrackerConfig.IMPORT_MAX_OUTPUT_ROWS) return reject(ImportRejection.OUTPUT_ROWS)
            return ImportCheck.Accepted(inputWidth = width, inputHeight = height, outputRows = rows, inputLayout = inputLayout)
        }
        val rawLayout = PoseOutputLayout.rawOf(outputShape)
        if (rawLayout != null) {
            val rows = maxOf(outputShape[AXIS_1], outputShape[AXIS_2])
            if (rows > TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS) return reject(ImportRejection.OUTPUT_ROWS)
            return ImportCheck.Accepted(
                inputWidth = width,
                inputHeight = height,
                outputRows = rows,
                outputLayout = rawLayout,
                inputLayout = inputLayout,
            )
        }
        return reject(
            when {
                isDetectOnly(outputShape) -> ImportRejection.OUTPUT_DETECT_ONLY
                isOtherKeypointCount(outputShape) -> ImportRejection.OUTPUT_KEYPOINT_COUNT
                isClassicHead(outputShape) -> ImportRejection.OUTPUT_CLASSIC_HEAD
                else -> ImportRejection.OUTPUT_SHAPE
            },
        )
    }

    private fun isQuantized(tensor: TensorDescription): Boolean = tensor.elementType == INT8 || tensor.elementType == UINT8

    /**
     * `[1, C, N]` or `[1, N, C]` with more rows `N` than channels `C`, `C` from 5 (4 box values and one class) up to
     * one below the 56 of a raw pose head, and no axis of exactly 56 (a small raw-looking shape is not a detector).
     * Channel counts that fit a pose head with another keypoint count are [isOtherKeypointCount] instead.
     */
    private fun isDetectOnly(shape: List<Int>): Boolean = fewChannels(shape) { !isOtherKeypointChannels(it) }

    /** `5 + 3k` channels (k = 1..15): a pose head with another keypoint count than 17, not a detector. */
    private fun isOtherKeypointCount(shape: List<Int>): Boolean = fewChannels(shape) { isOtherKeypointChannels(it) }

    private fun isOtherKeypointChannels(channels: Int): Boolean =
        channels >= TrackerConfig.POSE_BOX_VALUES + TrackerConfig.POSE_SCORE_VALUES + TrackerConfig.POSE_VALUES_PER_KEYPOINT &&
            (channels - TrackerConfig.POSE_BOX_VALUES - TrackerConfig.POSE_SCORE_VALUES) % TrackerConfig.POSE_VALUES_PER_KEYPOINT == 0

    private fun fewChannels(
        shape: List<Int>,
        accept: (Int) -> Boolean,
    ): Boolean {
        if (shape.size != OUTPUT_RANK || shape[AXIS_BATCH] != BATCH) return false
        if (shape[AXIS_1] == TrackerConfig.POSE_RAW_CHANNELS || shape[AXIS_2] == TrackerConfig.POSE_RAW_CHANNELS) return false
        val minChannels = TrackerConfig.POSE_BOX_VALUES + TrackerConfig.POSE_SCORE_VALUES

        fun fits(
            channels: Int,
            rows: Int,
        ) = channels in minChannels until TrackerConfig.POSE_RAW_CHANNELS && rows > channels && accept(channels)
        return fits(shape[AXIS_1], shape[AXIS_2]) || fits(shape[AXIS_2], shape[AXIS_1])
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
     * `N`, and neither equal to the 57 columns of the end-to-end head nor the 56 of a raw one.
     */
    private fun isClassicHead(shape: List<Int>): Boolean =
        shape.size == OUTPUT_RANK && shape[AXIS_BATCH] == BATCH && shape[AXIS_1] > 0 &&
            shape[AXIS_1] < shape[AXIS_2] && shape[AXIS_1] != TrackerConfig.POSE_OUTPUT_COLUMNS &&
            shape[AXIS_2] != TrackerConfig.POSE_OUTPUT_COLUMNS &&
            shape[AXIS_1] != TrackerConfig.POSE_RAW_CHANNELS && shape[AXIS_2] != TrackerConfig.POSE_RAW_CHANNELS

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
