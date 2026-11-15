package com.memo494.posetracker.core.stats

/**
 * Reads the device's thermal state; the Android implementation sits in the app module so that [ThermalSampler]
 * can be tested with a fake. Both calls may throw; the sampler contains that.
 */
interface ThermalReader {
    /** The platform thermal status (`PowerManager.THERMAL_STATUS_*`), or [UNAVAILABLE] when this device cannot tell. */
    fun status(): Int

    /** The platform thermal headroom (unitless ratio, 1.0 = throttling), or NaN when unsupported or asked too often. */
    fun headroom(): Float

    companion object {
        /** Returned by [status] when it cannot be read (an API level below the one that offers it). */
        const val UNAVAILABLE = -1
    }
}

/**
 * Samples a [ThermalReader] no more often than every [intervalNs] and keeps the last answer. Never throws and
 * never guesses: whatever cannot be read is [ThermalStatusText.NOT_AVAILABLE] / NaN.
 *
 * Thread-confined: call [sample] from one thread (the capture thread, at the HUD tick).
 *
 * @param reader the source.
 * @param intervalNs shortest time between two reads, nanoseconds (`TrackerConfig.thermalSampleIntervalNs`).
 * @param logger receives one line when a read fails (the first failure only, so a broken service cannot flood
 *   the log); may throw, the sampler contains it.
 */
class ThermalSampler(
    private val reader: ThermalReader,
    private val intervalNs: Long,
    private val logger: (String, Throwable?) -> Unit,
) {
    private var sampled = false
    private var lastSampleNs = 0L
    private var failureLogged = false

    /** Name of the last status (`NONE` to `SHUTDOWN`), [ThermalStatusText.NOT_AVAILABLE] when unknown. */
    var status: String = ThermalStatusText.NOT_AVAILABLE
        private set

    /** The last headroom, a finite non-negative ratio; NaN when unknown. */
    var headroom: Float = Float.NaN
        private set

    /** Reads the thermal state when at least [intervalNs] has passed since the last read (the first call always reads). */
    fun sample(nowNs: Long) {
        if (sampled && nowNs - lastSampleNs < intervalNs) return
        sampled = true
        lastSampleNs = nowNs
        try {
            val newStatus = ThermalStatusText.of(reader.status())
            val newHeadroom = reader.headroom()
            status = newStatus
            headroom = if (newHeadroom.isFinite() && newHeadroom >= 0f) newHeadroom else Float.NaN
        } catch (e: Throwable) {
            status = ThermalStatusText.NOT_AVAILABLE
            headroom = Float.NaN
            logFailure(e)
        }
    }

    private fun logFailure(error: Throwable) {
        if (failureLogged) return
        failureLogged = true
        try {
            logger("reading the thermal state failed", error)
        } catch (e: Throwable) {
            // A failing logger must not break the capture thread; there is nowhere left to report it.
        }
    }
}
