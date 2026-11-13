package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.detector.Accelerator
import org.junit.Test

class HudFormatterTest {
    private val buffer = CharArray(64)

    private val idleFps = TrackerConfig().hudIdleFps

    private fun stats(state: OverlayState): String {
        val length = HudFormatter.formatStats(state, buffer, idleFps)
        return String(buffer, 0, length)
    }

    private fun warning(state: OverlayState): String {
        val length = HudFormatter.formatWarning(state, buffer)
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
}
