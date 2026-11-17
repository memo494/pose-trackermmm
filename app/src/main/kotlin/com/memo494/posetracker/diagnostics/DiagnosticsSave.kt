package com.memo494.posetracker.diagnostics

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure helpers of "Save diagnostics": the suggested file name and the stream copy. No Android types. */
internal object DiagnosticsSave {
    private const val NAME_PREFIX = "pose-tracker-diagnostics-"
    private const val NAME_SUFFIX = ".zip"
    private const val NAME_TIME_PATTERN = "yyyyMMdd-HHmmss"
    private const val COPY_BUFFER_BYTES = 8 * 1024

    private val nameTimeFormat = DateTimeFormatter.ofPattern(NAME_TIME_PATTERN, Locale.ROOT)

    /** `pose-tracker-diagnostics-<yyyyMMdd-HHmmss>.zip` for the time and zone of [clock]. */
    fun fileName(clock: Clock = Clock.systemDefaultZone()): String =
        NAME_PREFIX + LocalDateTime.now(clock).format(nameTimeFormat) + NAME_SUFFIX

    /**
     * Copies [input] to [output] and closes both. Returns `true` only when every byte was copied and both
     * streams closed; a `null` stream or any `Throwable` (an `Error` included) gives `false`.
     * File work: call it off the main thread.
     */
    @Suppress("TooGenericExceptionCaught")
    fun copy(
        input: InputStream?,
        output: OutputStream?,
    ): Boolean {
        var copied = false
        try {
            if (input != null && output != null) {
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.flush()
                copied = true
            }
        } catch (t: Throwable) {
            AppLog.log("copying the diagnostics zip failed", t)
        }
        val inputClosed = closeQuietly(input)
        val outputClosed = closeQuietly(output)
        return copied && inputClosed && outputClosed
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeQuietly(stream: Closeable?): Boolean =
        try {
            stream?.close()
            true
        } catch (t: Throwable) {
            AppLog.log("closing a diagnostics stream failed", t)
            false
        }
}
