package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.tracking.TrackSnapshot
import com.memo494.posetracker.detector.Accelerator
import org.junit.Test

class HudFormatterTest {
    private val buffer = CharArray(64)

    private val idleFps = TrackerConfig().hudIdleFps

    private fun stats(state: OverlayState): String {
        val length = HudFormatter.formatStats(state, buffer, idleFps)
        return String(buffer, 0, length)
    }

    private fun detections(state: OverlayState): String {
        val length = HudFormatter.formatDetections(state, buffer)
        return String(buffer, 0, length)
    }

    private fun frameWith(people: Int): DetectionFrame {
        val person = PoseDetection(0f, 0f, 1f, 1f, 0.9f, FloatArray(0), FloatArray(0))
        return DetectionFrame(0L, TimestampSource.IMAGE, 610, 1356, List(people) { person })
    }

    private fun snapshotOf(
        count: Int,
        shiftXImg: Float = 0f,
    ): TrackSnapshot = TrackSnapshot.of(FakeTracks(count, 1, shiftXImg), 320, 240)

    private fun warning(
        state: OverlayState,
        armed: Boolean = false,
    ): String {
        val length = HudFormatter.formatWarning(state, buffer, armed)
        return String(buffer, 0, length)
    }

    private val sample =
        OverlayState(
            accelerator = Accelerator.GPU,
            captureFps = 58.4f,
            inferenceMs = 12.3f,
            captureWidthImg = 610,
            captureHeightImg = 1356,
            timestampSource = TimestampSource.IMAGE,
        )

    @Test
    fun stats_writesTheDocumentedLine() {
        assertThat(stats(sample)).isEqualTo("GPU 12.3 ms 58 fps 610x1356 ts=img")
    }

    @Test
    fun stats_returnsTheLengthAndLeavesTheRestOfTheBufferUntouched() {
        buffer.fill('#')

        val length = HudFormatter.formatStats(sample, buffer, idleFps)

        assertThat(length).isEqualTo("GPU 12.3 ms 58 fps 610x1356 ts=img".length)
        assertThat(buffer[length]).isEqualTo('#')
    }

    @Test
    fun stats_cpuAndArrivalFallback() {
        val state = sample.copy(accelerator = Accelerator.CPU, timestampSource = TimestampSource.ARRIVAL)

        assertThat(stats(state)).isEqualTo("CPU 12.3 ms 58 fps 610x1356 ts=arrival")
    }

    @Test
    fun stats_roundsFpsToWholeNumbersAndMsToTenths() {
        val state = sample.copy(captureFps = 29.6f, inferenceMs = 7.04f)

        assertThat(stats(state)).isEqualTo("GPU 7.0 ms 30 fps 610x1356 ts=img")
    }

    @Test
    fun stats_writesZeroAndSingleDigitValues() {
        val state = sample.copy(captureFps = 0f, inferenceMs = 0.5f, captureWidthImg = 2, captureHeightImg = 4)

        assertThat(stats(state)).isEqualTo("GPU 0.5 ms 0 fps 2x4 ts=img")
    }

    @Test
    fun stats_unknownValuesFailClosedToDashes() {
        assertThat(stats(OverlayState.EMPTY)).isEqualTo("-- 0.0 ms 0 fps 0x0 ts=--")
    }

    @Test
    fun stats_nonFiniteOrNegativeNumbersPrintAsDashes() {
        val nan = sample.copy(captureFps = Float.NaN, inferenceMs = Float.POSITIVE_INFINITY)
        val negative = sample.copy(captureFps = -1f, inferenceMs = -2f)

        assertThat(stats(nan)).isEqualTo("GPU -- ms -- fps 610x1356 ts=img")
        assertThat(stats(negative)).isEqualTo("GPU -- ms -- fps 610x1356 ts=img")
    }

    @Test
    fun stats_aBufferThatIsTooSmallIsCutNotOverrun() {
        val small = CharArray(8)

        val length = HudFormatter.formatStats(sample, small, idleFps)

        assertThat(length).isEqualTo(8)
        assertThat(String(small, 0, length)).isEqualTo("GPU 12.3")
    }

    @Test
    fun warning_isEmptyWhenAllIsWell() {
        assertThat(warning(sample)).isEmpty()
    }

    @Test
    fun warning_namesTheCaptureSizeProblem() {
        val state = sample.copy(warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)

        assertThat(warning(state)).isEqualTo("Capture not display-sized: dots hidden")
    }

