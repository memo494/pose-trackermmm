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
        assertThat(check(tensor(1, 3, 416, 416))).isEqualTo(ImportCheck.Accepted(inputSize = 416, outputRows = 300))
    }

    @Test
    fun the640Export_isAccepted() {
        assertThat(check(tensor(1, 3, 640, 640))).isEqualTo(ImportCheck.Accepted(inputSize = 640, outputRows = 300))
    }

    @Test
    fun inputSizes_atBothLimitsAndInBetween_areAccepted() {
        for (size in listOf(config.importMinInputSize, 320, 352, config.importMaxInputSize)) {
            val result = check(tensor(1, 3, size, size))

            assertThat(result).isEqualTo(ImportCheck.Accepted(inputSize = size, outputRows = 300))
        }
    }

    @Test
    fun outputRows_areReportedForAnyPositiveNUpTo300() {
        for (rows in listOf(1, 299, 300)) {
            val result = check(tensor(1, 3, 640, 640), tensor(1, rows, 57))

            assertThat(result).isEqualTo(ImportCheck.Accepted(inputSize = 640, outputRows = rows))
        }
    }

    @Test
    fun limitsComeFromTheConfig() {
        val custom = TrackerConfig(importMinInputSize = 320, importMaxInputSize = 416)

        val accepted = ImportedModelRules.check(listOf(tensor(1, 3, 416, 416)), listOf(tensor(1, 300, 57)), custom)
        val tooBig = ImportedModelRules.check(listOf(tensor(1, 3, 448, 448)), listOf(tensor(1, 300, 57)), custom)
        val tooSmall = ImportedModelRules.check(listOf(tensor(1, 3, 288, 288)), listOf(tensor(1, 300, 57)), custom)

        assertThat(accepted).isEqualTo(ImportCheck.Accepted(inputSize = 416, outputRows = 300))
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
        for (type in listOf("uint8", "int8", "float16", "int32", "float64", "FLOAT32", "float32 ", "")) {
            val result = check(tensor(1, 3, 640, 640, type = type))

            assertRejected(result, ImportRejection.INPUT_TYPE)
        }
    }

    @Test
    fun inputType_isCheckedBeforeTheLayout() {
        val result = check(tensor(1, 640, 640, 3, type = "uint8"))

        assertRejected(result, ImportRejection.INPUT_TYPE)
    }

    // ---- input layout ----

    @Test
    fun nhwcInput_isRecognisedByName() {
        val result = check(tensor(1, 640, 640, 3), tensor(1, 56, 8400))

        val rejected = assertRejected(result, ImportRejection.INPUT_NHWC)
        assertThat(rejected.details).isEqualTo("input [1,640,640,3] float32; output [1,56,8400] float32")
    }

    @Test
    fun nonSquareNhwcInput_isStillRecognisedAsNhwc() {
        assertRejected(check(tensor(1, 640, 480, 3)), ImportRejection.INPUT_NHWC)
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
    fun nonSquareInput_isRejected() {
        for ((h, w) in listOf(640 to 480, 480 to 640, 352 to 608, -1 to 640)) {
            assertRejected(check(tensor(1, 3, h, w)), ImportRejection.INPUT_NOT_SQUARE)
        }
    }

    @Test
    fun squareInputNotAMultipleOf32_isRejected() {
        for (size in listOf(161, 170, 330, 641, 1279)) {
            assertRejected(check(tensor(1, 3, size, size)), ImportRejection.INPUT_SIZE_NOT_MULTIPLE)
        }
    }

    @Test
    fun squareInputOutsideTheLimits_isRejected() {
        for (size in listOf(-1, 0, 32, 128, 1312, 4096)) {
            assertRejected(check(tensor(1, 3, size, size)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        }
    }

    @Test
    fun sizeJustOutsideTheLimits_isRejectedAndAtTheLimitsAccepted() {
        assertThat(check(tensor(1, 3, 160, 160))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertThat(check(tensor(1, 3, 1280, 1280))).isInstanceOf(ImportCheck.Accepted::class.java)
        assertRejected(check(tensor(1, 3, 128, 128)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
        assertRejected(check(tensor(1, 3, 1312, 1312)), ImportRejection.INPUT_SIZE_OUT_OF_RANGE)
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
        for (type in listOf("uint8", "int8", "float16", "int32", "Float32")) {
            assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, 57, type = type)), ImportRejection.OUTPUT_TYPE)
        }
    }

    @Test
    fun classicHead_isRecognisedByName() {
        val result = check(tensor(1, 3, 640, 640), tensor(1, 56, 8400))

        val rejected = assertRejected(result, ImportRejection.OUTPUT_CLASSIC_HEAD)
        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; output [1,56,8400] float32")
    }

    @Test
    fun anyChannelsFirstHead_isRecognisedAsClassic() {
        for (shape in listOf(intArrayOf(1, 56, 8400), intArrayOf(1, 84, 8400), intArrayOf(1, 56, 300), intArrayOf(1, 5, 8400))) {
            val result = check(tensor(1, 3, 640, 640), TensorDescription(shape.toList(), "float32"))

            assertRejected(result, ImportRejection.OUTPUT_CLASSIC_HEAD)
        }
    }

    @Test
    fun wrongColumnCountWithRowsFirst_isRejectedAsOutputShape() {
        for (columns in listOf(51, 56, 58, 6)) {
            assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, columns)), ImportRejection.OUTPUT_SHAPE)
        }
    }

    @Test
    fun outputRowsJustAbove300_areRejectedAsOutputRows() {
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
        val rejected = assertRejected(check(tensor(1, 3, 640, 640), tensor(1, 300, 56)), ImportRejection.OUTPUT_SHAPE)

        assertThat(rejected.details).isEqualTo("input [1,3,640,640] float32; output [1,300,56] float32")
    }

    // ---- order and robustness ----

    @Test
    fun inputRules_comeBeforeOutputRules() {
        val result = check(tensor(1, 640, 640, 3), tensor(1, 56, 8400, type = "uint8"))

        assertRejected(result, ImportRejection.INPUT_NHWC)
    }

    @Test
    fun countRules_comeBeforeTypeAndShapeRules() {
        val result = ImportedModelRules.check(listOf(tensor(1, 3, 640, 640, type = "uint8")), emptyList(), config)

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
            "input_not_nchw",
            "input_nhwc",
            "input_not_square",
            "input_size_not_multiple",
            "input_size_out_of_range",
            "output_count",
            "output_type",
            "output_classic_head",
            "output_rows",
            "output_shape",
        )
    }

    @Test
    fun poseColumns_are57() {
        assertThat(TrackerConfig.POSE_OUTPUT_COLUMNS).isEqualTo(57)
    }
}
