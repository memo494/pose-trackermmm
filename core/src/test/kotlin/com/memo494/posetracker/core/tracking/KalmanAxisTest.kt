package com.memo494.posetracker.core.tracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

class KalmanAxisTest {
    private val axis = KalmanAxis()

    @Test
    fun constantVelocityTarget_withoutNoise_isTrackedExactlyAfterSixtySteps() {
        axis.reset(START_POSITION, MEASUREMENT_VARIANCE, INITIAL_VELOCITY_VARIANCE)

        for (i in 1..60) {
            axis.predict(DT_20_FPS, SIGMA_ACCEL)
            axis.update(truthAt(i), MEASUREMENT_VARIANCE)
        }

        assertThat(abs(axis.position - truthAt(60))).isLessThan(0.01f)
        assertThat(abs(axis.velocity - VELOCITY)).isLessThan(0.01f)
    }

    @Test
    fun constantVelocityTarget_withSeededNoise_staysWithinFiveSigmaOfTheTruth() {
        val random = Random(SEED)
        axis.reset(START_POSITION, MEASUREMENT_VARIANCE, INITIAL_VELOCITY_VARIANCE)
        var worstPositionError = 0f
        var worstVelocityError = 0f

        for (i in 1..300) {
            axis.predict(DT_20_FPS, SIGMA_ACCEL)
            axis.update(truthAt(i) + MEASUREMENT_SIGMA * random.nextGaussian().toFloat(), MEASUREMENT_VARIANCE)
            if (i > 100) {
                worstPositionError = maxOf(worstPositionError, abs(axis.position - truthAt(i)))
                worstVelocityError = maxOf(worstVelocityError, abs(axis.velocity - VELOCITY))
            }
        }

        // Steady-state posterior sigma is 1.09 px and 5.7 px/s for these numbers; the bounds are about 5.5 and 3.5 sigma.
        assertThat(worstPositionError).isLessThan(6f)
        assertThat(worstVelocityError).isLessThan(20f)
    }

    @Test
    fun predictOnly_twoHalfSteps_giveTheStateAndCovarianceOfOneFullStep_whenThereIsNoProcessNoise() {
        val full = movingAxis()
        val halves = movingAxis()

        full.predict(0.1f, 0f)
        halves.predict(0.05f, 0f)
        halves.predict(0.05f, 0f)

        assertStatesEqual(full, halves)
    }

    @Test
    fun predictOnly_twoHalfSteps_giveTheSameStateAsOneFullStep_withProcessNoise() {
        val full = movingAxis()
        val halves = movingAxis()

        full.predict(0.1f, SIGMA_ACCEL)
        halves.predict(0.05f, SIGMA_ACCEL)
        halves.predict(0.05f, SIGMA_ACCEL)

        assertThat(halves.position).isWithin(relative(full.position)).of(full.position)
        assertThat(halves.velocity).isWithin(relative(full.velocity)).of(full.velocity)
    }

    @Test
    fun processNoise_isPerStep_soTwoHalfStepsAddLessVelocityVarianceThanOneFullStep() {
        // The discrete white-noise acceleration model is not additive in dt: q * dt^2 for velocity, not q * dt.
        // Pinned here so nobody relies on a split step having the covariance of the whole step.
        val full = movingAxis()
        val halves = movingAxis()

        full.predict(0.1f, SIGMA_ACCEL)
        halves.predict(0.05f, SIGMA_ACCEL)
        halves.predict(0.05f, SIGMA_ACCEL)

        assertThat(halves.pVel).isLessThan(full.pVel)
    }

    @Test
    fun predict_matchesTheDiscreteWhiteNoiseAccelerationFormulaForOneStep() {
        axis.reset(10f, 4f, 9f)
        val dt = 0.1
        val q = SIGMA_ACCEL.toDouble() * SIGMA_ACCEL
        val expectedPPos = 4 + dt * dt * 9 + q * dt * dt * dt * dt / 4
        val expectedPPosVel = dt * 9 + q * dt * dt * dt / 2
        val expectedPVel = 9 + q * dt * dt

        axis.predict(0.1f, SIGMA_ACCEL)

        assertThat(axis.pPos.toDouble()).isWithin(1e-4 * expectedPPos).of(expectedPPos)
        assertThat(axis.pPosVel.toDouble()).isWithin(1e-4 * expectedPPosVel).of(expectedPPosVel)
        assertThat(axis.pVel.toDouble()).isWithin(1e-4 * expectedPVel).of(expectedPVel)
    }

