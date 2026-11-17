package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.tracking.FakeTrack
import com.memo494.posetracker.core.tracking.FakeTrackList
import com.memo494.posetracker.core.tracking.TrackSnapshot
import org.junit.jupiter.api.Test

class RedrawPolicyTest {
    private val deadband = 0.5f
    private val minScore = 0.5f
    private val fadeStep = 0.1f
    private val maxLostMs = 800f
    private val fadeMinAlpha = 0.25f

    private fun person(
        shiftX: Float = 0f,
        shiftY: Float = 0f,
        keypointScore: Float = 0.9f,
        keypointCount: Int = 3,
    ): PoseDetection {
        val xy = FloatArray(keypointCount * 2)
        for (i in 0 until keypointCount) {
            xy[i * 2] = 100f + i * 10f + shiftX
            xy[i * 2 + 1] = 200f + i * 10f + shiftY
        }
        return PoseDetection(
            boxLeftImg = 50f + shiftX,
            boxTopImg = 60f + shiftY,
            boxRightImg = 150f + shiftX,
            boxBottomImg = 360f + shiftY,
            score = 0.9f,
            keypointsXyImg = xy,
            keypointScores = FloatArray(keypointCount) { keypointScore },
        )
    }

    /** The default person with keypoint [index] moved [byImg] pixels along x; the box does not move. */
    private fun personWithKeypointMoved(
        index: Int,
        byImg: Float,
    ): PoseDetection {
        val base = person()
        val xy = FloatArray(base.keypointCount * 2)
        for (k in 0 until base.keypointCount) {
            xy[k * 2] = base.keypointXImg(k) + if (k == index) byImg else 0f
            xy[k * 2 + 1] = base.keypointYImg(k)
        }
        return PoseDetection(
            boxLeftImg = base.boxLeftImg,
            boxTopImg = base.boxTopImg,
            boxRightImg = base.boxRightImg,
            boxBottomImg = base.boxBottomImg,
            score = base.score,
            keypointsXyImg = xy,
            keypointScores = base.keypointScores,
        )
    }

    private fun frame(
        vararg people: PoseDetection,
        widthImg: Int = 610,
        heightImg: Int = 1356,
    ) = DetectionFrame(1L, TimestampSource.IMAGE, widthImg, heightImg, people.toList())

    private fun redraw(
        previous: DetectionFrame?,
        current: DetectionFrame?,
        previousShown: Boolean = true,
        currentShown: Boolean = true,
        previousWarning: OverlayWarning? = null,
        currentWarning: OverlayWarning? = null,
        hudDue: Boolean = false,
        keypointsDrawn: Boolean = true,
    ): Boolean =
        RedrawPolicy.shouldRedraw(
            previous = previous,
            current = current,
            previousShown = previousShown,
            currentShown = currentShown,
            previousWarning = previousWarning,
            currentWarning = currentWarning,
            deadbandImg = deadband,
            keypointMinScore = minScore,
            keypointsDrawn = keypointsDrawn,
            hudDue = hudDue,
        )

    @Test
    fun identicalFrames_doNotRedraw() {
        assertThat(redraw(frame(person()), frame(person()))).isFalse()
    }

    @Test
    fun noFrameOnBothSides_doesNotRedraw() {
        assertThat(redraw(null, null)).isFalse()
    }

    @Test
    fun emptyFrameVersusNoFrame_doesNotRedraw() {
        assertThat(redraw(null, frame())).isFalse()
        assertThat(redraw(frame(), null)).isFalse()
    }

    @Test
    fun firstPersonAppearing_redraws() {
        assertThat(redraw(null, frame(person()))).isTrue()
        assertThat(redraw(frame(), frame(person()))).isTrue()
    }

    @Test
    fun lastPersonDisappearing_redraws() {
        assertThat(redraw(frame(person()), frame())).isTrue()
        assertThat(redraw(frame(person()), null)).isTrue()
    }

    @Test
    fun detectionCountChange_redraws() {
        assertThat(redraw(frame(person()), frame(person(), person(shiftX = 300f)))).isTrue()
    }

