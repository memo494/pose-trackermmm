package com.memo494.posetracker.capture

import com.memo494.posetracker.diagnostics.AppLog

/** Log sink of the capture package; it writes to the app log (diagnostics buffer and logcat). */
internal object CaptureLog {
    /** Logs [message]; a non-null [error] is logged as a warning with its stack trace. */
    fun log(
        message: String,
        error: Throwable?,
    ) {
        AppLog.log(message, error)
    }
}