    @Test
    fun inflate_scalesTheProcessNoiseVariance() {
        val plain = movingAxis()
        val inflated = movingAxis()
        val before = movingAxis()

        plain.predict(0.05f, SIGMA_ACCEL)
        inflated.predict(0.05f, SIGMA_ACCEL, 10f)

        val plainGrowth = plain.pVel - before.pVel
        val inflatedGrowth = inflated.pVel - before.pVel
        assertThat(inflatedGrowth).isWithin(1e-3f * inflatedGrowth).of(10f * plainGrowth)
    }

    @Test
    fun missDecay_halvesTheVelocityAfterTauTimesLnTwo() {
        val moving = movingAxis()
        val velocityBefore = moving.velocity
        assertThat(velocityBefore).isGreaterThan(1f)

        moving.decayVelocity(TAU_S * ln(2.0).toFloat(), TAU_S)

        assertThat(moving.velocity).isWithin(relative(velocityBefore / 2f)).of(velocityBefore / 2f)
    }

    @Test
    fun missDecay_inSmallStepsEqualsOneBigStep() {
        val whole = movingAxis()
        val parts = movingAxis()

        whole.decayVelocity(0.1f, TAU_S)
        repeat(10) { parts.decayVelocity(0.01f, TAU_S) }

        assertThat(parts.velocity).isWithin(relative(whole.velocity)).of(whole.velocity)
    }

    @Test
    fun missDecay_doesNotTouchThePosition() {
        val moving = movingAxis()
        val positionBefore = moving.position

        moving.decayVelocity(0.05f, TAU_S)

        assertThat(moving.position).isEqualTo(positionBefore)
    }

    @Test
    fun afterAHugeInnovation_inflatingTheNextPredictReconvergesFasterThanNot() {
        val plain = settledAxis()
        val inflated = settledAxis()
        // A jump of 20 measurement sigma: the innovation is "huge", so the tracker would inflate the next step.
        plain.predict(DT_20_FPS, JUMP_SIGMA_ACCEL)
        inflated.predict(DT_20_FPS, JUMP_SIGMA_ACCEL)
        plain.update(truthAt(JUMP_STEP + 1) + JUMP, MEASUREMENT_VARIANCE)
        inflated.update(truthAt(JUMP_STEP + 1) + JUMP, MEASUREMENT_VARIANCE)
        var plainError = 0f
        var inflatedError = 0f

        for (i in JUMP_STEP + 2..JUMP_STEP + 11) {
            val target = truthAt(i) + JUMP
            plain.predict(DT_20_FPS, JUMP_SIGMA_ACCEL)
            inflated.predict(DT_20_FPS, JUMP_SIGMA_ACCEL, if (i == JUMP_STEP + 2) 10f else 1f)
            plain.update(target, MEASUREMENT_VARIANCE)
            inflated.update(target, MEASUREMENT_VARIANCE)
            plainError += abs(plain.position - target)
            inflatedError += abs(inflated.position - target)
        }

        // The error summed over ten steps is about 19 percent smaller with the single inflated step.
        assertThat(inflatedError).isLessThan(0.9f * plainError)
    }

