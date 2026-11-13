package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticsReportTest {
    private val appInfo = linkedMapOf("versionName" to "0.1.7", "gitSha" to "abc1234")
    private val deviceInfo = linkedMapOf("model" to "Poco X7 Pro", "sdk" to "35")
    private val stats =
        CaptureStats(
            accelerator = "GPU",
            captureFps = 31.5f,
            inferenceMs = 12.25f,
            captureWidthImg = 610,
            captureHeightImg = 1356,
            timestampSource = "IMAGE",
            warning = null,
            dotsVisible = true,
            frames = 120L,
            droppedSizeMismatch = 1L,
            copyFailures = 2L,
            arrivalFallbacks = 3L,
            failures = 4L,
        )

    private fun build(
        stats: CaptureStats? = this.stats,
        logLines: List<String> = listOf("first", "second"),
        modelInfo: String? = "{\"sizes\":{}}",
        lastCrash: String? = "boom",
    ): List<ReportEntry> = DiagnosticsReport.entries(appInfo, deviceInfo, "TrackerConfig(x=1)", stats, logLines, modelInfo, lastCrash)

    private fun List<ReportEntry>.text(name: String): String = first { it.name == name }.text

    @Test
    fun everythingPresent_hasTheFiveEntriesInOrder() {
        assertThat(build().map { it.name })
            .containsExactly("summary.txt", "config.txt", "log.txt", "model_info.json", "last_crash.txt")
            .inOrder()
    }

    @Test
    fun everythingMissing_hasTheSameFiveEntriesExceptTheModelName() {
        val entries = build(stats = null, logLines = emptyList(), modelInfo = null, lastCrash = null)

        assertThat(entries.map { it.name })
            .containsExactly("summary.txt", "config.txt", "log.txt", "model_info.txt", "last_crash.txt")
            .inOrder()
    }

    @Test
    fun everyCombinationOfMissingParts_keepsFiveEntriesAndTheOrder() {
        for (mask in 0 until 16) {
            val entries =
                build(
                    stats = if (mask and 1 != 0) null else stats,
                    logLines = if (mask and 2 != 0) emptyList() else listOf("line"),
                    modelInfo = if (mask and 4 != 0) null else "{}",
                    lastCrash = if (mask and 8 != 0) null else "trace",
                )

            assertThat(entries).hasSize(5)
            assertThat(entries.map { it.name }.filterIndexed { index, _ -> index != 3 })
                .containsExactly("summary.txt", "config.txt", "log.txt", "last_crash.txt")
                .inOrder()
            assertThat(entries[3].name).isEqualTo(if (mask and 4 != 0) "model_info.txt" else "model_info.json")
        }
    }

    @Test
    fun summary_listsAppInfoThenDeviceInfoThenCaptureStats() {
        val summary = build().text("summary.txt")

        val appAt = summary.indexOf("versionName=0.1.7")
        val deviceAt = summary.indexOf("model=Poco X7 Pro")
        val captureAt = summary.indexOf("accelerator=GPU")
        assertThat(appAt).isAtLeast(0)
        assertThat(deviceAt).isGreaterThan(appAt)
        assertThat(captureAt).isGreaterThan(deviceAt)
    }

    @Test
    fun summary_keepsMapIterationOrder() {
        val summary = build().text("summary.txt")

        assertThat(summary.indexOf("gitSha=abc1234")).isGreaterThan(summary.indexOf("versionName=0.1.7"))
        assertThat(summary.indexOf("sdk=35")).isGreaterThan(summary.indexOf("model=Poco X7 Pro"))
    }

    @Test
    fun summary_hasOneKeyValueLinePerCaptureStat() {
        val lines = build().text("summary.txt").split("\n")
        val expected =
            listOf(
                "accelerator=GPU",
                "captureFps=31.5",
                "inferenceMs=12.25",
                "captureWidthImg=610",
                "captureHeightImg=1356",
                "timestampSource=IMAGE",
                "warning=none",
                "dotsVisible=true",
                "frames=120",
                "droppedSizeMismatch=1",
                "copyFailures=2",
                "arrivalFallbacks=3",
                "failures=4",
            )

        assertThat(lines).containsAtLeastElementsIn(expected)
    }

    @Test
    fun summary_showsUnknownForStatsThatAreNotKnownYet() {
        val unknown = stats.copy(accelerator = null, timestampSource = null, warning = "MODEL_NOT_LOADED")

        val lines = build(stats = unknown).text("summary.txt").split("\n")

        assertThat(lines).containsAtLeast("accelerator=unknown", "timestampSource=unknown", "warning=MODEL_NOT_LOADED")
    }

    @Test
    fun summary_withoutStats_saysThereIsNoSessionYet() {
        val summary = build(stats = null).text("summary.txt")

        assertThat(summary.split("\n")).contains("capture: no session yet")
        assertThat(summary).doesNotContain("accelerator=")
    }

    @Test
    fun summary_withoutStats_stillHasAppAndDeviceInfo() {
        val summary = build(stats = null).text("summary.txt")

        assertThat(summary).contains("versionName=0.1.7")
        assertThat(summary).contains("model=Poco X7 Pro")
    }

    @Test
    fun config_isTheGivenText() {
        assertThat(build().text("config.txt")).contains("TrackerConfig(x=1)")
    }

    @Test
    fun log_hasOneLinePerLogLineInOrder() {
        val lines = build(logLines = listOf("one", "two", "three")).text("log.txt").trimEnd('\n').split("\n")

        assertThat(lines).containsExactly("one", "two", "three").inOrder()
    }

    @Test
    fun log_whenEmpty_saysSo() {
        assertThat(build(logLines = emptyList()).text("log.txt")).contains("(log is empty)")
    }

    @Test
    fun modelInfo_isPassedThroughUnchanged() {
        assertThat(build(modelInfo = "{\"a\": 1}").text("model_info.json")).isEqualTo("{\"a\": 1}")
    }

    @Test
    fun modelInfo_whenUnreadable_isAnEntryThatSaysSo() {
        val entry = build(modelInfo = null).first { it.name == "model_info.txt" }

        assertThat(entry.text).contains("could not be read")
    }

    @Test
    fun lastCrash_isPassedThroughOrSaysNoneRecorded() {
        assertThat(build(lastCrash = "java.lang.Boom").text("last_crash.txt")).contains("java.lang.Boom")
        assertThat(build(lastCrash = null).text("last_crash.txt")).contains("no crash recorded")
    }

    @Test
    fun texts_useUnixLineEndsOnly() {
        for (entry in build()) assertThat(entry.text).doesNotContain("\r")
    }

    @Test
    fun sameInputs_giveTheSameOutput() {
        val first = build().map { it.name to it.text }
        val second = build().map { it.name to it.text }

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun summary_listsTheDetectionDiagnostics() {
        val withDetections =
            stats.copy(
                displayWidthPx = 2712,
                displayHeightPx = 1220,
                detections = 2,
                framesWithDetections = 7L,
                maxRawScore = 0.5f,
                peakMaxRawScore = 0.75f,
                rawRowsAboveThreshold = 3,
                nanRawRows = 0,
                headXImg = 100.5f,
                headYImg = 40.25f,
            )

        val lines = build(stats = withDetections).text("summary.txt").split("\n")
        val expected =
            listOf(
                "displayWidthPx=2712",
                "displayHeightPx=1220",
                "detections=2",
                "framesWithDetections=7",
                "maxRawScore=0.5",
                "peakMaxRawScore=0.75",
                "rawRowsAboveThreshold=3",
                "nanRawRows=0",
                "headXImg=100.5",
                "headYImg=40.25",
            )

        assertThat(lines).containsAtLeastElementsIn(expected)
    }
}
