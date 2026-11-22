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
import com.memo494.posetracker.core.modelimport.autoSlotFor
import com.memo494.posetracker.core.modelimport.fitsSlot
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
     * tripped load; a marker that names another model is not). A built-in slot has no model. A model that does not fit
     * the orientation ([fitsSlot]) is marked [StoredModelState.wrongShape], so it is never loaded there.
     */
    fun viewFor(orientation: ModelOrientation): StoredModelState {
        val entry = slotEntry(orientation) ?: return StoredModelState(null, false, ImportGuardState.Idle, anyFileStored)
        val wrongShape = !fitsSlot(entry.meta, orientation)
        return StoredModelState(entry.meta, entry.fileExists, guardFor(entry.meta.sha256), anyFileStored, wrongShape)
    }

    /** The orientations whose slot holds a library model that does not fit it ([fitsSlot]), in enum order. */
    fun slotsRefused(): Set<ModelOrientation> =
        ModelOrientation.entries.filterTo(LinkedHashSet()) { orientation ->
            slotEntry(orientation)?.let { !fitsSlot(it.meta, orientation) } ?: false
        }

    private fun slotEntry(orientation: ModelOrientation): LibraryEntry? {
        val choice = slots.choiceFor(orientation)
        return if (choice is SlotChoice.Library) entries.firstOrNull { it.meta.sha256 == choice.sha256 } else null
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
 * `model-*.tflite`). The first read moves it into its own directory, points the slots it fits at it (`fitsSlot`: square both,
 * wide Landscape only, tall Portrait only; the other slot is built-in) and removes the old
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
    /** Reads a record file; a failure makes the record unreadable. A hook so tests can make a read fail. */
    private val readFile: (File) -> String = { it.readText(Charsets.UTF_8) },
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
        log = { message, error -> AppLog.log(message, error) },
    )

    /** Everything stored now. Runs the migration and the crash-marker conversion first. */
    fun state(): LibraryState {
        threadCheck()
        synchronized(LOCK) { return readState() }
    }

    override fun newTempFile(): File = root.newTempFile()

    override fun guard(): ImportGuardState = state().guard

    /**
     * Stores the crash marker. A new `Pending` is refused (`false`) while a crash of one model is still recorded in the
     * marker itself (it could not be moved to the blocked list yet) or while another attempt is pending: a new marker
     * would erase what the old one says.
     */
    override fun setGuard(state: ImportGuardState): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val current = readState().guard
            if (state is ImportGuardState.Pending && (current is ImportGuardState.Pending || isModelTrip(current))) return false
            return root.setGuard(state)
        }
    }

    override fun store(
        temp: File,
        meta: ImportedModelMeta,
    ): StoreResult = storeReporting(temp, meta).result

    /**
     * Adds the model and, in the same step under the same lock, puts a NEW model into the slot it fits
     * ([autoSlotFor], given the library as it was before the add). A duplicate or a full library assigns nothing.
     * Fail closed: when the slot record cannot be read or written the model stays stored and no slot is reported.
     */
    override fun storeReporting(
        temp: File,
        meta: ImportedModelMeta,
    ): StoreReport {
        threadCheck()
        synchronized(LOCK) {
            val current = readState()
            val before = current.entries.size
            val added = current.library.add(meta, config.modelLibraryMax)
            val entryStore = root.inDirectory(entryDirectory(meta.sha256))
            if (added == LibraryAdd.Duplicate) {
                // The same model again: it is not added twice. For a blocked model it is the way to try it again, and
                // when the stored file is missing or damaged the new copy replaces it.
                val listed = current.entries.firstOrNull { it.meta.sha256 == meta.sha256 }
                if (listed?.fileExists == true && entryStore.storedFileMatches(meta)) {
                    temp.delete()
                    return StoreReport(resultOf(unblock(current, meta.sha256)), null, true, before)
                }
                if (!entryStore.accept(temp, meta)) return StoreReport(StoreResult.FAILED, null, true, before)
                return StoreReport(resultOf(unblock(current, meta.sha256)), null, true, before)
            }
            if (added == LibraryAdd.Full) return StoreReport(StoreResult.LIBRARY_FULL, null, false, before)
            val stored = entryStore.accept(temp, meta)
            if (!stored) return StoreReport(StoreResult.FAILED, null, false, before)
            // A new file gets a new chance: a probe trip (or a marker without a model) is lifted; a model's crash stays.
            liftTripUnlessAModelCrash(current.guard)
            return StoreReport(StoreResult.STORED, autoAssign(current, meta), false, before + 1)
        }
    }

    private fun resultOf(done: Boolean): StoreResult = if (done) StoreResult.STORED else StoreResult.FAILED

    /** The slot the new model [meta] was put into, or `null` (nothing assigned, also when the slot record cannot be read or written). */
    private fun autoAssign(
        before: LibraryState,
        meta: ImportedModelMeta,
    ): ModelOrientation? {
        // Fail closed: a blocked model (or any model, while the blocked list cannot be read) is never put into a slot.
        if (before.blockedUnreadable) {
            log("the new model was not assigned to a slot: the blocked list is unreadable", null)
            return null
        }
        if (meta.sha256 in before.blocked) {
            log("the new model was not assigned to a slot: the model is blocked", null)
            return null
        }
        if (before.slotsUnreadable.isNotEmpty()) {
            log("the new model was not assigned to a slot: the stored choice is unreadable", null)
            return null
        }
        val target = autoSlotFor(meta, before.slots, before.library) ?: return null
        val next = before.slots.with(target, SlotChoice.Library(meta.sha256))
        if (!writeAtomically(slotsFile(), next.encode())) {
            log("the new model was not assigned to ${target.logName}: the choice could not be stored", null)
            return null
        }
        return target
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
     * `false` (nothing changed) when the model is not in the library, does not fit [orientation] ([fitsSlot]: a wide
     * model is for Landscape only, a tall one for Portrait only) or the record could not be written.
     */
    fun setSlot(
        orientation: ModelOrientation,
        sha256: String?,
    ): Boolean {
        threadCheck()
        synchronized(LOCK) {
            val current = readState()
            if (sha256 != null) {
                val meta = current.library.find(sha256) ?: return false
                if (!fitsSlot(meta, orientation)) return false
            }
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
            // Files first: a model whose files could not be deleted stays listed (and blocked) and can be deleted again.
            val directory = entryDirectory(sha256)
            val removed = root.inDirectory(directory).clear() && directory.delete()
            if (!removed) return false
            return unblock(current, sha256)
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
     * Brings the model of the version before the library into its own directory (see the class comment). Does nothing
     * when there is none. The old file is never moved: it is copied next to its new place (synced), accepted there
     * (the entry's own crash-safe order), checked by length and sha256, and only then is the old record removed, so
     * a failure or a process death at any step leaves the old model where it was. A half-finished entry (a model file
     * without its meta) is finished from whichever copy exists. An old record whose file is gone becomes an entry
     * without a file, which the screen reports as "file missing". A step that fails is retried by the next read.
     */
    private fun migrateSingleModel() {
        val legacy = root.state()
        val meta = legacy.meta ?: return
        val entryStore = root.inDirectory(entryDirectory(meta.sha256))
        val stored = entryStore.state()
        val hasMeta = stored.meta?.sha256 == meta.sha256
        if (!(hasMeta && stored.fileExists && entryStore.storedFileMatches(meta))) {
            if (!blockLegacyTrip(legacy.guard, meta.sha256)) return
            // Only a file that still has its length and sha256 is copied; any other counts as "file missing", once.
            val oldFile = root.modelFileFor(meta)
            val entryFile = entryStore.modelFileFor(meta)
            val source =
                when {
                    oldFile.isFile && root.storedFileMatches(meta) -> oldFile
                    entryFile.isFile && entryStore.storedFileMatches(meta) -> entryFile
                    else -> null
                }
            if ((source != null || !hasMeta) && !copyIntoEntry(entryStore, meta, source)) return
        }
        if (!slotsFile().exists() && !writeAtomically(slotsFile(), slotsFitting(meta))) return
        if (!root.clear()) log("the old single-model record could not be removed yet", null)
    }

    /**
     * Copies [source] (`null` = there is no file: an entry without one) into the entry as its model and meta, and
     * checks the result. `false` (logged) when any step failed; [source] is never changed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun copyIntoEntry(
        entryStore: ImportedModelStore,
        meta: ImportedModelMeta,
        source: File?,
    ): Boolean {
        try {
            val temp = entryStore.newTempFile()
            FileOutputStream(temp).use { output ->
                source?.inputStream()?.use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            if (!entryStore.accept(temp, meta)) {
                temp.delete()
                log("moving the old imported model into the library failed", null)
                return false
            }
            if (source == null) {
                entryStore.modelFileFor(meta).delete()
            } else if (!entryStore.storedFileMatches(meta)) {
                log("the copy of the old imported model does not match, the old model stays where it is", null)
                return false
            }
            return entryStore.state().meta?.sha256 == meta.sha256
        } catch (t: Throwable) {
            log("copying the old imported model into the library failed", t)
            return false
        }
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

    /** The slots of the migrated single model [meta]: it goes only into the slots it fits ([fitsSlot]), the other is built-in. */
    private fun slotsFitting(meta: ImportedModelMeta): String {
        fun choice(orientation: ModelOrientation): SlotChoice =
            if (fitsSlot(meta, orientation)) SlotChoice.Library(meta.sha256) else SlotChoice.BuiltIn
        return OrientationSlots(choice(ModelOrientation.PORTRAIT), choice(ModelOrientation.LANDSCAPE)).encode()
    }

    /**
     * Takes [sha256] off the blocked list (and lifts the marker if it names it); `false` when that could not be stored.
     * A blocked list that cannot be read counts as "every library model is blocked": it is rewritten without [sha256].
     */
    private fun unblock(
        current: LibraryState,
        sha256: String,
    ): Boolean {
        var done = true
        if (current.blockedUnreadable) {
            val everyModel = current.entries.mapTo(HashSet()) { it.meta.sha256 }
            done = writeBlocked(everyModel - sha256)
        } else if (sha256 in current.blocked) {
            done = writeBlocked(current.blocked - sha256)
        }
        if (done && current.guard.blames(sha256)) done = root.setGuard(ImportGuard.clear())
        return done
    }

    private fun liftTripUnlessAModelCrash(guard: ImportGuardState) {
        if (guard is ImportGuardState.Tripped && !isModelTrip(guard) && !root.setGuard(ImportGuard.clear())) {
            log("the old crash record could not be lifted", null)
        }
    }

    /** The model a tripped load marker names; `null` for any other marker (or one that names no model). */
    private fun crashedModel(guard: ImportGuardState): String? =
        if (guard is ImportGuardState.Tripped && guard.stage == ImportStage.LOAD) guard.sha256 else null

    private fun ImportGuardState.blames(sha256: String): Boolean = crashedModel(this) == sha256

    private fun isModelTrip(guard: ImportGuardState): Boolean = crashedModel(guard) != null

    private fun entryDirectory(sha256: String): File = File(root.directory, sha256.take(TrackerConfig.STAGED_MODEL_SHA_PREFIX_LENGTH))

    private fun entryDirectories(): List<File> =
        try {
            root.directory
                .listFiles { file -> file.isDirectory && isEntryName(file.name) }
                ?.sortedBy { it.name }
                .orEmpty()
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
            if (file.isFile) readFile(file) else null
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
        // Whatever sits in the way (a directory, a special file) goes first, so the record can be repaired.
        val cleared = !target.exists() || target.isFile || target.deleteRecursively()
        if (cleared && rename(scratch, target)) return true
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