    @Test
    fun movementInsideTheDeadband_doesNotRedraw() {
        assertThat(redraw(frame(person()), frame(person(shiftX = 0.4f, shiftY = -0.4f)))).isFalse()
    }

    @Test
    fun movementExactlyAtTheDeadband_doesNotRedraw() {
        assertThat(redraw(frame(person()), frame(person(shiftX = 0.5f)))).isFalse()
    }

    @Test
    fun movementBeyondTheDeadband_redraws() {
        assertThat(redraw(frame(person()), frame(person(shiftX = 0.6f)))).isTrue()
        assertThat(redraw(frame(person()), frame(person(shiftY = -0.6f)))).isTrue()
    }

    @Test
    fun onlyAKeypointMovingBeyondTheDeadband_redraws() {
        val moved = person()
        moved.keypointsXyImg[2] += 2f

        assertThat(redraw(frame(person()), frame(moved))).isTrue()
    }

    @Test
    fun onlyABoxEdgeMovingBeyondTheDeadband_redraws() {
        val base = person()
        val wider =
            PoseDetection(
                boxLeftImg = base.boxLeftImg,
                boxTopImg = base.boxTopImg,
                boxRightImg = base.boxRightImg + 2f,
                boxBottomImg = base.boxBottomImg,
                score = base.score,
                keypointsXyImg = base.keypointsXyImg,
                keypointScores = base.keypointScores,
            )

        assertThat(redraw(frame(base), frame(wider))).isTrue()
    }

    @Test
    fun keypointCrossingTheScoreThreshold_redraws() {
        assertThat(redraw(frame(person(keypointScore = 0.9f)), frame(person(keypointScore = 0.1f)))).isTrue()
    }

    @Test
    fun keypointScoreChangeThatDoesNotCrossTheThreshold_doesNotRedraw() {
        assertThat(redraw(frame(person(keypointScore = 0.9f)), frame(person(keypointScore = 0.6f)))).isFalse()
    }

    @Test
    fun movingLowScoreKeypoints_doesNotRedraw() {
        val before = person(keypointScore = 0.1f)
        val after = person(keypointScore = 0.1f)
        after.keypointsXyImg[2] += 50f

        assertThat(redraw(frame(before), frame(after))).isFalse()
    }

    // ---- keypoints not drawn (debug marks off): only what is on the screen counts ----

    private val wristIndex = 9
    private val noseIndex = 0

    @Test
    fun wristMovingBeyondTheDeadband_withoutDebugMarks_doesNotRedraw() {
        val moved = person(keypointCount = 17)
        moved.keypointsXyImg[wristIndex * 2] += 20f

        assertThat(redraw(frame(person(keypointCount = 17)), frame(moved), keypointsDrawn = false)).isFalse()
    }

    @Test
    fun wristMovingBeyondTheDeadband_withDebugMarks_redraws() {
        val moved = person(keypointCount = 17)
        moved.keypointsXyImg[wristIndex * 2] += 20f

        assertThat(redraw(frame(person(keypointCount = 17)), frame(moved), keypointsDrawn = true)).isTrue()
    }

    @Test
    fun wristCrossingTheScoreThreshold_withoutDebugMarks_doesNotRedraw() {
        val dropped = person(keypointCount = 17)
        dropped.keypointScores[wristIndex] = 0.1f

        assertThat(redraw(frame(person(keypointCount = 17)), frame(dropped), keypointsDrawn = false)).isFalse()
        assertThat(redraw(frame(person(keypointCount = 17)), frame(dropped), keypointsDrawn = true)).isTrue()
    }

    @Test
    fun headPointMoving_redrawsWithAndWithoutDebugMarks() {
        val moved = person(keypointCount = 17)
        moved.keypointsXyImg[noseIndex * 2] += 20f

        assertThat(redraw(frame(person(keypointCount = 17)), frame(moved), keypointsDrawn = false)).isTrue()
        assertThat(redraw(frame(person(keypointCount = 17)), frame(moved), keypointsDrawn = true)).isTrue()
    }

