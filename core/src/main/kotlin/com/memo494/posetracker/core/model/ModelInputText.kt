package com.memo494.posetracker.core.model

/**
 * The one text of a model input size (Mdl pixels): a square input is its side (`640`), any other is width,
 * `x`, height (`832x384`). The HUD, `summary.txt`, the log line and the control screen all use it.
 *
 * [text] builds a `String` (off the frame path); [write] writes the same characters into a caller-owned
 * [CharArray] without allocating (the HUD, per draw). A side that is not positive means "unknown" and gives
 * `0`, as the diagnostics always printed it, never a guessed size.
 */
object ModelInputText {
    private const val UNKNOWN_CHAR = '0'
    private const val SEPARATOR = 'x'
    private const val DECIMAL_BASE = 10

    /** The text of a [width] x [height] input; `0` when either side is not positive. */
    fun text(
        width: Int,
        height: Int,
    ): String =
        when {
            width <= 0 || height <= 0 -> UNKNOWN_CHAR.toString()
            width == height -> width.toString()
            else -> "$width$SEPARATOR$height"
        }

    /**
     * Writes the same characters as [text] into [out] from [pos] on and returns the position after the last
     * one. Text that does not fit is cut, never thrown. Allocation-free.
     */
    fun write(
        width: Int,
        height: Int,
        out: CharArray,
        pos: Int,
    ): Int {
        if (width <= 0 || height <= 0) return putChar(out, pos, UNKNOWN_CHAR)
        var next = putWhole(out, pos, width)
        if (width == height) return next
        next = putChar(out, next, SEPARATOR)
        return putWhole(out, next, height)
    }

    private fun putChar(
        out: CharArray,
        pos: Int,
        char: Char,
    ): Int {
        if (pos >= out.size) return pos
        out[pos] = char
        return pos + 1
    }

    /** Writes a positive whole number in decimal. */
    private fun putWhole(
        out: CharArray,
        pos: Int,
        value: Int,
    ): Int {
        var divisor = 1
        while (value / divisor >= DECIMAL_BASE) divisor *= DECIMAL_BASE
        var next = pos
        while (divisor > 0) {
            next = putChar(out, next, '0' + (value / divisor) % DECIMAL_BASE)
            divisor /= DECIMAL_BASE
        }
        return next
    }
}
