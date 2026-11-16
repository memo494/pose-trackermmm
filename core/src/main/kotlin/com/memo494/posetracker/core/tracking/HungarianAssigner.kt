package com.memo494.posetracker.core.tracking

import java.util.Arrays

/**
 * Optimal assignment by the Hungarian algorithm (shortest augmenting paths with row and column potentials,
 * `O(n^2 m)` for `n <= m`), no matrix library, no allocation after construction. [maxRows] x [maxCols] is
 * the capacity (tracks x detections); the cost matrix is one row-major [FloatArray] with stride [maxCols].
 *
 * **Objective (see [Assigner]):** most non-forbidden pairs first, then least total cost. Inside the solver a
 * forbidden pair costs `BIG = n * maxReal + 1`, where `n = min(rows, cols)` and `maxReal` is the largest real
 * (finite) cost of this call. The solver saturates the smaller side, so an assignment with `f` forbidden pairs
 * and `n - f` real pairs costs `realSum + f * BIG` with `0 <= realSum <= (n - f) * maxReal < BIG`. Hence one
 * more forbidden pair always costs more than any possible saving in real cost, and the optimum has the fewest
 * forbidden pairs, which is the most real pairs (every set of real pairs extends to a saturating assignment,
 * because the matrix is complete once forbidden pairs are priced); among assignments with the same count the
 * `f * BIG` part is equal, so the smallest real total wins. With `maxReal <= MAX_COST` (1e6) and `n <= 128`,
 * BIG is below 1.3e8, far inside the exact range of a `Double` next to float costs, so rounding cannot reorder
 * the two levels. Forbidden pairs that the solver picks are dropped from the answer.
 *
 * **Ties:** rows are added in index order and every scan over columns uses a strict `<`, so among equal
 * choices the lower column index (and, because rows are processed in index order, the lower row index) is
 * taken. When there are more rows than columns the problem is solved transposed (the smaller side as the rows
 * of the algorithm), which gives the same rule with the roles swapped: the lower track index wins. The result
 * only depends on the matrix, so the same input always gives the same output.
 */
class HungarianAssigner(
    override val maxRows: Int,
    override val maxCols: Int,
) : Assigner {
    private val costs = FloatArray(maxRows * maxCols)
    private val rowPotential = DoubleArray(maxOf(maxRows, maxCols) + 1)
    private val colPotential = DoubleArray(maxOf(maxRows, maxCols) + 1)
    private val minSlack = DoubleArray(maxOf(maxRows, maxCols) + 1)
    private val matchedRow = IntArray(maxOf(maxRows, maxCols) + 1)
    private val pathFrom = IntArray(maxOf(maxRows, maxCols) + 1)
    private val visited = BooleanArray(maxOf(maxRows, maxCols) + 1)

    init {
        require(maxRows >= 1 && maxCols >= 1) { "capacity $maxRows x $maxCols must be at least 1 x 1" }
    }

    override fun setCost(
        row: Int,
        col: Int,
        cost: Float,
    ) {
        require(row in 0 until maxRows && col in 0 until maxCols) {
            "cost position ($row, $col) outside $maxRows x $maxCols"
        }
        costs[row * maxCols + col] = cost
    }

    override fun assign(
        rows: Int,
        cols: Int,
        rowToCol: IntArray,
        colToRow: IntArray,
    ): Int {
        require(rows in 0..maxRows) { "rows $rows outside [0, $maxRows]" }
        require(cols in 0..maxCols) { "cols $cols outside [0, $maxCols]" }
        require(rowToCol.size >= rows) { "rowToCol has ${rowToCol.size} entries for $rows rows" }
        require(colToRow.size >= cols) { "colToRow has ${colToRow.size} entries for $cols columns" }
        Arrays.fill(rowToCol, 0, rows, Assigner.UNASSIGNED)
        Arrays.fill(colToRow, 0, cols, Assigner.UNASSIGNED)
        if (rows == 0 || cols == 0) return 0

        val maxReal = largestRealCost(rows, cols)
        val transposed = rows > cols
        val n = if (transposed) cols else rows
        val m = if (transposed) rows else cols
        val big = n * maxReal + 1.0
        solve(n, m, transposed, big)
        return extract(m, transposed, rowToCol, colToRow)
    }

    /** Checks every real cost of the window and returns the largest one (0 when there is none). */
    private fun largestRealCost(
        rows: Int,
        cols: Int,
    ): Double {
        var largest = 0f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cost = costs[r * maxCols + c]
                if (cost < Assigner.FORBIDDEN) {
                    require(cost >= 0f && cost <= Assigner.MAX_COST) {
                        "cost $cost at ($r, $c) outside [0, ${Assigner.MAX_COST}]"
                    }
                    if (cost > largest) largest = cost
                }
            }
        }
        return largest.toDouble()
    }

    /** Cost of algorithm row [i] with algorithm column [j] (0-based); a forbidden or `NaN` pair costs [big]. */
    private fun pairCost(
        i: Int,
        j: Int,
        transposed: Boolean,
        big: Double,
    ): Double {
        val cost = if (transposed) costs[j * maxCols + i] else costs[i * maxCols + j]
        return if (cost < Assigner.FORBIDDEN) cost.toDouble() else big
    }

    /** Saturates the [n] algorithm rows over [m] >= [n] columns; the answer is left in [matchedRow]. */
    private fun solve(
        n: Int,
        m: Int,
        transposed: Boolean,
        big: Double,
    ) {
        Arrays.fill(rowPotential, 0, n + 1, 0.0)
        Arrays.fill(colPotential, 0, m + 1, 0.0)
        Arrays.fill(matchedRow, 0, m + 1, 0)
        for (i in 1..n) {
            matchedRow[0] = i
            var j0 = 0
            Arrays.fill(minSlack, 0, m + 1, Double.POSITIVE_INFINITY)
            Arrays.fill(visited, 0, m + 1, false)
            while (true) {
                visited[j0] = true
                val i0 = matchedRow[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (!visited[j]) {
                        val slack = pairCost(i0 - 1, j - 1, transposed, big) - rowPotential[i0] - colPotential[j]
                        if (slack < minSlack[j]) {
                            minSlack[j] = slack
                            pathFrom[j] = j0
                        }
                        if (minSlack[j] < delta) {
                            delta = minSlack[j]
                            j1 = j
                        }
                    }
                }
                for (j in 0..m) {
                    if (visited[j]) {
                        rowPotential[matchedRow[j]] += delta
                        colPotential[j] -= delta
                    } else {
                        minSlack[j] -= delta
                    }
                }
                j0 = j1
                if (matchedRow[j0] == 0) break
            }
            while (j0 != 0) {
                val previous = pathFrom[j0]
                matchedRow[j0] = matchedRow[previous]
                j0 = previous
            }
        }
    }

    /** Copies the real pairs of [matchedRow] into the caller's arrays, in track x detection orientation. */
    private fun extract(
        m: Int,
        transposed: Boolean,
        rowToCol: IntArray,
        colToRow: IntArray,
    ): Int {
        var pairs = 0
        for (j in 1..m) {
            val i = matchedRow[j]
            if (i != 0) {
                val row = if (transposed) j - 1 else i - 1
                val col = if (transposed) i - 1 else j - 1
                if (costs[row * maxCols + col] < Assigner.FORBIDDEN) {
                    rowToCol[row] = col
                    colToRow[col] = row
                    pairs++
                }
            }
        }
        return pairs
    }
}
