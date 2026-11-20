package com.memo494.posetracker.modelimport

import android.content.Context
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportGuard
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.LibraryAdd
import com.memo494.posetracker.core.modelimport.ModelBlocklist
import com.memo494.posetracker.core.modelimport.ModelLibrary
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.OrientationSlots
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.diagnostics.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One stored library model.
 *
 * @property meta what is stored about it.
 * @property fileExists whether its model file exists (the file is verified when it is loaded).
 */
data class LibraryEntry(
    val meta: ImportedModelMeta,
    val fileExists: Boolean,
)

/**
 * Everything the model library holds, as one immutable read (M1b-1).
 *
 * @property entries the stored models.
 * @property slots the model of each orientation; a slot that could not be read is built-in.
 * @property slotsUnreadable the orientations whose stored choice could not be read (built-in is used and the screen says so).
 * @property blocked the SHA-256 of the models that crashed LiteRT while loading: they are not used.
 * @property blockedUnreadable the blocked list exists but could not be read: fail closed, every model counts as blocked.
 * @property guard the crash marker, with the app-start step already applied.
 * @property anyFileStored whether any stored model file exists, also one no meta describes (an orphan).
 */
data class LibraryState(
    val entries: List<LibraryEntry>,
    val slots: OrientationSlots,
    val slotsUnreadable: Set<ModelOrientation>,
    val blocked: Set<String>,
    val blockedUnreadable: Boolean,
    val guard: ImportGuardState,
    val anyFileStored: Boolean,
) {
    /** The models as the pure [ModelLibrary]. */
    val library: ModelLibrary get() = ModelLibrary.of(entries.map { it.meta })

    /**
     * What the slot of [orientation] stores, in the form [ModelChoice.resolve] reads: the model of the slot with the
     * crash marker as it applies to that model (a model on the blocked list, or a marker that names no model, is a
     * tripped load; a marker that names another model is not). A built-in slot has no model.
     */
    fun viewFor(orientation: ModelOrientation): StoredModelState {
        val choice = slots.choiceFor(orientation)
        val entry = if (choice is SlotChoice.Library) entries.firstOrNull { it.meta.sha256 == choice.sha256 } else null
        if (entry == null) return StoredModelState(null, false, ImportGuardState.Idle, anyFileStored)
        return StoredModelState(entry.meta, entry.fileExists, guardFor(entry.meta.sha256), anyFileStored)
    }

    /** Whether the model with [sha256] is switched off by a crash at its load. */
    fun isBlocked(sha256: String): Boolean {
        val current = guardFor(sha256)
        return current is ImportGuardState.Tripped && current.stage == ImportStage.LOAD
    }

    private fun guardFor(sha256: String): ImportGuardState {
        if (blockedUnreadable || sha256 in blocked) return ImportGuardState.Tripped(ImportStage.LOAD, sha256)
        val current = guard
        val namesAnotherModel =
            current is ImportGuardState.Tripped &&
                current.stage == ImportStage.LOAD &&
                current.sha256 != null &&
                current.sha256 != sha256
        return if (namesAnotherModel) ImportGuardState.Idle else current
    }
}

/**
 * The model library on disk (M1b-1): up to [TrackerConfig.modelLibraryMax] imported models keyed by sha256, the
 * model of each orientation, and the models that crashed LiteRT.
 *
 * Layout under the root store's directory (`noBackupFilesDir/imported/`):
 * - `<first 16 hex of sha256>/` one directory per model, managed by an [ImportedModelStore] over that directory
 *   (the model file `model-<16 hex>.tflite` and `meta.txt`, written in the crash-safe order that store documents);
 * - `slots.txt` the choice of each orientation (`OrientationSlots`), `blocked.txt` the blocked sha256 values; each
 *   written to a scratch file, synced and renamed over the old one, so a read sees the old or the new text;
 * - `import.tflite` the temporary file of the import in progress.
 *
 * Migration: the single model of the version before the library lived in the root directory itself (`meta.txt` and
 * `model-*.tflite`). The first read moves it into its own directory, points both slots at it and removes the old
 * record; every step can be repeated, so a process death in between is finished by the next read. A load crash
 * recorded without a model by that version blocks the migrated model.
 *
 * Crash marker: the marker names the model being loaded. A marker found tripped for a model moves that model to
 * `blocked.txt` and the marker goes back to idle, so only that model is switched off and the others keep working.
 * Importing a model that is already blocked (same sha256) unblocks it: that is how the owner tries it again.
 *
 * Threading: every method does disk work and throws [IllegalStateException] on the main thread. Methods are
 * serialised by one process-wide lock (the root store's own lock is always taken after it).
 *
 * Fail closed: a model record or a slot record that cannot be read is skipped or built-in for that slot and logged.
 */
