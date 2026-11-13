package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.core.TrackerConfig

/**
 * Fixed-size log kept in memory for the diagnostics zip and the in-app log screen. When it is full, adding a
 * line drops the oldest one. Thread-safe: any thread may [add] or take a [snapshot].
 *
 * @param capacity most lines kept; must be positive.
 */
internal class RingLog(
    private val capacity: Int = TrackerConfig.LOG_RING_CAPACITY,
) {
    private val lines = ArrayDeque<String>()

    init {
        require(capacity > 0) { "capacity $capacity must be positive" }
    }

    /** Appends [line]; it is cut to [TrackerConfig.LOG_LINE_MAX_CHARS] characters first. */
    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line.take(TrackerConfig.LOG_LINE_MAX_CHARS))
    }

    /** A copy of the kept lines, oldest first. Later [add] calls do not change it. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
