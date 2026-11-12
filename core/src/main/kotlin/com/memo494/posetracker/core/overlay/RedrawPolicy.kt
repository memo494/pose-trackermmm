package com.memo494.posetracker.core.overlay

import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import kotlin.math.abs

/**
 * When the overlay must redraw (ARCHITECTURE §6.5, Consult C1-B). The overlay is part of the screen
 * capture, so a free-running redraw loop would feed back into the frames; the overlay is invalidated only
 * when this policy says the picture really changed.
 *
 * Pure and allocation-free: frames are compared in place, by index, with no iterators or temporaries.
 * "Previous" is the state the overlay was last asked to draw, so slow drift accumulates against it and
 * eventually crosses the deadband.
 */
object RedrawPolicy {
    /**
     * Whether the overlay must be redrawn for [current] given what [previous] drew.
     *
     * Redraw when the number of drawn people changed, when the warning changed, when any
     * box edge or any drawn keypoint moved by more than [deadbandImg] along an axis, when a keypoint
     * crossed [keypointMinScore], or when the HUD is [hudDue]. Detections count as empty while not shown
     * (so a shown/hidden change redraws only when it changes the number of drawn people; with none drawn
     * on both sides the flag alone changes nothing on screen), and a missing frame counts as an empty one.
     *
     * @param previous frame the overlay last drew, Img space; `null` = none yet.
     * @param current newest frame, Img space; `null` = none.
     * @param previousShown whether [previous] was drawn (dots visible and no warning).
     * @param currentShown whether [current] would be drawn.
     * @param previousWarning warning that was shown, if any.
     * @param currentWarning warning to show, if any.
     * @param deadbandImg smallest movement that counts, Img pixels (`redrawDeadbandPx / scale`).
     * @param keypointMinScore keypoints below this confidence are not drawn, unitless in [0, 1].
     * @param hudDue result of [hudDue] for this update.
     */
    fun shouldRedraw(
        previous: DetectionFrame?,
        current: DetectionFrame?,
        previousShown: Boolean,
        currentShown: Boolean,
        previousWarning: OverlayWarning?,
        currentWarning: OverlayWarning?,
        deadbandImg: Float,
        keypointMinScore: Float,
        hudDue: Boolean,
    ): Boolean {
        if (hudDue) return true
        if (previousWarning != currentWarning) return true
        val previousCount = drawnCount(previous, previousShown)
        val currentCount = drawnCount(current, currentShown)
        if (previousCount != currentCount) return true
        if (previousCount == 0) return false
        if (previous == null || current == null) return true
        if (previous.widthImg != current.widthImg || previous.heightImg != current.heightImg) return true
        for (i in 0 until previousCount) {
            if (personChanged(previous.detections[i], current.detections[i], deadbandImg, keypointMinScore)) return true
        }
        return false
    }

    /**
     * Whether the HUD text should be refreshed now: its content changed and at least [intervalNs] passed
     * since it was last refreshed (`TrackerConfig.hudIntervalNs`).
     * All times are `System.nanoTime()` values; [intervalNs] is a duration.
     */
    fun hudDue(
        nowNs: Long,
        lastHudNs: Long,
        intervalNs: Long,
        hudChanged: Boolean,
    ): Boolean = hudChanged && nowNs - lastHudNs >= intervalNs

    private fun drawnCount(
        frame: DetectionFrame?,
        shown: Boolean,
    ): Int = if (frame == null || !shown) 0 else frame.detections.size

    private fun personChanged(
        previous: PoseDetection,
        current: PoseDetection,
        deadbandImg: Float,
        keypointMinScore: Float,
    ): Boolean {
        if (moved(previous.boxLeftImg, current.boxLeftImg, deadbandImg)) return true
        if (moved(previous.boxTopImg, current.boxTopImg, deadbandImg)) return true
        if (moved(previous.boxRightImg, current.boxRightImg, deadbandImg)) return true
        if (moved(previous.boxBottomImg, current.boxBottomImg, deadbandImg)) return true
        if (previous.keypointCount != current.keypointCount) return true
        for (k in 0 until previous.keypointCount) {
            val wasDrawn = previous.keypointScores[k] >= keypointMinScore
            val isDrawn = current.keypointScores[k] >= keypointMinScore
            if (wasDrawn != isDrawn) return true
            if (!isDrawn) continue
            if (moved(previous.keypointXImg(k), current.keypointXImg(k), deadbandImg)) return true
            if (moved(previous.keypointYImg(k), current.keypointYImg(k), deadbandImg)) return true
        }
        return false
    }

    private fun moved(
        before: Float,
        after: Float,
        deadbandImg: Float,
    ): Boolean = abs(after - before) > deadbandImg
}
