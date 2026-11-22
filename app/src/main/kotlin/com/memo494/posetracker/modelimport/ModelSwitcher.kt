package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.detector.BundledDetector
import com.memo494.posetracker.detector.ModelProbe
import com.memo494.posetracker.detector.PoseDetector

/**
 * A model that was just loaded for an orientation.
 *
 * @property loaded the detector (or none) with its source, fallback and message, as [SessionModelLoader] returns it.
 * @property orientation the orientation it was loaded for.
 * @property ms how long closing the old detector and loading this one took, in milliseconds.
 * @property isSwitch `true` for a change caused by a change of orientation, `false` for the load at Start.
 */
internal class ModelSelection(
    val loaded: LoadedModel,
    val orientation: ModelOrientation,
    val ms: Long,
    val isSwitch: Boolean,
) {
    /** The one log line of the choice: `model: <name> for landscape (switch 240ms)`; `start` instead of `switch` at Start. */
    fun logLine(): String {
        val imported = if (loaded.source == ModelSource.IMPORTED) loaded.meta else null
        val name = imported?.displayName ?: BUILT_IN_NAME
        val what = if (isSwitch) "switch" else "start"
        return "model: $name for ${orientation.logName} ($what ${ms}ms)"
    }

    private companion object {
        const val BUILT_IN_NAME = "built-in"
    }
}

/**
 * Keeps the detector in step with the orientation (M1b-1): the model of the slot of the capture orientation runs.
 * Free of `android.*`; every function it is given does disk or LiteRT work, so call it on the capture thread, which
 * owns the detector. Never throws.
 *
 * [select] is called at Start and whenever the capture geometry changes. When the slot's model differs from the one
 * that was loaded for the previous request, it closes the old detector and loads the new one through a
 * [SessionModelLoader], which keeps the crash marker around the load (it names the model, so a crash blocks only that
 * model), falls back to the built-in model when the model is blocked, missing or fails to load, and never stops the
 * session. A load that fails is tried again the next time its orientation comes back.
 *
 * @param readLibrary what is stored now (`ModelLibraryStore.state`); a failure means "built-in, unreadable".
 * @param setGuard stores the crash marker (`ModelLibraryStore.setGuard`).
 * @param loadImported builds the detector of a library model (`PoseDetectors.fromImported`).
 * @param builtInSha256 the sha256 of the bundled model of an orientation (`null` = unknown): the crash marker names it.
 * @param loadBuiltIn builds the detector of the built-in model of an orientation (`PoseDetectors.fromAssets`).
 * @param loadFallbackBuiltIn builds the detector of the square fallback model, used when [loadBuiltIn] fails.
 * @param verifyStored whether a stored file still has its length and sha256 (`ModelLibraryStore.storedFileMatches`).
 * @param nowNs a monotonic clock in nanoseconds.
 * @param log receives a message and the failure, if any; it must not throw.
 */