    @Test
    fun predict_withZeroNegativeOrNonFiniteDt_isANoOp() {
        for (dt in listOf(0f, -0.05f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val moving = movingAxis()
            val reference = movingAxis()

            moving.predict(dt, SIGMA_ACCEL, 10f)

            assertStatesEqual(reference, moving, exact = true)
        }
    }

    @Test
    fun predict_withBadNoiseArguments_isANoOp() {
        val badArguments =
            listOf(
                Float.NaN to 1f,
                -1f to 1f,
                Float.POSITIVE_INFINITY to 1f,
                SIGMA_ACCEL to Float.NaN,
                SIGMA_ACCEL to 0f,
                SIGMA_ACCEL to -1f,
                SIGMA_ACCEL to Float.POSITIVE_INFINITY,
            )
        for ((sigma, inflate) in badArguments) {
            val moving = movingAxis()

            moving.predict(0.05f, sigma, inflate)

            assertStatesEqual(movingAxis(), moving, exact = true)
        }
    }

    @Test
    fun aRepeatedTimestamp_doesNotChangeTheStateAndNeverProducesNan() {
        val moving = movingAxis()
        val reference = movingAxis()
        moving.predict(0.05f, SIGMA_ACCEL)
        reference.predict(0.05f, SIGMA_ACCEL)

        moving.predict(0f, SIGMA_ACCEL)
        moving.decayVelocity(0f, TAU_S)

        assertStatesEqual(reference, moving, exact = true)
    }

    @Test
    fun decayVelocity_withBadArguments_isANoOp() {
        val badArguments =
            listOf(
                0f to TAU_S,
                -0.1f to TAU_S,
                Float.NaN to TAU_S,
                Float.POSITIVE_INFINITY to TAU_S,
                0.05f to 0f,
                0.05f to -TAU_S,
                0.05f to Float.NaN,
            )
        for ((dt, tau) in badArguments) {
            val moving = movingAxis()

            moving.decayVelocity(dt, tau)

            assertStatesEqual(movingAxis(), moving, exact = true)
        }
    }

    @Test
    fun update_withNonFiniteMeasurement_isIgnoredAndReturnsFalse() {
        for (z in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val moving = movingAxis()

            val accepted = moving.update(z, MEASUREMENT_VARIANCE)

            assertThat(accepted).isFalse()
            assertStatesEqual(movingAxis(), moving, exact = true)
        }
    }

    @Test
    fun update_withVarianceThatIsNotFiniteAndPositive_isIgnoredAndReturnsFalse() {
        for (variance in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val moving = movingAxis()

            val accepted = moving.update(50f, variance)

            assertThat(accepted).isFalse()
            assertStatesEqual(movingAxis(), moving, exact = true)
        }
    }

    @Test
    fun update_withAGoodMeasurement_returnsTrueAndMovesTowardsIt() {
        val moving = movingAxis()
        val before = moving.position

        val accepted = moving.update(before + 10f, MEASUREMENT_VARIANCE)

        assertThat(accepted).isTrue()
        assertThat(moving.position).isGreaterThan(before)
        assertThat(moving.position).isLessThan(before + 10f)
    }

    @Test
    fun innovation_isTheMeasurementMinusThePrediction_andItsVarianceIsPPosPlusR() {
        axis.reset(100f, 4f, 9f)
        axis.predict(0.1f, SIGMA_ACCEL)

        assertThat(axis.innovation(130f)).isEqualTo(30f)
        assertThat(axis.innovationVariance(5f)).isWithin(1e-4f * (axis.pPos + 5f)).of(axis.pPos + 5f)
    }

    @Test
    fun innovationAndVariance_doNotChangeTheState() {
        val moving = movingAxis()

        moving.innovation(77f)
        moving.innovationVariance(3f)

        assertStatesEqual(movingAxis(), moving, exact = true)
    }

    @Test
    fun reset_clearsTheVelocityAndSetsTheCovariance() {
        val moving = movingAxis()
        assertThat(moving.velocity).isNotEqualTo(0f)

        val accepted = moving.reset(5f, 2f, 3f)

        assertThat(accepted).isTrue()
        assertThat(moving.position).isEqualTo(5f)
        assertThat(moving.velocity).isEqualTo(0f)
        assertThat(moving.pPos).isEqualTo(2f)
        assertThat(moving.pPosVel).isEqualTo(0f)
        assertThat(moving.pVel).isEqualTo(3f)
    }

    @Test
    fun reset_withBadArguments_isIgnoredAndReturnsFalse() {
        val badArguments =
            listOf(
                Triple(Float.NaN, 1f, 1f),
                Triple(Float.POSITIVE_INFINITY, 1f, 1f),
                Triple(1f, 0f, 1f),
                Triple(1f, -1f, 1f),
                Triple(1f, Float.NaN, 1f),
                Triple(1f, 1f, 0f),
                Triple(1f, 1f, Float.POSITIVE_INFINITY),
            )
        for ((position, positionVariance, velocityVariance) in badArguments) {
            val moving = movingAxis()

            val accepted = moving.reset(position, positionVariance, velocityVariance)

            assertThat(accepted).isFalse()
            assertStatesEqual(movingAxis(), moving, exact = true)
        }
    }

    @Test
    fun covarianceStaysSymmetricPositiveAndFinite_over10000RandomSteps_atFourFrameRates() {
        for (dt in listOf(1f / 60f, 1f / 30f, 1f / 20f, 0.1f)) {
            val random = Random(SEED)
            val filter = KalmanAxis()
            filter.reset(0f, 25f, 400f)
            var truth = 0f

            for (step in 1..10_000) {
                truth += 300f * (random.nextFloat() - 0.5f) * dt
                filter.predict(dt, 1f + 200f * random.nextFloat(), if (random.nextInt(10) == 0) 10f else 1f)
                assertCovarianceValid(filter, dt, step)
                if (random.nextInt(8) == 0) {
                    filter.decayVelocity(dt, TAU_S)
                } else {
                    val measurementVariance = 0.01f + 100f * random.nextFloat()
                    val jump = if (random.nextInt(50) == 0) 500f else 0f
                    filter.update(truth + jump + 3f * random.nextGaussian().toFloat(), measurementVariance)
                }
                assertCovarianceValid(filter, dt, step)
            }
        }
    }

    @Test
    fun steadyState_atTrackingIndexTwoThirds_positionGainIsAlphaOfTheAlphaBetaFilter() {
        // Tracking index lambda = sigmaAccel * dt^2 / sigmaMeas = 1600 * 0.0025 / 6 = 0.667 -> alpha = 0.68, beta = 0.377.
        val dt = 0.05f
        val measurementVariance = 36f
        axis.reset(0f, measurementVariance, 100f)

        repeat(500) {
            axis.predict(dt, 1600f)
            axis.update(0f, measurementVariance)
        }
        axis.predict(dt, 1600f)

        assertThat(axis.positionGain(measurementVariance)).isWithin(0.02f).of(0.68f)
        assertThat(axis.pPosVel / (axis.pPos + measurementVariance) * dt).isWithin(0.02f).of(0.377f)
    }

    @Test
    fun steadyState_atTrackingIndexOne_gainsAreAlphaThreeQuartersAndBetaOneHalf() {
        // lambda = 2400 * 0.0025 / 6 = 1: alpha = 0.75 and beta = 0.5 exactly (beta^2 / (1 - alpha) = lambda^2).
        val dt = 0.05f
        val measurementVariance = 36f
        axis.reset(0f, measurementVariance, 100f)

        repeat(500) {
            axis.predict(dt, 2400f)
            axis.update(0f, measurementVariance)
        }
        axis.predict(dt, 2400f)

        assertThat(axis.positionGain(measurementVariance)).isWithin(0.01f).of(0.75f)
        assertThat(axis.pPosVel / (axis.pPos + measurementVariance) * dt).isWithin(0.01f).of(0.5f)
    }

    private fun truthAt(step: Int): Float = START_POSITION + VELOCITY * step * DT_20_FPS

    /** A filter that has settled on the constant-velocity target for [JUMP_STEP] steps. */
    private fun settledAxis(): KalmanAxis {
        val filter = KalmanAxis()
        filter.reset(START_POSITION, MEASUREMENT_VARIANCE, INITIAL_VELOCITY_VARIANCE)
        for (i in 1..JUMP_STEP) {
            filter.predict(DT_20_FPS, JUMP_SIGMA_ACCEL)
            filter.update(truthAt(i), MEASUREMENT_VARIANCE)
        }
        return filter
    }

    /** A filter with a non-zero velocity and a full covariance matrix; identical on every call. */
    private fun movingAxis(): KalmanAxis {
        val filter = KalmanAxis()
        filter.reset(100f, 4f, 400f)
        filter.predict(0.05f, SIGMA_ACCEL)
        filter.update(105f, 4f)
        filter.predict(0.05f, SIGMA_ACCEL)
        filter.update(112f, 4f)
        return filter
    }

    private fun relative(value: Float): Float = 1e-4f * abs(value)

    private fun assertStatesEqual(
        expected: KalmanAxis,
        actual: KalmanAxis,
        exact: Boolean = false,
    ) {
        val values = listOf(expected.position, expected.velocity, expected.pPos, expected.pPosVel, expected.pVel)
        val got = listOf(actual.position, actual.velocity, actual.pPos, actual.pPosVel, actual.pVel)
        for (i in values.indices) {
            val tolerance = if (exact) 0f else relative(values[i])
            assertThat(got[i]).isWithin(tolerance).of(values[i])
        }
    }

    private fun assertCovarianceValid(
        filter: KalmanAxis,
        dt: Float,
        step: Int,
    ) {
        val label = "dt=$dt step=$step"
        assertThat(filter.position.isFinite()).isTrue()
        assertThat(filter.velocity.isFinite()).isTrue()
        assertThat(filter.pPos.isFinite()).isTrue()
        assertThat(filter.pPosVel.isFinite()).isTrue()
        assertThat(filter.pVel.isFinite()).isTrue()
        assertThat(filter.pPos > 0f).isTrue()
        assertThat(filter.pVel > 0f).isTrue()
        val crossSquared = filter.pPosVel.toDouble() * filter.pPosVel
        val product = filter.pPos.toDouble() * filter.pVel
        assertWithMessage(label).that(crossSquared <= product).isTrue()
    }

    private companion object {
        const val SEED = 20_261_115L
        const val DT_20_FPS = 0.05f
        const val SIGMA_ACCEL = 50f
        const val JUMP_SIGMA_ACCEL = 200f
        const val JUMP = 40f
        const val JUMP_STEP = 100
        const val MEASUREMENT_SIGMA = 2f
        const val MEASUREMENT_VARIANCE = 4f
        const val INITIAL_VELOCITY_VARIANCE = 400f
        const val START_POSITION = 100f
        const val VELOCITY = 40f
        const val TAU_S = 0.1f
    }
}
