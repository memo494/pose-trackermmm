package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig

/**
 * An immutable copy of the shown tracks (`TrackList.isShown`) at one moment, in list order, for the overlay
 * (docs/plans/M3.md, M3b). The tracker's own [TrackList] is a reused view that is valid only until its next call,
 * so the pipeline takes a snapshot on the capture thread right after each call and publishes that.
 *
 * The snapshot also carries the size of the image its positions belong to ([widthImg] x [heightImg]); the view
 * draws it only when that equals the capture size of the state (fail closed, like the raw detections).
 *
 * Positions are Img pixels. The arrays are private and never handed out; every getter throws
 * `IndexOutOfBoundsException` for an index outside `0 until count`. Non-finite values are copied as they are
 * (the tracker already removes tracks that are not healthy).
 *
 * Layout: `ids[i]`; `flags[2 * i]` = lost, `flags[2 * i + 1]` = head estimated; `values[9 * i ..]` = head x, head y,
 * box left, top, right, bottom, lost time in ms (0 unless lost), lead time in ms (0 when no lead was applied), filtered
 * speed in track heights per second (0 unless smoothed).
 */
class TrackSnapshot private constructor(
    /** Number of tracks in the snapshot. */
    val count: Int,
    /** Width of the image the positions belong to, Img pixels; 0 for [EMPTY]. */
    val widthImg: Int,
    /** Height of the image the positions belong to, Img pixels; 0 for [EMPTY]. */
    val heightImg: Int,
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

    /**
     * Milliseconds the track has been without a matching detection; 0 unless it is `LOST`. For an idle snapshot
     * ([ofIdle]) the idle time since the newest frame is included, capped at the limit the tracker keeps a track.
     */
    fun lostMs(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + LOST_MS]

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

    /**
     * The lead time in milliseconds that moved the head point of entry [index] ahead of the smoothed point (the display
     * lead, `gSpeed * gStop * L`, before the length cap); 0 when no lead was applied (and for [of] and [ofIdle]).
     */
    fun leadMs(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + LEAD_MS]

    /** The smoother's filtered speed of entry [index] in track heights per second; 0 unless it came from [ofSmoothed]. */
    fun speedHps(index: Int): Float = values[VALUES_PER_TRACK * checked(index) + SPEED_HPS]

    /** Index of the track with [id] in this snapshot, or -1 when it is not in it. Allocation-free. */
    fun indexOfId(id: Int): Int {
        for (i in 0 until count) {
            if (ids[i] == id) return i
        }
        return -1
    }

    private fun checked(index: Int): Int {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, count $count")
        return index
    }

    companion object {
        /** The snapshot without tracks (image size 0 x 0); shared, so nothing is allocated while nobody is tracked. */
        val EMPTY: TrackSnapshot = TrackSnapshot(0, 0, 0, IntArray(0), BooleanArray(0), FloatArray(0))

        private const val FLAGS_PER_TRACK = 2
        private const val LOST = 0
        private const val ESTIMATED = 1
        private const val VALUES_PER_TRACK = 9
        private const val HEAD_X = 0
        private const val HEAD_Y = 1
        private const val BOX_LEFT = 2
        private const val BOX_TOP = 3
        private const val BOX_RIGHT = 4
        private const val BOX_BOTTOM = 5
        private const val LOST_MS = 6
        private const val LEAD_MS = 7
        private const val SPEED_HPS = 8
        private const val MS_PER_S = 1000f
        private const val PLAIN = 0
        private const val SMOOTHED = 1
        private const val IDLE = 2

        /**
         * Copies the shown entries of [tracks], in list order, for a frame of [widthImg] x [heightImg] Img pixels.
         * Returns the shared [EMPTY] (no allocation) when none
         * is shown; otherwise allocates the snapshot and three arrays sized to the shown count (once per processed
         * frame, on the capture thread).
         */
        fun of(
            tracks: TrackList,
            widthImg: Int,
            heightImg: Int,
        ): TrackSnapshot = build(tracks, widthImg, heightImg, null, 0L, PLAIN, 0f, 0f, 0f)

        /**
         * Like [of], with the head point of every shown track passed through [smoother] for the frame taken at
         * [timestampNs] (the timestamp the tracker was given): the smoother runs `begin`, `smooth` for each shown
         * track and `end` (also when none is shown, so the slots of vanished tracks are freed). The box is moved by
         * the same delta as the head point, so ring and brackets stay together. Capture thread; allocates like [of].
         *
         * The display lead (`TrackerConfig.displayLeadEnabled`, see `DisplayLead`) then moves the head of a fast, confirmed
         * track with a measured head ahead along its velocity; [resultAgeMs] is the time since the frame was taken (a
         * value that is not positive or not a number counts as 0). The lead never reaches the tracker or its list.
         */
        fun ofSmoothed(
            tracks: TrackList,
            widthImg: Int,
            heightImg: Int,
            smoother: HeadSmoother,
            timestampNs: Long,
            resultAgeMs: Float = 0f,
        ): TrackSnapshot = build(tracks, widthImg, heightImg, smoother, timestampNs, SMOOTHED, 0f, 0f, resultAgeMs)

        /**
         * Like [of] for the idle republish: a shown track the [smoother] knows keeps its last drawn head point
         * (smoothed, plus the lead that was on screen; the box moved by the same delta), a track it does not know
         * (newly promoted) passes through unsmoothed.
         * The smoother is not advanced. A `LOST` track's [TrackSnapshot.lostMs] is its lost time plus [idleMs] (the
         * time since the newest frame; a negative or non-numeric value counts as 0), capped at [maxLostMs]
         * (`trackMaxLostMs`).
         */
        fun ofIdle(
            tracks: TrackList,
            widthImg: Int,
            heightImg: Int,
            smoother: HeadSmoother,
            idleMs: Float,
            maxLostMs: Float,
        ): TrackSnapshot = build(tracks, widthImg, heightImg, smoother, 0L, IDLE, if (idleMs > 0f) idleMs else 0f, maxLostMs, 0f)

        private fun build(
            tracks: TrackList,
            widthImg: Int,
            heightImg: Int,
            smoother: HeadSmoother?,
            timestampNs: Long,
            mode: Int,
            idleMs: Float,
            maxLostMs: Float,
            resultAgeMs: Float,
        ): TrackSnapshot {
            if (mode == SMOOTHED) smoother?.begin(timestampNs)
            var shown = 0
            for (i in 0 until tracks.size) {
                if (!tracks.isShown(i)) continue
                shown++
                if (mode == SMOOTHED) smoother?.mark(tracks.id(i))
            }
            if (shown == 0) {
                if (mode == SMOOTHED) smoother?.end()
                return EMPTY
            }
            val ids = IntArray(shown)
            val flags = BooleanArray(shown * FLAGS_PER_TRACK)
            val values = FloatArray(shown * VALUES_PER_TRACK)
            var next = 0
            for (i in 0 until tracks.size) {
                if (!tracks.isShown(i)) continue
                ids[next] = tracks.id(i)
                val lost = tracks.state(i) == TrackState.LOST
                flags[FLAGS_PER_TRACK * next + LOST] = lost
                flags[FLAGS_PER_TRACK * next + ESTIMATED] = tracks.isHeadEstimated(i)
                val base = VALUES_PER_TRACK * next
                val rawXImg = tracks.headXImg(i)
                val rawYImg = tracks.headYImg(i)
                var headXImg = rawXImg
                var headYImg = rawYImg
                if (smoother != null && mode == SMOOTHED) {
                    smoother.smooth(ids[next], rawXImg, rawYImg, tracks.heightImg(i))
                    headXImg = smoother.outXImg
                    headYImg = smoother.outYImg
                    values[base + SPEED_HPS] = smoother.outSpeedHps
                    val leadMs = leadTimeFor(tracks, i, smoother, resultAgeMs)
                    val seconds = leadMs / MS_PER_S
                    val capImg = smoother.config.displayLeadMaxRatio * tracks.heightImg(i)
                    var targetXImg = tracks.velocityXImgPerS(i) * seconds
                    var targetYImg = tracks.velocityYImgPerS(i) * seconds
                    val scale = DisplayLead.capScale(targetXImg, targetYImg, capImg)
                    targetXImg *= scale
                    targetYImg *= scale
                    smoother.settleLead(
                        if (leadMs > 0f) targetXImg else 0f,
                        if (leadMs > 0f) targetYImg else 0f,
                        capImg,
                        fade = leadGated(tracks, i, smoother.config),
                    )
                    headXImg += smoother.outLeadXImg
                    headYImg += smoother.outLeadYImg
                    if (leadMs > 0f && scale > 0f) values[base + LEAD_MS] = leadMs
                } else if (smoother != null && smoother.recall(ids[next])) {
                    headXImg = smoother.outXImg + smoother.outLeadXImg
                    headYImg = smoother.outYImg + smoother.outLeadYImg
                }
                val deltaXImg = deltaOf(headXImg, rawXImg)
                val deltaYImg = deltaOf(headYImg, rawYImg)
                values[base + HEAD_X] = headXImg
                values[base + HEAD_Y] = headYImg
                values[base + BOX_LEFT] = tracks.boxLeftImg(i) + deltaXImg
                values[base + BOX_TOP] = tracks.boxTopImg(i) + deltaYImg
                values[base + BOX_RIGHT] = tracks.boxRightImg(i) + deltaXImg
                values[base + BOX_BOTTOM] = tracks.boxBottomImg(i) + deltaYImg
                values[base + LOST_MS] = if (lost) lostMsOf(tracks.lostMs(i), idleMs, maxLostMs, mode == IDLE) else 0f
                next++
            }
            if (mode == SMOOTHED) smoother?.end()
            return TrackSnapshot(shown, widthImg, heightImg, ids, flags, values)
        }

        /** True when a gate switches the lead off for track [i]: disabled, not confirmed (lost) or an estimated head. */
        private fun leadGated(
            tracks: TrackList,
            i: Int,
            config: TrackerConfig,
        ): Boolean = !config.displayLeadEnabled || tracks.state(i) != TrackState.CONFIRMED || tracks.isHeadEstimated(i)

        /**
         * The lead time `gSpeed * gStop * L` in milliseconds for shown track [i] right after `smoother.smooth`, or 0 when
         * the lead is off or must not apply: a lost track, an estimated head, a track not confirmed, a first sample.
         */
        private fun leadTimeFor(
            tracks: TrackList,
            i: Int,
            smoother: HeadSmoother,
            resultAgeMs: Float,
        ): Float {
            val config = smoother.config
            if (leadGated(tracks, i, config)) return 0f
            val speedGain = DisplayLead.speedGain(smoother.outSpeedHps, config)
            if (speedGain <= 0f) return 0f
            val stopGain =
                DisplayLead.stopGain(
                    tracks.velocityXImgPerS(i),
                    tracks.velocityYImgPerS(i),
                    tracks.measuredVelocityXImgPerS(i),
                    tracks.measuredVelocityYImgPerS(i),
                    config,
                )
            return speedGain * stopGain * DisplayLead.leadTimeMs(resultAgeMs, smoother.outIntervalMs, config)
        }

        /** [lostMs], plus [idleMs] capped at [maxLostMs] when [idle]. */
        private fun lostMsOf(
            lostMs: Float,
            idleMs: Float,
            maxLostMs: Float,
            idle: Boolean,
        ): Float {
            if (!idle) return lostMs
            val total = lostMs + idleMs
            return if (total > maxLostMs) maxLostMs else total
        }

        /** [smoothed] minus [raw], or 0 when that is not a finite number (the box then stays where it is). */
        private fun deltaOf(
            smoothed: Float,
            raw: Float,
        ): Float {
            val delta = smoothed - raw
            return if (delta.isFinite()) delta else 0f
        }
    }
}
