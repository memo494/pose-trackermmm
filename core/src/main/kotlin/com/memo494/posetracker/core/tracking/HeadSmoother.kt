package com.memo494.posetracker.core.tracking

import com.memo494.posetracker.core.TrackerConfig
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot

/**
 * The display smoother (docs/plans/M4.md, Decision 2): a OneEuro filter per shown track on the head point, in Img
 * pixels, run after the tracker and before the snapshot. The tracker's own state is never touched.
 *
 * Per axis the filter low-passes the head point with a cutoff that grows with the head point's speed:
 * `alpha(fc, dt) = r / (r + 1)` with `r = 2 * pi * fc * dt` (`fc` in hertz, `dt` in seconds); the speed is the
 * low-passed derivative (cutoff [TrackerConfig.smoothDerivativeCutoffHz]); the cutoff is
 * `fc = smoothMinCutoffHz + smoothBeta * speed / heightImg`, with the speed of x and y combined (`hypot`), so a
 * still head is steady and a moving one follows closely. The first sample of an id passes through.
 *
 * Use per processed frame: [begin] with the frame's timestamp, [smooth] once for each shown track, [end]. A track
 * that was not smoothed between the two loses its slot, so an id that comes back starts again as a first sample.
 * When the pool is full, a new id takes the slot of an id not yet smoothed in the frame. [recall] reads the stored
 * point of an id, and the lead on top of it, without advancing anything (the idle republish). [reset] forgets every
 * id (call it wherever the tracker is reset).
 *
 * Holds a pool of [TrackerConfig.maxTracks] slots keyed by track id; no call allocates after construction. Results
 * are written to [outXImg] and [outYImg]. With [TrackerConfig.smoothEnabled] `false` it is the identity. Not
 * thread-safe: one thread (the capture thread) only.
 */
