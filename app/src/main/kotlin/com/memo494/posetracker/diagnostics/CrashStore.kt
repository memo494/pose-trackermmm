package com.memo494.posetracker.diagnostics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the stack trace of the last uncaught exception in `filesDir/diagnostics/last_crash.txt` so the next
 * start can offer it. [install] runs in `Application.onCreate`; [read] and [clear] touch a file, so the
 * activity calls them from a background dispatcher, never from the main thread at startup.
 */
internal object CrashStore {
    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "last_crash.txt"

    /**
     * Wraps the current default uncaught-exception handler: the crash is saved (any failure while saving is
     * logged and swallowed), then the previous handler always runs so the system behaves as before.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(appContext, thread, error)
            } catch (ignored: Throwable) {
                // Saving the crash must not hide it from the system handler below.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved crash text, or `null` when there is none or it cannot be read. Not for the main thread. */
    fun read(context: Context): String? =
        try {
            val file = fileIn(context)
            if (file.isFile) file.readText().ifBlank { null } else null
        } catch (e: Throwable) {
            AppLog.log("reading the saved crash failed", e)
            null
        }

    /** Deletes the saved crash. Not for the main thread. */
    fun clear(context: Context) {
        try {
            fileIn(context).delete()
        } catch (e: Throwable) {
            AppLog.log("clearing the saved crash failed", e)
        }
    }

    private fun save(
        context: Context,
        thread: Thread,
        error: Throwable,
    ) {
        val trace = StringWriter()
        PrintWriter(trace).use { error.printStackTrace(it) }
        val file = fileIn(context)
        file.parentFile?.mkdirs()
        file.writeText("thread=${thread.name}\ntime=${Instant.now()}\n\n$trace")
    }

    private fun fileIn(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)
}
