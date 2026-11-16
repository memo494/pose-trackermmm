package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.DetectionFrame

/**
 * Multi-person tracker: detections in, stable tracks with ids and a smoothed head point out
 * (docs/plans/M3.md, M3a). Not thread-safe: one capture thread calls it.
 *
 * **Skeleton, implemented in M3a T6:** today [update] and [onIdle] return an empty list and [reset] does nothing.
 * No call allocates.
 */
class Tracker(
    private val config: TrackerConfig,
) {
    private val emptyTracks: TrackList = EmptyTrackList

    /** Feeds one frame of detections and returns the tracks afterwards (skeleton: always empty). */
    fun update(frame: DetectionFrame): TrackList = emptyTracks

    /**
     * Tells the tracker that [idleNs] nanoseconds have passed since the newest frame arrived (measured by the
     * caller) and returns the tracks afterwards (skeleton: always empty).
     */
    fun onIdle(idleNs: Long): TrackList = emptyTracks

    /** Forgets every track and the last timestamp; the id counter goes on (skeleton: does nothing). */
    fun reset() = Unit

    private object EmptyTrackList : TrackList {
        override val size: Int = 0

        override fun id(index: Int): Int = outOfRange(index)

        override fun state(index: Int): TrackState = outOfRange(index)

        override fun isShown(index: Int): Boolean = outOfRange(index)

        override fun headXImg(index: Int): Float = outOfRange(index)

        override fun headYImg(index: Int): Float = outOfRange(index)

        override fun velocityXImgPerS(index: Int): Float = outOfRange(index)

        override fun velocityYImgPerS(index: Int): Float = outOfRange(index)

        override fun boxLeftImg(index: Int): Float = outOfRange(index)

        override fun boxTopImg(index: Int): Float = outOfRange(index)

        override fun boxRightImg(index: Int): Float = outOfRange(index)

        override fun boxBottomImg(index: Int): Float = outOfRange(index)

        override fun heightImg(index: Int): Float = outOfRange(index)

        override fun score(index: Int): Float = outOfRange(index)

        override fun isHeadEstimated(index: Int): Boolean = outOfRange(index)

        override fun lostMs(index: Int): Float = outOfRange(index)

        private fun outOfRange(index: Int): Nothing = throw IndexOutOfBoundsException("index $index, size 0")
    }
}