class HeadSmoother(
    internal val config: TrackerConfig,
) {
    private val capacity = config.maxTracks
    private val ids = IntArray(capacity)
    private val inUse = BooleanArray(capacity)
    private val seen = BooleanArray(capacity)
    private val storedXImg = DoubleArray(capacity)
    private val storedYImg = DoubleArray(capacity)
    private val dxImgPerS = DoubleArray(capacity)
    private val dyImgPerS = DoubleArray(capacity)
    private val lastNs = LongArray(capacity)
    private val leadXImg = DoubleArray(capacity)
    private val leadYImg = DoubleArray(capacity)
    private var lastSlot = -1
    private var frameNs = 0L

    /** Head x of the newest [smooth] or successful [recall] call, Img pixels. */
    var outXImg: Float = 0f
        private set

    /** Head y of the newest [smooth] or successful [recall] call, Img pixels. */
    var outYImg: Float = 0f
        private set

    /**
     * The filtered speed of the newest [smooth] call in track heights per second (the speed the cutoff was chosen
     * from); 0 for a first sample, a point passed through, a repeated frame time and a disabled smoother. The
     * display lead (`DisplayLead`) reads it.
     */
    var outSpeedHps: Float = 0f
        private set

    /** Milliseconds between the newest [smooth] call's frame and the id's frame before it; 0 where [outSpeedHps] is 0. */
    var outIntervalMs: Float = 0f
        private set

    /** Lead x of the newest [settleLead] or successful [recall] call, Img pixels: what is drawn on top of [outXImg]. */
    var outLeadXImg: Float = 0f
        private set

    /** Lead y of the newest [settleLead] or successful [recall] call, Img pixels. */
    var outLeadYImg: Float = 0f
        private set

    /** Number of ids that currently hold a slot. */
    val activeCount: Int
        get() {
            var count = 0
            for (i in 0 until capacity) {
                if (inUse[i]) count++
            }
            return count
        }

    /** Starts a frame taken at [timestampNs] (the timestamp the tracker was given). */
    fun begin(timestampNs: Long) {
        frameNs = timestampNs
        for (i in 0 until capacity) seen[i] = false
    }

    /**
     * Smooths the head point ([xImg], [yImg]) of track [id], whose box is [heightImg] high, and writes the result to
     * [outXImg] / [outYImg]. The first sample of an id, a point that is not finite, a height that is not a positive
     * finite number, a disabled smoother and a full pool all pass the point through unchanged (a non-finite
     * point never enters the state). A frame time that is not after the id's last one (`dt <= 0`) returns the stored
     * point without updating.
     */
    fun smooth(
        id: Int,
        xImg: Float,
        yImg: Float,
        heightImg: Float,
    ) {
        outXImg = xImg
        outYImg = yImg
        outSpeedHps = 0f
        outIntervalMs = 0f
        outLeadXImg = 0f
        outLeadYImg = 0f
        lastSlot = -1
        if (!config.smoothEnabled) return
        var slot = find(id)
        lastSlot = slot
        if (!xImg.isFinite() || !yImg.isFinite() || !heightImg.isFinite() || heightImg <= 0f) {
            if (slot >= 0) seen[slot] = true
            return
        }
        if (slot < 0) {
            slot = freeSlot()
            if (slot < 0) return
            ids[slot] = id
            lastSlot = slot
            leadXImg[slot] = 0.0
            leadYImg[slot] = 0.0
            inUse[slot] = true
            seen[slot] = true
            storedXImg[slot] = xImg.toDouble()
            storedYImg[slot] = yImg.toDouble()
            dxImgPerS[slot] = 0.0
            dyImgPerS[slot] = 0.0
            lastNs[slot] = frameNs
            return
        }
        seen[slot] = true
        val dtS = (frameNs - lastNs[slot]).toDouble() / TrackerConfig.NANOS_PER_SECOND.toDouble()
        if (dtS <= 0.0) {
            outXImg = storedXImg[slot].toFloat()
            outYImg = storedYImg[slot].toFloat()
            return
        }
        lastNs[slot] = frameNs
        val derivativeAlpha = alpha(config.smoothDerivativeCutoffHz.toDouble(), dtS)
        dxImgPerS[slot] += derivativeAlpha * ((xImg - storedXImg[slot]) / dtS - dxImgPerS[slot])
        dyImgPerS[slot] += derivativeAlpha * ((yImg - storedYImg[slot]) / dtS - dyImgPerS[slot])
        val speedHeightsPerS = hypot(dxImgPerS[slot], dyImgPerS[slot]) / heightImg
        outSpeedHps = speedHeightsPerS.toFloat()
        outIntervalMs = (dtS * MS_PER_S).toFloat()
        val cutoffHz = config.smoothMinCutoffHz + config.smoothBeta * speedHeightsPerS
        val a = alpha(cutoffHz, dtS)
        storedXImg[slot] += a * (xImg - storedXImg[slot])
        storedYImg[slot] += a * (yImg - storedYImg[slot])
        outXImg = storedXImg[slot].toFloat()
        outYImg = storedYImg[slot].toFloat()
    }

    /**
     * Sets the lead of the track of the newest [smooth] call: the target ([targetXImg], [targetYImg], Img pixels; a
     * value that is not finite counts as 0) or, when [fade] is true, the lead that was on screen for the track decayed
     * over this frame's interval with `displayLeadFadeMs` (cut at once when that is 0) if that is longer. So a lead
     * that is switched off by a gate (a miss, an estimated head) fades out instead of dropping to 0, and a lead never
     * grows by fading. With [fade] false (a stop or a turn of the measured head) the target is taken as it is. After a
     * frame time that is not after the last one, or a point that is not finite (no interval), the lead on screen
     * decays to 0. The result is limited to [maxLengthImg] and written to [outLeadXImg] / [outLeadYImg]; it is kept
     * for the next frame and for [recall] (the idle republish). A call after a [smooth] that gave no slot (smoother
     * off, full pool), after [recall], [end] or [reset] passes the target through and keeps the stored lead.
     */
    fun settleLead(
        targetXImg: Float,
        targetYImg: Float,
        maxLengthImg: Float,
        fade: Boolean,
    ) {
        var x = if (targetXImg.isFinite()) targetXImg.toDouble() else 0.0
        var y = if (targetYImg.isFinite()) targetYImg.toDouble() else 0.0
        val slot = lastSlot
        if (slot >= 0) {
            val fadeMs = config.displayLeadFadeMs
            val decay =
                if (!fade) {
                    0.0
                } else if (fadeMs > 0f) {
                    exp(-outIntervalMs.toDouble() / fadeMs)
                } else {
                    0.0
                }
            val fadedX = leadXImg[slot] * decay
            val fadedY = leadYImg[slot] * decay
            if (fadedX * fadedX + fadedY * fadedY > x * x + y * y) {
                x = fadedX
                y = fadedY
            }
            val length = hypot(x, y)
            if (length > maxLengthImg) {
                val scale = if (maxLengthImg > 0f) maxLengthImg / length else 0.0
                x *= scale
                y *= scale
            }
            leadXImg[slot] = x
            leadYImg[slot] = y
        }
        outLeadXImg = x.toFloat()
        outLeadYImg = y.toFloat()
    }

    /**
     * Marks [id] as shown in this frame before any [smooth], so the full-pool fallback never takes its slot. An id
     * without a slot is ignored.
     */
    fun mark(id: Int) {
        val slot = find(id)
        if (slot >= 0) seen[slot] = true
    }

    /** Frees the slot of every id that was not smoothed since [begin]. */
    fun end() {
        lastSlot = -1
        for (i in 0 until capacity) {
            if (inUse[i] && !seen[i]) inUse[i] = false
            seen[i] = false
        }
    }

    /**
     * Writes the stored point of [id] to [outXImg] / [outYImg] and returns true; returns false (and leaves the
     * outputs alone) when the id has no slot or the smoother is disabled. Changes no state.
     */
    fun recall(id: Int): Boolean {
        lastSlot = -1
        if (!config.smoothEnabled) return false
        val slot = find(id)
        if (slot < 0) return false
        outXImg = storedXImg[slot].toFloat()
        outYImg = storedYImg[slot].toFloat()
        outLeadXImg = leadXImg[slot].toFloat()
        outLeadYImg = leadYImg[slot].toFloat()
        return true
    }

    /** Forgets every id: the next sample of each is a first sample. */
    fun reset() {
        lastSlot = -1
        for (i in 0 until capacity) {
            inUse[i] = false
            seen[i] = false
        }
    }

    private fun find(id: Int): Int {
        for (i in 0 until capacity) {
            if (inUse[i] && ids[i] == id) return i
        }
        return -1
    }

    /**
     * An unused slot, else the slot of an id not smoothed yet in this frame (its track is gone when the pool is
     * full, because the tracker never holds more tracks than the pool has slots), else -1.
     */
    private fun freeSlot(): Int {
        for (i in 0 until capacity) {
            if (!inUse[i]) return i
        }
        for (i in 0 until capacity) {
            if (!seen[i]) return i
        }
        return -1
    }

    private fun alpha(
        cutoffHz: Double,
        dtS: Double,
    ): Double {
        val r = 2.0 * PI * cutoffHz * dtS
        return r / (r + 1.0)
    }

    private companion object {
        const val MS_PER_S = 1000.0
    }
}
