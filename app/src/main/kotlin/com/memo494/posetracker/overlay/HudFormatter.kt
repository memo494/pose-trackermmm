package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.detector.Accelerator
import kotlin.math.roundToInt

/**
 * Text of the debug HUD (ARCHITECTURE §6.5). Pure Kotlin and allocation-free: every function writes into
 * a caller-owned [CharArray] and returns the number of characters written, so the draw phase can keep one
 * buffer per line and call `Canvas.drawText(char[], …)`. Text longer than the buffer is cut, never thrown.
 *
 * The version + git SHA line comes from `BuildLabel` and is converted once by the view.
 */
object HudFormatter {
    /** Text of the red banner shown when the detector runs on the CPU (ARCHITECTURE §6.1). */
    const val CPU_BANNER = "Running on CPU"

    private const val WARNING_CAPTURE_SIZE = "Capture not display-sized: dots hidden"
    private const val WARNING_NOT_VISIBLE = "Content not visible: dots hidden"

    private const val UNKNOWN = "--"
    private const val NOT_AVAILABLE = -1
    private const val DECIMAL_BASE = 10
    private const val TENTHS_PER_UNIT = 10f
    private const val WHOLE_PER_UNIT = 1f

    /** Largest number shown; anything above (or non-finite, or negative) prints as [UNKNOWN]. */
    private const val MAX_SHOWN_VALUE = 9999f

    /**
     * Writes the stats line, e.g. `GPU 12.3 ms 58 fps 610x1356 ts=img`: accelerator, inference time (one
     * decimal), capture fps (rounded), capture size, timestamp source. Unknown values print as `--`.
     * Returns the number of characters written to [out].
     */
    fun formatStats(
        state: OverlayState,
        out: CharArray,
    ): Int {
        var pos = 0
        pos = putText(out, pos, acceleratorText(state.accelerator))
        pos = putText(out, pos, " ")
        pos = putTenths(out, pos, quantize(state.inferenceMs, TENTHS_PER_UNIT))
        pos = putText(out, pos, " ms ")
        pos = putWhole(out, pos, quantize(state.captureFps, WHOLE_PER_UNIT))
        pos = putText(out, pos, " fps ")
        pos = putWhole(out, pos, state.captureWidthImg)
        pos = putText(out, pos, "x")
        pos = putWhole(out, pos, state.captureHeightImg)
        pos = putText(out, pos, " ts=")
        pos = putText(out, pos, timestampSourceText(state.timestampSource))
        return pos
    }

    /**
     * Writes the warning line into [out]: the fail-closed warning if there is one, else a note when the
     * captured content is not visible. Returns the number of characters written; 0 = nothing to show.
     */
    fun formatWarning(
        state: OverlayState,
        out: CharArray,
    ): Int {
        if (state.warning == OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED) return putText(out, 0, WARNING_CAPTURE_SIZE)
        if (!state.dotsVisible) return putText(out, 0, WARNING_NOT_VISIBLE)
        return 0
    }

    /**
     * Whether the HUD text of [after] differs from that of [before], using the same rounding as
     * [formatStats] (so jitter below the printed precision does not count). Pure, allocation-free.
     */
    fun hudChanged(
        before: OverlayState,
        after: OverlayState,
    ): Boolean {
        if (before.accelerator != after.accelerator) return true
        if (before.timestampSource != after.timestampSource) return true
        if (before.captureWidthImg != after.captureWidthImg) return true
        if (before.captureHeightImg != after.captureHeightImg) return true
        if (before.dotsVisible != after.dotsVisible) return true
        if (before.warning != after.warning) return true
        if (quantize(before.captureFps, WHOLE_PER_UNIT) != quantize(after.captureFps, WHOLE_PER_UNIT)) return true
        return quantize(before.inferenceMs, TENTHS_PER_UNIT) != quantize(after.inferenceMs, TENTHS_PER_UNIT)
    }

    private fun acceleratorText(accelerator: Accelerator?): String =
        when (accelerator) {
            Accelerator.GPU -> "GPU"
            Accelerator.CPU -> "CPU"
            null -> UNKNOWN
        }

    private fun timestampSourceText(source: TimestampSource?): String =
        when (source) {
            TimestampSource.IMAGE -> "img"
            TimestampSource.ARRIVAL -> "arrival"
            null -> UNKNOWN
        }

    /** [value] times [scale], rounded; [NOT_AVAILABLE] when [value] is not finite, negative or too large. */
    private fun quantize(
        value: Float,
        scale: Float,
    ): Int = if (value.isFinite() && value >= 0f && value <= MAX_SHOWN_VALUE) (value * scale).roundToInt() else NOT_AVAILABLE

    private fun putChar(
        out: CharArray,
        pos: Int,
        char: Char,
    ): Int {
        if (pos >= out.size) return pos
        out[pos] = char
        return pos + 1
    }

    private fun putText(
        out: CharArray,
        pos: Int,
        text: String,
    ): Int {
        var next = pos
        for (i in 0 until text.length) {
            next = putChar(out, next, text[i])
        }
        return next
    }

    /** Writes a non-negative whole number in decimal; [NOT_AVAILABLE] or less prints [UNKNOWN]. */
    private fun putWhole(
        out: CharArray,
        pos: Int,
        value: Int,
    ): Int {
        if (value < 0) return putText(out, pos, UNKNOWN)
        var divisor = 1
        while (value / divisor >= DECIMAL_BASE) divisor *= DECIMAL_BASE
        var next = pos
        while (divisor > 0) {
            next = putChar(out, next, '0' + (value / divisor) % DECIMAL_BASE)
            divisor /= DECIMAL_BASE
        }
        return next
    }

    /** Writes [tenths] tenths of a unit as `whole.fraction`; a negative value prints [UNKNOWN]. */
    private fun putTenths(
        out: CharArray,
        pos: Int,
        tenths: Int,
    ): Int {
        if (tenths < 0) return putText(out, pos, UNKNOWN)
        var next = putWhole(out, pos, tenths / DECIMAL_BASE)
        next = putChar(out, next, '.')
        return putChar(out, next, '0' + tenths % DECIMAL_BASE)
    }
}
