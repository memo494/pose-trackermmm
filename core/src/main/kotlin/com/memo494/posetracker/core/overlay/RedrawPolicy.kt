package com.memo494.posetracker.core.overlay

import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.tracking.TrackSnapshot
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
     * box edge or the head point (centre of the head ring) moved by more than [deadbandImg] along an axis,
     * or when the HUD is [hudDue]. With [keypointsDrawn] (debug marks) also when any drawn keypoint moved by
     * more than [deadbandImg] or crossed [keypointMinScore]; without it the other keypoints are not on the
     * screen, so their movement is not a change of the picture (only the head keypoints matter, through the
     * head point). The ring radius follows the box width, so it is covered by the box edges.
     * Detections count as empty while not shown
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
     * @param keypointsDrawn whether every keypoint is drawn (`config.debugMarks`); `false` = only the head ring
     *   and the box marks are drawn.
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
        keypointsDrawn: Boolean,
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
            if (personChanged(previous.detections[i], current.detections[i], deadbandImg, keypointMinScore, keypointsDrawn)) return true
        }
        return false
    }

    /**
     * Whether the overlay must be redrawn when it draws tracks (ARCHITECTURE §6.5, M3b): the decision
     * `OverlayStateHolder` uses. Order: [hudDue], a changed warning, a change of the tracks ([tracksChanged]),
     * and, with [debugMarks], the raw detections as in [shouldRedraw] (with every keypoint drawn). Without
     * [debugMarks] the detections are never looked at. A side that is not shown counts as having no tracks
     * (and no detections), so shown to hidden with tracks redraws and hidden to hidden never does (apart from
     * [hudDue] and the warning).
     *
     * @param previousTracks tracks the overlay last drew.
     * @param currentTracks newest tracks.
     * @param previousFrame frame the overlay last drew (only used with [debugMarks]), Img space; `null` = none.
     * @param currentFrame newest frame (only used with [debugMarks]), Img space; `null` = none.
     * @param previousShown whether the previous state was drawn (marks visible and no warning).
     * @param currentShown whether the new state would be drawn.
     * @param previousWarning warning that was shown, if any.
     * @param currentWarning warning to show, if any.
     * @param deadbandImg smallest movement that counts, Img pixels (`redrawDeadbandPx / scale`).
     * @param keypointMinScore keypoints below this confidence are not drawn, unitless in [0, 1].
     * @param debugMarks whether the raw detections are drawn as well (`config.debugMarks`).
     * @param hudDue result of [hudDue] for this update.
     */
    fun shouldRedrawMarks(
        previousTracks: TrackSnapshot,
        currentTracks: TrackSnapshot,
        previousFrame: DetectionFrame?,
        currentFrame: DetectionFrame?,
        previousShown: Boolean,
        currentShown: Boolean,
        previousWarning: OverlayWarning?,
        currentWarning: OverlayWarning?,
        deadbandImg: Float,
        keypointMinScore: Float,
        debugMarks: Boolean,
        hudDue: Boolean,
    ): Boolean {
        if (hudDue) return true
        if (previousWarning != currentWarning) return true
        val previousDrawn = if (previousShown) previousTracks else TrackSnapshot.EMPTY
        val currentDrawn = if (currentShown) currentTracks else TrackSnapshot.EMPTY
        if (tracksChanged(previousDrawn, currentDrawn, deadbandImg)) return true
        if (!debugMarks) return false
        return shouldRedraw(
            previous = previousFrame,
            current = currentFrame,
            previousShown = previousShown,
            currentShown = currentShown,
            previousWarning = previousWarning,
            currentWarning = currentWarning,
            deadbandImg = deadbandImg,
            keypointMinScore = keypointMinScore,
            keypointsDrawn = true,
            hudDue = false,
        )
    }

    /**
     * Whether the tracks drawn in [previous] and those in [current] differ on screen: the counts differ, an id
     * differs at an index, or a head coordinate or a box edge moved by more than [deadbandImg] (Img pixels,
     * along an axis). `isLost` and `isHeadEstimated` are not compared (they change no pixel in M3b). Allocation-free.
     */
    fun tracksChanged(
        previous: TrackSnapshot,
        current: TrackSnapshot,
        deadbandImg: Float,
    ): Boolean {
        if (previous.count != current.count) return true
        for (i in 0 until previous.count) {
            if (previous.id(i) != current.id(i)) return true
            if (moved(previous.headXImg(i), current.headXImg(i), deadbandImg)) return true
            if (moved(previous.headYImg(i), current.headYImg(i), deadbandImg)) return true
            if (moved(previous.boxLeftImg(i), current.boxLeftImg(i), deadbandImg)) return true
            if (moved(previous.boxTopImg(i), current.boxTopImg(i), deadbandImg)) return true
            if (moved(previous.boxRightImg(i), current.boxRightImg(i), deadbandImg)) return true
            if (moved(previous.boxBottomImg(i), current.boxBottomImg(i), deadbandImg)) return true
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
        keypointsDrawn: Boolean,
    ): Boolean {
        if (moved(previous.boxLeftImg, current.boxLeftImg, deadbandImg)) return true
        if (moved(previous.boxTopImg, current.boxTopImg, deadbandImg)) return true
        if (moved(previous.boxRightImg, current.boxRightImg, deadbandImg)) return true
        if (moved(previous.boxBottomImg, current.boxBottomImg, deadbandImg)) return true
        if (!keypointsDrawn) return headPointMoved(previous, current, deadbandImg, keypointMinScore)
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

    private fun headPointMoved(
        previous: PoseDetection,
        current: PoseDetection,
        deadbandImg: Float,
        keypointMinScore: Float,
    ): Boolean =
        moved(previous.headXImg(keypointMinScore), current.headXImg(keypointMinScore), deadbandImg) ||
            moved(previous.headYImg(keypointMinScore), current.headYImg(keypointMinScore), deadbandImg)

    private fun moved(
        before: Float,
        after: Float,
        deadbandImg: Float,
    ): Boolean = abs(after - before) > deadbandImg
}
