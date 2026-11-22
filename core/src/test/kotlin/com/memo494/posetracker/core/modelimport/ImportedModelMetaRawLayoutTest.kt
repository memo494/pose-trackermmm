package com.memo494.posetracker.core.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class ImportedModelMetaRawLayoutTest {
    private val sha = "0123456789abcdef".repeat(4)

    private val endToEnd = ImportedModelMeta("m.tflite", sha, 1_000L, 640, 640, 300)
    private val rawFirst = endToEnd.copy(outputRows = 8400, outputLayout = PoseOutputLayout.RAW_CHANNELS_FIRST)
    private val rawLast = endToEnd.copy(outputRows = 8400, outputLayout = PoseOutputLayout.RAW_CHANNELS_LAST)

    private fun v3(
        rows: String = "8400",
        layout: String = "outputLayout=raw_channels_first",
    ) = listOf(
        "posetracker-imported-model v3",
        "name=m.tflite",
        "sha256=$sha",
        "bytes=1000",
        "inputWidth=640",
        "inputHeight=640",
        "outputRows=$rows",
        layout,
    ).joinToString("\n")

    @Test
    fun aMetaWithoutALayout_isEndToEnd() {
        assertThat(endToEnd.outputLayout).isEqualTo(PoseOutputLayout.END_TO_END)
    }

    @Test
    fun endToEndMeta_stillEncodesAsVersion2_andOldTextStillLoads() {
        assertThat(endToEnd.encode().lines().first()).isEqualTo("posetracker-imported-model v2")
        assertThat(endToEnd.encode().lines()).hasSize(7)
        assertThat(ImportedModelMeta.decode(endToEnd.encode())).isEqualTo(endToEnd)
    }

    @Test
    fun rawMeta_encodesAsVersion3_withTheLayoutLine() {
        assertThat(rawFirst.encode()).isEqualTo(v3())
        assertThat(rawLast.encode()).isEqualTo(v3(layout = "outputLayout=raw_channels_last"))
    }

    @Test
    fun rawMeta_roundTrips() {
        assertThat(ImportedModelMeta.decode(rawFirst.encode())).isEqualTo(rawFirst)
        assertThat(ImportedModelMeta.decode(rawLast.encode())).isEqualTo(rawLast)
    }

    @Test
    fun rawRows_goUpToTheRawCap_butEndToEndRowsStayAt300() {
        val atCap = rawFirst.copy(outputRows = TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS)
        assertThat(ImportedModelMeta.decode(atCap.encode())).isEqualTo(atCap)
        assertThat(ImportedModelMeta.decode(v3(rows = "${TrackerConfig.IMPORT_MAX_RAW_OUTPUT_ROWS + 1}"))).isNull()
        assertThat(ImportedModelMeta.decode(v3(rows = "0"))).isNull()
    }

    @Test
    fun version3_refusesAnythingTheImporterCouldNotHaveWritten() {
        val bad =
            listOf(
                v3(layout = "outputLayout=end_to_end"),
                v3(layout = "outputLayout=RAW_CHANNELS_FIRST"),
                v3(layout = "outputLayout="),
                v3(layout = "layout=raw_channels_first"),
                v3(layout = "outputLayout=raw_channels_first\n"),
                v3().substringBeforeLast("\n"),
                v3() + "\nextra=1",
                v3().replace("\n", "\r\n"),
            )

        for (text in bad) assertThat(ImportedModelMeta.decode(text)).isNull()
    }

    @Test
    fun version2_withRowsAbove300_isStillRefused() {
        val text =
            listOf(
                "posetracker-imported-model v2",
                "name=m.tflite",
                "sha256=$sha",
                "bytes=1000",
                "inputWidth=640",
                "inputHeight=640",
                "outputRows=8400",
            ).joinToString("\n")

        assertThat(ImportedModelMeta.decode(text)).isNull()
    }
}
