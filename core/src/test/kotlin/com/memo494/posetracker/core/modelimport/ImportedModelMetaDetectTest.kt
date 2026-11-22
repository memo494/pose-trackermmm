package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ImportedModelMetaDetectTest {
    private val sha = "0123456789abcdef".repeat(4)

    private val poseMeta = ImportedModelMeta("m.tflite", sha, 1_000L, 640, 640, 300)
    private val detectFirst =
        ImportedModelMeta(
            "m.tflite",
            sha,
            1_000L,
            320,
            320,
            2100,
            outputLayout = PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST,
            classes = 2,
        )
    private val detectLast = detectFirst.copy(outputLayout = PoseOutputLayout.DETECT_RAW_CHANNELS_LAST)
    private val detectEndToEnd = detectFirst.copy(outputRows = 300, outputLayout = PoseOutputLayout.DETECT_END_TO_END, classes = 0)

    private fun v5(
        rows: String = "2100",
        layout: String = "outputLayout=detect_raw_channels_first",
        input: String = "inputLayout=nchw",
        classes: String = "classes=2",
        width: String = "320",
    ) = listOf(
        "posetracker-imported-model v5",
        "name=m.tflite",
        "sha256=$sha",
        "bytes=1000",
        "inputWidth=$width",
        "inputHeight=320",
        "outputRows=$rows",
        layout,
        input,
        classes,
    ).joinToString("\n")

    @Test
    fun aMetaWithoutClasses_hasNone() {
        assertThat(poseMeta.classes).isEqualTo(0)
    }

    @Test
    fun detectMeta_encodesAsVersion5_withLayoutInputLayoutAndClasses() {
        assertThat(detectFirst.encode()).isEqualTo(v5())
        assertThat(detectLast.encode()).isEqualTo(v5(layout = "outputLayout=detect_raw_channels_last"))
        assertThat(detectEndToEnd.encode())
            .isEqualTo(v5(rows = "300", layout = "outputLayout=detect_end_to_end", classes = "classes=0"))
    }

    @Test
    fun detectMeta_roundTrips_forEveryDetectLayoutAndInputLayout() {
        for (meta in listOf(detectFirst, detectLast, detectEndToEnd)) {
            for (input in InputLayout.entries) {
                val candidate = meta.copy(inputLayout = input)

                assertThat(ImportedModelMeta.decode(candidate.encode())).isEqualTo(candidate)
            }
        }
    }

    @Test
    fun nhwcDetectMeta_writesNhwcInVersion5() {
        val nhwc = detectFirst.copy(inputLayout = InputLayout.NHWC)

        assertThat(nhwc.encode()).isEqualTo(v5(input = "inputLayout=nhwc"))
    }

    @Test
    fun poseMetas_keepTheirOlderTexts() {
        val raw = poseMeta.copy(outputRows = 8400, outputLayout = PoseOutputLayout.RAW_CHANNELS_FIRST)
        val nhwc = poseMeta.copy(inputLayout = InputLayout.NHWC)

        assertThat(poseMeta.encode().lines().first()).isEqualTo("posetracker-imported-model v2")
        assertThat(raw.encode().lines().first()).isEqualTo("posetracker-imported-model v3")
        assertThat(nhwc.encode().lines().first()).isEqualTo("posetracker-imported-model v4")
        for (meta in listOf(poseMeta, raw, nhwc)) assertThat(ImportedModelMeta.decode(meta.encode())).isEqualTo(meta)
    }

    @Test
    fun olderVersionsStillDecodeWithoutClasses() {
        val v4 =
            listOf(
                "posetracker-imported-model v4",
                "name=m.tflite",
                "sha256=$sha",
                "bytes=1000",
                "inputWidth=640",
                "inputHeight=640",
                "outputRows=8400",
                "outputLayout=raw_channels_first",
                "inputLayout=nhwc",
            ).joinToString("\n")

        val meta = ImportedModelMeta.decode(v4)

        assertThat(meta?.classes).isEqualTo(0)
        assertThat(meta?.outputLayout).isEqualTo(PoseOutputLayout.RAW_CHANNELS_FIRST)
    }

    @Test
    fun detectRows_goUpToTheRawCap_butEndToEndRowsStayAt300() {
        val atCap = TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS
        assertThat(ImportedModelMeta.decode(v5(rows = "$atCap"))).isNotNull()
        assertThat(ImportedModelMeta.decode(v5(rows = "${atCap + 1}"))).isNull()
        assertThat(ImportedModelMeta.decode(v5(rows = "0"))).isNull()
        val endToEnd = v5(layout = "outputLayout=detect_end_to_end", classes = "classes=0")
        assertThat(ImportedModelMeta.decode(endToEnd.replace("outputRows=2100", "outputRows=300"))).isNotNull()
        assertThat(ImportedModelMeta.decode(endToEnd.replace("outputRows=2100", "outputRows=301"))).isNull()
    }

    @Test
    fun classes_mustBeInRangeForRawAndZeroForEndToEnd() {
        assertThat(ImportedModelMeta.decode(v5(classes = "classes=1"))).isNotNull()
        assertThat(ImportedModelMeta.decode(v5(classes = "classes=80"))).isNotNull()
        assertThat(ImportedModelMeta.decode(v5(classes = "classes=0"))).isNull()
        assertThat(ImportedModelMeta.decode(v5(classes = "classes=81"))).isNull()
        assertThat(ImportedModelMeta.decode(v5(layout = "outputLayout=detect_end_to_end", classes = "classes=2"))).isNull()
    }

    @Test
    fun strictness_unknownOrMalformedValuesAreRefused() {
        val bad =
            listOf(
                v5(layout = "outputLayout=raw_channels_first"),
                v5(layout = "outputLayout=end_to_end"),
                v5(layout = "outputLayout=detect_raw"),
                v5(layout = "outputLayout="),
                v5(input = "inputLayout=chw"),
                v5(input = "inputLayout="),
                v5(classes = "classes=02"),
                v5(classes = "classes=-1"),
                v5(classes = "classes=x"),
                v5(classes = "classes="),
                v5(classes = "count=2"),
                v5(width = "330"),
                v5(width = "01"),
                v5() + "\n",
                v5() + "\nextra=1",
                v5().replace("\n", "\r\n"),
                v5().lines().filterIndexed { i, _ -> i != 9 }.joinToString("\n"),
                v5().replace("v5", "v6"),
            )

        for (text in bad) assertThat(ImportedModelMeta.decode(text)).isNull()
    }

    @Test
    fun aV4OrOlderText_cannotCarryADetectLayout() {
        val v4 =
            listOf(
                "posetracker-imported-model v4",
                "name=m.tflite",
                "sha256=$sha",
                "bytes=1000",
                "inputWidth=320",
                "inputHeight=320",
                "outputRows=2100",
                "outputLayout=detect_raw_channels_first",
                "inputLayout=nhwc",
            ).joinToString("\n")
        val v3 =
            v4
                .replace("v4", "v3")
                .lines()
                .dropLast(1)
                .joinToString("\n")

        assertThat(ImportedModelMeta.decode(v4)).isNull()
        assertThat(ImportedModelMeta.decode(v3)).isNull()
    }

    @Test
    fun aV5TextOutsideTheAspectLimit_isRefused() {
        val text = v5().replace("inputHeight=320", "inputHeight=32")

        assertThat(ImportedModelMeta.decode(text)).isNull()
    }
}
