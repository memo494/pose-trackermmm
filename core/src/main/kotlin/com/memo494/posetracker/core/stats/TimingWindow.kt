package com.memo494.posetracker.core.stats

import com.memo494.posetracker.core.TrackerConfig
import java.util.Arrays
import kotlin.math.ceil

/**
 * The newest [capacity] timing samples, in milliseconds, with percentiles over them.
 *
 * **Not thread-safe by design: one owner thread** (the capture thread) calls [add] and [percentile]. Other
 * threads read only the immutable `TimingSummary` that the owner builds from it.
 *
 * Both arrays are allocated once in the constructor. [add] allocates nothing; [percentile] allocates nothing
 * either (it sorts the reused scratch array, and only when a sample arrived since the last sort).
 *
 * **Percentile rule (nearest rank):** with `n` samples sorted ascending, the percentile `p` in [0, 1] is the
 * value at 1-based rank `ceil(p * n)`, rank 1 for `p = 0`. Nothing is interpolated, so the result is always
 * a sample that really was measured. An empty window, or a `p` outside [0, 1] or NaN, gives [Float.NaN].
 *
 * @param capacity number of samples kept, in [TrackerConfig.MIN_TIMING_WINDOW_FRAMES] to
 *   [TrackerConfig.MAX_TIMING_WINDOW_FRAMES]; the oldest sample is replaced when the window is full.
 */
class TimingWindow(
    val capacity: Int,
) {
    init {
        require(capacity in TrackerConfig.MIN_TIMING_WINDOW_FRAMES..TrackerConfig.MAX_TIMING_WINDOW_FRAMES) {
            "capacity $capacity outside [${TrackerConfig.MIN_TIMING_WINDOW_FRAMES}, ${TrackerConfig.MAX_TIMING_WINDOW_FRAMES}]"
        }
    }

    private val samples = FloatArray(capacity)
    private val scratch = FloatArray(capacity)
    private var next = 0
    private var scratchSorted = false

    /** Samples held now, at most [capacity]. */
    var count: Int = 0
        private set

    /**
     * Adds one sample, in milliseconds. NaN, infinite and negative values are dropped (not counted).
     *
     * @return `true` when the sample was kept.
     */
    fun add(valueMs: Float): Boolean {
        if (valueMs.isNaN() || valueMs.isInfinite() || valueMs < 0f) return false
        samples[next] = valueMs
        next = if (next + 1 == capacity) 0 else next + 1
        if (count < capacity) count++
        scratchSorted = false
        return true
    }

    /** The percentile [p] in [0, 1] of the samples held, in milliseconds (see the class KDoc); NaN when not defined. */
    fun percentile(p: Double): Float {
        if (count == 0 || p.isNaN() || p < 0.0 || p > 1.0) return Float.NaN
        if (!scratchSorted) {
            // The ring is filled from index 0 and only wraps once full, so its first `count` slots are the samples.
            System.arraycopy(samples, 0, scratch, 0, count)
            Arrays.sort(scratch, 0, count)
            scratchSorted = true
        }
        val rank = ceil(p * count).toInt().coerceIn(1, count)
        return scratch[rank - 1]
    }
}
