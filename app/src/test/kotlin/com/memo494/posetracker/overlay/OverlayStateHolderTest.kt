package com.memo494.posetracker.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.detector.Accelerator
import org.junit.Test

class OverlayStateHolderTest {
    private val intervalNs = TrackerConfig().hudIntervalNs
    private var nowNs = 10_000_000_000L
    private var redraws = 0
    private val holder = OverlayStateHolder(TrackerConfig()) { nowNs }

    init {
        holder.redrawRequester = Runnable { redraws++ }
    }

    private fun person(shiftX: Float = 0f): PoseDetection =
        PoseDetection(
            boxLeftImg = 50f + shiftX,
            boxTopImg = 60f,
            boxRightImg = 150f + shiftX,
            boxBottomImg = 360f,
            score = 0.9f,
            keypointsXyImg = floatArrayOf(100f + shiftX, 80f),
            keypointScores = floatArrayOf(0.9f),
        )

    private fun state(
        shiftX: Float = 0f,
        captureFps: Float = 30f,
        inferenceMs: Float = 10f,
    ): OverlayState =
        OverlayState(
            frame = DetectionFrame(1L, TimestampSource.IMAGE, 610, 1356, listOf(person(shiftX))),
            accelerator = Accelerator.GPU,
            captureFps = captureFps,
            inferenceMs = inferenceMs,
            captureWidthImg = 610,
            captureHeightImg = 1356,
            timestampSource = TimestampSource.IMAGE,
        )

    @Test
    fun startsEmpty() {
        assertThat(holder.latest()).isEqualTo(OverlayState.EMPTY)
        assertThat(holder.hud()).isEqualTo(OverlayState.EMPTY)
    }