internal class ModelSwitcher(
    private val readLibrary: () -> LibraryState,
    private val setGuard: (ImportGuardState) -> Boolean,
    private val loadImported: (ImportedModelMeta) -> PoseDetector,
    private val builtInSha256: (ModelOrientation) -> String?,
    private val loadBuiltIn: (ModelOrientation) -> BundledDetector,
    private val loadFallbackBuiltIn: () -> BundledDetector,
    private val verifyStored: (ImportedModelMeta) -> Boolean,
    private val nowNs: () -> Long,
    private val log: (String, Throwable?) -> Unit,
) {
    private var current: LoadedModel? = null

    /** What the slot asked for when [current] was loaded: the sha256, or [builtInKey]; `null` = nothing loaded yet. */
    private var loadedFor: String? = null
    private var lastLibrary: LibraryState? = null

    /** The orientation the built-in model of [current] was loaded for; `null` when [current] is an imported model. */
    private var loadedBuiltInFor: ModelOrientation? = null

    /** Set while one [select] runs: the old detector was closed / the new model was built (for [recover]). */
    private var oldClosed = false
    private var built: LoadedModel? = null

    /** Changes of the model caused by a change of orientation since this switcher was made (Start not counted). */
    var switches: Int = 0
        private set

    /** The longest of those changes, milliseconds. */
    var switchMsMax: Long = 0L
        private set

    /**
     * Makes the model of [orientation] the running one. Returns what was loaded, or `null` when the running model is
     * already the one this orientation asks for (or an unexpected failure was logged: the previous state stays).
     * The detector of the previous model is closed here before the new one loads.
     */
    @Suppress("TooGenericExceptionCaught")
    fun select(orientation: ModelOrientation): ModelSelection? {
        oldClosed = false
        built = null
        return try {
            choose(orientation)
        } catch (t: Throwable) {
            safeLog("choosing the model for ${orientation.logName} failed", t)
            if (oldClosed) {
                recover(orientation, t)
            } else {
                // Nothing was running before (Start): a detector that was built is not returned, so it is closed here.
                closeQuietly(built?.detector)
                null
            }
        }
    }

    /**
     * Something failed after the old detector was closed: the session must not keep a closed model. Any detector that
     * was already built is closed too, and the result is a selection without a detector (the HUD shows
     * DETECTOR_UNAVAILABLE and frames are still drained); the next [select] tries again.
     */
    private fun recover(
        orientation: ModelOrientation,
        error: Throwable,
    ): ModelSelection {
        closeQuietly(built?.detector)
        val failed = LoadedModel(null, ModelSource.BUILT_IN, null, ModelFallback.LOAD_FAILED, ModelProbe.failureMessage(error))
        current = failed
        loadedFor = null
        loadedBuiltInFor = null
        switches++
        return ModelSelection(failed, orientation, 0L, true)
    }

    /** The status of the running model with the two slots and the switch counters; `null` before the first [select]. */
    fun status(): ModelStatus? {
        val loaded = current ?: return null
        return loaded.status().copy(
            portrait = slotText(ModelOrientation.PORTRAIT),
            landscape = slotText(ModelOrientation.LANDSCAPE),
            switches = switches,
            switchMsMax = switchMsMax,
        )
    }

    private fun choose(orientation: ModelOrientation): ModelSelection? {
        val library = readOrNull()
        if (library != null) lastLibrary = library
        val wanted = library?.let { shaOf(it.slots.choiceFor(orientation)) } ?: builtInKey(orientation)
        val running = current
        if (running != null && running.detector != null && wanted == loadedFor && builtInMatches(orientation)) return null
        val startNs = nowNs()
        if (running != null) closeQuietly(running.detector)
        oldClosed = running != null
        current = null
        val loaded =
            SessionModelLoader(
                readState = { stateOf(library, orientation) },
                setGuard = setGuard,
                loadImported = loadImported,
                builtInSha256 = { builtInSha256(orientation) },
                isBuiltInBlocked = { sha256 -> library?.isBlocked(sha256) ?: true },
                loadBuiltIn = { loadBuiltIn(orientation) },
                loadFallbackBuiltIn = loadFallbackBuiltIn,
                log = ::safeLog,
                verifyStored = verifyStored,
            ).load()
        built = loaded
        val ms = (nowNs() - startNs) / TrackerConfig.NANOS_PER_MILLI
        current = loaded
        loadedFor = wanted
        loadedBuiltInFor = if (loaded.source == ModelSource.BUILT_IN) orientation else null
        val isSwitch = running != null
        if (isSwitch) {
            switches++
            if (ms > switchMsMax) switchMsMax = ms
        }
        return ModelSelection(loaded, orientation, ms, isSwitch)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readOrNull(): LibraryState? =
        try {
            readLibrary()
        } catch (t: Throwable) {
            safeLog("cannot read the model library", t)
            null
        }

    /** What the loader reads for [orientation]; a library or a choice that could not be read makes it fall back. */
    private fun stateOf(
        library: LibraryState?,
        orientation: ModelOrientation,
    ): StoredModelState {
        checkNotNull(library) { "the model library could not be read" }
        check(orientation !in library.slotsUnreadable) { "the stored model choice for ${orientation.logName} could not be read" }
        return library.viewFor(orientation)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeQuietly(detector: PoseDetector?) {
        try {
            detector?.close()
        } catch (t: Throwable) {
            safeLog("closing the previous detector failed", t)
        }
    }

    /** [log] that cannot throw: a failing logger must not stop a switch. */
    @Suppress("TooGenericExceptionCaught")
    private fun safeLog(
        message: String,
        error: Throwable?,
    ) {
        try {
            log(message, error)
        } catch (t: Throwable) {
            // Nowhere left to report it.
        }
    }

    /**
     * Whether the running model still fits [orientation]: an imported model fits both, the built-in model only the one
     * it was loaded for (also when it runs because the slot's model failed, so a rotation loads the other orientation's).
     */
    private fun builtInMatches(orientation: ModelOrientation): Boolean = loadedBuiltInFor.let { it == null || it == orientation }

    /** The built-in model differs per orientation, so a change of orientation reloads it; a sha256 never looks like this. */
    private fun builtInKey(orientation: ModelOrientation): String = "$BUILT_IN_KEY_PREFIX${orientation.logName}"

    private fun shaOf(choice: SlotChoice): String? = if (choice is SlotChoice.Library) choice.sha256 else null

    private fun slotText(orientation: ModelOrientation): String {
        val library = lastLibrary ?: return ModelStatus.SLOT_BUILT_IN
        val choice = library.slots.choiceFor(orientation)
        if (choice !is SlotChoice.Library) return ModelStatus.SLOT_BUILT_IN
        val meta = library.library.find(choice.sha256) ?: return ModelStatus.SLOT_BUILT_IN
        return "${meta.displayName} ${meta.sha256.take(TrackerConfig.SHA256_SHORT_CHARS)}"
    }

    private companion object {
        const val BUILT_IN_KEY_PREFIX = "builtin:"
    }
}
