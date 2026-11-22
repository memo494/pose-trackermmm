package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ImportedModelMetaInputLayoutTest {
    private val sha = "0123456789abcdef".repeat(4)

    private val nchw = ImportedModelMeta("m.tflite", sha, 1_000L, 640, 640, 300)
    private val nhwc = nchw.copy(inputLayout = InputLayout.NHWC)
    private val nhwcRawFirst = nhwc.copy(outputRows = 8400, outputLayout = PoseOutputLayout.RAW_CHANNELS_FIRST)

    private fun v4(
        width: String = "640",
        height: String = "640",
        rows: String = "300",
        outputLayout: String = "outputLayout=end_to_end",
        inputLayout: String = "inputLayout=nhwc",
    ) = listOf(
        "posetracker-imported-model v4",
        "name=m.tflite",
        "sha256=$sha",
        "bytes=1000",
        "inputWidth=$width",
        "inputHeight=$height",
        "outputRows=$rows",
        outputLayout,
        inputLayout,
    ).joinToString("\n")

    private val v2Text =
        listOf(
            "posetracker-imported-model v2",
            "name=m.tflite",
            "sha256=$sha",
            "bytes=1000",
            "inputWidth=640",
            "inputHeight=640",
            "outputRows=300",
        ).joinToString("\n")

    private val v3Text =
        listOf(
            "posetracker-imported-model v3",
            "name=m.tflite",
            "sha256=$sha",
            "bytes=1000",
            "inputWidth=640",
            "inputHeight=640",
            "outputRows=8400",
            "outputLayout=raw_channels_first",
        ).joinToString("\n")

    private val v1Text =
        listOf(
            "posetracker-imported-model v1",
            "name=m.tflite",
            "sha256=$sha",
            "bytes=1000",
            "inputSize=640",
            "outputRows=300",
        ).joinToString("\n")

    @Test
    fun aMetaWithoutAnInputLayout_isNchw() {
        assertThat(nchw.inputLayout).isEqualTo(InputLayout.NCHW)
    }

    @Test
    fun nhwcMeta_encodesAsVersion4_withBothLayoutLines() {
        assertThat(nhwc.encode()).isEqualTo(v4())
        assertThat(nhwcRawFirst.encode())
            .isEqualTo(v4(rows = "8400", outputLayout = "outputLayout=raw_channels_first"))
    }

    @Test
    fun nhwcMeta_roundTrips_forEveryOutputLayout() {
        for (layout in PoseOutputLayout.entries) {
            val rows = if (layout.isRaw) 8400 else 300
            val meta = nhwc.copy(outputRows = rows, outputLayout = layout)

            assertThat(ImportedModelMeta.decode(meta.encode())).isEqualTo(meta)
        }
    }

    @Test
    fun nhwcMeta_rectangular_keepsWidthAndHeightApart() {
        val meta = nhwc.copy(inputWidth = 832, inputHeight = 384)

        val decoded = ImportedModelMeta.decode(meta.encode())

        assertThat(decoded).isEqualTo(meta)
        assertThat(decoded!!.inputWidth).isEqualTo(832)
        assertThat(decoded.inputHeight).isEqualTo(384)
    }

    @Test
    fun nchwMeta_stillEncodesAsTheOldVersions() {
        assertThat(nchw.encode()).isEqualTo(v2Text)
        assertThat(nchw.copy(outputRows = 8400, outputLayout = PoseOutputLayout.RAW_CHANNELS_FIRST).encode()).isEqualTo(v3Text)
    }

    @Test
    fun olderTexts_stillDecode_asNchw() {
        assertThat(ImportedModelMeta.decode(v1Text)!!.inputLayout).isEqualTo(InputLayout.NCHW)
        assertThat(ImportedModelMeta.decode(v2Text)!!.inputLayout).isEqualTo(InputLayout.NCHW)
        assertThat(ImportedModelMeta.decode(v3Text)!!.inputLayout).isEqualTo(InputLayout.NCHW)
    }

    @Test
    fun version4_withAnUnknownOrNonCanonicalInputLayout_isRefused() {
        val bad =
            listOf(
                v4(inputLayout = "inputLayout="),
                v4(inputLayout = "inputLayout=NHWC"),
                v4(inputLayout = "inputLayout=hwc"),
                v4(inputLayout = "inputLayout=nchw"),
                v4(inputLayout = "inputLayout=nhwc "),
                v4(inputLayout = "layout=nhwc"),
            )

        for (text in bad) assertThat(ImportedModelMeta.decode(text)).isNull()
    }

    @Test
    fun version4_withAnUnknownOutputLayout_isRefused() {
        for (layout in listOf("outputLayout=", "outputLayout=raw", "outputLayout=END_TO_END", "output=end_to_end")) {
            assertThat(ImportedModelMeta.decode(v4(outputLayout = layout))).isNull()
        }
    }

    @Test
    fun version4_isStrict_aboutLinesAndOrder() {
        val text = v4()
        val bad =
            listOf(
                text + "\n",
                text + "\nextra=1",
                text.substringBeforeLast("\n"),
                text.replace("\n", "\r\n"),
                text.lines().let { it.take(7) + it[8] + it[7] }.joinToString("\n"),
                text.replace("posetracker-imported-model v4", "posetracker-imported-model v5"),
            )

        for (b in bad) assertThat(ImportedModelMeta.decode(b)).isNull()
    }

    @Test
    fun version4_obeysTheSameRangeAndAspectRulesAsTheOthers() {
        assertThat(ImportedModelMeta.decode(v4(width = "100"))).isNull()
        assertThat(ImportedModelMeta.decode(v4(height = "650"))).isNull()
        assertThat(ImportedModelMeta.decode(v4(width = "1280", height = "160"))).isNull()
        assertThat(ImportedModelMeta.decode(v4(rows = "0"))).isNull()
        assertThat(ImportedModelMeta.decode(v4(rows = "301"))).isNull()
        assertThat(ImportedModelMeta.decode(v4(rows = "${TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS}"))).isNull()
        val raw = v4(rows = "${TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS}", outputLayout = "outputLayout=raw_channels_first")
        assertThat(ImportedModelMeta.decode(raw)).isNotNull()
    }

    @Test
    fun orientationAndFit_useRealWidthAndHeight_whateverTheLayout() {
        val wideNhwc = nhwc.copy(inputWidth = 832, inputHeight = 384)
        val wideNchw = nchw.copy(inputWidth = 832, inputHeight = 384)

        assertThat(orientationOfShape(wideNhwc)).isEqualTo(orientationOfShape(wideNchw))
        assertThat(shapeWord(wideNhwc)).isEqualTo(shapeWord(wideNchw))
    }
}
