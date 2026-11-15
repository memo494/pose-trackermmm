package com.memo494.posetracker.modelimport

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportGuard
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelpack.Sha256
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What is stored about the one imported model (M0c D1, D4).
 *
 * @property meta the stored description, or `null` when there is none or it does not decode.
 * @property fileExists whether the file named by [meta] (`ImportedModelStore.modelFileFor`) exists; it is only
 *   meaningful together with [meta].
 * @property guard the crash marker, with the app-start step already applied (a stale `Pending` is `Tripped`).
 * @property anyFileStored whether any stored model file exists, with or without a [meta] (an orphan left by a
 *   process death); the screen then offers **Use built-in model**, which removes it. Defaults to [fileExists].
 */
data class StoredModelState(
    val meta: ImportedModelMeta?,
    val fileExists: Boolean,
    val guard: ImportGuardState,
    val anyFileStored: Boolean = fileExists,
)

/** The one string value that holds the encoded [ImportGuardState]; the Android shell is [SharedPreferences]. */
internal interface GuardPrefs {
    /** The stored text, or `null` when nothing is stored. */
    fun read(): String?

    /** Stores [text] synchronously; `false` when it could not be written. */
    fun write(text: String): Boolean
}

/**
 * The one owner of everything on disk for the imported model: the directory `noBackupFilesDir/imported/` with
 * the model file `model-<first 16 hex of its sha256>.tflite` (named by content, so two different imports never
 * share a file name: the vendored GPU program cache is keyed by file name and byte size), one temporary file
 * `import.tflite` for the import in progress (it keeps the `.tflite` extension LiteRT may look at) and
 * `meta.txt`, plus the crash-marker value in `SharedPreferences`.
 *
 * Threading: every method does disk work, so each one throws [IllegalStateException] when called on the main
 * thread; call them from a worker thread (even the constructor is free of disk work, so it may be built anywhere).
 * Methods are serialised by one process-wide lock, so calls from the import thread and a capture thread are safe.
 *
 * Crash safety of [accept]: the store never leaves a `meta.txt` that describes a different file. The meta holds the
 * sha256 and the file name derives from it, so a meta only ever points at the file with its own name; the order
 * keeps that true at every instant. [accept] writes the new meta to a scratch file, deletes the old meta, renames
 * the model into its content name, stores the guard as `Idle`, renames the new meta into place, and only then
 * deletes the other model files. A process death before the last meta rename leaves no meta, which [state]
 * reports as "no imported model" (the stray model files are orphans that [clear] and the next [accept] remove);
 * a death after it leaves the new meta next to its own file and the old one as an orphan. The order "model
 * first, meta second" would leave the old meta next to the new content; the order "new meta first" would leave
 * the new meta without its file. When a step before the meta rename fails, the old meta is written back and the
 * old file was never touched, so the previous model stays usable. (`PoseDetectors.fromImported` re-checks
 * length and hash anyway.)
 */