    @Test
    fun warning_namesTheMissingModel() {
        val state = sample.copy(warning = OverlayWarning.DETECTOR_UNAVAILABLE)

        assertThat(warning(state)).isEqualTo("Model not loaded: no detection")
    }

    @Test
    fun warning_everyWarningFitsTheHudLine() {
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)
        for (value in OverlayWarning.entries) {
            val length = HudFormatter.formatWarning(sample.copy(warning = value), roomy)

            assertThat(length).isGreaterThan(0)
            assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
        }
    }

    @Test
    fun warning_theMissingModelWinsOverInvisibleContent() {
        val state = sample.copy(dotsVisible = false, warning = OverlayWarning.DETECTOR_UNAVAILABLE)

        assertThat(warning(state)).isEqualTo("Model not loaded: no detection")
    }

    @Test
    fun warning_whileArmed_saysTapAPerson() {
        assertThat(warning(sample, armed = true)).isEqualTo("tap a person")
    }

    @Test
    fun warning_notArmed_isEmptyAsBefore() {
        assertThat(warning(sample, armed = false)).isEmpty()
    }

    @Test
    fun warning_armedHintFitsTheHudLine() {
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)

        val length = HudFormatter.formatWarning(sample, roomy, armed = true)

        assertThat(length).isGreaterThan(0)
        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    }

    @Test
    fun warning_aRealWarningWinsOverTheArmedHint() {
        val missing = sample.copy(warning = OverlayWarning.DETECTOR_UNAVAILABLE)
        val hidden = sample.copy(dotsVisible = false)
        val note = sample.copy(warning = OverlayWarning.IMPORTED_MODEL_NOT_USED)

        assertThat(warning(missing, armed = true)).isEqualTo("Model not loaded: no detection")
        assertThat(warning(hidden, armed = true)).isEqualTo("Content not visible: dots hidden")
        assertThat(warning(note, armed = true)).isEqualTo("Imported model not used: built-in model")
    }

    @Test
    fun warning_saysWhenTheContentIsNotVisible() {
        val state = sample.copy(dotsVisible = false)

        assertThat(warning(state)).isEqualTo("Content not visible: dots hidden")
    }

    @Test
    fun warning_theCaptureProblemWinsOverInvisibleContent() {
        val state = sample.copy(dotsVisible = false, warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)

        assertThat(warning(state)).isEqualTo("Capture not display-sized: dots hidden")
    }

    @Test
    fun hudChanged_ignoresChangesThatDoNotShowInTheText() {
        val same = sample.copy(captureFps = 58.1f, inferenceMs = 12.26f)

        assertThat(HudFormatter.hudChanged(sample, same, idleFps)).isFalse()
    }

    @Test
    fun hudChanged_seesEveryVisibleField() {
        assertThat(HudFormatter.hudChanged(sample, sample.copy(captureFps = 59f), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(inferenceMs = 12.4f), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(accelerator = Accelerator.CPU), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(captureWidthImg = 1356), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(captureHeightImg = 610), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(timestampSource = TimestampSource.ARRIVAL), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(dotsVisible = false), idleFps)).isTrue()
        val warned = sample.copy(warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)
        assertThat(HudFormatter.hudChanged(sample, warned, idleFps)).isTrue()
        val unavailable = sample.copy(warning = OverlayWarning.DETECTOR_UNAVAILABLE)
        assertThat(HudFormatter.hudChanged(sample, unavailable, idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(warned, unavailable, idleFps)).isTrue()
    }

    @Test
    fun stats_fpsBelowTheIdleThresholdPrintsZero() {
        val state = sample.copy(captureFps = idleFps - 0.1f)

        assertThat(stats(state)).isEqualTo("GPU 12.3 ms 0 fps 610x1356 ts=img")
    }

    @Test
    fun stats_fpsAtTheIdleThresholdPrintsTheValue() {
        val state = sample.copy(captureFps = idleFps)

        assertThat(stats(state)).isEqualTo("GPU 12.3 ms 4 fps 610x1356 ts=img")
    }

    @Test
    fun hudChanged_twoIdleStatesWithDifferentInferenceTimeAreTheSame() {
        val idle = sample.copy(captureFps = 0f, inferenceMs = 9.0f)
        val idleAgain = idle.copy(inferenceMs = 31.7f)

        assertThat(HudFormatter.hudChanged(idle, idleAgain, idleFps)).isFalse()
    }

    @Test
    fun hudChanged_twoIdleStatesWithDifferentFpsAreTheSame() {
        val idle = sample.copy(captureFps = 1f, inferenceMs = 9.0f)
        val idleAgain = sample.copy(captureFps = 2f, inferenceMs = 31.7f)

        assertThat(HudFormatter.hudChanged(idle, idleAgain, idleFps)).isFalse()
    }

    @Test
    fun hudChanged_goingIdleIsAChange() {
        assertThat(HudFormatter.hudChanged(sample, sample.copy(captureFps = 0f), idleFps)).isTrue()
    }

    @Test
    fun hudChanged_leavingIdleIsAChange() {
        assertThat(HudFormatter.hudChanged(sample.copy(captureFps = 0f), sample, idleFps)).isTrue()
    }

    @Test
    fun hudChanged_inferenceTimeStillCountsWhileActive() {
        val other = sample.copy(captureFps = 30f, inferenceMs = 20.0f)

        assertThat(HudFormatter.hudChanged(sample.copy(captureFps = 30f), other, idleFps)).isTrue()
    }

    @Test
    fun hudChanged_otherFieldsStillCountWhileIdle() {
        val idle = sample.copy(captureFps = 0f)

        assertThat(HudFormatter.hudChanged(idle, idle.copy(accelerator = Accelerator.CPU), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(idle, idle.copy(dotsVisible = false), idleFps)).isTrue()
    }

    @Test
    fun cpuBanner_isTheDocumentedText() {
        assertThat(HudFormatter.CPU_BANNER).isEqualTo("Running on CPU")
    }

    @Test
    fun detections_writesPeopleRawRowsAndTheMaxRawScore() {
        val state = sample.copy(frame = frameWith(2), rawRowsAboveThreshold = 3, maxRawScore = 0.8921f)

        assertThat(detections(state)).isEqualTo("det 2/3 trk 0 max 0.89")
    }

    @Test
    fun detections_unknownValuesPrintAsDashes() {
        assertThat(detections(sample)).isEqualTo("det --/-- trk 0 max --")
    }

    @Test
    fun detections_lowScoresKeepTheirLeadingZeroDigit() {
        val state = sample.copy(frame = frameWith(0), rawRowsAboveThreshold = 0, maxRawScore = 0.034f)

        assertThat(detections(state)).isEqualTo("det 0/0 trk 0 max 0.03")
    }

    @Test
    fun hudChanged_whenThePeopleCountChanges() {
        val before = sample.copy(frame = frameWith(0))
        val after = sample.copy(frame = frameWith(1))

        assertThat(HudFormatter.hudChanged(before, after, idleFps)).isTrue()
    }

    @Test
    fun detections_builtInModelShowsTheInputSizeWithoutAMark() {
        val state =
            sample.copy(
                frame = frameWith(1),
                rawRowsAboveThreshold = 1,
                maxRawScore = 0.84f,
                modelInputWidth = 416,
                modelInputHeight = 416,
            )

        assertThat(detections(state)).isEqualTo("det 1/1 trk 0 max 0.84 in 416")
    }

    @Test
    fun detections_importedModelShowsTheInputSizeAndTheMark() {
        val state =
            sample.copy(
                frame = frameWith(1),
                rawRowsAboveThreshold = 1,
                maxRawScore = 0.84f,
                modelInputWidth = 640,
                modelInputHeight = 640,
                modelImported = true,
            )

        assertThat(detections(state)).isEqualTo("det 1/1 trk 0 max 0.84 in 640 imp")
    }

    @Test
    fun detections_rectangularImportedModelShowsWidthXHeightAndTheMark() {
        val state =
            sample.copy(
                frame = frameWith(2),
                rawRowsAboveThreshold = 3,
                maxRawScore = 0.89f,
                modelInputWidth = 832,
                modelInputHeight = 384,
                modelImported = true,
            )

        assertThat(detections(state)).isEqualTo("det 2/3 trk 0 max 0.89 in 832x384 imp")
    }

    @Test
    fun detections_aPortraitModelShowsWidthFirst() {
        val state =
            sample.copy(
                frame = frameWith(1),
                rawRowsAboveThreshold = 1,
                maxRawScore = 0.5f,
                modelInputWidth = 384,
                modelInputHeight = 832,
            )

        assertThat(detections(state)).isEqualTo("det 1/1 trk 0 max 0.50 in 384x832")
    }

    @Test
    fun detections_oneUnknownSideLeavesTheSizeOut() {
        val state = sample.copy(modelInputWidth = 832, modelInputHeight = 0, modelImported = true)

        assertThat(detections(state)).isEqualTo("det --/-- trk 0 max --")
    }

    @Test
    fun detections_longestRectangularTextFitsTheHudLine() {
        val longest =
            sample.copy(
                frame = frameWith(9999),
                tracks = snapshotOf(9999),
                rawRowsAboveThreshold = 9999,
                maxRawScore = 99.99f,
                modelInputWidth = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
                modelInputHeight = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT - TrackerConfig.IMPORT_INPUT_SIZE_STEP,
                modelImported = true,
            )
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)

        val length = HudFormatter.formatDetections(longest, roomy)

        assertThat(String(roomy, 0, length)).isEqualTo("det 9999/9999 trk 9999 max 99.99 in 4096x4064 imp")
        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    }

    @Test
    fun hudChanged_seesAChangeOfOnlyOneModelSide() {
        val square = sample.copy(modelInputWidth = 640, modelInputHeight = 640)

        assertThat(HudFormatter.hudChanged(square, square.copy(modelInputHeight = 384), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(square, square.copy(modelInputWidth = 832), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(square, square.copy(), idleFps)).isFalse()
    }

    @Test
    fun detections_withoutAModelTheSizeIsLeftOut() {
        val state = sample.copy(modelInputWidth = 0, modelInputHeight = 0, modelImported = false)

        assertThat(detections(state)).isEqualTo("det --/-- trk 0 max --")
    }

    @Test
    fun detections_worstCaseFitsTheHudLineAndStaysShorterThanTheStatsLine() {
        val worst =
            sample.copy(
                frame = frameWith(9999),
                tracks = snapshotOf(9999),
                rawRowsAboveThreshold = 9999,
                maxRawScore = 99.99f,
                modelInputWidth = 4096,
                modelInputHeight = 1024,
                modelImported = true,
            )
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)

        val length = HudFormatter.formatDetections(worst, roomy)

        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
        assertThat(String(roomy, 0, length)).isEqualTo("det 9999/9999 trk 9999 max 99.99 in 4096x1024 imp")
    }

    @Test
    fun detections_typicalImportedLineIsNotWiderThanTheStatsLine() {
        val typical =
            sample.copy(
                frame = frameWith(3),
                rawRowsAboveThreshold = 12,
                maxRawScore = 0.93f,
                modelInputWidth = 640,
                modelInputHeight = 640,
                modelImported = true,
            )

        assertThat(detections(typical).length).isAtMost(stats(sample).length)
    }

    @Test
    fun detections_aSmallBufferCutsTheTextInsteadOfThrowing() {
        val state = sample.copy(frame = frameWith(1), modelInputWidth = 640, modelInputHeight = 640, modelImported = true)
        val small = CharArray(20)

        val length = HudFormatter.formatDetections(state, small)

        assertThat(length).isEqualTo(20)
    }

    @Test
    fun warning_namesTheImportedModelThatIsNotUsed() {
        val state = sample.copy(warning = OverlayWarning.IMPORTED_MODEL_NOT_USED)

        assertThat(warning(state)).isEqualTo("Imported model not used: built-in model")
    }

    @Test
    fun warning_theImportedModelNoteComesAfterEveryOtherWarning() {
        val note = OverlayWarning.IMPORTED_MODEL_NOT_USED

        assertThat(warning(sample.copy(dotsVisible = false, warning = note))).isEqualTo("Content not visible: dots hidden")
        assertThat(warning(sample.copy(warning = note))).isEqualTo("Imported model not used: built-in model")
    }

    @Test
    fun warning_aModelOfTheWrongShape_hasItsOwnShortNote() {
        val state = sample.copy(warning = OverlayWarning.MODEL_WRONG_SHAPE)

        assertThat(warning(state)).isEqualTo("Model wrong shape: built-in runs")
        assertThat(warning(sample.copy(dotsVisible = false, warning = OverlayWarning.MODEL_WRONG_SHAPE)))
            .isEqualTo("Content not visible: dots hidden")
    }

    @Test
    fun warning_namesTheThermalStepDown() {
        val state = sample.copy(warning = OverlayWarning.THERMAL_STEPPED_DOWN)

        assertThat(warning(state)).isEqualTo("Hot: smaller model (320)")
    }

    @Test
    fun warning_theThermalNoteLosesToRealWarningsAndBeatsOnlyTheArmedHint() {
        val note = OverlayWarning.THERMAL_STEPPED_DOWN

        assertThat(warning(sample.copy(dotsVisible = false, warning = note))).isEqualTo("Content not visible: dots hidden")
        assertThat(warning(sample.copy(warning = note), armed = true)).isEqualTo("Hot: smaller model (320)")
        assertThat(warning(sample, armed = true)).isEqualTo("tap a person")
    }

    @Test
    fun warning_theThermalNoteFitsTheHudLine() {
        val buffer = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)

        val length = HudFormatter.formatWarning(sample.copy(warning = OverlayWarning.THERMAL_STEPPED_DOWN), buffer)

        assertThat(length).isEqualTo("Hot: smaller model (320)".length)
        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    }

    @Test
    fun hudChanged_seesTheThermalNote() {
        assertThat(HudFormatter.hudChanged(sample, sample.copy(warning = OverlayWarning.THERMAL_STEPPED_DOWN), idleFps)).isTrue()
    }

    @Test
    fun hudChanged_seesTheModelSizeAndTheImportedFlag() {
        assertThat(HudFormatter.hudChanged(sample, sample.copy(modelInputWidth = 640, modelInputHeight = 640), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(modelImported = true), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample, sample.copy(warning = OverlayWarning.IMPORTED_MODEL_NOT_USED), idleFps)).isTrue()
    }

    @Test
    fun hudChanged_seesTheModelFieldsWhileIdle() {
        val idle = sample.copy(captureFps = 0f)

        assertThat(HudFormatter.hudChanged(idle, idle.copy(modelInputWidth = 640, modelInputHeight = 640), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(idle, idle.copy(modelImported = true), idleFps)).isTrue()
    }

    @Test
    fun detections_theTrackCountIsWrittenApartFromThePeopleCount() {
        val state = sample.copy(frame = frameWith(2), tracks = snapshotOf(3), rawRowsAboveThreshold = 3, maxRawScore = 0.8921f)

        assertThat(detections(state)).isEqualTo("det 2/3 trk 3 max 0.89")
    }

    @Test
    fun detections_theTrackCountComesBeforeTheModelSize() {
        val state =
            sample.copy(
                frame = frameWith(2),
                tracks = snapshotOf(2),
                rawRowsAboveThreshold = 3,
                maxRawScore = 0.89f,
                modelInputWidth = 640,
                modelInputHeight = 640,
                modelImported = true,
            )

        assertThat(detections(state)).isEqualTo("det 2/3 trk 2 max 0.89 in 640 imp")
    }

    @Test
    fun detections_aStateWithoutAFrameKeepsDashesForThePeopleAndWritesTheTrackCount() {
        assertThat(detections(sample)).isEqualTo("det --/-- trk 0 max --")
        assertThat(detections(sample.copy(tracks = snapshotOf(2)))).isEqualTo("det --/-- trk 2 max --")
    }

    @Test
    fun detections_theLargestRealisticLineFitsTheHudLine() {
        val busy =
            sample.copy(
                frame = frameWith(TrackerConfig.MAX_DETECTIONS_PER_FRAME),
                tracks = snapshotOf(TrackerConfig.DEFAULT_MAX_TRACKS),
                rawRowsAboveThreshold = 300,
                maxRawScore = 0.99f,
                modelInputWidth = 1280,
                modelInputHeight = 1280,
                modelImported = true,
            )
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)

        val length = HudFormatter.formatDetections(busy, roomy)

        assertThat(String(roomy, 0, length)).isEqualTo("det 30/300 trk 32 max 0.99 in 1280 imp")
        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    }

    @Test
    fun detections_theWidestRectangularModelWithTheMostTracksFitsTheHudLine() {
        val busy =
            sample.copy(
                frame = frameWith(TrackerConfig.MAX_DETECTIONS_PER_FRAME),
                tracks = snapshotOf(TrackerConfig.MAX_MAX_TRACKS),
                rawRowsAboveThreshold = 300,
                maxRawScore = 0.99f,
                modelInputWidth = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT,
                modelInputHeight = TrackerConfig.MAX_IMPORT_INPUT_SIZE_LIMIT - TrackerConfig.IMPORT_INPUT_SIZE_STEP,
                modelImported = true,
            )
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)

        val length = HudFormatter.formatDetections(busy, roomy)

        assertThat(String(roomy, 0, length)).isEqualTo("det 30/300 trk 128 max 0.99 in 4096x4064 imp")
        assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    }

    @Test
    fun hudChanged_whenTheTrackCountChanges() {
        val before = sample.copy(frame = frameWith(1), tracks = snapshotOf(1))

        assertThat(HudFormatter.hudChanged(before, before.copy(tracks = snapshotOf(2)), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(before, before.copy(tracks = TrackSnapshot.EMPTY), idleFps)).isTrue()
    }

    @Test
    fun hudChanged_whenTheTrackCountChangesWhileIdle() {
        val idle = sample.copy(captureFps = 0f, tracks = snapshotOf(1))

        assertThat(HudFormatter.hudChanged(idle, idle.copy(tracks = snapshotOf(0)), idleFps)).isTrue()
    }

    @Test
    fun hudChanged_notWhenOnlyTheTrackPositionsChange() {
        val before = sample.copy(frame = frameWith(2), tracks = snapshotOf(2))
        val moved = before.copy(tracks = snapshotOf(2, shiftXImg = 40f))

        assertThat(HudFormatter.hudChanged(before, moved, idleFps)).isFalse()
    }

    @Test
    fun warning_namesTheSquareFallbackThatRunsBecauseTheBuiltInModelFailed() {
        val square = sample.copy(modelInputWidth = 416, modelInputHeight = 416, warning = OverlayWarning.BUILT_IN_MODEL_FAILED)
        val rectangular = sample.copy(modelInputWidth = 448, modelInputHeight = 960, warning = OverlayWarning.BUILT_IN_MODEL_FAILED)

        assertThat(warning(square)).isEqualTo("Built-in model failed: 416")
        assertThat(warning(rectangular)).isEqualTo("Built-in model failed: 448x960")
    }

    @Test
    fun warning_theBuiltInModelFailedNoteComesAfterTheOtherWarnings() {
        val note = OverlayWarning.BUILT_IN_MODEL_FAILED

        assertThat(warning(sample.copy(dotsVisible = false, warning = note))).isEqualTo("Content not visible: dots hidden")
        assertThat(warning(sample.copy(warning = note), armed = true)).startsWith("Built-in model failed")
    }

    // ---- box-only models (M-legacy T3) ----

    @Test
    fun warning_aBoxOnlyModel_showsTheNoteWithItsPersonClass() {
        assertThat(warning(sample.copy(boxOnlyClass = 0))).isEqualTo("Box-only model: head from box (class 0)")
        assertThat(warning(sample.copy(boxOnlyClass = 1))).isEqualTo("Box-only model: head from box (class 1)")
        assertThat(warning(sample.copy(boxOnlyClass = TrackerConfig.DETECT_MAX_CLASSES - 1)))
            .isEqualTo("Box-only model: head from box (class 79)")
    }

    @Test
    fun warning_aPoseModel_hasNoBoxOnlyNote() {
        assertThat(sample.boxOnlyClass).isEqualTo(OverlayState.NOT_BOX_ONLY)
        assertThat(warning(sample)).isEmpty()
    }

    @Test
    fun warning_theBoxOnlyNoteFitsTheHudLine_forEveryClass() {
        val roomy = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS * 2)
        for (personClass in 0 until TrackerConfig.DETECT_MAX_CLASSES) {
            val length = HudFormatter.formatWarning(sample.copy(boxOnlyClass = personClass), roomy)

            assertThat(length).isGreaterThan(0)
            assertThat(length).isAtMost(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
        }
    }

    @Test
    fun warning_everyRealWarningAndTheArmedHintWinOverTheBoxOnlyNote() {
        val boxOnly = sample.copy(boxOnlyClass = 0)

        assertThat(warning(boxOnly.copy(warning = OverlayWarning.DETECTOR_UNAVAILABLE))).isEqualTo("Model not loaded: no detection")
        assertThat(warning(boxOnly.copy(dotsVisible = false))).isEqualTo("Content not visible: dots hidden")
        assertThat(warning(boxOnly.copy(warning = OverlayWarning.THERMAL_STEPPED_DOWN))).isEqualTo("Hot: smaller model (320)")
        assertThat(warning(boxOnly, armed = true)).isEqualTo("tap a person")
    }

    @Test
    fun hudChanged_seesTheBoxOnlyNote() {
        assertThat(HudFormatter.hudChanged(sample, sample.copy(boxOnlyClass = 0), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample.copy(boxOnlyClass = 0), sample.copy(boxOnlyClass = 1), idleFps)).isTrue()
        assertThat(HudFormatter.hudChanged(sample.copy(boxOnlyClass = 0), sample.copy(boxOnlyClass = 0), idleFps)).isFalse()
    }
}
