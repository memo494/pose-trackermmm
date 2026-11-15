package com.memo494.posetracker.capture

import android.os.Build
import android.os.PowerManager
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.stats.ThermalReader

/**
 * [ThermalReader] over the platform [PowerManager]. Android glue, not covered by JVM tests (the sampling rule
 * and the mapping are tested through a fake reader). Below the API level that offers a value the answer is "not
 * available": the status needs API 29, the headroom API 30; the app's minSdk is lower than both.
 *
 * @param powerManager the system service; `null` = not available.
 */
internal class PowerManagerThermalReader(
    private val powerManager: PowerManager?,
) : ThermalReader {
    override fun status(): Int =
        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            ThermalReader.UNAVAILABLE
        }

    override fun headroom(): Float =
        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(TrackerConfig.THERMAL_HEADROOM_FORECAST_SECONDS)
        } else {
            Float.NaN
        }
}
