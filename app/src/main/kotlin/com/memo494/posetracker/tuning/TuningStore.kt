package com.memo494.posetracker.tuning

import android.content.Context
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.modelimport.WorkerThreadCheck
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The saved tuning sheet: one small text file (`TuningOverrides.encode`) in an app-private directory, the same
 * pattern as the model library (`noBackupFilesDir`; written to a scratch file, synced, renamed over the old one, so a
 * process death leaves the old or the new file, never half of one). A file instead of `SharedPreferences` because the
 * values are one self-describing record that [TuningOverrides] already reads, checks against the current bounds and
 * counts; preferences would need a second copy of that logic.
 *
 * Threading: [read] and [write] do disk work and throw [IllegalStateException] on the main thread.
 *
 * Fail closed: a missing file is no overrides; a file that cannot be read or is not a tuning file is no overrides plus
 * one log line (the next [write] replaces it); a value outside the current bounds or of an unknown key is ignored.
 */
internal class TuningStore(
    private val directory: File,
    private val threadCheck: () -> Unit,
    private val rename: (File, File) -> Boolean,
    private val readFile: (File) -> String,
    private val log: (String, Throwable?) -> Unit,
) {
    /** The production store, in `noBackupFilesDir/tuning/`. */
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, DIRECTORY),
        { WorkerThreadCheck.require() },
        File::renameTo,
        { it.readText(Charsets.UTF_8) },
        { message, error -> AppLog.log(message, error) },
    )

    private val target: File get() = File(directory, FILE_NAME)

    /** The saved overrides; never throws (except on the main thread), see the class text. */
    fun read(): TuningOverrides {
        threadCheck()
        synchronized(LOCK) {
            val file = target
            if (!file.exists()) return TuningOverrides.NONE
            val text =
                try {
                    readFile(file)
                } catch (t: Throwable) {
                    log("tuning: file unreadable, using the defaults", t)
                    return TuningOverrides.NONE
                }
            val decoded = TuningOverrides.decode(text)
            if (decoded == null) log("tuning: file is not a tuning file, using the defaults", null)
            return decoded ?: TuningOverrides.NONE
        }
    }

    /** Saves [overrides]; `false` (logged once, scratch file removed, the old file kept) when it could not be written. */
    fun write(overrides: TuningOverrides): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val scratch = File(directory, FILE_NAME + SCRATCH_SUFFIX)
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
                FileOutputStream(scratch).use { output ->
                    output.write(overrides.encode().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                val file = target
                if (file.exists() && !file.isFile) file.deleteRecursively()
                if (!rename(scratch, file)) throw IOException("cannot rename $scratch")
                return true
            } catch (t: Throwable) {
                scratch.delete()
                log("tuning: saving failed", t)
                return false
            }
        }
    }

    companion object {
        /** Directory inside `noBackupFilesDir`. */
        const val DIRECTORY = "tuning"

        /** Name of the saved file. */
        const val FILE_NAME = "tuning.txt"

        private const val SCRATCH_SUFFIX = ".tmp"

        /** One lock for the whole process: a write and a read never overlap. */
        private val LOCK = Any()
    }
}
