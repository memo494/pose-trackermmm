package com.memo494.posetracker.capture

/**
 * Source of `System.nanoTime()`-style time, injected so tests can control it. A primitive-returning
 * interface on purpose: a Kotlin `() -> Long` boxes its result on every call, which would be a
 * per-frame allocation.
 */
internal fun interface NanoClock {
    /** Current monotonic time, nanoseconds (System.nanoTime() timebase). */
    fun nowNs(): Long
}