class ImportedModelStore private constructor(
    private val directoryLazy: Lazy<File>,
    private val prefsLazy: Lazy<GuardPrefs>,
    private val appStartApplied: AtomicBoolean,
    private val threadCheck: () -> Unit,
    private val importInFlight: () -> Boolean,
    private val rename: (File, File) -> Boolean,
) {
    /** The production store: files under `noBackupFilesDir/imported/`, the marker in private preferences. */
    constructor(context: Context) : this(
        lazy { File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME) },
        lazy {
            SharedPreferencesGuardPrefs(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
        },
        PROCESS_APP_START_APPLIED,
        { WorkerThreadCheck.require() },
        { ModelImporter.isImporting() },
        File::renameTo,
    )

    /**
     * For tests: [directory] is the store's own directory, [guardPrefs] the marker value, [appStartApplied] the
     * "this process already applied the app-start step" flag (a fresh `AtomicBoolean(false)` is a fresh process),
     * [threadCheck] the "not on the main thread" check that every method runs first (the production one is
     * `WorkerThreadCheck.require`, which needs a real `Looper`; it throws [IllegalStateException] when it objects),
     * [importInFlight] ("an import is running now", the production one is `ModelImporter.isImporting`; the
     * first access of a process deletes a leftover temporary file only while it says `false`)
     * and [rename] the file move used by [accept] (`false` means it failed).
     */
    internal constructor(
        directory: File,
        guardPrefs: GuardPrefs,
        appStartApplied: AtomicBoolean,
        threadCheck: () -> Unit,
        importInFlight: () -> Boolean = { ModelImporter.isImporting() },
        rename: (File, File) -> Boolean = File::renameTo,
    ) : this(lazyOf(directory), lazyOf(guardPrefs), appStartApplied, threadCheck, importInFlight, rename)

    /** The file that holds the model described by [meta]: `model-<first 16 hex of sha256>.tflite`; it may not exist. */
    fun modelFileFor(meta: ImportedModelMeta): File {
        threadCheck()
        return File(directoryLazy.value, modelNameFor(meta))
    }

    /**
     * Whether the stored model file of [meta] exists, has [ImportedModelMeta.bytes] bytes and the sha256 of
     * [ImportedModelMeta.sha256] (length first, so a wrong file is not read). Reads the whole file, so call it
     * only when needed (after a probe trip), from a worker thread. Fail closed: any problem, an `Error` included,
     * is `false`. It takes no lock: [accept] names files by content, so a concurrent accept can at worst make the
     * file vanish, which reads as `false`.
     *
     * Like every method here it throws [IllegalStateException] on the main thread, and without reading the file:
     * the check runs before the `try`, so the catch-all cannot turn that refusal into `false`.
     */
    @Suppress("TooGenericExceptionCaught")
    fun storedFileMatches(meta: ImportedModelMeta): Boolean {
        threadCheck()
        val file = File(directoryLazy.value, modelNameFor(meta))
        return try {
            file.isFile && file.length() == meta.bytes && file.inputStream().use { Sha256.hex(it) } == meta.sha256
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * What is stored now. The first call in a process (this or any other method) applies [ImportGuard.onAppStart]
     * once and stores the result: a `Pending` left by a crashed run becomes `Tripped`, and a leftover temporary
     * file of a dead process is deleted (unless an import runs now: it owns that file). A meta that does not decode
     * or a missing file is simply "no imported model"; this never throws for bad data.
     */
    fun state(): StoredModelState {
        threadCheck()
        synchronized(LOCK) {
            val guard = currentGuard()
            val directory = directoryLazy.value
            val meta = readMeta(File(directory, META_NAME))
            val fileExists = meta != null && isFile(File(directory, modelNameFor(meta)))
            return StoredModelState(meta, fileExists, guard, anyFileStored = fileExists || modelFiles(directory).isNotEmpty())
        }
    }

    /**
     * A fresh, empty temporary file inside the store's directory (the same file system as the model files, so
     * [accept] can rename it). Any leftover from an earlier run is deleted first.
     *
     * Like every method here it is a store access: the first one of the process runs the app-start step (and with
     * it the cleanup of a leftover temporary file of a dead process) before a file is handed out, so the store can
     * never delete a temporary file that this process was given, whatever the in-flight flag says.
     *
     * @throws IOException when the directory cannot be created or the leftover cannot be deleted.
     */
    fun newTempFile(): File {
        threadCheck()
        synchronized(LOCK) {
            currentGuard()
            val directory = ensureDirectory() ?: throw IOException("Cannot create $DIRECTORY_NAME directory")
            val temp = File(directory, TEMP_NAME)
            if (temp.exists() && !temp.delete()) throw IOException("Cannot delete the old temporary file")
            return temp
        }
    }

    /**
     * Makes [temp] (from [newTempFile]) the stored model with the description [meta]; see the class comment for
     * the order. Also stores the guard as `Idle` inside the same lock (the new file replaces the one that may have
     * tripped it, and a process death after this call must not leave a stale trip), and removes every other model
     * file. Returns `false` when it failed; the previous model and meta are then unchanged (or, in the rare case
     * that even restoring the old meta failed, there is no meta: "no imported model"). [temp] is left for the
     * caller to delete on failure. Never leaves a meta that describes a different file.
     */
    fun accept(
        temp: File,
        meta: ImportedModelMeta,
    ): Boolean {
        threadCheck()
        synchronized(LOCK) {
            currentGuard()
            return try {
                acceptLocked(temp, meta)
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }

    /**
     * Deletes every model file, the temporary file and the meta, and sets the guard to `Idle` (lifting a trip).
     * Returns `true` only when everything is gone and the guard was stored. While an import runs it returns `false`
     * and changes nothing (no file deleted, the guard not written): the running import owns the temporary file and
     * the marker.
     */
    fun clear(): Boolean {
        threadCheck()
        synchronized(LOCK) {
            currentGuard()
            if (importInFlight()) return false
            val directory = directoryLazy.value
            // The meta goes first: a meta without a file is harmless, a file with a stale meta is not.
            var done = true
            for (name in listOf(META_NAME, META_TEMP_NAME, TEMP_NAME)) {
                done = deleteIfExists(File(directory, name)) && done
            }
            for (file in modelFiles(directory)) done = deleteIfExists(file) && done
            return prefsLazy.value.write(ImportGuard.encode(ImportGuard.clear())) && done
        }
    }

    /** Stores the crash marker synchronously, before the caller goes on; `false` when it could not be written. */
    fun setGuard(state: ImportGuardState): Boolean {
        threadCheck()
        synchronized(LOCK) {
            currentGuard()
            return prefsLazy.value.write(ImportGuard.encode(state))
        }
    }

    // ---- everything below runs under LOCK ----

    /** The stored guard; the first call of the process turns a stale `Pending` into `Tripped` and stores that. */
    private fun currentGuard(): ImportGuardState {
        val prefs = prefsLazy.value
        val stored = ImportGuard.decode(prefs.read())
        if (!appStartApplied.compareAndSet(false, true)) return stored
        // newTempFile() runs this step before it hands a file out, so on the first access of the process a temporary
        // file is a leftover of a dead process, never one of this process. The flag is a second guard: it keeps the
        // file of a running import when the step is applied again after a trip that could not be stored.
        if (!importInFlight()) deleteLeftoverTemp()
        val applied = ImportGuard.onAppStart(stored)
        // If the trip cannot be stored, apply it again on the next call: a stale Pending must not read as usable.
        if (applied != stored && !prefs.write(ImportGuard.encode(applied))) appStartApplied.set(false)
        return applied
    }

    /** Best effort: a leftover that cannot be deleted is deleted again by the next [newTempFile]. */
    private fun deleteLeftoverTemp() {
        try {
            File(directoryLazy.value, TEMP_NAME).delete()
        } catch (e: SecurityException) {
            // Nothing to do: see above.
        }
    }

    private fun acceptLocked(
        temp: File,
        meta: ImportedModelMeta,
    ): Boolean {
        val directory = ensureDirectory() ?: return false
        val modelFile = File(directory, modelNameFor(meta))
        val metaFile = File(directory, META_NAME)
        val metaTemp = File(directory, META_TEMP_NAME)
        val previousMeta = if (metaFile.isFile) readText(metaFile) else null

        if (!writeSynced(metaTemp, meta.encode())) return false
        if (metaFile.exists() && !metaFile.delete()) {
            metaTemp.delete()
            return false
        }
        val modelFileExistedBefore = modelFile.exists()
        if (!rename(temp, modelFile) || !prefsLazy.value.write(ImportGuard.encode(ImportGuard.clear()))) {
            // Nothing else changed yet: put the old meta back. A file that already had this name has the same
            // content (the name is the hash), so it stays; a file this call created is removed again.
            if (!modelFileExistedBefore) modelFile.delete()
            metaTemp.delete()
            if (previousMeta != null) writeAtomically(metaFile, metaTemp, previousMeta)
            return false
        }
        // The new model is in place; without the meta rename there is simply no usable imported model.
        if (!rename(metaTemp, metaFile)) {
            metaTemp.delete()
            return false
        }
        // Best effort: a file that cannot be deleted is an orphan, shown by state() and removed by clear().
        for (file in modelFiles(directory)) {
            if (file != modelFile) file.delete()
        }
        return true
    }

    private fun writeAtomically(
        target: File,
        scratch: File,
        text: String,
    ): Boolean {
        if (!writeSynced(scratch, text)) return false
        if (rename(scratch, target)) return true
        scratch.delete()
        return false
    }

    private fun ensureDirectory(): File? {
        val directory = directoryLazy.value
        return if (directory.isDirectory || directory.mkdirs() || directory.isDirectory) directory else null
    }

    private fun readMeta(file: File): ImportedModelMeta? {
        val text = if (isFile(file)) readText(file) else null
        return text?.let(ImportedModelMeta::decode)
    }

    /** Reading a damaged file must never crash a session, so even an `Error` (a huge file: OOM) means "no text". */
    @Suppress("TooGenericExceptionCaught")
    private fun readText(file: File): String? =
        try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }

    private fun isFile(file: File): Boolean =
        try {
            file.isFile
        } catch (e: SecurityException) {
            false
        }

    private fun writeSynced(
        file: File,
        text: String,
    ): Boolean =
        try {
            FileOutputStream(file).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            true
        } catch (e: IOException) {
            file.delete()
            false
        }

    /** Every stored model file in [directory] (any content name, and the pre-R1 `model.tflite`); never `null`. */
    private fun modelFiles(directory: File): List<File> =
        try {
            directory.listFiles { _, name -> isModelFileName(name) }?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun isModelFileName(name: String): Boolean =
        name == LEGACY_MODEL_NAME || (name.startsWith(MODEL_PREFIX) && name.endsWith(MODEL_SUFFIX))

    private fun modelNameFor(meta: ImportedModelMeta): String =
        MODEL_PREFIX + meta.sha256.take(TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH) + MODEL_SUFFIX

    private fun deleteIfExists(file: File): Boolean = !file.exists() || file.delete()

    /** The Android shell of [GuardPrefs]: one string in private preferences. */
    private class SharedPreferencesGuardPrefs(
        private val preferences: SharedPreferences,
    ) : GuardPrefs {
        override fun read(): String? = preferences.getString(KEY_GUARD, null)

        // commit(), not apply(): the marker must be on disk before LiteRT touches the file, in case it kills us.
        @SuppressLint("ApplySharedPref")
        override fun write(text: String): Boolean = preferences.edit().putString(KEY_GUARD, text).commit()
    }

    private companion object {
        const val DIRECTORY_NAME = "imported"
        const val MODEL_PREFIX = "model-"
        const val MODEL_SUFFIX = ".tflite"

        /** The name before review R1-5; no longer written, still removed. */
        const val LEGACY_MODEL_NAME = "model.tflite"

        /** Ends in `.tflite`: LiteRT may look at the extension when it opens a path (R1-4). */
        const val TEMP_NAME = "import.tflite"
        const val META_NAME = "meta.txt"
        const val META_TEMP_NAME = "meta.tmp"
        const val PREFS_NAME = "imported_model"
        const val KEY_GUARD = "guard"

        /** One lock for the whole process: every store instance shares the same files and preferences. */
        val LOCK = Any()

        /** Set once the app-start step ran in this process. */
        val PROCESS_APP_START_APPLIED = AtomicBoolean(false)
    }
}