class ModelLibraryStore internal constructor(
    private val root: ImportedModelStore,
    private val config: TrackerConfig,
    private val threadCheck: () -> Unit,
    private val rename: (File, File) -> Boolean,
    private val log: (String, Throwable?) -> Unit,
) : ImportTarget {
    /** The production store. */
    constructor(
        context: Context,
        config: TrackerConfig = TrackerConfig(),
    ) : this(
        ImportedModelStore(context),
        config,
        { WorkerThreadCheck.require() },
        File::renameTo,
        { message, error -> AppLog.log(message, error) },
    )

    /** Everything stored now. Runs the migration and the crash-marker conversion first. */
    fun state(): LibraryState {
        threadCheck()
        synchronized(LOCK) { return readState() }
    }

    override fun newTempFile(): File = root.newTempFile()

    override fun guard(): ImportGuardState = state().guard

    /**
     * Stores the crash marker. Refused (`false`) while a crash of one model is still recorded in the marker itself
     * (it could not be moved to the blocked list yet): a new marker would erase it.
     */
    override fun setGuard(state: ImportGuardState): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val current = readState().guard
            if (state is ImportGuardState.Pending && isModelTrip(current)) return false
            return root.setGuard(state)
        }
    }

    override fun store(
        temp: File,
        meta: ImportedModelMeta,
    ): StoreResult {
        threadCheck()
        synchronized(LOCK) {
            val current = readState()
            val added = current.library.add(meta, config.modelLibraryMax)
            if (added == LibraryAdd.Duplicate) {
                // The same model again: it is not added twice. For a blocked model it is the way to try it again.
                val unblocked = unblock(current, meta.sha256)
                temp.delete()
                return if (unblocked) StoreResult.STORED else StoreResult.FAILED
            }
            if (added == LibraryAdd.Full) return StoreResult.LIBRARY_FULL
            val stored = root.inDirectory(entryDirectory(meta.sha256)).accept(temp, meta)
            // Accepting lifts a trip (a new file gets a new chance), but not the record of another model's crash.
            if (stored) restoreModelTrip(current.guard)
            return if (stored) StoreResult.STORED else StoreResult.FAILED
        }
    }

    /** The file of [meta]'s model: `<root>/<16 hex>/model-<16 hex>.tflite`; it may not exist. */
    fun modelFileFor(meta: ImportedModelMeta): File {
        threadCheck()
        return root.inDirectory(entryDirectory(meta.sha256)).modelFileFor(meta)
    }

    /** Whether the stored file of [meta] has its length and sha256 (reads the whole file); see [ImportedModelStore.storedFileMatches]. */
    fun storedFileMatches(meta: ImportedModelMeta): Boolean {
        threadCheck()
        return root.inDirectory(entryDirectory(meta.sha256)).storedFileMatches(meta)
    }

    /**
     * Sets the model of [orientation] to the library model [sha256], or to the built-in model when [sha256] is `null`.
     * `false` (nothing changed) when the model is not in the library or the record could not be written.
     */
    fun setSlot(
        orientation: ModelOrientation,
        sha256: String?,
    ): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val current = readState()
            if (sha256 != null && !current.library.contains(sha256)) return false
            val choice = if (sha256 == null) SlotChoice.BuiltIn else SlotChoice.Library(sha256)
            return writeAtomically(slotsFile(), current.slots.with(orientation, choice).encode())
        }
    }

    /**
     * Deletes the model [sha256]: the slots that pointed to it become built-in first, then its files go and it leaves
     * the blocked list. `false` while an import runs, when the model is not stored, or when something could not be
     * done (what was done stays done; a model whose files could not be deleted is still listed and can be deleted again).
     */
    fun delete(sha256: String): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val current = readState()
            if (ModelImporter.isImporting() || !current.library.contains(sha256)) return false
            val slots = current.slots.withoutModel(sha256)
            if (slots != current.slots && !writeAtomically(slotsFile(), slots.encode())) return false
            if (sha256 in current.blocked && !writeBlocked(current.blocked - sha256)) return false
            val directory = entryDirectory(sha256)
            val cleared = root.inDirectory(directory).clear()
            if (!current.guard.blames(sha256)) restoreModelTrip(current.guard)
            return directory.delete() && cleared
        }
    }

    /**
     * Deletes every model, the choices, the blocked list, the temporary file and the old single-model record, and
     * sets the crash marker to idle. `true` only when everything is gone. While an import runs it returns `false` and
     * changes nothing.
     */
    fun clearAll(): Boolean {
        threadCheck()
        synchronized(LOCK) {
            if (ModelImporter.isImporting()) return false
            var done = true
            for (directory in entryDirectories()) {
                done = root.inDirectory(directory).clear() && done
                done = directory.delete() && done
            }
            done = root.clear() && done
            for (file in listOf(slotsFile(), scratchOf(slotsFile()), blockedFile(), scratchOf(blockedFile()))) {
                done = (!file.exists() || file.delete()) && done
            }
            return done
        }
    }

    // ---- everything below runs under LOCK ----

    private fun readState(): LibraryState {
        migrateSingleModel()
        var guard = root.state().guard
        var blocked = readBlocked()
        val crashed = crashedModel(guard)
        if (crashed != null && !blocked.unreadable) {
            // The crash is now remembered for that model alone; if it cannot be stored the marker keeps it.
            val next = blocked.shas + crashed
            if (writeBlocked(next)) {
                blocked = BlockedRead(next, false)
                if (root.setGuard(ImportGuard.clear())) guard = ImportGuard.clear()
            }
        }
        val entries =
            entryDirectories()
                .mapNotNull { readEntry(it) }
                .sortedWith(compareBy({ it.meta.displayName }, { it.meta.sha256 }))
        val library = ModelLibrary.of(entries.map { it.meta })
        val slotsRead = OrientationSlots.read(readText(slotsFile()), library)
        if (slotsRead.unreadable.isNotEmpty()) {
            log("model choice unreadable for ${slotsRead.unreadable}, using the built-in model", null)
        }
        val anyFile = entries.isNotEmpty() || root.state().anyFileStored || entryDirectories().any { hasAnyFile(it) }
        return LibraryState(entries, slotsRead.slots, slotsRead.unreadable, blocked.shas, blocked.unreadable, guard, anyFile)
    }

    private fun hasAnyFile(directory: File): Boolean = root.inDirectory(directory).state().anyFileStored

    private fun readEntry(directory: File): LibraryEntry? {
        val stored = root.inDirectory(directory).state()
        val meta = stored.meta
        if (meta == null || !meta.sha256.startsWith(directory.name)) {
            if (stored.anyFileStored) log("model record in ${directory.name} is unreadable, skipped", null)
            return null
        }
        return LibraryEntry(meta, stored.fileExists)
    }

    /**
     * Moves the model of the version before the library into its own directory (see the class comment). Does nothing
     * when there is none. A step that fails is retried by the next read.
     */
    private fun migrateSingleModel() {
        val legacy = root.state()
        val meta = legacy.meta ?: return
        val entryStore = root.inDirectory(entryDirectory(meta.sha256))
        if (entryStore.state().meta?.sha256 != meta.sha256) {
            if (!legacy.fileExists) {
                log("the old imported model has no file, its record is dropped", null)
                root.clear()
                return
            }
            if (!blockLegacyTrip(legacy.guard, meta.sha256)) return
            if (!entryStore.accept(root.modelFileFor(meta), meta)) {
                log("moving the old imported model into the library failed", null)
                return
            }
        }
        if (!slotsFile().exists() && !writeAtomically(slotsFile(), pointingBoth(meta.sha256))) return
        if (!root.clear()) log("the old single-model record could not be removed yet", null)
    }

    /** A crash at the load recorded without a model blames the one model there was; `false` when that could not be stored. */
    private fun blockLegacyTrip(
        guard: ImportGuardState,
        sha256: String,
    ): Boolean {
        val unknownLoadTrip = guard is ImportGuardState.Tripped && guard.stage == ImportStage.LOAD && guard.sha256 == null
        if (!unknownLoadTrip) return true
        val blocked = readBlocked()
        return !blocked.unreadable && writeBlocked(blocked.shas + sha256)
    }

    private fun pointingBoth(sha256: String): String = OrientationSlots(SlotChoice.Library(sha256), SlotChoice.Library(sha256)).encode()

    /** Takes [sha256] off the blocked list (and lifts the marker if it names it); `false` when that could not be stored. */
    private fun unblock(
        current: LibraryState,
        sha256: String,
    ): Boolean {
        var done = true
        if (sha256 in current.blocked) done = writeBlocked(current.blocked - sha256)
        if (done && current.guard.blames(sha256)) done = root.setGuard(ImportGuard.clear())
        return done
    }

    /** The model a tripped load marker names; `null` for any other marker (or one that names no model). */
    private fun crashedModel(guard: ImportGuardState): String? =
        if (guard is ImportGuardState.Tripped && guard.stage == ImportStage.LOAD) guard.sha256 else null

    private fun ImportGuardState.blames(sha256: String): Boolean = crashedModel(this) == sha256

    private fun isModelTrip(guard: ImportGuardState): Boolean = crashedModel(guard) != null

    private fun restoreModelTrip(previous: ImportGuardState) {
        if (isModelTrip(previous) && !root.setGuard(previous)) log("the record of a model crash could not be restored", null)
    }

    private fun entryDirectory(sha256: String): File = File(root.directory, sha256.take(TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH))

    private fun entryDirectories(): List<File> =
        try {
            root.directory.listFiles { file -> file.isDirectory && isEntryName(file.name) }?.sortedBy { it.name }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun isEntryName(name: String): Boolean =
        name.length == TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH && name.all { it in '0'..'9' || it in 'a'..'f' }

    private fun slotsFile(): File = File(root.directory, SLOTS_NAME)

    private fun blockedFile(): File = File(root.directory, BLOCKED_NAME)

    private fun scratchOf(file: File): File = File(file.parentFile, file.name + SCRATCH_SUFFIX)

    private fun writeBlocked(shas: Set<String>): Boolean = writeAtomically(blockedFile(), ModelBlocklist.encode(shas))

    /** The blocked list; a file that exists but cannot be read is [BlockedRead.unreadable] (fail closed). */
    private fun readBlocked(): BlockedRead {
        val file = blockedFile()
        if (!file.exists()) return BlockedRead(emptySet(), false)
        val text = readText(file)
        return if (text == null || text == UNREADABLE) BlockedRead(emptySet(), true) else BlockedRead(ModelBlocklist.decode(text), false)
    }

    /** The text of [file]; `null` when it does not exist, [UNREADABLE] (never a valid record) when it cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    private fun readText(file: File): String? =
        try {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        } catch (t: Throwable) {
            UNREADABLE
        }

    /** [text] into a scratch file, synced, then renamed over [target]; `false` (and the scratch removed) on any failure. */
    private fun writeAtomically(
        target: File,
        text: String,
    ): Boolean {
        val scratch = scratchOf(target)
        try {
            if (!root.directory.isDirectory && !root.directory.mkdirs()) return false
            FileOutputStream(scratch).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        } catch (e: IOException) {
            scratch.delete()
            return false
        }
        if (rename(scratch, target)) return true
        scratch.delete()
        return false
    }

    private class BlockedRead(
        val shas: Set<String>,
        val unreadable: Boolean,
    )

    private companion object {
        const val SLOTS_NAME = "slots.txt"
        const val BLOCKED_NAME = "blocked.txt"
        const val SCRATCH_SUFFIX = ".tmp"

        /** What [readText] returns for a file that exists but cannot be read: not a valid record of any kind. */
        const val UNREADABLE = "\u0000unreadable"

        /** One lock for the whole process, taken before the root store's own lock. */
        val LOCK = Any()
    }
}
