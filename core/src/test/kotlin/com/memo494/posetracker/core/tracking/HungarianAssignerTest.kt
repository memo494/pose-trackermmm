package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Random

class HungarianAssignerTest {
    private val assigner = HungarianAssigner(MAX_ROWS, MAX_COLS)

    @Test
    fun threeByThree_findsTheKnownOptimum() {
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(4f, 1f, 3f),
                    floatArrayOf(2f, 0f, 5f),
                    floatArrayOf(3f, 2f, 2f),
                ),
            )

        assertThat(result.rowToCol.toList()).containsExactly(1, 0, 2).inOrder()
        assertThat(result.colToRow.toList()).containsExactly(1, 0, 2).inOrder()
        assertThat(result.count).isEqualTo(3)
    }

    @Test
    fun twoRowsFourColumns_findsTheKnownOptimum() {
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(9f, 2f, 7f, 8f),
                    floatArrayOf(6f, 4f, 3f, 7f),
                ),
            )

        assertThat(result.rowToCol.toList()).containsExactly(1, 2).inOrder()
        assertThat(result.colToRow.toList()).containsExactly(-1, 0, 1, -1).inOrder()
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun fourRowsTwoColumns_findsTheKnownOptimum() {
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(9f, 6f),
                    floatArrayOf(2f, 4f),
                    floatArrayOf(7f, 3f),
                    floatArrayOf(8f, 7f),
                ),
            )

        assertThat(result.rowToCol.toList()).containsExactly(-1, 0, 1, -1).inOrder()
        assertThat(result.colToRow.toList()).containsExactly(1, 2).inOrder()
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun aRowOfOnlyForbiddenPairs_staysUnassigned() {
        val f = Assigner.FORBIDDEN
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(1f, 5f, 5f),
                    floatArrayOf(f, f, f),
                    floatArrayOf(5f, 1f, 5f),
                ),
            )

        assertThat(result.rowToCol.toList()).containsExactly(0, -1, 1).inOrder()
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun aColumnOfOnlyForbiddenPairs_staysUnassigned() {
        val f = Assigner.FORBIDDEN
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(1f, f, 5f),
                    floatArrayOf(5f, f, 1f),
                ),
            )

        assertThat(result.rowToCol.toList()).containsExactly(0, 2).inOrder()
        assertThat(result.colToRow.toList()).containsExactly(0, -1, 1).inOrder()
    }

    @Test
    fun aMatrixOfOnlyForbiddenPairs_assignsNothing() {
        val f = Assigner.FORBIDDEN
        val result = solve(assigner, arrayOf(floatArrayOf(f, f), floatArrayOf(f, f), floatArrayOf(f, f)))

        assertThat(result.count).isEqualTo(0)
        assertThat(result.rowToCol.toList()).containsExactly(-1, -1, -1)
        assertThat(result.colToRow.toList()).containsExactly(-1, -1)
    }

    @Test
    fun nanCost_isTreatedAsForbidden() {
        val result = solve(assigner, arrayOf(floatArrayOf(Float.NaN, 1f), floatArrayOf(Float.NaN, Float.NaN)))

        assertThat(result.rowToCol.toList()).containsExactly(1, -1).inOrder()
    }

    @Test
    fun moreAssignedPairs_beatALowerTotalCost() {
        val f = Assigner.FORBIDDEN
        // One pair costs 1; two pairs cost 100 + 1. The objective is count first, then cost.
        val result = solve(assigner, arrayOf(floatArrayOf(1f, 100f), floatArrayOf(1f, f)))

        assertThat(result.count).isEqualTo(2)
        assertThat(result.rowToCol.toList()).containsExactly(1, 0).inOrder()
    }

    @Test
    fun aSinglePairThatIsTheOnlyRealOne_isStillAssigned() {
        val f = Assigner.FORBIDDEN
        val result = solve(assigner, arrayOf(floatArrayOf(f, f), floatArrayOf(f, 0.9f)))

        assertThat(result.rowToCol.toList()).containsExactly(-1, 1).inOrder()
    }

    @Test
    fun emptyProblems_assignNothing() {
        assertThat(solve(assigner, emptyArray(), cols = 3).count).isEqualTo(0)
        assertThat(solve(assigner, Array(3) { FloatArray(0) }).count).isEqualTo(0)
    }

    @Test
    fun identicalCosts_pickTheLowestIndicesOnEachSide() {
        val three = solve(assigner, Array(3) { FloatArray(3) { 0.4f } })
        val wide = solve(assigner, Array(2) { FloatArray(4) { 0.4f } })
        val tall = solve(assigner, Array(4) { FloatArray(2) { 0.4f } })

        assertThat(three.rowToCol.toList()).containsExactly(0, 1, 2).inOrder()
        assertThat(wide.rowToCol.toList()).containsExactly(0, 1).inOrder()
        assertThat(tall.rowToCol.toList()).containsExactly(0, 1, -1, -1).inOrder()
    }

    @Test
    fun oneTrackAndOneDetectionAmongTies_takeTheLowerIndex() {
        val twoTracks = solve(assigner, arrayOf(floatArrayOf(0.2f), floatArrayOf(0.2f)))
        val twoDetections = solve(assigner, arrayOf(floatArrayOf(0.2f, 0.2f)))

        assertThat(twoTracks.rowToCol.toList()).containsExactly(0, -1).inOrder()
        assertThat(twoDetections.rowToCol.toList()).containsExactly(0)
    }

    @Test
    fun theSameInputAlwaysGivesTheSameOutput_onOneInstanceAndOnAFreshOne() {
        val matrix = randomMatrix(Random(SEED), rows = 6, cols = 5, forbiddenShare = 0.3f)

        val first = solve(assigner, matrix)
        val second = solve(assigner, matrix)
        val fresh = solve(HungarianAssigner(MAX_ROWS, MAX_COLS), matrix)

        assertThat(second.rowToCol.toList()).isEqualTo(first.rowToCol.toList())
        assertThat(fresh.rowToCol.toList()).isEqualTo(first.rowToCol.toList())
        assertThat(fresh.colToRow.toList()).isEqualTo(first.colToRow.toList())
    }

    @Test
    fun anEarlierBiggerProblem_doesNotLeakIntoALaterSmallerOne() {
        val big = randomMatrix(Random(SEED), rows = 9, cols = 7, forbiddenShare = 0.2f)
        solve(assigner, big)

        val small = arrayOf(floatArrayOf(3f, 1f), floatArrayOf(1f, 3f))
        val result = solve(assigner, small)

        assertThat(result.rowToCol.toList()).containsExactly(1, 0).inOrder()
    }

    @Test
    fun rowAndColumnPermutations_keepTheMatchCountAndTheOptimalTotal() {
        val random = Random(SEED)
        repeat(60) {
            val rows = 1 + random.nextInt(8)
            val cols = 1 + random.nextInt(8)
            val matrix = randomMatrix(random, rows, cols, forbiddenShare = 0.25f)
            val rowOrder = shuffled(rows, random)
            val colOrder = shuffled(cols, random)
            val permuted = Array(rows) { r -> FloatArray(cols) { c -> matrix[rowOrder[r]][colOrder[c]] } }

            val original = solve(assigner, matrix)
            val other = solve(assigner, permuted)

            assertThat(other.count).isEqualTo(original.count)
            assertThat(totalOf(permuted, other)).isWithin(TOTAL_TOLERANCE).of(totalOf(matrix, original))
        }
    }

    @Test
    fun randomSmallMatrices_matchABruteForceSearch() {
        val random = Random(SEED)
        repeat(400) { round ->
            val rows = 1 + random.nextInt(5)
            val cols = 1 + random.nextInt(5)
            val matrix = randomMatrix(random, rows, cols, forbiddenShare = random.nextFloat() * 0.7f)

            val result = solve(assigner, matrix)
            val best = bruteForce(matrix)

            assertWithMessage("round $round count").that(result.count).isEqualTo(best.first)
            assertWithMessage("round $round total").that(totalOf(matrix, result)).isWithin(TOTAL_TOLERANCE).of(best.second)
            assertConsistent(matrix, result)
        }
    }

    @Test
    fun aFullSizeProblem_givesAValidMatchingAndIsPermutationStable() {
        val random = Random(SEED)
        val matrix = randomMatrix(random, MAX_ROWS, MAX_COLS, forbiddenShare = 0.4f)
        val rowOrder = shuffled(MAX_ROWS, random)
        val colOrder = shuffled(MAX_COLS, random)
        val permuted = Array(MAX_ROWS) { r -> FloatArray(MAX_COLS) { c -> matrix[rowOrder[r]][colOrder[c]] } }

        val original = solve(assigner, matrix)
        val other = solve(assigner, permuted)

        assertConsistent(matrix, original)
        assertThat(other.count).isEqualTo(original.count)
        assertThat(totalOf(permuted, other)).isWithin(TOTAL_TOLERANCE).of(totalOf(matrix, original))
    }

    @Test
    fun moreRowsThanTheCapacity_isRejected() {
        val small = HungarianAssigner(2, 3)

        assertThrows(IllegalArgumentException::class.java) { small.assign(3, 1, IntArray(3), IntArray(1)) }
    }

    @Test
    fun moreColumnsThanTheCapacity_isRejected() {
        val small = HungarianAssigner(2, 3)

        assertThrows(IllegalArgumentException::class.java) { small.assign(1, 4, IntArray(1), IntArray(4)) }
    }

    @Test
    fun outputArraysThatAreTooSmall_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { assigner.assign(3, 3, IntArray(2), IntArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { assigner.assign(3, 3, IntArray(3), IntArray(2)) }
    }

    @Test
    fun aCostOutsideTheMatrixWindow_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { assigner.setCost(MAX_ROWS, 0, 1f) }
        assertThrows(IllegalArgumentException::class.java) { assigner.setCost(0, MAX_COLS, 1f) }
        assertThrows(IllegalArgumentException::class.java) { assigner.setCost(-1, 0, 1f) }
    }

    @Test
    fun aNegativeCost_isAProgrammingError() {
        assigner.setCost(0, 0, -0.5f)

        assertThrows(IllegalArgumentException::class.java) { assigner.assign(1, 1, IntArray(1), IntArray(1)) }
    }

    @Test
    fun aCostAboveTheLargestAcceptedValue_isAProgrammingError() {
        assigner.setCost(0, 0, Assigner.MAX_COST * 2f)

        assertThrows(IllegalArgumentException::class.java) { assigner.assign(1, 1, IntArray(1), IntArray(1)) }
    }

    @Test
    fun theLargestAcceptedCost_isStillSolvedCorrectly() {
        val big = Assigner.MAX_COST
        val f = Assigner.FORBIDDEN
        val result = solve(assigner, arrayOf(floatArrayOf(big, big), floatArrayOf(big, f)))

        assertThat(result.count).isEqualTo(2)
        assertThat(result.rowToCol.toList()).containsExactly(1, 0).inOrder()
    }

    private class Result(
        val rowToCol: IntArray,
        val colToRow: IntArray,
        val count: Int,
    )

    private fun solve(
        target: HungarianAssigner,
        matrix: Array<FloatArray>,
        cols: Int = if (matrix.isEmpty()) 0 else matrix[0].size,
    ): Result {
        val rows = matrix.size
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                target.setCost(r, c, matrix[r][c])
            }
        }
        val rowToCol = IntArray(rows) { SENTINEL }
        val colToRow = IntArray(cols) { SENTINEL }
        val count = target.assign(rows, cols, rowToCol, colToRow)
        return Result(rowToCol, colToRow, count)
    }

    private fun randomMatrix(
        random: Random,
        rows: Int,
        cols: Int,
        forbiddenShare: Float,
    ): Array<FloatArray> =
        Array(rows) {
            FloatArray(cols) {
                val roll = random.nextFloat()
                if (roll < forbiddenShare) {
                    if (random.nextBoolean()) Assigner.FORBIDDEN else Float.NaN
                } else {
                    random.nextInt(COST_LEVELS) / COST_LEVELS.toFloat() * MAX_RANDOM_COST
                }
            }
        }

    private fun shuffled(
        size: Int,
        random: Random,
    ): IntArray {
        val order = IntArray(size) { it }
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        return order
    }

    private fun isReal(cost: Float): Boolean = cost < Assigner.FORBIDDEN

    private fun totalOf(
        matrix: Array<FloatArray>,
        result: Result,
    ): Float {
        var total = 0f
        for (r in matrix.indices) {
            if (result.rowToCol[r] >= 0) total += matrix[r][result.rowToCol[r]]
        }
        return total
    }

    /** The two arrays agree, no pair is forbidden, no column is used twice and `count` is the number of pairs. */
    private fun assertConsistent(
        matrix: Array<FloatArray>,
        result: Result,
    ) {
        var pairs = 0
        for (r in matrix.indices) {
            val c = result.rowToCol[r]
            if (c >= 0) {
                pairs++
                assertThat(result.colToRow[c]).isEqualTo(r)
                assertThat(isReal(matrix[r][c])).isTrue()
            }
        }
        assertThat(result.count).isEqualTo(pairs)
        assertThat(result.colToRow.count { it >= 0 }).isEqualTo(pairs)
    }

    /** Best (pair count, total cost) over all partial matchings of the non-forbidden pairs. */
    private fun bruteForce(matrix: Array<FloatArray>): Pair<Int, Float> {
        var bestCount = -1
        var bestTotal = 0f
        val cols = if (matrix.isEmpty()) 0 else matrix[0].size
        val used = BooleanArray(cols)

        fun search(
            row: Int,
            count: Int,
            total: Float,
        ) {
            if (row == matrix.size) {
                if (count > bestCount || (count == bestCount && total < bestTotal)) {
                    bestCount = count
                    bestTotal = total
                }
                return
            }
            search(row + 1, count, total)
            for (c in 0 until cols) {
                if (!used[c] && isReal(matrix[row][c])) {
                    used[c] = true
                    search(row + 1, count + 1, total + matrix[row][c])
                    used[c] = false
                }
            }
        }
        search(0, 0, 0f)
        return Pair(bestCount, bestTotal)
    }

    private companion object {
        const val MAX_ROWS = 32
        const val MAX_COLS = 30
        const val SEED = 20_261_116L
        const val SENTINEL = -99
        const val COST_LEVELS = 40
        const val MAX_RANDOM_COST = 2f
        const val TOTAL_TOLERANCE = 1e-4f
    }
}
