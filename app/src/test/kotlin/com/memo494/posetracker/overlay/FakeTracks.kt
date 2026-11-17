package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.tracking.TrackList
import com.memo494.posetracker.core.tracking.TrackState

/**
 * [count] shown, confirmed tracks for tests, ids `firstId`, `firstId + 1`, …; every head and box sits [shiftXImg] to
 * the right of a fixed place, and track `i` another 10 px to the right per index; Img pixels.
 */
internal class FakeTracks(
    private val count: Int,
    private val firstId: Int = 1,
    private val shiftXImg: Float = 0f,
) : TrackList {
    override val size: Int = count

    override fun id(index: Int): Int = firstId + index

    override fun state(index: Int): TrackState = TrackState.CONFIRMED

    override fun isShown(index: Int): Boolean = true

    override fun headXImg(index: Int): Float = 100f + shiftXImg + 10f * index

    override fun headYImg(index: Int): Float = 80f

    override fun velocityXImgPerS(index: Int): Float = 0f

    override fun velocityYImgPerS(index: Int): Float = 0f

    override fun boxLeftImg(index: Int): Float = 50f + shiftXImg + 10f * index

    override fun boxTopImg(index: Int): Float = 60f

    override fun boxRightImg(index: Int): Float = 150f + shiftXImg + 10f * index

    override fun boxBottomImg(index: Int): Float = 360f

    override fun heightImg(index: Int): Float = 300f

    override fun score(index: Int): Float = 0.9f

    override fun isHeadEstimated(index: Int): Boolean = false

    override fun lostMs(index: Int): Float = 0f

    override fun matchedDetectionIndex(index: Int): Int = index
}
