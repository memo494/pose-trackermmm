package com.memo494.posetracker.diagnostics

import android.util.Log
import com.memo494.posetracker.core.TrackerConfig
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The app's log: every line goes to the in-memory [RingLog] (shown on the log screen and put in the
 * diagnostics zip) and to logcat. Safe from any thread; logging never throws.
 */
internal object AppLog {
    private const val TAG = "PoseCapture"
    private const val TIME_PATTERN = "HH:mm:ss.SSS"
    private const val LEVEL_INFO = "I"
    private const val LEVEL_WARN = "W"

    private val ring = RingLog()
    private val timeFormat = DateTimeFormatter.ofPattern(TIME_PATTERN)

    /**
     * Logs [message]. A non-null [error] makes it a warning: the buffer entry gets the error's class and
     * message on the same line and, below it in the same entry, at most [TrackerConfig.LOG_STACK_LINES]
     * stack lines; logcat gets the full stack trace.
     */
    fun log(
        message: String,
        error: Throwable?,
    ) {
        try {
            ring.add(format(message, error))
        } catch (ignored: Throwable) {
            // Logging must never fail the caller; the logcat line below is still written.
        }
        try {
            if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
        } catch (ignored: Throwable) {
            // Same: nothing to do when logcat itself fails.
        }
    }

    /** The kept log lines, oldest first (an entry with a stack trace spans several text lines). */
    fun lines(): List<String> = ring.snapshot()

    private fun format(
        message: String,
        error: Throwable?,
    ): String {
        val out = StringBuilder()
        out.append(LocalTime.now().format(timeFormat)).append(' ')
        out.append(if (error != null) LEVEL_WARN else LEVEL_INFO).append(' ').append(message)
        if (error != null) {
            out.append(" | ${error.javaClass.name}: ${error.message}")
            for (frame in error.stackTrace.take(TrackerConfig.LOG_STACK_LINES)) {
                out.append("\n    at ").append(frame)
            }
        }
        return out.toString()
    }
}
