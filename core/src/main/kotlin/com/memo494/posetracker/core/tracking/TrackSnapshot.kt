package com.memo494.posetracker.core.tracking

/**
 * An immutable copy of the shown tracks (`TrackList.isShown`) at one moment, in list order, for the overlay
 * (docs/plans/M3.md, M3b). The tracker's own [TrackList] is a reused view that is valid only until its next call,
 * so the pipeline takes a snapshot on the capture thread right after each call and publishes that.
 *
 * Positions are Img pixels. The arrays are private and never handed out; every getter throws
 * `IndexOutOfBoundsException` for an index outside `0 until count`. Non-finite values are copied as they are
 * (the tracker already removes tracks that are not healthy).
 *
 * Layout: `ids[i]`; `flags[2 * i]` = lost, `flags[2 * i + 1]` = head estimated; `values[6 * i ..]` = head x, head y,
 * box left, top, right, bottom.
 */
class TrackSnapshot private constructor(
    /** Number of tracks in the snapshot. */
    val count: Int,
    private val ids: IntArray,
    private val flags: BooleanArray,
    private val values: FloatArray,
) {
    /** Track id of entry [index], starting at 1; never reused by the tracker. */
    fun id(index: Int): Int = ids[checked(index)]

    /** True when the track was `LOST` (shown on its prediction, without a matching detection in the newest frame). */
    fun isLost(index: Int): Boolean = flags[FLAGS_PER_TRACK * checked(index) + LOST]

    /** True when the head point was the box fallback (fewer than the needed head keypoints were visible). */
    fun isHeadEstimated(index: Int): Boolean = flags[FLAGS_PER_TRACK * checked(index) + ESTIMATED]

    /** Head point x, Img pixels. */
    fun headXImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + HEAD_X]

    /** Head point y, Img pixels. */
    fun headYImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + HEAD_Y]

    /** Track box left edge, Img pixels. */
    fun boxLeftImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + BOX_LEFT]

    /** Track box top edge, Img pixels. */
    fun boxTopImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + BOX_TOP]

    /** Track box right edge, Img pixels. */
    fun boxRightImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + BOX_RIGHT]

    /** Track box bottom edge, Img pixels. */
    fun boxBottomImg(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + BOX_BOTTOM]

    private fun checked(index: Int): Int {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, count $count")
        return index
    }

    companion object {
        /** The snapshot without tracks; shared, so nothing is allocated while nobody is tracked. */
        val EMPTY: TrackSnapshot = TrackSnapshot(0, IntArray(0), BooleanArray(0), FloatArray(0))

        private const val FLAGS_PER_TRACK = 2
        private const val LOST = 0
        private const val ESTIMATED = 1
        private const val VALUES_PER_TRACK = 6
        private const val HEAD_X = 0
        private const val HEAD_Y = 1
        private const val BOX_LEFT = 2
        private const val BOX_TOP = 3
        private const val BOX_RIGHT = 4
        private const val BOX_BOTTOM = 5

        /**
         * Copies the shown entries of [tracks], in list order. Returns the shared [EMPTY] (no allocation) when none
         * is shown; otherwise allocates the snapshot and three arrays sized to the shown count (once per processed
         * frame, on the capture thread).
         */
        fun of(tracks: TrackList): TrackSnapshot {
            var shown = 0
            for (i in 0 until tracks.size) {
                if (tracks.isShown(i)) shown++
            }
            if (shown == 0) return EMPTY
            val ids = IntArray(shown)
            val flags = BooleanArray(shown * FLAGS_PER_TRACK)
            val values = FloatArray(shown * VALUES_PER_TRACK)
            var next = 0
            for (i in 0 until tracks.size) {
                if (!tracks.isShown(i)) continue
                ids[next] = tracks.id(i)
                flags[FLAGS_PER_TRACK * next + LOST] = tracks.state(i) == TrackState.LOST
                flags[FLAGS_PER_TRACK * next + ESTIMATED] = tracks.isHeadEstimated(i)
                val base = VALUES_PER_TRACK * next
                values[base + HEAD_X] = tracks.headXImg(i)
                values[base + HEAD_Y] = tracks.headYImg(i)
                values[base + BOX_LEFT] = tracks.boxLeftImg(i)
                values[base + BOX_TOP] = tracks.boxTopImg(i)
                values[base + BOX_RIGHT] = tracks.boxRightImg(i)
                values[base + BOX_BOTTOM] = tracks.boxBottomImg(i)
                next++
            }
            return TrackSnapshot(shown, ids, flags, values)
        }
    }
}
