package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.memo494.posetracker.core.TrackerConfig
import org.junit.jupiter.api.Test

class AssociationCostTest {
    private val config = TrackerConfig()

    @Test
    fun identicalBoxesAndHeads_costZero() {
        assertThat(costOf(scene())).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun disjointBoxesAtTheSameHead_costHalf() {
        val values = scene()
        values[DET_LEFT] = 200f
        values[DET_RIGHT] = 240f

        assertThat(costOf(values)).isWithin(TOLERANCE).of(0.5f)
    }

    @Test
    fun halfOverlappingBoxes_useTheIntersectionOverUnion() {
        // 10x10 boxes shifted by 5 in x: intersection 50, union 150, IoU 1/3, cost 0.5 * (2/3).
        val values = scene()
        values[TRACK_LEFT] = 0f
        values[TRACK_TOP] = 0f
        values[TRACK_RIGHT] = 10f
        values[TRACK_BOTTOM] = 10f
        values[DET_LEFT] = 5f
        values[DET_TOP] = 0f
        values[DET_RIGHT] = 15f
        values[DET_BOTTOM] = 10f

        assertThat(costOf(values)).isWithin(TOLERANCE).of(1f / 3f)
    }

    @Test
    fun headDistance_isScaledByHalfTheTrackHeight() {
        // h = 100, so the scale is 50 px; a head 25 px away adds 0.5 * 25 / 50.
        val values = scene()
        values[MEAS_X] = values[PRED_X] + 25f
        values[VAR_X] = LARGE_VARIANCE
        values[VAR_Y] = LARGE_VARIANCE

        assertThat(costOf(values)).isWithin(TOLERANCE).of(0.25f)
    }

    @Test
    fun headDistance_isCappedAtOne() {
        val values = scene()
        values[MEAS_Y] = values[PRED_Y] + 80f
        values[VAR_X] = LARGE_VARIANCE
        values[VAR_Y] = LARGE_VARIANCE

        assertThat(costOf(values)).isWithin(TOLERANCE).of(0.5f)
    }

    @Test
    fun headDistance_usesBothAxes() {
        // 3-4-5 triangle scaled by 5: distance 25 px, cost 0.5 * 25 / 50.
        val values = scene()
        values[MEAS_X] = values[PRED_X] + 15f
        values[MEAS_Y] = values[PRED_Y] + 20f
        values[VAR_X] = LARGE_VARIANCE
        values[VAR_Y] = LARGE_VARIANCE

        assertThat(costOf(values)).isWithin(TOLERANCE).of(0.25f)
    }

    @Test
    fun weightsAndScale_comeFromTheConfig() {
        val custom = TrackerConfig(assocIouWeight = 0.8f, assocHeadWeight = 0.2f, assocHeadScaleRatio = 1.0f)
        val values = scene()
        values[DET_LEFT] = 200f
        values[DET_RIGHT] = 240f
        values[MEAS_X] = values[PRED_X] + 25f
        values[VAR_X] = LARGE_VARIANCE
        values[VAR_Y] = LARGE_VARIANCE

        // IoU 0 -> 0.8 * 1; head 25 / (1.0 * 100) = 0.25 -> 0.2 * 0.25.
        assertThat(costOf(values, custom)).isWithin(TOLERANCE).of(0.85f)
    }

    @Test
    fun mahalanobisAbove9point21_isForbidden() {
        val inside = scene()
        inside[MEAS_X] = inside[PRED_X] + 30f
        inside[VAR_X] = 100f
        val outside = inside.copyOf()
        outside[MEAS_X] = outside[PRED_X] + 31f

        assertThat(costOf(inside)).isLessThan(Assigner.FORBIDDEN)
        assertThat(costOf(outside)).isEqualTo(Assigner.FORBIDDEN)
    }

    @Test
    fun mahalanobisExactlyAtTheGate_isAllowed() {
        val gate9 = TrackerConfig(trackGateChi2 = 9.0f)
        val values = scene()
        values[MEAS_X] = values[PRED_X] + 30f
        values[VAR_X] = 100f

        assertThat(costOf(values, gate9)).isLessThan(Assigner.FORBIDDEN)
    }

    @Test
    fun mahalanobis_treatsTheTwoAxesIndependently() {
        val tightX = scene()
        tightX[MEAS_Y] = tightX[PRED_Y] + 20f
        tightX[VAR_X] = 1f
        tightX[VAR_Y] = 100f
        val tightY = scene()
        tightY[MEAS_X] = tightY[PRED_X] + 20f
        tightY[VAR_X] = 1f
        tightY[VAR_Y] = 100f

        // 20^2 / 100 = 4 passes; 20^2 / 1 = 400 does not.
        assertThat(costOf(tightX)).isLessThan(Assigner.FORBIDDEN)
        assertThat(costOf(tightY)).isEqualTo(Assigner.FORBIDDEN)
    }

    @Test
    fun boxWithZeroOrNegativeArea_hasIouZero() {
        val flat = scene()
        flat[TRACK_LEFT] = 10f
        flat[TRACK_RIGHT] = 10f
        flat[DET_LEFT] = 10f
        flat[DET_RIGHT] = 10f
        val inverted = scene()
        inverted[TRACK_LEFT] = 40f
        inverted[TRACK_RIGHT] = 0f
        inverted[DET_LEFT] = 40f
        inverted[DET_RIGHT] = 0f

        assertThat(costOf(flat)).isWithin(TOLERANCE).of(0.5f)
        assertThat(costOf(inverted)).isWithin(TOLERANCE).of(0.5f)
    }

    @Test
    fun anyNonFiniteInput_isForbidden() {
        for (index in 0 until INPUT_COUNT) {
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val values = scene()
                values[index] = bad

                assertWithMessage("input $index = $bad").that(costOf(values)).isEqualTo(Assigner.FORBIDDEN)
            }
        }
    }

