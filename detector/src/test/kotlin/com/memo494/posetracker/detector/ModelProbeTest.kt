package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportCheck
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.ImportedModelRules
import com.memo494.posetracker.core.modelimport.InputLayout
import com.memo494.posetracker.core.modelimport.PoseOutputLayout
import com.memo494.posetracker.core.modelimport.TensorDescription
import org.junit.Test

class ModelProbeTest {
    private val config = TrackerConfig()

    @Test
    fun describeKeepsNormalNchwDimensionsAndNamesFloatFloat32() {
        val tensor = ModelProbe.describe(intArrayOf(1, 3, 640, 640), "FLOAT")

        assertThat(tensor).isEqualTo(TensorDescription(listOf(1, 3, 640, 640), "float32"))
    }

    @Test
    fun describeReportsNullDimensionsAsEmptyShape() {
        assertThat(ModelProbe.describe(null, "FLOAT").shape).isEmpty()
    }

    @Test
    fun describeReportsEmptyDimensionsAsEmptyShape() {
        assertThat(ModelProbe.describe(IntArray(0), "FLOAT").shape).isEmpty()
    }

    @Test
    fun describeKeepsDynamicDimensionsAsRead() {
        assertThat(ModelProbe.describe(intArrayOf(1, -1, 57), "FLOAT").shape).containsExactly(1, -1, 57).inOrder()
    }

    @Test
    fun describeMapsEveryKnownElementType() {
        val expected =
            mapOf(
                "FLOAT" to "float32",
                "INT" to "int32",
                "INT8" to "int8",
                "UINT8" to "uint8",
                "INT64" to "int64",
                "HALF" to "float16",
                "FLOAT16" to "float16",
                "BOOLEAN" to "bool",
                "BOOL" to "bool",
            )
        for ((liteRtName, name) in expected) {
            assertThat(ModelProbe.elementTypeName(liteRtName)).isEqualTo(name)
        }
    }

    @Test
    fun elementTypeNameIsLowerCaseOfAnUnknownName() {
        assertThat(ModelProbe.elementTypeName("COMPLEX64")).isEqualTo("complex64")
    }

    @Test
    fun elementTypeNameOfNullIsUnknown() {
        assertThat(ModelProbe.elementTypeName(null)).isEqualTo(ModelProbe.UNKNOWN_ELEMENT_TYPE)
        assertThat(ModelProbe.describe(intArrayOf(1), null).elementType).isEqualTo("unknown")
    }

    @Test
    fun realSixHundredFortyShapesAreAccepted() {
        val input = ModelProbe.describe(intArrayOf(1, 3, 640, 640), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 300, 57), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(check).isEqualTo(ImportCheck.Accepted(inputWidth = 640, inputHeight = 640, outputRows = 300))
    }

    @Test
    fun realRectangularShapesAreAcceptedWithWidthAndHeightApart() {
        val input = ModelProbe.describe(intArrayOf(1, 3, 384, 832), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 300, 57), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(check).isEqualTo(ImportCheck.Accepted(inputWidth = 832, inputHeight = 384, outputRows = 300))
    }

    @Test
    fun nhwcInputIsAcceptedAsNhwc() {
        val input = ModelProbe.describe(intArrayOf(1, 640, 640, 3), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 300, 57), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(
            check,
        ).isEqualTo(ImportCheck.Accepted(inputWidth = 640, inputHeight = 640, outputRows = 300, inputLayout = InputLayout.NHWC))
    }

    @Test
    fun realRectangularNhwcShapesAreAcceptedWithWidthAndHeightApart() {
        val input = ModelProbe.describe(intArrayOf(1, 384, 832, 3), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 300, 57), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(
            check,
        ).isEqualTo(ImportCheck.Accepted(inputWidth = 832, inputHeight = 384, outputRows = 300, inputLayout = InputLayout.NHWC))
    }

    @Test
    fun yoloV5ObjectnessHeadIsRejectedAsClassicHead() {
        val input = ModelProbe.describe(intArrayOf(1, 3, 640, 640), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 85, 8400), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat((check as ImportCheck.Rejected).reason).isEqualTo(ImportRejection.OUTPUT_CLASSIC_HEAD)
    }

    @Test
    fun cocoDetectOutputIsAcceptedAsRawDetectHead() {
        val input = ModelProbe.describe(intArrayOf(1, 3, 640, 640), "FLOAT")
        val output = ModelProbe.describe(intArrayOf(1, 84, 8400), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(check).isEqualTo(
            ImportCheck.Accepted(640, 640, 8400, PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST, classes = 80),
        )
    }

    @Test
    fun unreadableInputShapeIsRejected() {
        val input = ModelProbe.describe(null, null)
        val output = ModelProbe.describe(intArrayOf(1, 300, 57), "FLOAT")

        val check = ImportedModelRules.check(listOf(input), listOf(output), config)

        assertThat(check).isInstanceOf(ImportCheck.Rejected::class.java)
    }

    @Test
    fun failureMessageHasClassNameAndMessage() {
        val message = ModelProbe.failureMessage(IllegalStateException("bad model"))

        assertThat(message).isEqualTo("IllegalStateException: bad model")
    }

    @Test
    fun failureMessageWithoutMessageIsTheClassName() {
        assertThat(ModelProbe.failureMessage(OutOfMemoryError())).isEqualTo("OutOfMemoryError")
    }

    @Test
    fun failureMessageIsCapped() {
        val message = ModelProbe.failureMessage(RuntimeException("x".repeat(ModelProbe.MAX_MESSAGE_CHARS * 3)))

        assertThat(message.length).isAtMost(ModelProbe.MAX_MESSAGE_CHARS)
        assertThat(message).startsWith("RuntimeException: xxx")
    }

    @Test
    fun failureMessageStaysOnOneLine() {
        val message = ModelProbe.failureMessage(RuntimeException("line one\nline two\r\nline three"))

        assertThat(message).doesNotContain("\n")
        assertThat(message).doesNotContain("\r")
    }
}
