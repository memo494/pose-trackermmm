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
    fun aPairIsWeighedByItsGain_notCountedFirst() {
        val f = Assigner.FORBIDDEN
        val matrix = arrayOf(floatArrayOf(1f, 100f), floatArrayOf(2f, f))

        // Limit 1000: gain 999 (one pair) against 900 + 998 (two pairs). Limit 50: the 100 is not worth making.
        val high = solve(assigner, matrix, maxCost = 1000f)
        val low = solve(assigner, matrix, maxCost = 50f)

        assertThat(high.rowToCol.toList()).containsExactly(1, 0).inOrder()
        assertThat(low.rowToCol.toList()).containsExactly(0, -1).inOrder()
    }

    @Test
    fun oneGoodPair_beatsTwoPoorOnes() {
        // Limit 0.9: A-a 0.05 and B-b 0.05 gain 1.7; A-d 0.6 with B-a 0.6 gain only 0.6.
        val result = solve(assigner, arrayOf(floatArrayOf(0.05f, 0.6f), floatArrayOf(0.6f, 0.05f)), maxCost = 0.9f)

        assertThat(result.rowToCol.toList()).containsExactly(0, 1).inOrder()
    }

    @Test
    fun chainShift_leavesTheExtraTrackAndTheExtraDetectionUnassigned() {
        val f = Assigner.FORBIDDEN
        // Tracks A, B, C x detections a, b, d. The old rule (most pairs first) shifted every id along the row.
        val result =
            solve(
                assigner,
                arrayOf(
                    floatArrayOf(0.05f, f, 0.6f),
                    floatArrayOf(0.6f, 0.05f, f),
                    floatArrayOf(f, 0.6f, f),
                ),
                maxCost = 0.9f,
            )

        assertThat(result.rowToCol.toList()).containsExactly(0, 1, -1).inOrder()
        assertThat(result.colToRow.toList()).containsExactly(0, 1, -1).inOrder()
        assertThat(result.count).isEqualTo(2)
    }

    @Test
    fun aPairAtOrAboveTheLimit_isNeverReturned_evenWhenItIsTheOnlyCandidate() {
        val limit = 0.9f
        val result = solve(assigner, arrayOf(floatArrayOf(0.9f, 0.95f), floatArrayOf(0.95f, 5f)), maxCost = limit)

        assertThat(result.count).isEqualTo(0)
        assertThat(result.rowToCol.toList()).containsExactly(-1, -1)
        assertThat(result.colToRow.toList()).containsExactly(-1, -1)
    }

    @Test
    fun aPairJustBelowTheLimit_isReturned() {
        val limit = 0.9f
        val result = solve(assigner, arrayOf(floatArrayOf(Math.nextDown(limit))), maxCost = limit)

        assertThat(result.rowToCol.toList()).containsExactly(0)
    }

    @Test
    fun aLimitThatIsNotFinitePositiveAndWithinTheCostRange_isRejected() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f, Assigner.MAX_COST * 2f)
        for (limit in bad) {
            assertThrows(IllegalArgumentException::class.java) { assigner.assign(1, 1, limit, IntArray(1), IntArray(1)) }
        }
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
    fun rowAndColumnPermutations_keepThePairCountAndTheTotalGain() {
        val random = Random(SEED)
        repeat(60) {
            val rows = 1 + random.nextInt(8)
            val cols = 1 + random.nextInt(8)
            val matrix = randomMatrix(random, rows, cols, forbiddenShare = 0.25f)
            val limit = randomLimit(random)
            val rowOrder = shuffled(rows, random)
            val colOrder = shuffled(cols, random)
            val permuted = Array(rows) { r -> FloatArray(cols) { c -> matrix[rowOrder[r]][colOrder[c]] } }

            val original = solve(assigner, matrix, maxCost = limit)
            val other = solve(assigner, permuted, maxCost = limit)

            assertThat(other.count).isEqualTo(original.count)
            assertThat(gainOf(permuted, other, limit)).isWithin(TOTAL_TOLERANCE).of(gainOf(matrix, original, limit))
        }
    }

    @Test
    fun randomSmallMatrices_matchABruteForceSearch() {
        val random = Random(SEED)
        repeat(400) { round ->
            val rows = 1 + random.nextInt(5)
            val cols = 1 + random.nextInt(5)
            val matrix = randomMatrix(random, rows, cols, forbiddenShare = random.nextFloat() * 0.7f)
            val limit = randomLimit(random)

            val result = solve(assigner, matrix, maxCost = limit)

            assertWithMessage("round $round gain")
                .that(gainOf(matrix, result, limit))
                .isWithin(TOTAL_TOLERANCE)
                .of(bruteForce(matrix, limit))
            assertConsistent(matrix, result, limit)
        }
    }

    @Test
    fun aFullSizeProblem_givesAValidMatchingAndIsPermutationStable() {
        val random = Random(SEED)
        val matrix = randomMatrix(random, MAX_ROWS, MAX_COLS, forbiddenShare = 0.4f)
        val limit = randomLimit(random)
        val rowOrder = shuffled(MAX_ROWS, random)
        val colOrder = shuffled(MAX_COLS, random)
        val permuted = Array(MAX_ROWS) { r -> FloatArray(MAX_COLS) { c -> matrix[rowOrder[r]][colOrder[c]] } }

        val original = solve(assigner, matrix, maxCost = limit)
        val other = solve(assigner, permuted, maxCost = limit)

        assertConsistent(matrix, original, limit)
        assertThat(other.count).isEqualTo(original.count)
        assertThat(gainOf(permuted, other, limit)).isWithin(TOTAL_TOLERANCE).of(gainOf(matrix, original, limit))
    }

    @Test
    fun moreRowsThanTheCapacity_isRejected() {
        val small = HungarianAssigner(2, 3)

        assertThrows(IllegalArgumentException::class.java) { small.assign(3, 1, LIMIT, IntArray(3), IntArray(1)) }
    }

    @Test
    fun moreColumnsThanTheCapacity_isRejected() {
        val small = HungarianAssigner(2, 3)

        assertThrows(IllegalArgumentException::class.java) { small.assign(1, 4, LIMIT, IntArray(1), IntArray(4)) }
    }

    @Test
    fun outputArraysThatAreTooSmall_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { assigner.assign(3, 3, LIMIT, IntArray(2), IntArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { assigner.assign(3, 3, LIMIT, IntArray(3), IntArray(2)) }
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

        assertThrows(IllegalArgumentException::class.java) { assigner.assign(1, 1, LIMIT, IntArray(1), IntArray(1)) }
    }

    @Test
    fun aCostAboveTheLargestAcceptedValue_isAProgrammingError() {
        assigner.setCost(0, 0, Assigner.MAX_COST * 2f)

        assertThrows(IllegalArgumentException::class.java) { assigner.assign(1, 1, LIMIT, IntArray(1), IntArray(1)) }
    }

    @Test
    fun theLargestAcceptedLimitAndCosts_areStillSolvedCorrectly() {
        val half = Assigner.MAX_COST / 2f
        val f = Assigner.FORBIDDEN
        val result = solve(assigner, arrayOf(floatArrayOf(half, half), floatArrayOf(half, f)), maxCost = Assigner.MAX_COST)

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
        maxCost: Float = LIMIT,
    ): Result {
        val rows = matrix.size
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                target.setCost(r, c, matrix[r][c])
            }
        }
        val rowToCol = IntArray(rows) { SENTINEL }
        val colToRow = IntArray(cols) { SENTINEL }
        val count = target.assign(rows, cols, maxCost, rowToCol, colToRow)
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

    /**
     * A limit on the cost grid of [randomMatrix] shifted by an amount that no sum of grid steps can cancel, so two
     * assignments with a different number of pairs never have the same total gain (the pair count is then unique).
     */
    private fun randomLimit(random: Random): Float = (1 + random.nextInt(LIMIT_STEPS)) * LIMIT_GRID + LIMIT_OFFSET

    private fun isEligible(
        cost: Float,
        limit: Float,
    ): Boolean = cost < limit

    private fun gainOf(
        matrix: Array<FloatArray>,
        result: Result,
        limit: Float,
    ): Float {
        var gain = 0f
        for (r in matrix.indices) {
            if (result.rowToCol[r] >= 0) gain += limit - matrix[r][result.rowToCol[r]]
        }
        return gain
    }

    /** The two arrays agree, no returned pair is ineligible, no column is used twice and `count` is the number of pairs. */
    private fun assertConsistent(
        matrix: Array<FloatArray>,
        result: Result,
        limit: Float,
    ) {
        var pairs = 0
        for (r in matrix.indices) {
            val c = result.rowToCol[r]
            if (c >= 0) {
                pairs++
                assertThat(result.colToRow[c]).isEqualTo(r)
                assertThat(isEligible(matrix[r][c], limit)).isTrue()
            }
        }
        assertThat(result.count).isEqualTo(pairs)
        assertThat(result.colToRow.count { it >= 0 }).isEqualTo(pairs)
    }

    /** Largest total gain `sum(limit - cost)` over all partial matchings of the eligible pairs. */
    private fun bruteForce(
        matrix: Array<FloatArray>,
        limit: Float,
    ): Float {
        var bestGain = 0f
        val cols = if (matrix.isEmpty()) 0 else matrix[0].size
        val used = BooleanArray(cols)

        fun search(
            row: Int,
            gain: Float,
        ) {
            if (row == matrix.size) {
                if (gain > bestGain) bestGain = gain
                return
            }
            search(row + 1, gain)
            for (c in 0 until cols) {
                if (!used[c] && isEligible(matrix[row][c], limit)) {
                    used[c] = true
                    search(row + 1, gain + limit - matrix[row][c])
                    used[c] = false
                }
            }
        }
        search(0, 0f)
        return bestGain
    }

    private companion object {
        const val MAX_ROWS = 32
        const val MAX_COLS = 30
        const val SEED = 20_261_116L
        const val SENTINEL = -99
        const val LIMIT = 100f
        const val LIMIT_STEPS = 60
        const val LIMIT_GRID = 0.05f
        const val LIMIT_OFFSET = 0.0137f
        const val COST_LEVELS = 40
        const val MAX_RANDOM_COST = 2f
        const val TOTAL_TOLERANCE = 1e-4f
    }
}
