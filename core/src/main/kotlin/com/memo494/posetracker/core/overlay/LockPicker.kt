package com.memo494.posetracker.core.overlay

import com.memo494.posetracker.core.tracking.TrackSnapshot

/**
 * Which shown track a tap locks (docs/plans/M4.md, M4b T2; ARCHITECTURE §6.6). Pure and allocation-free.
 */
object LockPicker {
    /** Returned when no track is close enough (or the input is not usable). */
    const val NONE = -1

    /**
     * The id of the track whose head point is nearest to the tap ([tapXImg], [tapYImg]), counting only tracks whose
     * head point is within [maxDistanceRatio] times **that track's** box height of the tap (a tap exactly on the
     * limit counts); [NONE] when there is none. The nearest wins; equal distances go to the lower index. Fails
     * closed: a tap that is not finite, a ratio that is not a finite positive number, and a track with a head
     * point that is not finite or a box height that is not positive are never picked.
     *
     * @param snapshot the tracks the overlay draws; lost tracks count like confirmed ones.
     * @param tapXImg tap x in Img pixels (`FitCenter.viewToImgX` of the view x).
     * @param tapYImg tap y in Img pixels.
     * @param maxDistanceRatio farthest distance from the head point as a fraction of the box height
     *   (`TrackerConfig.lockMaxTapDistanceRatio`).
     */
    fun pick(
        snapshot: TrackSnapshot,
        tapXImg: Float,
        tapYImg: Float,
        maxDistanceRatio: Float,
    ): Int {
        if (!tapXImg.isFinite() || !tapYImg.isFinite()) return NONE
        if (!maxDistanceRatio.isFinite() || maxDistanceRatio <= 0f) return NONE
        var bestIndex = NONE
        var bestSquared = Float.MAX_VALUE
        for (i in 0 until snapshot.count) {
            val heightImg = snapshot.boxBottomImg(i) - snapshot.boxTopImg(i)
            val dx = snapshot.headXImg(i) - tapXImg
            val dy = snapshot.headYImg(i) - tapYImg
            val squared = dx * dx + dy * dy
            if (!heightImg.isFinite() || heightImg <= 0f || !squared.isFinite()) continue
            val limit = maxDistanceRatio * heightImg
            if (squared > limit * limit) continue
            if (bestIndex == NONE || squared < bestSquared) {
                bestIndex = i
                bestSquared = squared
            }
        }
        return if (bestIndex == NONE) NONE else snapshot.id(bestIndex)
    }
}
