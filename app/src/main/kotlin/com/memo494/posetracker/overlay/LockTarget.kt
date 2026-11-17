package com.memo494.posetracker.overlay

/**
 * Where the overlay's lock controls send the user's choice (ARCHITECTURE §6.6). Implemented by the capture session
 * on top of the frame pipeline; every method is thread-safe and cheap, and called on the main thread. The lock is
 * display-only: it only makes the overlay draw a person's rings differently, it never touches another app.
 */
interface LockTarget {
    /** Whether a person is locked (or a lock was just requested). */
    val hasLock: Boolean

    /** Locks the track with [id]; the lock ends by itself when that track is no longer shown. */
    fun lock(id: Int)

    /** Ends the lock. */
    fun unlock()
}