    @Test
    fun firstStateWithAPerson_requestsARedraw() {
        holder.publish(state())

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun repeatedIdenticalStates_requestNoFurtherRedraw() {
        holder.publish(state())
        holder.publish(state())
        holder.publish(state())

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun movementInsideTheDeadband_requestsNoRedrawButLatestIsUpdated() {
        holder.publish(state())
        val moved = state(shiftX = 0.3f)

        holder.publish(moved)

        assertThat(redraws).isEqualTo(1)
        assertThat(holder.latest()).isSameInstanceAs(moved)
    }

    @Test
    fun slowDrift_accumulatesAgainstTheLastDrawnStateAndEventuallyRedraws() {
        holder.publish(state())
        holder.publish(state(shiftX = 0.3f))
        holder.publish(state(shiftX = 0.45f))
        assertThat(redraws).isEqualTo(1)

        holder.publish(state(shiftX = 0.6f))

        assertThat(redraws).isEqualTo(2)
    }

    @Test
    fun theDeadbandIsConvertedFromViewPixelsWithTheCurrentScale() {
        holder.imgToViewScale = 2f
        holder.publish(state())

        holder.publish(state(shiftX = 0.3f))

        assertThat(redraws).isEqualTo(2)
    }

    @Test
    fun aNonPositiveScaleFallsBackToOne() {
        holder.imgToViewScale = 0f
        holder.publish(state())

        holder.publish(state(shiftX = 0.3f))

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun hudChange_isHeldBackUntilTheIntervalHasElapsed() {
        holder.publish(state(captureFps = 30f))
        nowNs += intervalNs / 2

        holder.publish(state(captureFps = 31f))

        assertThat(redraws).isEqualTo(1)
        assertThat(holder.hud().captureFps).isEqualTo(30f)
        assertThat(holder.latest().captureFps).isEqualTo(31f)
    }

    @Test
    fun hudChange_afterTheInterval_redrawsAndUpdatesTheHud() {
        holder.publish(state(captureFps = 30f))
        nowNs += intervalNs

        holder.publish(state(captureFps = 31f))

        assertThat(redraws).isEqualTo(2)
        assertThat(holder.hud().captureFps).isEqualTo(31f)
    }

    @Test
    fun hudWithoutVisibleChange_neverRedraws() {
        holder.publish(state(captureFps = 30f))
        nowNs += intervalNs * 10

        holder.publish(state(captureFps = 30.2f))

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun theHudTextRateStaysAtOrBelowTheConfiguredRateWhilePeopleMove() {
        holder.publish(state(shiftX = 0f, captureFps = 30f))
        val frameNs = intervalNs / 10
        var hudUpdates = 0
        var lastHud = holder.hud()
        for (i in 1..30) {
            nowNs += frameNs
            holder.publish(state(shiftX = i * 5f, captureFps = 30f + i))
            if (holder.hud() !== lastHud) {
                hudUpdates++
                lastHud = holder.hud()
            }
        }

        assertThat(hudUpdates).isAtMost(3)
        assertThat(redraws).isEqualTo(31)
    }

    @Test
    fun anIdleScreen_hudFeedbackFramesDoNotKeepTheHudRefreshing() {
        holder.publish(state(captureFps = 30f, inferenceMs = 10f))
        assertThat(redraws).isEqualTo(1)

        nowNs += intervalNs
        holder.publish(state(captureFps = 0f, inferenceMs = 10f))
        assertThat(redraws).isEqualTo(2)
        assertThat(holder.hud().captureFps).isEqualTo(0f)

        nowNs += intervalNs
        holder.publish(state(captureFps = 1f, inferenceMs = 12.5f))
        assertThat(redraws).isEqualTo(2)

        nowNs += intervalNs
        holder.publish(state(captureFps = 2f, inferenceMs = 14.1f))
        assertThat(redraws).isEqualTo(2)
        assertThat(holder.hud().captureFps).isEqualTo(0f)
        assertThat(holder.hud().inferenceMs).isEqualTo(10f)

        nowNs += intervalNs
        holder.publish(state(captureFps = 30f, inferenceMs = 10f))
        assertThat(redraws).isEqualTo(3)
        assertThat(holder.hud().captureFps).isEqualTo(30f)
    }

    @Test
    fun aNewWarning_redrawsAndReachesTheHudImmediately() {
        holder.publish(state())
        val warned = state().copy(warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)

        holder.publish(warned)

        assertThat(redraws).isEqualTo(2)
        assertThat(holder.hud().warning).isEqualTo(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)
    }

    @Test
    fun dotsBecomingInvisible_redrawsAndReachesTheHudImmediately() {
        holder.publish(state())

        holder.publish(state().copy(dotsVisible = false))

        assertThat(redraws).isEqualTo(2)
        assertThat(holder.hud().dotsVisible).isFalse()
    }

    @Test
    fun withoutARequester_publishingStillTracksTheLatestState() {
        holder.redrawRequester = null
        val newest = state(shiftX = 40f)

        holder.publish(state())
        holder.publish(newest)

        assertThat(holder.latest()).isSameInstanceAs(newest)
        assertThat(redraws).isEqualTo(0)
    }

    @Test
    fun theImportedModelNote_isUrgentAndLeavesTheDetectionsShown() {
        holder.publish(state())
        val noted = state().copy(warning = OverlayWarning.IMPORTED_MODEL_NOT_USED)

        holder.publish(noted)

        assertThat(noted.detectionsShown).isTrue()
        assertThat(holder.hud().warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)
        assertThat(redraws).isEqualTo(2)
    }

    @Test
    fun failClosedWarnings_stillHideTheDetections() {
        assertThat(state().copy(warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED).detectionsShown).isFalse()
        assertThat(state().copy(warning = OverlayWarning.DETECTOR_UNAVAILABLE).detectionsShown).isFalse()
        assertThat(state().copy(dotsVisible = false).detectionsShown).isFalse()
    }

    @Test
    fun theModelFields_reachTheHudWhenTheyChange() {
        holder.publish(state())
        nowNs += intervalNs

        holder.publish(state().copy(modelInputWidth = 832, modelInputHeight = 384, modelImported = true))

        assertThat(holder.hud().modelInputWidth).isEqualTo(832)
        assertThat(holder.hud().modelInputHeight).isEqualTo(384)
        assertThat(holder.hud().modelImported).isTrue()
    }

    @Test
    fun theEmptyState_knowsNoModel() {
        assertThat(OverlayState.EMPTY.modelInputWidth).isEqualTo(0)
        assertThat(OverlayState.EMPTY.modelInputHeight).isEqualTo(0)
        assertThat(OverlayState.EMPTY.modelImported).isFalse()
    }
}
