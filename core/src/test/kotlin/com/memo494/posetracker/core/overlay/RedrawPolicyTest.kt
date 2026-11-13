package com.memo494.posetracker.core.overlay

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.jupiter.api.Test

class RedrawPolicyTest {
    private val deadband = 0.5f
    private val minScore = 0.5f

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
}