    @Test
    fun headPointFallingBackToTheBoxTop_withoutDebugMarks_redraws() {
        val noHead = person(keypointCount = 17)
        for (k in 0 until TrackerConfig.HEAD_KEYPOINT_COUNT) noHead.keypointScores[k] = 0.1f

        assertThat(redraw(frame(person(keypointCount = 17)), frame(noHead), keypointsDrawn = false)).isTrue()
    }

    @Test
    fun boxEdgeMoving_withoutDebugMarks_redraws() {
        assertThat(redraw(frame(person()), frame(person(shiftX = 2f)), keypointsDrawn = false)).isTrue()
    }

    @Test
    fun keypointCountChange_redraws() {
        assertThat(redraw(frame(person(keypointCount = 3)), frame(person(keypointCount = 4)))).isTrue()
    }

    @Test
    fun imgSizeChange_redraws() {
        assertThat(redraw(frame(person()), frame(person(), widthImg = 1356, heightImg = 610))).isTrue()
    }

    @Test
    fun visibilityChange_redraws() {
        assertThat(redraw(frame(person()), frame(person()), previousShown = true, currentShown = false)).isTrue()
        assertThat(redraw(frame(person()), frame(person()), previousShown = false, currentShown = true)).isTrue()
    }

    @Test
    fun warningChange_redraws() {
        val warning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED

        assertThat(redraw(frame(person()), frame(person()), currentWarning = warning)).isTrue()
        assertThat(redraw(frame(person()), frame(person()), previousWarning = warning)).isTrue()
    }

    @Test
    fun hiddenDetections_areIgnoredEvenWhenTheyMove() {
        val moved = frame(person(shiftX = 80f))

        assertThat(redraw(frame(person()), moved, previousShown = false, currentShown = false)).isFalse()
    }

    @Test
    fun hiddenDetectionsOnOneSide_areTreatedAsEmpty() {
        assertThat(redraw(frame(person()), frame(person()), previousShown = false, currentShown = false)).isFalse()
        assertThat(redraw(frame(), frame(person()), previousShown = true, currentShown = false)).isFalse()
    }

    @Test
    fun hudDue_redrawsEvenWhenNothingMoved() {
        assertThat(redraw(frame(person()), frame(person()), hudDue = true)).isTrue()
    }

    @Test
    fun hudDue_needsAChangedHudAndAnElapsedInterval() {
        val interval = 500_000_000L

        assertThat(RedrawPolicy.hudDue(1_000_000_000L, 400_000_000L, interval, hudChanged = true)).isTrue()
        assertThat(RedrawPolicy.hudDue(900_000_000L, 400_000_000L, interval, hudChanged = true)).isTrue()
        assertThat(RedrawPolicy.hudDue(899_999_999L, 400_000_000L, interval, hudChanged = true)).isFalse()
        assertThat(RedrawPolicy.hudDue(1_000_000_000L, 400_000_000L, interval, hudChanged = false)).isFalse()
    }

    @Test
    fun hudDue_withAClockThatWentBackwards_isNotDue() {
        assertThat(RedrawPolicy.hudDue(100L, 5_000_000_000L, 500_000_000L, hudChanged = true)).isFalse()
    }

    private fun tracks(vararg entries: FakeTrack): TrackSnapshot = TrackSnapshot.of(FakeTrackList(*entries), 320, 240)

    private fun tracksChanged(
        previous: TrackSnapshot,
        current: TrackSnapshot,
    ): Boolean = RedrawPolicy.tracksChanged(previous, current, deadband, fadeStep, maxLostMs, fadeMinAlpha)

    @Test
    fun tracksChanged_identicalSnapshots_isFalse() {
        assertThat(tracksChanged(tracks(FakeTrack(1), FakeTrack(2)), tracks(FakeTrack(1), FakeTrack(2)))).isFalse()
        assertThat(tracksChanged(TrackSnapshot.EMPTY, TrackSnapshot.EMPTY)).isFalse()
    }

