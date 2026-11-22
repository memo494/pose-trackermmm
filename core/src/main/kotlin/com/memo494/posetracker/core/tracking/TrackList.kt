package com.memo494.posetracker.core.tracking

/**
 * The tracks a [Tracker] holds after its newest call: a reused view, valid until the next call to
 * [Tracker.update], [Tracker.onIdle] or [Tracker.reset]; entries are in creation order. Positions and sizes are in
 * Img pixels, times in milliseconds, velocities in Img pixels per second. An index outside `0 until size` throws
 * `IndexOutOfBoundsException`.
 */
interface TrackList {
    /** Number of tracks (tentative, confirmed and lost). */
    val size: Int

    /** Track id, starting at 1; never reused. */
    fun id(index: Int): Int

    /** Lifecycle state of the track. */
    fun state(index: Int): TrackState

    /** True for `CONFIRMED` and `LOST`: the track is drawn. */
    fun isShown(index: Int): Boolean

    /** Head point x, Img pixels. */
    fun headXImg(index: Int): Float

    /** Head point y, Img pixels. */
    fun headYImg(index: Int): Float

    /** Head point velocity x, Img pixels per second. */
    fun velocityXImgPerS(index: Int): Float

    /** Head point velocity y, Img pixels per second. */
    fun velocityYImgPerS(index: Int): Float

    /**
     * Velocity x of the raw measured head point between its last two measurements (the step divided by the time
     * between the two frames), Img pixels per second. 0 when the track was not matched in the newest update (lost,
     * tentative and missed, a call that is not an update) or has had one measurement only. The display lead reads it
     * to see that a head has stopped before the filter does; the default is 0 (no step known).
     */
    fun measuredVelocityXImgPerS(index: Int): Float = 0f

    /** Velocity y of the raw measured head point; see [measuredVelocityXImgPerS]. */
    fun measuredVelocityYImgPerS(index: Int): Float = 0f

    /** Track box left edge, Img pixels. */
    fun boxLeftImg(index: Int): Float

    /** Track box top edge, Img pixels. */
    fun boxTopImg(index: Int): Float

    /** Track box right edge, Img pixels. */
    fun boxRightImg(index: Int): Float

    /** Track box bottom edge, Img pixels. */
    fun boxBottomImg(index: Int): Float

    /** Track box height, Img pixels. */
    fun heightImg(index: Int): Float

    /** Score of the newest matched detection, unitless in [0, 1]. */
    fun score(index: Int): Float

    /** True when the newest head point was the box fallback (fewer than the needed head keypoints were visible). */
    fun isHeadEstimated(index: Int): Boolean

    /** Time in milliseconds the track has been without a matching detection; 0 unless it is `LOST`. */
    fun lostMs(index: Int): Float

    /**
     * Index, in the detection list of the newest [Tracker.update], of the detection this track was matched to (or
     * started on) in that call; -1 when it was not matched in it (lost, tentative and missed), after a call that
     * ignored the frame (`dt <= 0`), and for every call that is not an update ([Tracker.onIdle]
     * keeps what the last update set). Lets the caller read pixels or keypoints of exactly this person.
     */
    fun matchedDetectionIndex(index: Int): Int
}
