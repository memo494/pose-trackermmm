package com.memo494.posetracker.capture

import android.util.Log

/** Log sink of the capture package (T8 routes it to the diagnostics buffer). */
internal object CaptureLog {
    private const val TAG = "PoseCapture"

    /** Logs [message]; a non-null [error] is logged as a warning with its stack trace. */
    fun log(
        message: String,
        error: Throwable?,
    ) {
        if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
    }
}
