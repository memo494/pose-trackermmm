package com.memo494.posetracker.core.capture

import com.memo494.posetracker.core.model.TimestampSource

/**
 * Timestamp guard for captured frames (ARCHITECTURE §4.1, Consult C1-C). The preferred frame time is
 * `Image.timestamp` (CLOCK_MONOTONIC, the same base as `System.nanoTime()`); it is accepted only if
 * `0 <= now - ts <= guard`, otherwise the arrival time taken at `acquireLatestImage` is used and the
 * HUD flags the source. Allocation-free: primitive-returning functions plus the [TimestampSource] enum.
 *
 * All `*Ns` arguments and results are in the System.nanoTime() timebase; the guard is a duration.
 */
object FrameTimestamp {
    /**
     * `true` if [imageTimestampNs] is a non-negative stamp that is not in the future of [nowNs] and not
     * older than [guardNs]. A negative stamp is rejected outright (also avoids overflow in the age).
     */
    fun isImageStampValid(
        imageTimestampNs: Long,
        nowNs: Long,
        guardNs: Long,
    ): Boolean {
        if (imageTimestampNs < 0L) return false
        val ageNs = nowNs - imageTimestampNs
        return ageNs in 0L..guardNs
    }

    /** The frame time to use: [imageTimestampNs] if valid, else the fallback [acquireNs]. */
    fun resolveNs(
        imageTimestampNs: Long,
        nowNs: Long,
        acquireNs: Long,
        guardNs: Long,
    ): Long = if (isImageStampValid(imageTimestampNs, nowNs, guardNs)) imageTimestampNs else acquireNs

    /** Where [resolveNs] got its answer: [TimestampSource.IMAGE] or [TimestampSource.ARRIVAL]. */
    fun sourceOf(
        imageTimestampNs: Long,
        nowNs: Long,
        guardNs: Long,
    ): TimestampSource = if (isImageStampValid(imageTimestampNs, nowNs, guardNs)) TimestampSource.IMAGE else TimestampSource.ARRIVAL
}
