package com.memo494.posetracker.core

/**
 * Pure-Kotlin helpers shared by the tracking pipeline.
 *
 * `core` is a JVM module (ARCHITECTURE §3): no `android.*` here, so everything is unit-testable
 * on the JVM without a device. This file is a minimal M0a placeholder that proves the module and
 * its tests build; the real geometry and tracking types arrive in M2/M3.
 */
object CoreMath {
    /** Clamps [value] into the inclusive range [[min], [max]]. */
    fun clamp(
        value: Float,
        min: Float,
        max: Float,
    ): Float =
        when {
            value < min -> min
            value > max -> max
            else -> value
        }
}