    @Test
    fun nonPositiveVarianceOrHeight_isForbidden() {
        for (index in listOf(VAR_X, VAR_Y, TRACK_HEIGHT)) {
            for (bad in listOf(0f, -1f)) {
                val values = scene()
                values[index] = bad

                assertWithMessage("input $index = $bad").that(costOf(values)).isEqualTo(Assigner.FORBIDDEN)
            }
        }
    }

    @Test
    fun costIsNeverNegativeAndNeverAboveTheWeightSum() {
        val random = java.util.Random(SEED)
        repeat(500) {
            val values = scene()
            values[DET_LEFT] = random.nextFloat() * 60f
            values[DET_RIGHT] = values[DET_LEFT] + random.nextFloat() * 60f
            values[MEAS_X] = values[PRED_X] + (random.nextFloat() - 0.5f) * 40f
            values[MEAS_Y] = values[PRED_Y] + (random.nextFloat() - 0.5f) * 40f
            values[VAR_X] = LARGE_VARIANCE
            values[VAR_Y] = LARGE_VARIANCE

            assertThat(costOf(values)).isAtLeast(0f)
            assertThat(costOf(values)).isAtMost(config.assocIouWeight + config.assocHeadWeight)
        }
    }

    /** A track and a detection with the same box (0, 0, 40, 100) and the head at (20, 10); variance 25 px^2. */
    private fun scene(): FloatArray =
        floatArrayOf(
            0f,
            0f,
            40f,
            100f,
            0f,
            0f,
            40f,
            100f,
            20f,
            10f,
            20f,
            10f,
            25f,
            25f,
            100f,
        )

    private fun costOf(
        values: FloatArray,
        usedConfig: TrackerConfig = config,
    ): Float =
        AssociationCost.cost(
            trackLeftImg = values[TRACK_LEFT],
            trackTopImg = values[TRACK_TOP],
            trackRightImg = values[TRACK_RIGHT],
            trackBottomImg = values[TRACK_BOTTOM],
            detLeftImg = values[DET_LEFT],
            detTopImg = values[DET_TOP],
            detRightImg = values[DET_RIGHT],
            detBottomImg = values[DET_BOTTOM],
            predHeadXImg = values[PRED_X],
            predHeadYImg = values[PRED_Y],
            measHeadXImg = values[MEAS_X],
            measHeadYImg = values[MEAS_Y],
            innovVarXImg2 = values[VAR_X],
            innovVarYImg2 = values[VAR_Y],
            trackHeightImg = values[TRACK_HEIGHT],
            config = usedConfig,
        )

    private companion object {
        const val TRACK_LEFT = 0
        const val TRACK_TOP = 1
        const val TRACK_RIGHT = 2
        const val TRACK_BOTTOM = 3
        const val DET_LEFT = 4
        const val DET_TOP = 5
        const val DET_RIGHT = 6
        const val DET_BOTTOM = 7
        const val PRED_X = 8
        const val PRED_Y = 9
        const val MEAS_X = 10
        const val MEAS_Y = 11
        const val VAR_X = 12
        const val VAR_Y = 13
        const val TRACK_HEIGHT = 14
        const val INPUT_COUNT = 15
        const val LARGE_VARIANCE = 10_000f
        const val TOLERANCE = 1e-5f
        const val SEED = 7L
    }
}
