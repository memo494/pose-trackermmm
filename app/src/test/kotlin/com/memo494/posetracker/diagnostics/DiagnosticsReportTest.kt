package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.stats.LeadSummary
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus
import org.junit.Test
import java.util.Locale

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

    // ---- tracker counters (M3b T4) ----

    private val trackKeys =
        listOf(
            "tracksShown",
            "tracksMostAtOnce",
            "trackBirths",
            "trackConfirmations",
            "trackIdlePromotions",
            "trackRecoveries",
            "trackRemovals",
        )

    @Test
    fun summary_trackLines_showTheNumbersInTheDocumentedOrder() {
        val tracked =
            stats.copy(
                tracksShown = 2,
                tracksMostAtOnce = 4,
                trackBirths = 9,
                trackConfirmations = 6,
                trackIdlePromotions = 1,
                trackRecoveries = 3,
                trackRemovals = 5,
            )

        val lines = build(stats = tracked).text("summary.txt").trimEnd('\n').split("\n")
        val first = lines.indexOf("tracksShown=2")

        assertThat(lines.subList(first, first + trackKeys.size))
            .containsExactly(
                "tracksShown=2",
                "tracksMostAtOnce=4",
                "trackBirths=9",
                "trackConfirmations=6",
                "trackIdlePromotions=1",
                "trackRecoveries=3",
                "trackRemovals=5",
            ).inOrder()
    }

    @Test
    fun summary_trackLines_areZeroBeforeTheFirstFrame() {
        val lines = summaryLines(stats)

        val values = trackKeys.map { key -> lines.first { it.startsWith("$key=") } }

        assertThat(values)
            .containsExactly(
                "tracksShown=0",
                "tracksMostAtOnce=0",
                "trackBirths=0",
                "trackConfirmations=0",
                "trackIdlePromotions=0",
                "trackRecoveries=0",
                "trackRemovals=0",
            ).inOrder()
    }

    @Test
    fun summary_trackLines_followTheDetectionCountersAndComeBeforeTheModelBlock() {
        val lines = summaryLines(stats)

        assertThat(lines.indexOf("tracksShown=0")).isEqualTo(lines.indexOf("headYImg=NaN") + 1)
        assertThat(lines.indexOf("trackRemovals=0")).isLessThan(lines.indexOf("modelSource=unknown"))
    }

    // ---- lock counters (M4c T2) ----

    private val lockKeys = listOf("locks", "lockRelocks", "lockRelockRejects", "lockExpired")

    @Test
    fun summary_lockLines_showTheNumbersAfterTheTrackLinesInTheDocumentedOrder() {
        val locked = stats.copy(locks = 3, lockRelocks = 2, lockRelockRejects = 5, lockExpired = 1)

        val lines = build(stats = locked).text("summary.txt").trimEnd('\n').split("\n")
        val first = lines.indexOf("locks=3")

        assertThat(first).isEqualTo(lines.indexOf("trackRemovals=0") + 1)
        assertThat(lines.subList(first, first + lockKeys.size))
            .containsExactly("locks=3", "lockRelocks=2", "lockRelockRejects=5", "lockExpired=1")
            .inOrder()
    }

    @Test
    fun summary_lockLines_areZeroBeforeTheFirstLock() {
        val lines = summaryLines(stats)

        val values = lockKeys.map { key -> lines.first { it.startsWith("$key=") } }

        assertThat(values).containsExactly("locks=0", "lockRelocks=0", "lockRelockRejects=0", "lockExpired=0").inOrder()
        assertThat(lines.indexOf("lockExpired=0")).isLessThan(lines.indexOf("modelSource=unknown"))
    }

    // ---- association misses (M4e T1) ----

    private val assocKeys =
        listOf(
            "assocMisses",
            "assocMissGate",
            "assocMissCost",
            "assocMissTaken",
            "assocMissLowBirth",
            "assocBirthNearLive",
            "assocBirthAfterRemoval",
            "assocBirthSuppressed",
        )

    @Test
    fun summary_assocMissLines_showTheNumbersAfterTheLockLinesInTheDocumentedOrder() {
        val missed =
            stats.copy(
                assocMisses = 6,
                assocMissGate = 3,
                assocMissCost = 2,
                assocMissTaken = 1,
                assocMissLowBirth = 4,
                assocBirthNearLive = 5,
                assocBirthAfterRemoval = 7,
                assocBirthSuppressed = 8,
            )

        val lines = build(stats = missed).text("summary.txt").trimEnd('\n').split("\n")
        val first = lines.indexOf("assocMisses=6")

        assertThat(first).isEqualTo(lines.indexOf("lockExpired=0") + 1)
        assertThat(lines.subList(first, first + assocKeys.size))
            .containsExactly(
                "assocMisses=6",
                "assocMissGate=3",
                "assocMissCost=2",
                "assocMissTaken=1",
                "assocMissLowBirth=4",
                "assocBirthNearLive=5",
                "assocBirthAfterRemoval=7",
                "assocBirthSuppressed=8",
            ).inOrder()
        assertThat(first + assocKeys.size).isEqualTo(lines.indexOf("modelSource=unknown"))
    }

    @Test
    fun summary_assocMissLines_areZeroBeforeTheFirstMiss_andAbsentWithoutStats() {
        val withStats = summaryLines(stats)
        val without = summaryLines(null)

        assertThat(assocKeys.map { key -> withStats.first { it.startsWith("$key=") } })
            .containsExactly(
                "assocMisses=0",
                "assocMissGate=0",
                "assocMissCost=0",
                "assocMissTaken=0",
                "assocMissLowBirth=0",
                "assocBirthNearLive=0",
                "assocBirthAfterRemoval=0",
                "assocBirthSuppressed=0",
            ).inOrder()
        assertThat(without.none { line -> assocKeys.any { line.startsWith("$it=") } }).isTrue()
    }

    @Test
    fun summary_withoutStats_hasNoLockLines() {
        val lines = summaryLines(null)

        assertThat(lines.none { line -> lockKeys.any { line.startsWith("$it=") } }).isTrue()
    }

    @Test
    fun summary_withoutStats_hasNoTrackLines() {
        val lines = summaryLines(null)

        assertThat(lines.none { it.startsWith("track") }).isTrue()
    }

    // ---- timing and thermal (M1a T5) ----

    private val timedStats =
        stats.copy(
            timing =
                TimingSummary(
                    samples = 480,
                    copyMs = Percentiles(1.04f, 2.26f),
                    preMs = Percentiles(1.5f, 2.0f),
                    inferMs = Percentiles(7.5f, 12.34f),
                    postMs = Percentiles(0.5f, 0.74f),
                    frameMs = Percentiles(13.04f, 20.96f),
                    resultAgeMs = Percentiles(17.0f, 31.52f),
                ),
            thermalStatus = "MODERATE",
            thermalHeadroom = 0.87f,
        )

    private val timingKeys =
        listOf(
            "timingSamples",
            "copyMsP50",
            "copyMsP95",
            "preMsP50",
            "preMsP95",
            "inferMsP50",
            "inferMsP95",
            "postMsP50",
            "postMsP95",
            "frameMsP50",
            "frameMsP95",
            "resultAgeMsP50",
            "resultAgeMsP95",
            "thermalStatus",
            "thermalHeadroom",
        )

    private fun summaryLines(stats: CaptureStats?): List<String> = build(stats = stats).text("summary.txt").trimEnd('\n').split("\n")

    @Test
    fun summary_timingLines_comeLastInTheDocumentedOrderWithOneDecimal() {
        val lines = summaryLines(timedStats)

        assertThat(lines.takeLast(timingKeys.size))
            .containsExactly(
                "timingSamples=480",
                "copyMsP50=1.0",
                "copyMsP95=2.3",
                "preMsP50=1.5",
                "preMsP95=2.0",
                "inferMsP50=7.5",
                "inferMsP95=12.3",
                "postMsP50=0.5",
                "postMsP95=0.7",
                "frameMsP50=13.0",
                "frameMsP95=21.0",
                "resultAgeMsP50=17.0",
                "resultAgeMsP95=31.5",
                "thermalStatus=MODERATE",
                "thermalHeadroom=0.87",
            ).inOrder()
    }

    @Test
    fun summary_leadLines_comeBeforeTheTimingLinesWithTheirNumbers() {
        val withLead =
            timedStats.copy(
                timing =
                    timedStats.timing.copy(
                        lead = LeadSummary(leadMsP50 = 93.4f, appliedFraction = 0.2543f, speedHpsP50 = 0.07f, speedHpsP99 = 2.346f),
                    ),
            )

        val lines = summaryLines(withLead)

        assertThat(lines.slice(lines.indexOf("leadMsP50=93.4")..lines.indexOf("timingSamples=480")))
            .containsExactly("leadMsP50=93.4", "leadAppliedFraction=0.25", "speedHpsP50=0.07", "speedHpsP99=2.35", "timingSamples=480")
            .inOrder()
    }

    @Test
    fun summary_leadLines_withoutASample_sayNotAvailable() {
        val lines = summaryLines(stats)

        assertThat(lines).containsAtLeast("leadMsP50=n/a", "leadAppliedFraction=n/a", "speedHpsP50=n/a", "speedHpsP99=n/a")
    }

    @Test
    fun summary_thermalStepDownLines_sitBetweenTheModelBlockAndTheTiming() {
        val lines =
            summaryLines(timedStats.copy(thermalState = "HOT", thermalSmallModel = "failed", thermalStepDowns = 2, thermalHotMs = 123_456L))

        assertThat(lines).containsAtLeast("thermalState=HOT", "thermalSmallModel=failed", "thermalStepDowns=2", "thermalHotMs=123456")
        assertThat(lines.indexOf("thermalState=HOT")).isLessThan(lines.indexOf("thermalStepDowns=2"))
        assertThat(lines.indexOf("thermalHotMs=123456")).isLessThan(lines.indexOf("timingSamples=480"))
        assertThat(lines.indexOf("thermalState=HOT")).isGreaterThan(lines.indexOf("modelSource=builtin"))
    }

    @Test
    fun summary_withoutAStepDown_saysNormalAndZero() {
        val lines = summaryLines(timedStats)

        assertThat(lines).containsAtLeast("thermalState=NORMAL", "thermalSmallModel=no", "thermalStepDowns=0", "thermalHotMs=0")
    }

    @Test
    fun summary_timingLines_followTheExistingCaptureLinesAndTheModelBlock() {
        val lines = summaryLines(timedStats)

        assertThat(lines.indexOf("timingSamples=480")).isGreaterThan(lines.indexOf("modelSource=builtin"))
        assertThat(lines.indexOf("timingSamples=480")).isGreaterThan(lines.indexOf("failures=4"))
    }

    @Test
    fun summary_withNoTimingSampleAndNoThermalReading_saysNotAvailable() {
        val lines = summaryLines(stats)

        assertThat(lines.takeLast(timingKeys.size))
            .containsExactly(
                "timingSamples=0",
                "copyMsP50=n/a",
                "copyMsP95=n/a",
                "preMsP50=n/a",
                "preMsP95=n/a",
                "inferMsP50=n/a",
                "inferMsP95=n/a",
                "postMsP50=n/a",
                "postMsP95=n/a",
                "frameMsP50=n/a",
                "frameMsP95=n/a",
                "resultAgeMsP50=n/a",
                "resultAgeMsP95=n/a",
                "thermalStatus=n/a",
                "thermalHeadroom=n/a",
            ).inOrder()
    }

    @Test
    fun summary_aTimingThatIsUnknown_isNotAvailableWhileTheOthersKeepTheirNumbers() {
        val partly =
            timedStats.copy(
                timing =
                    timedStats.timing.copy(
                        preMs = Percentiles.NONE,
                        postMs = Percentiles.NONE,
                        resultAgeMs = Percentiles(Float.NaN, 3.0f),
                    ),
            )

        val lines = summaryLines(partly)

        assertThat(lines).containsAtLeast("preMsP50=n/a", "preMsP95=n/a", "postMsP50=n/a", "postMsP95=n/a")
        assertThat(lines).containsAtLeast("resultAgeMsP50=n/a", "resultAgeMsP95=3.0")
        assertThat(lines).containsAtLeast("copyMsP50=1.0", "inferMsP95=12.3", "timingSamples=480")
    }

    @Test
    fun summary_thermalHeadroom_printsTwoDecimalsSoNearOneIsNotShownAsOne() {
        assertThat(summaryLines(timedStats.copy(thermalHeadroom = 0.95f))).contains("thermalHeadroom=0.95")
        assertThat(summaryLines(timedStats.copy(thermalHeadroom = 1.0f))).contains("thermalHeadroom=1.00")
        assertThat(summaryLines(timedStats.copy(thermalHeadroom = 0.0f))).contains("thermalHeadroom=0.00")
    }

    @Test
    fun summary_thermalStatusKnownButHeadroomUnknown_printsOnlyWhatIsKnown() {
        val lines = summaryLines(timedStats.copy(thermalStatus = "SEVERE", thermalHeadroom = Float.NaN))

        assertThat(lines).containsAtLeast("thermalStatus=SEVERE", "thermalHeadroom=n/a")
    }

    @Test
    fun summary_infiniteValues_areNotAvailableNotNumbers() {
        val lines =
            summaryLines(
                timedStats.copy(
                    timing = timedStats.timing.copy(copyMs = Percentiles(Float.POSITIVE_INFINITY, 1.0f)),
                    thermalHeadroom = Float.POSITIVE_INFINITY,
                ),
            )

        assertThat(lines).containsAtLeast("copyMsP50=n/a", "copyMsP95=1.0", "thermalHeadroom=n/a")
    }

    @Test
    fun summary_numbersUseADotWhateverTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)

            val lines = summaryLines(timedStats)

            assertThat(lines).containsAtLeast("inferMsP95=12.3", "thermalHeadroom=0.87")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun summary_withoutStats_hasNoTimingLines() {
        val summary = build(stats = null).text("summary.txt")

        assertThat(summary).doesNotContain("timingSamples")
        assertThat(summary).doesNotContain("thermalStatus")
    }

    // ---- the model block ----

    private val importedMeta = ImportedModelMeta("my-model.tflite", "0123456789abcdef".repeat(4), 12_345_678L, 640, 640, 300)

    private fun builtInStatus(
        width: Int,
        height: Int,
    ) = ModelStatus(ModelSource.BUILT_IN, meta = null, fallback = null, message = null, inputWidth = width, inputHeight = height)

    private fun importedStatus(
        meta: ImportedModelMeta,
        width: Int,
        height: Int,
    ) = ModelStatus(ModelSource.IMPORTED, meta, fallback = null, message = null, inputWidth = width, inputHeight = height)

    private fun modelLines(model: ModelStatus?): List<String> = build(stats = stats.copy(model = model)).text("summary.txt").split("\n")

    @Test
    fun summary_builtInModel_isDescribedWithoutAFile() {
        val lines = modelLines(builtInStatus(416, 416))

        assertThat(lines)
            .containsAtLeast(
                "modelSource=builtin",
                "modelFile=builtin",
                "modelSha256=none",
                "modelInputSize=416",
                "modelOutputRows=none",
                "modelFallback=none",
                "modelFallbackMessage=none",
            ).inOrder()
        assertThat(lines.filter { it.startsWith("modelFallbackFile=") }).isEmpty()
    }

    @Test
    fun summary_bundledBuiltInModel_namesItsFileAndShortHash() {
        val sha = "35b62098e36ae7aaec59cc4f36ef88bb4ab00482a393abd6251c7f5f3781dd45"
        val status = builtInStatus(448, 960).copy(builtInFile = "yolo26n-pose_448x960.tflite", builtInSha256 = sha)

        val lines = modelLines(status)

        assertThat(lines)
            .containsAtLeast(
                "modelSource=builtin",
                "modelFile=yolo26n-pose_448x960.tflite",
                "modelSha256=35b62098e36a",
                "modelInputSize=448x960",
                "modelOutputRows=none",
            ).inOrder()
    }

    @Test
    fun summary_importedModel_showsNameShortHashSizeAndRows() {
        val lines = modelLines(importedStatus(importedMeta, 640, 640))

        assertThat(lines)
            .containsAtLeast(
                "modelSource=imported",
                "modelFile=my-model.tflite",
                "modelSha256=0123456789ab",
                "modelInputSize=640",
                "modelOutputRows=300",
                "modelFallback=none",
                "modelFallbackMessage=none",
            ).inOrder()
    }

    @Test
    fun summary_rectangularImportedModel_printsWidthXHeightUnderTheSameKey() {
        val meta = importedMeta.copy(inputWidth = 832, inputHeight = 384)
        val lines = modelLines(importedStatus(meta, 832, 384))

        assertThat(lines).contains("modelInputSize=832x384")
        assertThat(lines.filter { it.startsWith("modelInputSize") }).hasSize(1)
    }

    @Test
    fun summary_modelWithoutAnInput_printsZero() {
        val lines = modelLines(ModelStatus(ModelSource.BUILT_IN, null, ModelFallback.LOAD_FAILED, "x", inputWidth = 0, inputHeight = 0))

        assertThat(lines).contains("modelInputSize=0")
    }

    @Test
    fun summary_fallbackAfterAFailedLoad_namesTheReasonTheMessageAndTheRefusedFile() {
        val status =
            ModelStatus(
                ModelSource.BUILT_IN,
                importedMeta,
                ModelFallback.LOAD_FAILED,
                "IllegalStateException: input is not NCHW",
                inputWidth = 416,
                inputHeight = 416,
            )

        val lines = modelLines(status)

        assertThat(lines)
            .containsAtLeast(
                "modelSource=builtin",
                "modelFile=builtin",
                "modelInputSize=416",
                "modelFallback=LOAD_FAILED",
                "modelFallbackMessage=IllegalStateException: input is not NCHW",
                "modelFallbackFile=my-model.tflite 0123456789ab",
            )
    }

    @Test
    fun summary_fallbackMessageStaysOnOneLine() {
        val status = ModelStatus(ModelSource.BUILT_IN, null, ModelFallback.LOAD_FAILED, "a\nb", inputWidth = 416, inputHeight = 416)

        assertThat(modelLines(status)).contains("modelFallbackMessage=a b")
    }

    @Test
    fun summary_everyFallbackReasonIsPrintedByName() {
        for (reason in ModelFallback.entries) {
            val status = ModelStatus(ModelSource.BUILT_IN, null, reason, null, inputWidth = 416, inputHeight = 416)

            assertThat(modelLines(status)).contains("modelFallback=${reason.name}")
        }
    }

    @Test
    fun summary_withoutAModelStatus_saysUnknown() {
        assertThat(modelLines(null)).contains("modelSource=unknown")
    }

    @Test
    fun summary_withoutAModelStatus_hasNoOtherModelLines() {
        assertThat(modelLines(null).filter { it.startsWith("modelFile=") }).isEmpty()
    }

    @Test
    fun summary_modelSlotsAndSwitchCounters_arePrinted() {
        val status =
            builtInStatus(416, 416).copy(
                portrait = "a.tflite 0123456789ab",
                landscape = "builtin",
                switches = 3,
                switchMsMax = 240L,
            )

        val lines = modelLines(status)

        assertThat(lines)
            .containsAtLeast("modelPortrait=a.tflite 0123456789ab", "modelLandscape=builtin", "modelSwitches=3", "modelSwitchMsMax=240")
            .inOrder()
    }

    @Test
    fun summary_modelSlotsDefault_toBuiltInAndZeroSwitches() {
        val lines = modelLines(builtInStatus(416, 416))

        assertThat(lines).containsAtLeast("modelPortrait=builtin", "modelLandscape=builtin", "modelSwitches=0", "modelSwitchMsMax=0")
    }

    @Test
    fun summary_modelSlotsRefused_isNoneByDefault() {
        assertThat(modelLines(builtInStatus(416, 416))).contains("modelSlotsRefused=none")
    }

    @Test
    fun summary_modelSlotsRefused_namesEachRefusedSlotInOrder() {
        val portrait = builtInStatus(448, 960).copy(slotsRefused = setOf(ModelOrientation.PORTRAIT))
        val both = builtInStatus(448, 960).copy(slotsRefused = setOf(ModelOrientation.LANDSCAPE, ModelOrientation.PORTRAIT))

        assertThat(modelLines(portrait)).contains("modelSlotsRefused=portrait")
        assertThat(modelLines(both)).contains("modelSlotsRefused=portrait,landscape")
    }

    // ---- model kind (M-legacy T3) ----

    private val detectMeta =
        importedMeta.copy(
            outputRows = 2100,
            outputLayout = com.memo494.posetracker.core.modelimport.PoseOutputLayout.DETECT_RAW_CHANNELS_FIRST,
            classes = 2,
        )

    @Test
    fun summary_aBoxOnlyImportedModel_isModelKindBox() {
        assertThat(modelLines(importedStatus(detectMeta, 320, 320))).contains("modelKind=box")
    }

    @Test
    fun summary_poseModels_areModelKindPose() {
        assertThat(modelLines(importedStatus(importedMeta, 640, 640))).contains("modelKind=pose")
        assertThat(modelLines(builtInStatus(416, 416))).contains("modelKind=pose")
    }

    @Test
    fun summary_aBoxOnlyModelThatFellBackToTheBuiltIn_isModelKindPose() {
        val status = ModelStatus(ModelSource.BUILT_IN, detectMeta, ModelFallback.LOAD_FAILED, "x", inputWidth = 416, inputHeight = 416)

        assertThat(modelLines(status)).contains("modelKind=pose")
    }

    @Test
    fun summary_printsThePersonClassOfTheSession() {
        val lines =
            DiagnosticsReport
                .entries(
                    appInfo,
                    deviceInfo,
                    "c",
                    stats.copy(model = importedStatus(detectMeta, 320, 320)),
                    emptyList(),
                    null,
                    null,
                    detectPersonClass = 1,
                ).text("summary.txt")
                .split("\n")

        assertThat(lines).contains("detectPersonClass=1")
        assertThat(lines.indexOf("modelKind=box")).isLessThan(lines.indexOf("detectPersonClass=1"))
    }

    @Test
    fun summary_withoutAModelStatus_stillPrintsTheDefaultPersonClass() {
        assertThat(modelLines(null)).contains("detectPersonClass=0")
    }
}
