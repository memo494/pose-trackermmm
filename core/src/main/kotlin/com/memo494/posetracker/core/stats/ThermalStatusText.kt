package com.memo494.posetracker.core.stats

/** Names of the platform thermal statuses (`android.os.PowerManager.THERMAL_STATUS_*`) for the diagnostics. */
object ThermalStatusText {
    /** Text for a status that is not known. */
    const val NOT_AVAILABLE = "n/a"

    // The values of PowerManager.THERMAL_STATUS_NONE .. THERMAL_STATUS_SHUTDOWN; core has no android.* access.
    private const val NONE = 0
    private const val LIGHT = 1
    private const val MODERATE = 2
    private const val SEVERE = 3
    private const val CRITICAL = 4
    private const val EMERGENCY = 5
    private const val SHUTDOWN = 6

    /** The name of the platform [status] (`NONE` to `SHUTDOWN`); [NOT_AVAILABLE] for any other value. */
    fun of(status: Int): String =
        when (status) {
            NONE -> "NONE"
            LIGHT -> "LIGHT"
            MODERATE -> "MODERATE"
            SEVERE -> "SEVERE"
            CRITICAL -> "CRITICAL"
            EMERGENCY -> "EMERGENCY"
            SHUTDOWN -> "SHUTDOWN"
            else -> NOT_AVAILABLE
        }
}
