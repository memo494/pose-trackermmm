package com.memo494.posetracker.core.relock

import com.memo494.posetracker.core.TrackerConfig

/**
 * The decisions of re-locking (docs/plans/M4.md, M4c), without any pixels: how long a lock whose track was removed
 * waits for the person ("pending"), and whether one new track is that person.
 *
 * Time is injected as frame timestamps (nanoseconds, the `System.nanoTime()` timebase of the frames), so a still
 * screen or a paused video does not use up the memory: only the time the frames themselves cover counts. A frame
 * time that goes backwards counts as no time passed.
 *
 * [judge] fails closed: the candidate is accepted only when it is close to the remembered signature
 * (`relockMaxDistance`) **and** every other shown track is farther than it by at least `relockMinMargin`; a tie, a
 * doubt or a number that is not a number is a rejection. Allocation-free; one instance belongs to one thread.
 */
class RelockMatcher(
    private val config: TrackerConfig,
) {
    /** What [judge] decided. */
    enum class Verdict {
        /** The candidate is the person: move the lock. */
        ACCEPT,

        /** The candidate's distance is above `relockMaxDistance` (or not a number). */
        TOO_FAR,

        /** Another shown track is not farther than the candidate by `relockMinMargin`. */
        AMBIGUOUS,
    }

    private var sinceNs = 0L

    /** True from [startPending] until [cancel]. */
    var isPending: Boolean = false
        private set

    /** Starts waiting; the memory runs from [frameTimestampNs], the timestamp of the newest frame. */
    fun startPending(frameTimestampNs: Long) {
        sinceNs = frameTimestampNs
        isPending = true
    }

    /** Ends the waiting (the person was found, the lock was ended, the tracker was reset). */
    fun cancel() {
        isPending = false
    }

    /**
     * True when this matcher is pending and the frames have covered more than `relockMemoryMs` since
     * [startPending]; false when it is not pending.
     */
    fun isExpired(frameTimestampNs: Long): Boolean = isPending && frameTimestampNs - sinceNs > config.relockMemoryNs

    /**
     * Judges a new track whose signature is [candidateDistance] away from the remembered one, while the nearest of
     * the other shown tracks is [bestOtherDistance] away (use [Float.POSITIVE_INFINITY] when there is no other).
     * Distances are Bhattacharyya distances, 0..1.
     */
    fun judge(
        candidateDistance: Float,
        bestOtherDistance: Float,
    ): Verdict =
        when {
            !(candidateDistance <= config.relockMaxDistance) -> Verdict.TOO_FAR
            !(bestOtherDistance - candidateDistance >= config.relockMinMargin) -> Verdict.AMBIGUOUS
            else -> Verdict.ACCEPT
        }
}
