package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ImportedModelRulesTest {
    private val config = TrackerConfig()

    private fun tensor(
        vararg shape: Int,
        type: String = "float32",
    ) = TensorDescription(shape.toList(), type)

    private fun check(
        input: TensorDescription,
        output: TensorDescription = tensor(1, 300, 57),
    ) = ImportedModelRules.check(listOf(input), listOf(output), config)

    private fun assertRejected(
        result: ImportCheck,
        reason: ImportRejection,
    ): ImportCheck.Rejected {
        assertThat(result).isInstanceOf(ImportCheck.Rejected::class.java)
        val rejected = result as ImportCheck.Rejected
        assertThat(rejected.reason).isEqualTo(reason)
        return rejected
    }

    // ---- accepted ----

    @Test
    fun builtInLayout416_isAccepted() {
        assertThat(check(tensor(1, 3, 416, 416))).isEqualTo(ImportCheck.Accepted(inputWidth = 416, inputHeight = 416, outputRows = 300))
    }

    @Test
    fun the640Export_isAccepted() {
        assertThat(check(tensor(1, 3, 640, 640))).isEqualTo(ImportCheck.Accepted(inputWidth = 640, inputHeight = 640, outputRows = 300))
    }

    @Test
    fun inputSizes_atBothLimitsAndInBetween_areAccepted() {
        for (size in listOf(config.importMinInputSize, 320, 352, config.importMaxInputSize)) {
            val result = check(tensor(1, 3, size, size))

            assertThat(result).isEqualTo(ImportCheck.Accepted(inputWidth = size, inputHeight = size, outputRows = 300))
        }
    }

    @Test
    fun rectangularInputs_areAcceptedWithWidthAndHeightTheRightWayRound() {
        // The tensor is [1, 3, H, W]; sizes are WIDTHxHEIGHT everywhere a person reads them.
        val cases = listOf(832 to 384, 384 to 832, 640 to 288)

        for ((width, height) in cases) {
            val result = check(tensor(1, 3, height, width))

            assertThat(result).isEqualTo(ImportCheck.Accepted(inputWidth = width, inputHeight = height, outputRows = 300))
        }
    }

    @Test
    fun rectangularInput_keepsHeightAndWidthApart() {
        val accepted = check(tensor(1, 3, 384, 832)) as ImportCheck.Accepted

        assertThat(accepted.inputHeight).isEqualTo(384)
        assertThat(accepted.inputWidth).isEqualTo(832)
    }

    @Test
    fun squareInput_hasEqualWidthAndHeight() {
        val square = check(tensor(1, 3, 640, 640)) as ImportCheck.Accepted

        assertThat(square.inputWidth).isEqualTo(640)
        assertThat(square.inputHeight).isEqualTo(640)
    }

    @Test
    fun wideInput_hasTheLongSideAsWidth() {
        val wide = check(tensor(1, 3, 288, 640)) as ImportCheck.Accepted

        assertThat(wide).isEqualTo(ImportCheck.Accepted(inputWidth = 640, inputHeight = 288, outputRows = 300))
    }

    @Test
    fun aspectExactlyAtTheLimit_isAcceptedOnBothAxes() {
        assertThat(check(tensor(1, 3, 160, 640))).isEqualTo(ImportCheck.Accepted(640, 160, 300))
        assertThat(check(tensor(1, 3, 640, 160))).isEqualTo(ImportCheck.Accepted(160, 640, 300))
        assertThat(check(tensor(1, 3, 320, 1280))).isEqualTo(ImportCheck.Accepted(1280, 320, 300))
    }

    @Test
    fun outputRows_areReportedForAnyPositiveNUpTo300() {
        for (rows in listOf(1, 299, 300)) {
            val result = check(tensor(1, 3, 640, 640), tensor(1, rows, 57))

            assertThat(result).isEqualTo(ImportCheck.Accepted(inputWidth = 640, inputHeight = 640, outputRows = rows))
        }
    }

    @Test
    fun aspectLimitComesFromTheConfig() {
        val loose = TrackerConfig(importMaxInputAspect = 8.0f)
        val strict = TrackerConfig(importMaxInputAspect = 1.0f)
        val wide = listOf(tensor(1, 3, 160, 1280))
        val rect = listOf(tensor(1, 3, 384, 832))
        val out = listOf(tensor(1, 300, 57))

        assertThat(ImportedModelRules.check(wide, out, loose)).isInstanceOf(ImportCheck.Accepted::class.java)
        assertRejected(ImportedModelRules.check(wide, out, config), ImportRejection.INPUT_ASPECT)
        assertRejected(ImportedModelRules.check(rect, out, strict), ImportRejection.INPUT_ASPECT)
        assertThat(ImportedModelRules.check(listOf(tensor(1, 3, 640, 640)), out, strict))
            .isEqualTo(ImportCheck.Accepted(640, 640, 300))
    }

    @Test
    fun limitsComeFromTheConfig() {
        val custom = TrackerConfig(importMinInputSize = 320, importMaxInputSize = 416)

        val accepted = ImportedModelRules.check(listOf(tensor(1, 3, 416, 416)), listOf(tensor(1, 300, 57)), custom)
        val tooBig = ImportedModelRules.check(listOf(tensor(1, 3, 448, 448)), listOf(tensor(1, 300, 57)), custom)
        val tooSmall = ImportedModelRules.check(listOf(tensor(1, 3, 288, 288)), listOf(tensor(1, 300, 57)), custom)

        assertThat(accepted).isEqualTo(ImportCheck.Accepted(inputWidth = 416, inputHeight = 416, outputRows = 300))
        assertRejected(tooBig, ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        assertRejected(tooSmall, ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
    }

    // ---- input count and type ----

    @Test
    fun noInput_isRejectedAsInputCount() {
        val result = ImportedModelRules.check(emptyList(), listOf(tensor(1, 300, 57)), config)

        val rejected = assertRejected(result, ImportRejection.INPUT_COUNT)
        assertThat(rejected.details).isEqualTo("inputs(0); output [1,300,57] float32")
    }

    @Test
    fun twoInputs_areRejectedAsInputCount() {
        val inputs = listOf(tensor(1, 3, 640, 640), tensor(1, 3, 640, 640))

        val result = ImportedModelRules.check(inputs, listOf(tensor(1, 300, 57)), config)

        val rejected = assertRejected(result, ImportRejection.INPUT_COUNT)
        assertThat(rejected.details).isEqualTo(
            "inputs(2) [1,3,640,640] float32, [1,3,640,640] float32; output [1,300,57] float32",
        )
    }

    @Test
    fun nonFloat32Input_isRejectedAsInputType() {
        for (type in listOf("float16", "int32", "float64", "FLOAT32", "float32 ", "")) {
            val result = check(tensor(1, 3, 640, 640, type = type))

            assertRejected(result, ImportRejection.INPUT_TYPE)
        }
    }

    @Test
    fun inputType_isCheckedBeforeTheLayout() {
        val result = check(tensor(1, 640, 640, 3, type = "float16"))

        assertRejected(result, ImportRejection.INPUT_TYPE)
    }

    // ---- input layout ----

    @Test
    fun nhwcInput_isAcceptedWithLayoutNhwc() {
        val result = check(tensor(1, 640, 640, 3), tensor(1, 56, 8400))

        assertThat(result)
            .isEqualTo(
                ImportCheck.Accepted(
                    inputWidth = 640,
                    inputHeight = 640,
                    outputRows = 8400,
                    outputLayout = PoseOutputLayout.RAW_CHANNELS_FIRST,
                    inputLayout = InputLayout.NHWC,
                ),
            )
    }

    @Test
    fun nchwInput_isAcceptedWithLayoutNchw() {
        val result = check(tensor(1, 3, 640, 640)) as ImportCheck.Accepted

        assertThat(result.inputLayout).isEqualTo(InputLayout.NCHW)
    }

    @Test
    fun nonSquareNhwcInput_isAcceptedWithWidthFromAxis2AndHeightFromAxis1() {
        // NHWC is [1, H, W, 3]: height is axis 1, width is axis 2.
        val cases = listOf(832 to 384, 384 to 832, 640 to 288, 480 to 640)

        for ((width, height) in cases) {
            val result = check(tensor(1, height, width, 3))

            assertThat(result)
                .isEqualTo(ImportCheck.Accepted(inputWidth = width, inputHeight = height, outputRows = 300, inputLayout = InputLayout.NHWC))
        }
    }

    @Test
    fun nhwcInput_atBothSizeLimits_isAccepted() {
        for (size in listOf(config.importMinInputSize, 320, config.importMaxInputSize)) {
            val result = check(tensor(1, size, size, 3)) as ImportCheck.Accepted

            assertThat(result.inputWidth).isEqualTo(size)
            assertThat(result.inputHeight).isEqualTo(size)
        }
    }

    @Test
    fun nhwcInput_sizeOutOfRange_isRefusedLikeNchw() {
        for ((h, w) in listOf(config.importMinInputSize - 32 to 640, 640 to config.importMaxInputSize + 32, 0 to 640, -1 to 640)) {
            assertRejected(check(tensor(1, h, w, 3)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
            assertRejected(check(tensor(1, 3, h, w)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        }
    }

    @Test
    fun nhwcInput_notAMultipleOfTheStep_isRefusedLikeNchw() {
        for ((h, w) in listOf(641 to 640, 640 to 625, 330 to 330)) {
            assertRejected(check(tensor(1, h, w, 3)), ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
            assertRejected(check(tensor(1, 3, h, w)), ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
        }
    }

    @Test
    fun nhwcInput_tooElongated_isRefusedLikeNchw() {
        val short = config.importMinInputSize
        val long = (short * config.importMaxInputAspect.toInt() + TrackerConfig.IMPORT_INPUT_SIZE_STEP)

        assertRejected(check(tensor(1, short, long, 3)), ImportRejection.INPUT_ASPECT)
        assertRejected(check(tensor(1, long, short, 3)), ImportRejection.INPUT_ASPECT)
        assertRejected(check(tensor(1, 3, short, long)), ImportRejection.INPUT_ASPECT)
    }

    @Test
    fun nhwcInput_int8_isQuantizedIo() {
        assertRejected(check(tensor(1, 640, 640, 3, type = "int8")), ImportRejection.QUANTIZED_IO)
        assertRejected(check(tensor(1, 640, 640, 3, type = "uint8")), ImportRejection.QUANTIZED_IO)
    }

    @Test
    fun nhwcInput_withAnOutputThatDoesNotFit_isRefusedForTheOutput() {
        val rejected = assertRejected(check(tensor(1, 640, 640, 3), tensor(1, 85, 8400)), ImportRejection.OUTPUT_CLASSIC_HEAD)

        assertThat(rejected.details).isEqualTo("input [1,640,640,3] float32; output [1,85,8400] float32")
    }

    @Test
    fun theNhwcRejectionKey_isKept_evenThoughARuleNoLongerReturnsIt() {
        assertThat(ImportRejection.INPUT_NHWC.key).isEqualTo("input_nhwc")
    }

    @Test
    fun wrongRankInput_isRejectedAsNotNchw() {
        for (shape in listOf(intArrayOf(), intArrayOf(640), intArrayOf(3, 640, 640), intArrayOf(1, 3, 640, 640, 1))) {
            val result = check(TensorDescription(shape.toList(), "float32"))

            assertRejected(result, ImportRejection.INPUT_NOT_NCHW)
        }
    }

    @Test
    fun batchNotOne_isRejectedAsNotNchw() {
        for (batch in listOf(2, 0, -1)) {
            assertRejected(check(tensor(batch, 3, 640, 640)), ImportRejection.INPUT_NOT_NCHW)
        }
    }

    @Test
    fun channelsNotThree_areRejectedAsNotNchw() {
        for (channels in listOf(1, 2, 4, 0, -1)) {
            assertRejected(check(tensor(1, channels, 640, 640)), ImportRejection.INPUT_NOT_NCHW)
        }
    }

    @Test
    fun notNchw_detailsNameTheShapeFound() {
        val rejected = assertRejected(check(tensor(2, 3, 640, 640)), ImportRejection.INPUT_NOT_NCHW)

        assertThat(rejected.details).isEqualTo("input [2,3,640,640] float32; output [1,300,57] float32")
    }

    @Test
    fun nonSquareInputsWithinTheLimits_areNoLongerRejectedForBeingNonSquare() {
        for ((h, w) in listOf(480 to 640, 640 to 480, 352 to 608, 608 to 352)) {
            assertThat(check(tensor(1, 3, h, w))).isInstanceOf(ImportCheck.Accepted::class.java)
        }
    }

    @Test
    fun tooWideInput_isRejectedAsAspect() {
        // W / H = 4.2 and 8: the width is the long side.
        for ((h, w) in listOf(160 to 672, 160 to 1280, 192 to 1280, 256 to 1056)) {
            assertRejected(check(tensor(1, 3, h, w)), ImportRejection.INPUT_ASPECT)
        }
    }

    @Test
    fun tooTallInput_isRejectedAsAspect() {
        // H / W = 4.2 and 8: the height is the long side.
        for ((h, w) in listOf(672 to 160, 1280 to 160, 1280 to 192, 1056 to 256)) {
            assertRejected(check(tensor(1, 3, h, w)), ImportRejection.INPUT_ASPECT)
        }
    }

    @Test
    fun aspectDetails_nameTheShapeFound() {
        val rejected = assertRejected(check(tensor(1, 3, 160, 1280)), ImportRejection.INPUT_ASPECT)

        assertThat(rejected.details).isEqualTo("input [1,3,160,1280] float32; output [1,300,57] float32")
    }

    @Test
    fun inputNotAMultipleOf32_isRejectedOnEitherAxis() {
        val shapes =
            listOf(
                intArrayOf(1, 3, 161, 161),
                intArrayOf(1, 3, 330, 330),
                intArrayOf(1, 3, 641, 641),
                intArrayOf(1, 3, 1279, 1279),
                // height off, width fine
                intArrayOf(1, 3, 385, 832),
                // width off, height fine
                intArrayOf(1, 3, 384, 833),
                intArrayOf(1, 3, 640, 650),
                intArrayOf(1, 3, 650, 640),
            )

        for (shape in shapes) {
            assertRejected(check(TensorDescription(shape.toList(), "float32")), ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
        }
    }

    @Test
    fun inputOutsideTheLimits_isRejectedOnEitherAxis() {
        val shapes =
            listOf(
                intArrayOf(1, 3, -1, 640),
                intArrayOf(1, 3, 640, -1),
                intArrayOf(1, 3, 0, 0),
                intArrayOf(1, 3, 32, 32),
                intArrayOf(1, 3, 128, 128),
                intArrayOf(1, 3, 1312, 1312),
                intArrayOf(1, 3, 4096, 4096),
                // height too small, width fine
                intArrayOf(1, 3, 128, 320),
                // width too small, height fine
                intArrayOf(1, 3, 320, 128),
                // height too large, width fine
                intArrayOf(1, 3, 1312, 1280),
                // width too large, height fine
                intArrayOf(1, 3, 1280, 1312),
            )

        for (shape in shapes) {
            assertRejected(check(TensorDescription(shape.toList(), "float32")), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        }
    }

    @Test
    fun sizeJustOutsideTheLimits_isRejectedAndAtTheLimitsAccepted() {
        assertThat(check(tensor(1, 3, 160, 160))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertThat(check(tensor(1, 3, 1280, 1280))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertThat(check(tensor(1, 3, 160, 640))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertThat(check(tensor(1, 3, 1280, 320))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertRejected(check(tensor(1, 3, 128, 128)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        assertRejected(check(tensor(1, 3, 1312, 1312)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
    }

    @Test
    fun inputRuleOrder_isRangeThenMultipleThenAspect() {
        // Out of range wins over not-a-multiple, and both win over aspect.
        assertRejected(check(tensor(1, 3, 161, 2000)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        assertRejected(check(tensor(1, 3, 161, 1280)), ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
        assertRejected(check(tensor(1, 3, 160, 1280)), ImportRejection.INPUT_ASPECT)
    }

    // ---- output ----

    @Test
    fun noOutput_isRejectedAsOutputCount() {
        val result = ImportedModelRules.check(listOf(tensor(1, 3, 640, 640)), emptyList(), config)

        val rejected = assertRejected(result, ImportRejection.OUTPUT_COUNT)
        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; outputs(0)")
    }

    @Test
    fun twoOutputs_areRejectedAsOutputCount() {
        val outputs = listOf(tensor(1, 300, 57), tensor(1, 300, 6))

        val result = ImportedModelRules.check(listOf(tensor(1, 3, 640, 640)), outputs, config)

        val rejected = assertRejected(result, ImportRejection.OUTPUT_COUNT)
        assertThat(rejected.details)
            .isEqualTo("input [1,3,640,640] float32; outputs(2) [1,300,57] float32, [1,300,6] float32")
    }

    @Test
    fun nonFloat32Output_isRejectedAsOutputType() {
        for (type in listOf("float16", "int32", "Float32")) {
            assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, 57, type = type)), ImportRejection.OUTPUT_TYPE)
        }
    }

    @Test
    fun classicHead_isRecognisedByName() {
        val result = check(tensor(1, 3, 640, 640), tensor(1, 85, 8400))

        val rejected = assertRejected(result, ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; output [1,85,8400] float32")
    }

    @Test
    fun anyChannelsFirstHead_isRecognisedAsClassic() {
        for (shape in listOf(intArrayOf(1, 85, 8400), intArrayOf(1, 116, 8400), intArrayOf(1, 59, 300), intArrayOf(1, 68, 8400))) {
            val result = check(tensor(1, 3, 640, 640), TensorDescription(shape.toList(), "float32"))

            assertRejected(result, ImportRejection.OUTPUT_CLASSIC_HEAD)
        }
    }

    @Test
    fun wrongColumnCountWithRowsFirst_isRejectedAsOutputShape() {
        for (columns in listOf(58, 68, 84)) {
            assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, columns)), ImportRejection.OUTPUT_SHAPE)
        }
    }

    @Test
    fun endToEndOutputRowsJustAbove300_areRejectedAsOutputRows() {
        val rejected = assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 301, 57)), ImportRejection.OUTPUT_ROWS)

        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; output [1,301,57] float32")
    }

    @Test
    fun manyOutputRows_areRejectedAsOutputRows() {
        assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 8400, 57)), ImportRejection.OUTPUT_ROWS)
    }

    @Test
    fun otherOutputShapes_areRejectedAsOutputShape() {
        val shapes =
            listOf(
                intArrayOf(),
                intArrayOf(300, 57),
                intArrayOf(2, 300, 57),
                intArrayOf(0, 300, 57),
                intArrayOf(1, 0, 57),
                intArrayOf(1, -1, 57),
                intArrayOf(1, 300, -1),
                intArrayOf(1, 300, 57, 1),
                intArrayOf(1, 57, 300, 1),
            )

        for (shape in shapes) {
            val result = check(tensor(1, 3, 640, 640), TensorDescription(shape.toList(), "float32"))

            assertRejected(result, ImportRejection.OUTPUT_SHAPE)
        }
    }

    @Test
    fun outputShapeDetails_nameTheShapeFound() {
        val rejected = assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, 58)), ImportRejection.OUTPUT_SHAPE)

        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; output [1,300,58] float32")
    }

    // ---- order and robustness ----

    @Test
    fun inputRules_comeBeforeOutputRules() {
        val result = check(tensor(1, 3, 100, 640), tensor(1, 56, 8400, type = "float16"))

        assertRejected(result, ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
    }

    @Test
    fun countRules_comeBeforeTypeAndShapeRules() {
        val result = ImportedModelRules.check(listOf(tensor(1, 3, 640, 640, type = "float16")), emptyList(), config)

        assertRejected(result, ImportRejection.OUTPUT_COUNT)
    }

    @Test
    fun garbageDescriptions_neverThrow() {
        val garbage =
            listOf(
                TensorDescription(emptyList(), ""),
                TensorDescription(listOf(Int.MIN_VALUE, Int.MAX_VALUE, 0, -1), "\u0000"),
                TensorDescription(List(1000) { it }, "float32"),
            )

        for (a in garbage) {
            for (b in garbage) {
                val result = ImportedModelRules.check(listOf(a), listOf(b), config)

                assertThat(result).isInstanceOf(ImportCheck.Rejected::class.java)
            }
        }
    }

    @Test
    fun everyRejection_hasAUniqueKey() {
        val keys = ImportRejection.entries.map { it.key }

        assertThat(keys).containsNoDuplicates()
        assertThat(keys).containsExactly(
            "input_count",
            "input_type",
            "quantized_io",
            "input_not_nchw",
            "input_nhwc",
            "input_aspect",
            "input_size_not_multiple",
            "input_size_out_of_range",
            "output_count",
            "output_type",
            "output_classic_head",
            "output_detect_only",
            "output_keypoint_count",
            "output_rows",
            "output_shape",
        )
    }

    @Test
    fun poseColumns_are57() {
        assertThat(TrackerConfig.POSE_OUTPUT_COLUMNS).isEqualTo(57)
    }

    // ---- raw pose output (M_final F2) ----

    private fun checkRaw(
        output: TensorDescription,
        input: TensorDescription = tensor(1, 3, 640, 640),
    ) = ImportedModelRules.check(listOf(input), listOf(output), config)

    @Test
    fun rawChannelsFirst_isAcceptedWithItsLayout() {
        assertThat(checkRaw(tensor(1, 56, 8400))).isEqualTo(
            ImportCheck.Accepted(640, 640, 8400, PoseOutputLayout.RAW_CHANNELS_FIRST),
        )
    }

    @Test
    fun rawChannelsLast_isAcceptedWithItsLayout() {
        assertThat(checkRaw(tensor(1, 8400, 56))).isEqualTo(
            ImportCheck.Accepted(640, 640, 8400, PoseOutputLayout.RAW_CHANNELS_LAST),
        )
    }

    @Test
    fun rawOutputWithFewRows_isAccepted() {
        assertThat(checkRaw(tensor(1, 56, 300))).isEqualTo(ImportCheck.Accepted(640, 640, 300, PoseOutputLayout.RAW_CHANNELS_FIRST))
        assertThat(checkRaw(tensor(1, 300, 56))).isEqualTo(ImportCheck.Accepted(640, 640, 300, PoseOutputLayout.RAW_CHANNELS_LAST))
    }

    @Test
    fun endToEndOutput_staysEndToEnd() {
        assertThat(check(tensor(1, 3, 640, 640)).let { (it as ImportCheck.Accepted).outputLayout })
            .isEqualTo(PoseOutputLayout.END_TO_END)
    }

    @Test
    fun rawRows_areCappedAtTheRawLimit() {
        val atCap = TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS
        assertThat(checkRaw(tensor(1, 56, atCap))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertRejected(checkRaw(tensor(1, 56, atCap + 1)), ImportRejection.OUTPUT_ROWS)
        assertRejected(checkRaw(tensor(1, atCap + 1, 56)), ImportRejection.OUTPUT_ROWS)
    }

    @Test
    fun theDefaultMaximumInput_fitsTheRawRowCap() {
        // 1280 x 1280: 160*160 + 80*80 + 40*40 = 33600 candidate rows.
        val result = checkRaw(tensor(1, 56, 33_600), tensor(1, 3, 1280, 1280))

        assertThat(result).isEqualTo(ImportCheck.Accepted(1280, 1280, 33_600, PoseOutputLayout.RAW_CHANNELS_FIRST))
    }

    @Test
    fun squareOutputWithBothAxes56_isAmbiguousAndRejected() {
        assertRejected(checkRaw(tensor(1, 56, 56)), ImportRejection.OUTPUT_SHAPE)
    }

    @Test
    fun rawOutputWithOtherRankBatchOrDynamicRows_isRejectedAsOutputShape() {
        for (shape in listOf(
            intArrayOf(2, 56, 8400),
            intArrayOf(1, 56, 8400, 1),
            intArrayOf(1, 56, -1),
            intArrayOf(1, 56, 0),
            intArrayOf(1, -1, 56),
        )) {
            assertRejected(checkRaw(TensorDescription(shape.toList(), "float32")), ImportRejection.OUTPUT_SHAPE)
        }
    }

    @Test
    fun detectOnlyOutput_stillRefusedShapes_areRejectedAsDetectOnly() {
        // Detect-like but not decodable: 300 rows with 51 channels is neither a raw head (rows not above the channels
        // rule for channels-last) nor an end-to-end `[1, N, 6]`; `[1, 5, 6]` is ambiguous between the two.
        val shapes = listOf(intArrayOf(1, 300, 51), intArrayOf(1, 5, 6), intArrayOf(1, 6, 5))

        for (shape in shapes) {
            val rejected = assertRejected(checkRaw(TensorDescription(shape.toList(), "float32")), ImportRejection.OUTPUT_DETECT_ONLY)
            assertThat(rejected.details).contains(shape.joinToString(",", "[", "]"))
        }
    }

    @Test
    fun quantizedInput_isRejectedAsQuantizedIo() {
        for (type in listOf("int8", "uint8")) {
            assertRejected(check(tensor(1, 3, 640, 640, type = type)), ImportRejection.QUANTIZED_IO)
        }
    }

    @Test
    fun quantizedOutput_isRejectedAsQuantizedIo() {
        for (type in listOf("int8", "uint8")) {
            assertRejected(checkRaw(tensor(1, 56, 8400, type = type)), ImportRejection.QUANTIZED_IO)
            assertRejected(checkRaw(tensor(1, 300, 57, type = type)), ImportRejection.QUANTIZED_IO)
        }
    }

    @Test
    fun otherNonFloat32Types_keepTheirGenericReasons() {
        assertRejected(check(tensor(1, 3, 640, 640, type = "float16")), ImportRejection.INPUT_TYPE)
        assertRejected(checkRaw(tensor(1, 56, 8400, type = "float16")), ImportRejection.OUTPUT_TYPE)
    }

    @Test
    fun smallRawLookingShapes_withA56Channel_areNeverDetectOnly() {
        for (shape in listOf(intArrayOf(1, 56, 21), intArrayOf(1, 56, 7), intArrayOf(1, 57, 60))) {
            assertRejected(checkRaw(TensorDescription(shape.toList(), "float32")), ImportRejection.OUTPUT_SHAPE)
        }
    }

    @Test
    fun smallShapesWithAnAxisOf56_areDetectHeadsWhenTheyFitTheDetectRules() {
        assertThat(accepted(tensor(1, 21, 56)).outputLayout).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST)
        assertThat(accepted(tensor(1, 5, 56)).outputLayout).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST)
        assertThat(accepted(tensor(1, 56, 6)).outputLayout).isEqualTo(PoseOutputLayout.DETECT_END_TO_END)
    }

    @Test
    fun poseHeadsWithOtherKeypointCounts_areRejectedAsKeypointCount() {
        // 5 + 3k channels (channels first or last): 8 = 1 keypoint, 41 = 12 keypoints, 53 = 16 keypoints.
        for (channels in listOf(8, 41, 53)) {
            for (shape in listOf(intArrayOf(1, channels, 8400), intArrayOf(1, 8400, channels))) {
                val rejected = assertRejected(checkRaw(TensorDescription(shape.toList(), "float32")), ImportRejection.OUTPUT_KEYPOINT_COUNT)
                assertThat(rejected.details).contains(shape.joinToString(",", "[", "]"))
            }
        }
    }

    @Test
    fun rawLayoutOfShape_isOneSharedRuleForListsAndArrays() {
        assertThat(PoseOutputLayout.rawOf(intArrayOf(1, 56, 8400))).isEqualTo(PoseOutputLayout.RAW_CHANNELS_FIRST)
        assertThat(PoseOutputLayout.rawOf(listOf(1, 8400, 56))).isEqualTo(PoseOutputLayout.RAW_CHANNELS_LAST)
        assertThat(PoseOutputLayout.rawOf(intArrayOf(2, 56, 8400))).isNull()
        assertThat(PoseOutputLayout.rawOf(intArrayOf(1, 56, 56))).isNull()
        assertThat(PoseOutputLayout.rawOf(intArrayOf(1, 300, 57))).isNull()
        assertThat(PoseOutputLayout.rawOf(intArrayOf(1, 56, 8400, 1))).isNull()
    }

    // ---- detect-only output (M-legacy T2) ----

    private fun accepted(output: TensorDescription) = checkRaw(output) as ImportCheck.Accepted

    @Test
    fun ownersModel_channelsFirstWithTwoClasses_isAccepted() {
        assertThat(checkRaw(tensor(1, 6, 2100), tensor(1, 3, 320, 320))).isEqualTo(
            ImportCheck.Accepted(320, 320, 2100, PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, classes = 2),
        )
    }

    @Test
    fun rawDetectChannelsFirst_acceptsOneToEightyClasses() {
        for (classes in listOf(1, 2, 3, 80)) {
            val result = accepted(tensor(1, 4 + classes, 8400))

            assertThat(result.outputLayout).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST)
            assertThat(result.classes).isEqualTo(classes)
            assertThat(result.outputRows).isEqualTo(8400)
        }
    }

    @Test
    fun rawDetectChannelsLast_needsMoreRowsThanTheEndToEndLimit() {
        val result = accepted(tensor(1, 2100, 6))

        assertThat(result.outputLayout).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)
        assertThat(result.classes).isEqualTo(2)
        assertThat(result.outputRows).isEqualTo(2100)
        assertThat(accepted(tensor(1, 8400, 84)).classes).isEqualTo(80)
        assertThat(accepted(tensor(1, TrackerConfig.IMPORT_MAX_OUTPUT_ROWS + 1, 5)).outputLayout)
            .isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)
    }

    @Test
    fun endToEndDetect_isSixColumnsWithUpToThreeHundredRows() {
        assertThat(checkRaw(tensor(1, 300, 6))).isEqualTo(
            ImportCheck.Accepted(640, 640, 300, PoseOutputLayout.DETECT_END_TO_END),
        )
        assertThat(accepted(tensor(1, 1, 6)).outputLayout).isEqualTo(PoseOutputLayout.DETECT_END_TO_END)
        assertThat(accepted(tensor(1, 100, 6)).outputRows).isEqualTo(100)
    }

    @Test
    fun yoloV8DetectAnd84Channels_areAccepted() {
        assertThat(checkRaw(tensor(1, 84, 8400))).isEqualTo(
            ImportCheck.Accepted(640, 640, 8400, PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, classes = 80),
        )
        assertThat(checkRaw(tensor(1, 5, 8400))).isEqualTo(
            ImportCheck.Accepted(640, 640, 8400, PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, classes = 1),
        )
    }

    @Test
    fun detectHeads_keepTheInputLayout() {
        val input = TensorDescription(listOf(1, 320, 320, 3), "float32")

        val result = checkRaw(tensor(1, 6, 2100), input) as ImportCheck.Accepted

        assertThat(result.inputLayout).isEqualTo(InputLayout.NHWC)
        assertThat(result.outputLayout).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST)
    }

    @Test
    fun keypointCountChannels_stayRefusedAsKeypointCount() {
        // 4 + C = 8 (C = 4), 11, 14 ... are 5 + 3k: unchanged precedence over detect heads.
        for (channels in listOf(8, 11, 14, 41, 53)) {
            for (shape in listOf(intArrayOf(1, channels, 8400), intArrayOf(1, 8400, channels))) {
                assertRejected(checkRaw(TensorDescription(shape.toList(), "float32")), ImportRejection.OUTPUT_KEYPOINT_COUNT)
            }
        }
    }

    @Test
    fun objectnessAndTooManyClassChannels_stayRefused() {
        assertRejected(checkRaw(tensor(1, 85, 8400)), ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertRejected(checkRaw(tensor(1, 86, 8400)), ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertRejected(checkRaw(tensor(1, 100, 8400)), ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertRejected(checkRaw(tensor(1, 8400, 85)), ImportRejection.OUTPUT_SHAPE)
    }

    @Test
    fun boxOnlyChannelCount4_isNotADetector() {
        assertRejected(checkRaw(tensor(1, 4, 2100)), ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertRejected(checkRaw(tensor(1, 2100, 4)), ImportRejection.OUTPUT_SHAPE)
    }

    @Test
    fun poseChannelCounts56And57_areNeverTakenForDetectHeads() {
        assertThat(accepted(tensor(1, 56, 8400)).outputLayout).isEqualTo(PoseOutputLayout.RAW_CHANNELS_FIRST)
        assertThat(accepted(tensor(1, 8400, 56)).outputLayout).isEqualTo(PoseOutputLayout.RAW_CHANNELS_LAST)
        assertThat(accepted(tensor(1, 300, 57)).outputLayout).isEqualTo(PoseOutputLayout.END_TO_END)
        assertRejected(checkRaw(tensor(1, 57, 8400)), ImportRejection.OUTPUT_SHAPE)
        assertRejected(checkRaw(tensor(1, 8400, 57)), ImportRejection.OUTPUT_ROWS)
    }

    @Test
    fun poseAcceptedShapes_haveNoClasses() {
        assertThat(accepted(tensor(1, 300, 57)).classes).isEqualTo(0)
        assertThat(accepted(tensor(1, 56, 8400)).classes).isEqualTo(0)
        assertThat(accepted(tensor(1, 300, 6)).classes).isEqualTo(0)
    }

    @Test
    fun detectRows_areCappedAtTheRawLimit() {
        val atCap = TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS
        assertThat(checkRaw(tensor(1, 6, atCap))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertThat(checkRaw(tensor(1, atCap, 6))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertRejected(checkRaw(tensor(1, 6, atCap + 1)), ImportRejection.OUTPUT_ROWS)
        assertRejected(checkRaw(tensor(1, atCap + 1, 6)), ImportRejection.OUTPUT_ROWS)
    }

    @Test
    fun detectShapes_withOtherBatchRankOrDynamicRows_areRejected() {
        for (shape in listOf(
            intArrayOf(2, 6, 2100),
            intArrayOf(1, 6, 2100, 1),
            intArrayOf(1, 6, -1),
            intArrayOf(1, 6, 0),
            intArrayOf(1, -1, 6),
            intArrayOf(1, 0, 6),
        )) {
            assertThat(checkRaw(TensorDescription(shape.toList(), "float32"))).isInstanceOf(ImportCheck.Rejected::class.java)
        }
    }

    @Test
    fun detectLayoutOfShape_isOneSharedRuleForListsAndArrays() {
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 6, 2100))).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST)
        assertThat(PoseOutputLayout.detectOf(listOf(1, 2100, 6))).isEqualTo(PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 300, 6))).isEqualTo(PoseOutputLayout.DETECT_END_TO_END)
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 56, 8400))).isNull()
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 300, 57))).isNull()
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 5, 6))).isNull()
        assertThat(PoseOutputLayout.detectOf(intArrayOf(2, 6, 2100))).isNull()
        assertThat(PoseOutputLayout.detectOf(intArrayOf(1, 6, 2100, 1))).isNull()
    }

    @Test
    fun detectClassCount_isTheScoreColumnsOfARawHead() {
        assertThat(PoseOutputLayout.detectClassesOf(intArrayOf(1, 6, 2100))).isEqualTo(2)
        assertThat(PoseOutputLayout.detectClassesOf(intArrayOf(1, 2100, 84))).isEqualTo(80)
        assertThat(PoseOutputLayout.detectClassesOf(intArrayOf(1, 300, 6))).isEqualTo(0)
        assertThat(PoseOutputLayout.detectClassesOf(intArrayOf(1, 56, 8400))).isEqualTo(0)
    }

    @Test
    fun layoutKinds_separatePoseRawFromDetect() {
        assertThat(PoseOutputLayout.entries.filter { it.isRaw }).containsExactly(
            PoseOutputLayout.RAW_CHANNELS_FIRST,
            PoseOutputLayout.RAW_CHANNELS_LAST,
        )
        assertThat(PoseOutputLayout.entries.filter { it.isDetectOnly }).containsExactly(
            PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST,
            PoseOutputLayout.DETECT_RAW_CHANNELS_LAST,
            PoseOutputLayout.DETECT_END_TO_END,
        )
    }
}
