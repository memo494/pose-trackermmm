package com.memo494.posetracker.core.tracking

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A constant-velocity Kalman filter for one axis (ARCHITECTURE §6.3; the tracker runs three of them: head x,
 * head y and box height). State: position and velocity. The class is unit-neutral; the tracker uses Img px
 * for the position and Img px/s for the velocity, so the covariance is in px², px·px/s and (px/s)².
 *
 * Model: discrete white-noise acceleration. With `q = sigmaAccel² · inflate` a step of `dt` seconds does
 * `position += velocity · dt` and `P = F P Fᵀ + q · [[dt⁴/4, dt³/2], [dt³/2, dt²]]`. Process noise is a
 * per-step quantity of `dt`, not additive over sub-steps: two half steps add less velocity variance than one
 * whole step. The alpha-beta tracking index `sigmaAccel · dt² / sigmaMeas` relies on exactly this model.
 *
 * Covariance guard: the 2x2 covariance is kept as three floats ([pPos], [pPosVel], [pVel]) and computed with
 * `Double` intermediates. After every step it is forced to be valid: [pPos] and [pVel] are raised to a tiny
 * positive floor ([MIN_VARIANCE]) and `|pPosVel|` is limited to [MAX_CORRELATION] times `sqrt(pPos · pVel)`,
 * so `pPos > 0`, `pVel > 0` and `pPosVel² <= pPos · pVel` hold whatever rounding does. A step whose result
 * would not be finite is dropped as a whole (fail closed: the state stays as it was).
 *
 * Not thread-safe (the tracker runs on one thread). No method allocates (AGENTS rule 8).
 */
class KalmanAxis {
    /** Estimated position (Img px for the tracker). */
    var position: Float = 0f
        private set

    /** Estimated velocity (position unit per second; Img px/s for the tracker). */
    var velocity: Float = 0f
        private set

    /** Position variance (position unit squared); always > 0. */
    var pPos: Float = MIN_VARIANCE
        private set

    /** Position-velocity covariance; `pPosVel² <= pPos · pVel` always. */
    var pPosVel: Float = 0f
        private set

    /** Velocity variance ((position unit per second) squared); always > 0. */
    var pVel: Float = MIN_VARIANCE
        private set

    /**
     * Starts the filter at [position] with velocity 0 and a diagonal covariance. Returns false and changes
     * nothing when [position] is not finite or either variance is not finite and positive.
     */
    fun reset(
        position: Float,
        positionVariance: Float,
        velocityVariance: Float,
    ): Boolean {
        if (!position.isFinite() || !isUsableVariance(positionVariance) || !isUsableVariance(velocityVariance)) {
            return false
        }
        return commit(position.toDouble(), 0.0, positionVariance.toDouble(), 0.0, velocityVariance.toDouble())
    }

    /**
     * Moves the state forward by [dtS] seconds: constant velocity plus process noise `q = sigmaAccel² · inflate`.
     * [inflate] is the adaptive-noise factor (1 normally, 10 for one step after a gate failure). A [dtS] that
     * is not finite and positive (a repeated timestamp, a clock going backwards), a negative or non-finite
     * [sigmaAccel], or an [inflate] that is not finite and positive is a no-op.
     */
    fun predict(
        dtS: Float,
        sigmaAccel: Float,
        inflate: Float = 1f,
    ) {
        if (!isPositive(dtS) || !(sigmaAccel >= 0f && sigmaAccel.isFinite()) || !isPositive(inflate)) {
            return
        }
        val dt = dtS.toDouble()
        val q = sigmaAccel.toDouble() * sigmaAccel * inflate
        val dt2 = dt * dt
        commit(
            position = position + velocity * dt,
            velocity = velocity.toDouble(),
            pPos = pPos + dt * (2.0 * pPosVel + dt * pVel) + q * dt2 * dt2 / 4.0,
            pPosVel = pPosVel + dt * pVel + q * dt2 * dt / 2.0,
            pVel = pVel + q * dt2,
        )
    }

    /** The innovation `z - position` (same unit as the position) for gating, before [update]. NaN for a NaN [z]. */
    fun innovation(z: Float): Float = z - position

    /** The innovation variance `pPos + R` for gating, before [update]; [measurementVariance] is `R`. */
    fun innovationVariance(measurementVariance: Float): Float = pPos + measurementVariance

    /** The Kalman gain of the position, `pPos / (pPos + R)` (0 to 1): how far an update moves the position. */
    fun positionGain(measurementVariance: Float): Float = pPos / (pPos + measurementVariance)

    /**
     * Standard Kalman update with the measurement [z] of the position and its variance [measurementVariance].
     * Returns false and leaves the state untouched when [z] is not finite or the variance is not finite and
     * positive (fail closed: a bad measurement never moves the track).
     */
    fun update(
        z: Float,
        measurementVariance: Float,
    ): Boolean {
        if (!z.isFinite() || !isUsableVariance(measurementVariance)) {
            return false
        }
        val r = measurementVariance.toDouble()
        val s = pPos + r
        val gainPos = pPos / s
        val gainVel = pPosVel / s
        val innovation = z.toDouble() - position
        return commit(
            position = position + gainPos * innovation,
            velocity = velocity + gainVel * innovation,
            pPos = pPos * r / s,
            pPosVel = pPosVel * r / s,
            pVel = pVel - pPosVel.toDouble() * pPosVel / s,
        )
    }

    /**
     * Miss handling: `velocity *= exp(-dtS / tauS)`. The position and the covariance are left alone. A [dtS]
     * or [tauS] that is not finite and positive is a no-op.
     */
    fun decayVelocity(
        dtS: Float,
        tauS: Float,
    ) {
        if (!isPositive(dtS) || !isPositive(tauS)) {
            return
        }
        val decayed = velocity * exp(-dtS.toDouble() / tauS)
        if (decayed.isFinite()) {
            velocity = decayed.toFloat()
        }
    }

    /**
     * Stores a new state after clamping the covariance (see the class KDoc). Returns false and stores nothing
     * when any value is not finite once rounded to float.
     */
    private fun commit(
        position: Double,
        velocity: Double,
        pPos: Double,
        pPosVel: Double,
        pVel: Double,
    ): Boolean {
        val newPosition = position.toFloat()
        val newVelocity = velocity.toFloat()
        val newPPos = maxOf(pPos.toFloat(), MIN_VARIANCE)
        val newPVel = maxOf(pVel.toFloat(), MIN_VARIANCE)
        val crossLimit = MAX_CORRELATION * sqrt(newPPos.toDouble() * newPVel)
        val rawCross = pPosVel.toFloat()
        val newPPosVel = if (abs(rawCross) > crossLimit) Math.copySign(crossLimit, rawCross.toDouble()).toFloat() else rawCross
        val allFinite =
            newPosition.isFinite() && newVelocity.isFinite() && newPPos.isFinite() && newPVel.isFinite() && newPPosVel.isFinite()
        if (!allFinite) {
            return false
        }
        this.position = newPosition
        this.velocity = newVelocity
        this.pPos = newPPos
        this.pPosVel = newPPosVel
        this.pVel = newPVel
        return true
    }

    private fun isPositive(value: Float): Boolean = value > 0f && value.isFinite()

    private fun isUsableVariance(variance: Float): Boolean = isPositive(variance)

    private companion object {
        /** Numerical floor of the two variances, in the squared unit of the axis; far below any real variance. */
        const val MIN_VARIANCE = 1e-9f

        /** Upper limit of `|pPosVel| / sqrt(pPos · pVel)`; just below 1 so float rounding cannot break the invariant. */
        const val MAX_CORRELATION = 0.999_999
    }
}
