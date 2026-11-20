package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.SlotChoice
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
 * @param loadBuiltIn builds the built-in detector (`PoseDetectors.fromAssets`).
 * @param verifyStored whether a stored file still has its length and sha256 (`ModelLibraryStore.storedFileMatches`).
 * @param nowNs a monotonic clock in nanoseconds.
 * @param log receives a message and the failure, if any; it must not throw.
 */
internal class ModelSwitcher(
    private val readLibrary: () -> LibraryState,
    private val setGuard: (ImportGuardState) -> Boolean,
    private val loadImported: (ImportedModelMeta) -> PoseDetector,
    private val loadBuiltIn: () -> PoseDetector,
    private val verifyStored: (ImportedModelMeta) -> Boolean,
    private val nowNs: () -> Long,
    private val log: (String, Throwable?) -> Unit,
) {
    private var current: LoadedModel? = null

    /** The sha256 the slot asked for when [current] was loaded; `null` = the built-in model. */
    private var loadedFor: String? = null
    private var lastLibrary: LibraryState? = null

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
    fun select(orientation: ModelOrientation): ModelSelection? =
        try {
            choose(orientation)
        } catch (t: Throwable) {
            log("choosing the model for ${orientation.logName} failed", t)
            null
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
        val wanted = library?.let { shaOf(it.slots.choiceFor(orientation)) }
        val running = current
        if (running != null && running.detector != null && wanted == loadedFor) return null
        val startNs = nowNs()
        if (running != null) closeQuietly(running.detector)
        current = null
        val loaded =
            SessionModelLoader(
                readState = { stateOf(library, orientation) },
                setGuard = setGuard,
                loadImported = loadImported,
                loadBuiltIn = loadBuiltIn,
                log = log,
                verifyStored = verifyStored,
            ).load()
        val ms = (nowNs() - startNs) / TrackerConfig.NANOS_PER_MILLI
        current = loaded
        loadedFor = wanted
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
            log("cannot read the model library", t)
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
            log("closing the previous detector failed", t)
        }
    }

    private fun shaOf(choice: SlotChoice): String? = if (choice is SlotChoice.Library) choice.sha256 else null

    private fun slotText(orientation: ModelOrientation): String {
        val library = lastLibrary ?: return ModelStatus.SLOT_BUILT_IN
        val choice = library.slots.choiceFor(orientation)
        if (choice !is SlotChoice.Library) return ModelStatus.SLOT_BUILT_IN
        val meta = library.library.find(choice.sha256) ?: return ModelStatus.SLOT_BUILT_IN
        return "${meta.displayName} ${meta.sha256.take(TrackerConfig.SHA256_SHORT_CHARS)}"
    }
}