    @Test
    fun tracksChanged_aDifferentCount_isTrue() {
        assertThat(tracksChanged(TrackSnapshot.EMPTY, tracks(FakeTrack(1)))).isTrue()
        assertThat(tracksChanged(tracks(FakeTrack(1)), TrackSnapshot.EMPTY)).isTrue()
        assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(FakeTrack(1), FakeTrack(2)))).isTrue()
    }

    @Test
    fun tracksChanged_anIdSwapAtEqualPositions_isTrue() {
        assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(FakeTrack(2)))).isTrue()
    }

    @Test
    fun tracksChanged_aTrackSwappedWithItsNeighbourAtEqualPositions_isTrue() {
        assertThat(tracksChanged(tracks(FakeTrack(1), FakeTrack(2)), tracks(FakeTrack(2), FakeTrack(1)))).isTrue()
    }

    @Test
    fun tracksChanged_eachCoordinateJustBelowTheDeadband_isFalse() {
        val below = deadband - 0.01f
        for (moved in everyMovedCopy(below)) {
            assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(moved))).isFalse()
        }
    }

    @Test
    fun tracksChanged_eachCoordinateJustAboveTheDeadband_isTrue() {
        val above = deadband + 0.01f
        for (moved in everyMovedCopy(above)) {
            assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(moved))).isTrue()
        }
    }

    @Test
    fun tracksChanged_aMoveInTheNegativeDirection_isTrue() {
        assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(FakeTrack(1, headXImg = 100f - deadband - 0.01f)))).isTrue()
    }

    @Test
    fun tracksChanged_aMoveOfTheSecondTrack_isTrue() {
        val previous = tracks(FakeTrack(1), FakeTrack(2))
        val current = tracks(FakeTrack(1), FakeTrack(2, boxBottomImg = 240f + deadband + 0.01f))

        assertThat(tracksChanged(previous, current)).isTrue()
    }

    @Test
    fun tracksChanged_slowDriftAccumulatesAgainstThePreviousDrawnSnapshot() {
        val drawn = tracks(FakeTrack(1))

        assertThat(tracksChanged(drawn, tracks(FakeTrack(1, headXImg = 100f + 0.4f)))).isFalse()
        assertThat(tracksChanged(drawn, tracks(FakeTrack(1, headXImg = 100f + 0.8f)))).isTrue()
    }

    @Test
    fun tracksChanged_theLostFlagAlone_isFalse() {
        assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(FakeTrack(1, lost = true)))).isFalse()
        assertThat(tracksChanged(tracks(FakeTrack(1, lost = true)), tracks(FakeTrack(1)))).isFalse()
    }

    @Test
    fun tracksChanged_theEstimatedHeadFlagAlone_isFalse() {
        assertThat(tracksChanged(tracks(FakeTrack(1)), tracks(FakeTrack(1, headEstimated = true)))).isFalse()
    }

    private fun lostAt(lostMs: Float): TrackSnapshot = tracks(FakeTrack(1, lost = true, lostMs = lostMs))

    @Test
    fun tracksChanged_aFadeStepOfTheAlpha_isTrue() {
        // 100 ms -> alpha 0.875, 200 ms -> 0.75: a step of 0.125.
        assertThat(tracksChanged(lostAt(100f), lostAt(200f))).isTrue()
    }

    @Test
    fun tracksChanged_aFadeBelowTheStep_isFalse() {
        // 100 ms -> 0.875, 150 ms -> 0.8125: 0.0625.
        assertThat(tracksChanged(lostAt(100f), lostAt(150f))).isFalse()
    }

    @Test
    fun tracksChanged_theFadeAccumulatesAgainstThePreviousDrawnSnapshot() {
        val drawn = lostAt(100f)

        assertThat(tracksChanged(drawn, lostAt(150f))).isFalse()
        assertThat(tracksChanged(drawn, lostAt(200f))).isTrue()
    }

    @Test
    fun tracksChanged_aFadeInTheOtherDirection_isTrue() {
        assertThat(tracksChanged(lostAt(200f), lostAt(100f))).isTrue()
    }

    @Test
    fun tracksChanged_aLostTrackThatRecovered_isTrueWhenTheAlphaJumpsByAStep() {
        assertThat(tracksChanged(lostAt(300f), tracks(FakeTrack(1)))).isTrue()
        assertThat(tracksChanged(tracks(FakeTrack(1)), lostAt(300f))).isTrue()
    }

    @Test
    fun tracksChanged_whereBothAlphasAreTheFloor_isFalse() {
        assertThat(tracksChanged(lostAt(700f), lostAt(800f))).isFalse()
    }

    @Test
    fun tracksChanged_aFadeOnTheSecondTrack_isTrue() {
        val previous = tracks(FakeTrack(1), FakeTrack(2, lost = true, lostMs = 100f))
        val current = tracks(FakeTrack(1), FakeTrack(2, lost = true, lostMs = 200f))

        assertThat(tracksChanged(previous, current)).isTrue()
    }

    @Test
    fun marks_aFadeStepOfALostTrack_redraws() {
        assertThat(redrawMarks(previousTracks = lostAt(100f), currentTracks = lostAt(200f))).isTrue()
    }

    @Test
    fun marks_aFadeBelowTheStep_doesNotRedraw() {
        assertThat(redrawMarks(previousTracks = lostAt(100f), currentTracks = lostAt(150f))).isFalse()
    }

    /** Copies of the track with id 1 in which exactly one of the six coordinates moved by [shift]. */
    private fun everyMovedCopy(shift: Float): List<FakeTrack> {
        val base = FakeTrack(1)
        return listOf(
            base.copy(headXImg = base.headXImg + shift),
            base.copy(headYImg = base.headYImg + shift),
            base.copy(boxLeftImg = base.boxLeftImg + shift),
            base.copy(boxTopImg = base.boxTopImg + shift),
            base.copy(boxRightImg = base.boxRightImg + shift),
            base.copy(boxBottomImg = base.boxBottomImg + shift),
        )
    }

    private fun redrawMarks(
        previousTracks: TrackSnapshot = tracks(FakeTrack(1)),
        currentTracks: TrackSnapshot = tracks(FakeTrack(1)),
        previousFrame: DetectionFrame? = frame(person()),
        currentFrame: DetectionFrame? = frame(person()),
        previousShown: Boolean = true,
        currentShown: Boolean = true,
        previousWarning: OverlayWarning? = null,
        currentWarning: OverlayWarning? = null,
        debugMarks: Boolean = false,
        hudDue: Boolean = false,
    ): Boolean =
        RedrawPolicy.shouldRedrawMarks(
            previousTracks = previousTracks,
            currentTracks = currentTracks,
            previousFrame = previousFrame,
            currentFrame = currentFrame,
            previousShown = previousShown,
            currentShown = currentShown,
            previousWarning = previousWarning,
            currentWarning = currentWarning,
            deadbandImg = deadband,
            keypointMinScore = minScore,
            debugMarks = debugMarks,
            hudDue = hudDue,
            fadeStepAlpha = fadeStep,
            maxLostMs = maxLostMs,
            fadeMinAlpha = fadeMinAlpha,
        )

    @Test
    fun marks_nothingChanged_doesNotRedraw() {
        assertThat(redrawMarks()).isFalse()
        assertThat(redrawMarks(debugMarks = true)).isFalse()
    }

    @Test
    fun marks_hudDue_redrawsEvenWhenNothingChanged() {
        assertThat(redrawMarks(hudDue = true)).isTrue()
        assertThat(redrawMarks(previousTracks = TrackSnapshot.EMPTY, currentTracks = TrackSnapshot.EMPTY, hudDue = true)).isTrue()
    }

    @Test
    fun marks_aChangedWarning_redraws() {
        assertThat(redrawMarks(currentWarning = OverlayWarning.DETECTOR_UNAVAILABLE)).isTrue()
        assertThat(
            redrawMarks(
                previousWarning = OverlayWarning.IMPORTED_MODEL_NOT_USED,
                currentWarning = null,
                previousTracks = TrackSnapshot.EMPTY,
                currentTracks = TrackSnapshot.EMPTY,
            ),
        ).isTrue()
    }

    @Test
    fun marks_aTrackAppearingOrDisappearing_redraws() {
        assertThat(redrawMarks(previousTracks = TrackSnapshot.EMPTY)).isTrue()
        assertThat(redrawMarks(currentTracks = TrackSnapshot.EMPTY)).isTrue()
    }

    @Test
    fun marks_aTrackMovingBeyondTheDeadband_redraws() {
        val moved = tracks(FakeTrack(1, headYImg = 50f + deadband + 0.01f))

        assertThat(redrawMarks(currentTracks = moved)).isTrue()
    }

    @Test
    fun marks_aTrackMovingWithinTheDeadband_doesNotRedraw() {
        val moved = tracks(FakeTrack(1, headYImg = 50f + deadband - 0.01f))

        assertThat(redrawMarks(currentTracks = moved)).isFalse()
    }

    @Test
    fun marks_aTrackBecomingLost_doesNotRedraw() {
        assertThat(redrawMarks(currentTracks = tracks(FakeTrack(1, lost = true)))).isFalse()
    }

    @Test
    fun marks_detectionsMoveAndTracksDoNot_withoutDebugMarks_doesNotRedraw() {
        val moved = frame(person(shiftX = 20f, shiftY = 20f))

        assertThat(redrawMarks(currentFrame = moved, debugMarks = false)).isFalse()
        assertThat(redrawMarks(currentFrame = frame(), debugMarks = false)).isFalse()
        assertThat(redrawMarks(previousFrame = null, currentFrame = null, debugMarks = false)).isFalse()
    }

    @Test
    fun marks_detectionsMoveAndTracksDoNot_withDebugMarks_redraws() {
        val moved = frame(person(shiftX = 20f, shiftY = 20f))

        assertThat(redrawMarks(currentFrame = moved, debugMarks = true)).isTrue()
    }

    @Test
    fun marks_aKeypointMovingWithTracksStill_redrawsOnlyWithDebugMarks() {
        val before = frame(person())
        val after = frame(personWithKeypointMoved(index = 2, byImg = 5f))

        assertThat(redrawMarks(previousFrame = before, currentFrame = after, debugMarks = false)).isFalse()
        assertThat(redrawMarks(previousFrame = before, currentFrame = after, debugMarks = true)).isTrue()
    }

    @Test
    fun marks_detectionsWithinTheDeadband_withDebugMarks_doNotRedraw() {
        val nearlyStill = frame(person(shiftX = deadband - 0.01f))

        assertThat(redrawMarks(currentFrame = nearlyStill, debugMarks = true)).isFalse()
    }

    @Test
    fun marks_hiddenOnBothSides_neverRedraws() {
        val moved = tracks(FakeTrack(1, headXImg = 500f))

        assertThat(redrawMarks(currentTracks = moved, previousShown = false, currentShown = false)).isFalse()
        assertThat(redrawMarks(currentTracks = TrackSnapshot.EMPTY, previousShown = false, currentShown = false)).isFalse()
        assertThat(
            redrawMarks(
                currentFrame = frame(person(shiftX = 20f)),
                previousShown = false,
                currentShown = false,
                debugMarks = true,
            ),
        ).isFalse()
    }

    @Test
    fun marks_hiddenOnBothSides_stillRedrawsForTheHudAndTheWarning() {
        assertThat(redrawMarks(previousShown = false, currentShown = false, hudDue = true)).isTrue()
        assertThat(
            redrawMarks(previousShown = false, currentShown = false, currentWarning = OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED),
        ).isTrue()
    }

    @Test
    fun marks_shownThenHiddenWithTracks_redraws() {
        assertThat(redrawMarks(previousShown = true, currentShown = false)).isTrue()
    }

    @Test
    fun marks_hiddenThenShownWithTracks_redraws() {
        assertThat(redrawMarks(previousShown = false, currentShown = true)).isTrue()
    }

    @Test
    fun marks_shownThenHiddenWithoutTracks_doesNotRedraw() {
        assertThat(
            redrawMarks(
                previousTracks = TrackSnapshot.EMPTY,
                currentTracks = TrackSnapshot.EMPTY,
                previousShown = true,
                currentShown = false,
            ),
        ).isFalse()
    }
}
