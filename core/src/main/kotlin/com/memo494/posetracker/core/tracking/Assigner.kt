package com.memo494.posetracker.core.tracking

/**
 * One-to-one assignment of rows (tracks) to columns (detections) over a rectangular cost matrix that the
 * caller fills with [setCost] (ARCHITECTURE §6.2). Implementations allocate everything in their constructor;
 * [setCost] and [assign] allocate nothing.
 *
 * Objective: among all sets of pairs without a repeated row or column and without a forbidden pair, first
 * the largest number of pairs, then the smallest total cost. A pair whose cost is [FORBIDDEN] (or `NaN`, fail
 * closed) is never returned as assigned.
 */
interface Assigner {
    /** Most rows (tracks) one [assign] call accepts. */
    val maxRows: Int

    /** Most columns (detections) one [assign] call accepts. */
    val maxCols: Int

    /**
     * Writes the cost of pairing [row] with [col]: [FORBIDDEN] for a gated pair, otherwise a finite value in
     * `[0, MAX_COST]`. Throws `IllegalArgumentException` when the position is outside `maxRows x maxCols`.
     */
    fun setCost(
        row: Int,
        col: Int,
        cost: Float,
    )

    /**
     * Assigns the first [rows] rows to the first [cols] columns using the costs written by [setCost]; entries
     * outside that window are ignored. Writes the column of each row into `rowToCol[0 until rows]` and the row
     * of each column into `colToRow[0 until cols]` (`-1` = unassigned) and returns the number of pairs.
     * Throws `IllegalArgumentException` for sizes above the capacity, output arrays that are too short, or a
     * finite cost that is negative or above [MAX_COST]; it never truncates silently.
     */
    fun assign(
        rows: Int,
        cols: Int,
        rowToCol: IntArray,
        colToRow: IntArray,
    ): Int

    companion object {
        /** Cost of a pair that must never be assigned (gated out). */
        const val FORBIDDEN = Float.POSITIVE_INFINITY

        /** Largest finite cost an assigner accepts; the real costs are at most the sum of the cost weights (below 2). */
        const val MAX_COST = 1_000_000f

        /** Value of `rowToCol` / `colToRow` for an unassigned row or column. */
        const val UNASSIGNED = -1
    }
}
