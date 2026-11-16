package com.memo494.posetracker.core.tracking

/**
 * Lifecycle state of a track (docs/plans/M3.md, T5 b).
 *
 * `TENTATIVE`: started recently, not yet matched often enough to be shown. `CONFIRMED`: shown. `LOST`: confirmed,
 * but without a matching detection in the newest frame (still shown, its head point is predicted).
 */
enum class TrackState {
    TENTATIVE,
    CONFIRMED,
    LOST,
}
