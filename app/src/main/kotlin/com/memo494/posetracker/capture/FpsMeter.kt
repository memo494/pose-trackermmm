package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig

/**
 * Frames per second over a sliding window, from a ring of the most recent frame times. The ring is one
 * `LongArray` allocated at construction; [onFrame] and [fps] allocate nothing. Not thread-safe (capture
 * thread only).
 *
 * The rate is capped at `capacity / window`: when more frames than [capacity] arrive inside the window,
 * the oldest ones are overwritten and no longer counted.
 *
 * @param windowNs length of the counting window, nanoseconds (a duration); must be positive.
 * @param capacity number of frame times remembered, frames; must be positive.
 */
internal class FpsMeter(
    private val windowNs: Long = TrackerConfig.FPS_WINDOW_NS,
    capacity: Int = TrackerConfig.FPS_RING_CAPACITY,
) {
    init {
        require(windowNs > 0L) { "windowNs $windowNs" }
        require(capacity > 0) { "capacity $capacity" }
    }

    private val timesNs = LongArray(capacity)
    private val windowSeconds = windowNs / TrackerConfig.NANOS_PER_SECOND
    private var nextIndex = 0
    private var count = 0

    /** Records one frame at [timeNs], nanoseconds (System.nanoTime() timebase). */
    fun onFrame(timeNs: Long) {
        timesNs[nextIndex] = timeNs
        nextIndex = if (nextIndex + 1 == timesNs.size) 0 else nextIndex + 1
        if (count < timesNs.size) count++
    }

    /**
     * Frames per second at [nowNs] (nanoseconds, same timebase): the recorded frames with
     * `0 <= nowNs - time <= window`, divided by the window in seconds. 0 when the last frame is older
     * than the window (idle screen) or nothing was recorded.
     */
    fun fps(nowNs: Long): Float {
        var inWindow = 0
        for (i in 0 until count) {
            val ageNs = nowNs - timesNs[i]
            if (ageNs in 0L..windowNs) inWindow++
        }
        return inWindow / windowSeconds
    }
}
