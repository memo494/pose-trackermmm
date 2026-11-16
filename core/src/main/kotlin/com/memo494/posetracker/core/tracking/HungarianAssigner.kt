package com.memo494.posetracker.core.tracking

import java.util.Arrays

/**
 * Optimal assignment by the Hungarian algorithm (shortest augmenting paths with row and column potentials,
 * `O(n^2 m)` for `n <= m`), no matrix library, no allocation after construction. [maxRows] x [maxCols] is
 * the capacity (tracks x detections); the cost matrix is one row-major [FloatArray] with stride [maxCols].
 *
 * **Objective (see [Assigner]):** the largest total gain `sum(maxCost - cost)` over eligible pairs (finite cost
 * below `maxCost`). Inside the solver every ineligible pair costs exactly `maxCost` and every eligible pair its
 * own cost, and the smaller side is saturated (every row of the algorithm gets a column). A saturating
 * assignment whose eligible pairs are `E` costs `n * maxCost - sum_E(maxCost - cost)`, with `n = min(rows, cols)`
 * fixed, so the cheapest saturating assignment is the one with the largest gain over its eligible pairs. Every
 * set of eligible pairs extends to a saturating assignment (the matrix is complete once ineligible pairs are
 * priced at `maxCost`), and the extension only adds ineligible pairs, whose gain is 0, so the maximum over
 * saturating assignments equals the maximum over all sets of eligible pairs. Ineligible pairs that the solver
 * picks are dropped from the answer. With `maxCost` and every cost at most `MAX_COST` (1e6) and `n <= 128`,
 * all sums stay below 1.3e8, far inside the exact range of a `Double` next to float costs.
 *
 * **Ties:** the result is deterministic: it depends only on the matrix, so the same input always gives the
 * same pairs. Among assignments with the same total gain the solver's scan order decides (rows are added in
 * index order, every scan over columns uses a strict `<`, and a problem with more rows than columns is solved
 * transposed); no rule such as "the lower track index wins" is promised. For example `[[0.25, 0.125],
 * [0.125, 0.0]]` with a limit of 0.7 gives `rowToCol = [1, 0]`, although `[0, 1]` costs the same.
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
        maxCost: Float,
        rowToCol: IntArray,
        colToRow: IntArray,
    ): Int {
        require(maxCost.isFinite() && maxCost > 0f && maxCost <= Assigner.MAX_COST) {
            "maxCost $maxCost outside (0, ${Assigner.MAX_COST}]"
        }
        require(rows in 0..maxRows) { "rows $rows outside [0, $maxRows]" }
        require(cols in 0..maxCols) { "cols $cols outside [0, $maxCols]" }
        require(rowToCol.size >= rows) { "rowToCol has ${rowToCol.size} entries for $rows rows" }
        require(colToRow.size >= cols) { "colToRow has ${colToRow.size} entries for $cols columns" }
        Arrays.fill(rowToCol, 0, rows, Assigner.UNASSIGNED)
        Arrays.fill(colToRow, 0, cols, Assigner.UNASSIGNED)
        if (rows == 0 || cols == 0) return 0

        validateCosts(rows, cols)
        val transposed = rows > cols
        val n = if (transposed) cols else rows
        val m = if (transposed) rows else cols
        solve(n, m, transposed, maxCost)
        return extract(m, transposed, maxCost, rowToCol, colToRow)
    }

    /** Checks that every real (finite) cost of the window is in `[0, MAX_COST]`; `NaN` and [Assigner.FORBIDDEN] are allowed. */
    private fun validateCosts(
        rows: Int,
        cols: Int,
    ) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cost = costs[r * maxCols + c]
                if (cost < Assigner.FORBIDDEN) {
                    require(cost >= 0f && cost <= Assigner.MAX_COST) {
                        "cost $cost at ($r, $c) outside [0, ${Assigner.MAX_COST}]"
                    }
                }
            }
        }
    }

    /** Cost of algorithm row [i] with algorithm column [j] (0-based); an ineligible pair costs [maxCost]. */
    private fun pairCost(
        i: Int,
        j: Int,
        transposed: Boolean,
        maxCost: Float,
    ): Double {
        val cost = if (transposed) costs[j * maxCols + i] else costs[i * maxCols + j]
        return if (cost < maxCost) cost.toDouble() else maxCost.toDouble()
    }

    /** Saturates the [n] algorithm rows over [m] >= [n] columns; the answer is left in [matchedRow]. */
    private fun solve(
        n: Int,
        m: Int,
        transposed: Boolean,
        maxCost: Float,
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
                        val slack = pairCost(i0 - 1, j - 1, transposed, maxCost) - rowPotential[i0] - colPotential[j]
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

    /** Copies the eligible pairs of [matchedRow] into the caller's arrays, in track x detection orientation. */
    private fun extract(
        m: Int,
        transposed: Boolean,
        maxCost: Float,
        rowToCol: IntArray,
        colToRow: IntArray,
    ): Int {
        var pairs = 0
        for (j in 1..m) {
            val i = matchedRow[j]
            if (i != 0) {
                val row = if (transposed) j - 1 else i - 1
                val col = if (transposed) i - 1 else j - 1
                if (costs[row * maxCols + col] < maxCost) {
                    rowToCol[row] = col
                    colToRow[col] = row
                    pairs++
                }
            }
        }
        return pairs
    }
}
