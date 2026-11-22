package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.model.ModelInputText
import com.memo494.posetracker.core.modelimport.ImportGuard
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.detector.BundledDetector
import com.memo494.posetracker.detector.ModelProbe
import com.memo494.posetracker.detector.PoseDetector

/**
 * The outcome of [SessionModelLoader.load].
 *
 * @property detector the detector to run, or `null` when even the built-in model could not be loaded (the HUD then
 *   shows `DETECTOR_UNAVAILABLE`). The caller owns it and must close it.
 * @property source see [ModelStatus.source].
 * @property meta see [ModelStatus.meta].
 * @property fallback see [ModelStatus.fallback].
 * @property message see [ModelStatus.message].
 * @property builtInFile see [ModelStatus.builtInFile].
 * @property builtInSha256 see [ModelStatus.builtInSha256].
 * @property builtInFailed see [ModelStatus.builtInFailed].
 */
data class LoadedModel(
    val detector: PoseDetector?,
    val source: ModelSource,
    val meta: ImportedModelMeta?,
    val fallback: ModelFallback?,
    val message: String? = null,
    val builtInFile: String? = null,
    val builtInSha256: String? = null,
    val builtInFailed: Boolean = false,
) {
    /** Whether the HUD shows the "imported model not used" note: an imported model's fallback happened. */
    val importedModelNotUsed: Boolean
        get() = fallback != null && fallback != ModelFallback.BUILT_IN_FAILED

    /** The status for [ModelStatusHolder]; the input width and height are the running detector's, 0 without one. */
    fun status(): ModelStatus =
        ModelStatus(
            source,
            meta,
            fallback,
            message,
            detector?.inputWidth ?: 0,
            detector?.inputHeight ?: 0,
            builtInFile = builtInFile,
            builtInSha256 = builtInSha256,
            builtInFailed = builtInFailed,
        )

    /** The one log line of the model choice; the input text is [ModelInputText.text] (`0` without a detector). */
    fun logLine(): String {
        val input = ModelInputText.text(detector?.inputWidth ?: 0, detector?.inputHeight ?: 0)
        return "model: $source, input $input, fallback ${fallback ?: "none"}"
    }
}

/**
 * The model choice at Start (M0c D4), free of `android.*`: decides between the imported and the built-in model,
 * keeps the crash marker around the risky load and never throws. Run it on the capture thread, which owns the
 * detector; every function it is given does disk or LiteRT work.
 *
 * @param readState what is stored about the imported model (`ImportedModelStore.state`).
 * @param setGuard stores the crash marker synchronously; `false` = it could not be stored
 *   (`ImportedModelStore.setGuard`).
 * @param loadImported builds the detector for the imported model (`PoseDetectors.fromImported`); any `Throwable`
 *   means "use the built-in model".
 * @param builtInSha256 the sha256 of the bundled model about to be loaded for the orientation, `null` = unknown (it
 *   then loads without a crash marker).
 * @param isBuiltInBlocked whether the model with that sha256 is on the blocked list (the process died while loading it).
 * @param loadBuiltIn builds the detector of the built-in model of the orientation being loaded
 *   (`PoseDetectors.fromAssets`: 448x960 portrait, 960x448 landscape).
 * @param loadFallbackBuiltIn builds the detector of the square fallback model (`TrackerConfig.modelInputSize`), the
 *   last resort when [loadBuiltIn] throws or the bundled model is blocked. It has no crash marker.
 * @param log receives a message and the failure, if any; it must not throw.
 * @param verifyStored whether the stored model file still has the length and sha256 of its meta
 *   (`ImportedModelStore.storedFileMatches`); asked only after a probe trip, which keeps such a model. The default
 *   says no (fail closed).
 */
class SessionModelLoader(
    private val readState: () -> StoredModelState,
    private val setGuard: (ImportGuardState) -> Boolean,
    private val loadImported: (ImportedModelMeta) -> PoseDetector,
    private val builtInSha256: () -> String?,
    private val isBuiltInBlocked: (String) -> Boolean,
    private val loadBuiltIn: () -> BundledDetector,
    private val loadFallbackBuiltIn: () -> BundledDetector,
    private val log: (String, Throwable?) -> Unit,
    private val verifyStored: (ImportedModelMeta) -> Boolean = { false },
) {
    /**
     * Never throws (`Error`s such as `OutOfMemoryError` included).
     *
     * - An imported model that is present and not blocked: the marker `Pending(LOAD, sha256)` (it names the model, so a
     *   crash blocks only that model) is stored, the model loads, the
     *   marker goes back to `Idle`. If the marker cannot be stored, the imported model is not loaded (the crash guard
     *   would be blind): built-in with [ModelFallback.GUARD_NOT_STORED].
     * - A catchable load failure is not a crash: the marker goes back to `Idle` and the built-in model runs with
     *   [ModelFallback.LOAD_FAILED] and a short message.
     * - A guard tripped at the probe of a new file does not stop a stored model that still matches its meta
     *   (`ModelChoice.resolve`); it loads like any other. Its load stores `Pending(LOAD)` and then `Idle`, so the
     *   first Start of the kept model lifts the probe trip on purpose (the trip suspected a file that was never
     *   stored, and the model that ran has just loaded without killing the process).
     * - Otherwise the built-in model; the fallback mirrors the reason of `ModelChoice.BuiltIn`, and is `null` when
     *   nothing is imported.
     * - The bundled model of the orientation is loaded with the marker `Pending(LOAD, <its sha256>)` like an imported
     *   one. When it is blocked (it killed the process before), its marker cannot be stored, or it throws (any
     *   `Throwable`), the square fallback model loads instead (logged): the status names its file, `message` says why
     *   and `fallback` is [ModelFallback.BUILT_IN_FAILED] unless an imported model's fallback is already set. The
     *   fallback failing too gives `detector == null` (logged), keeping the other fields.
     */
    fun load(): LoadedModel {
        val choice =
            try {
                ModelChoice.resolve(readState(), verifyStored)
            } catch (t: Throwable) {
                log("cannot read the stored model state", t)
                return builtIn(null, ModelFallback.STATE_UNREADABLE, ModelProbe.failureMessage(t))
            }
        return when (choice) {
            is ModelChoice.Imported -> loadImportedOrBuiltIn(choice.meta)
            is ModelChoice.BuiltIn -> builtIn(null, choice.reason?.toFallback(), null)
        }
    }

    private fun loadImportedOrBuiltIn(meta: ImportedModelMeta): LoadedModel {
        if (!store(ImportGuard.begin(ImportStage.LOAD, meta.sha256))) {
            log("crash marker not stored, imported model not loaded", null)
            return builtIn(meta, ModelFallback.GUARD_NOT_STORED, null)
        }
        val detector =
            try {
                loadImported(meta)
            } catch (t: Throwable) {
                log("imported model failed to load, using the built-in model", t)
                // A caught failure is not a crash.
                storeAfterLoad()
                return builtIn(meta, ModelFallback.LOAD_FAILED, ModelProbe.failureMessage(t))
            }
        storeAfterLoad()
        return LoadedModel(detector, ModelSource.IMPORTED, meta, fallback = null)
    }

    /** The marker goes back to `Idle` once the load ended without killing the process; a failure is only logged. */
    private fun storeAfterLoad() {
        if (!store(ImportGuard.succeed())) log("crash marker not cleared after the load", null)
    }

    private fun builtIn(
        attempted: ImportedModelMeta?,
        fallback: ModelFallback?,
        message: String?,
    ): LoadedModel {
        var note = message
        var failed = false
        var bundled: BundledDetector? = null
        val skipped = bundledSkipReason()
        if (skipped != null) {
            log(skipped, null)
            failed = true
            note = note ?: skipped
        } else {
            try {
                bundled = loadBundledGuarded()
            } catch (t: Throwable) {
                log("built-in model failed to load, using the square fallback model", t)
                failed = true
                note = note ?: "built-in model failed: ${ModelProbe.failureMessage(t)}"
            }
        }
        if (bundled == null) {
            bundled =
                try {
                    loadFallbackBuiltIn()
                } catch (t: Throwable) {
                    // No detector is not fatal: the HUD shows DETECTOR_UNAVAILABLE and frames are still drained.
                    log("detector unavailable", t)
                    null
                }
        }
        val squareRuns = failed && bundled != null
        val shown = fallback ?: if (squareRuns) ModelFallback.BUILT_IN_FAILED else null
        return LoadedModel(bundled?.detector, ModelSource.BUILT_IN, attempted, shown, note, bundled?.fileName, bundled?.sha256, squareRuns)
    }

    /** The hash of the bundled model about to load, or `null` when unknown (then no marker can name it). */
    private fun bundledSha(): String? =
        try {
            builtInSha256()
        } catch (t: Throwable) {
            log("the hash of the built-in model is unknown", t)
            null
        }

    /**
     * Why the bundled model must not be loaded now (fail closed), or `null`: it is on the blocked list because the
     * process died while loading it (a check that fails counts as blocked), or its crash marker could not be stored.
     * With no known hash the model loads without a marker (its own failure to read the hash is a catchable one).
     */
    private fun bundledSkipReason(): String? {
        val sha = bundledSha() ?: return null
        val blocked =
            try {
                isBuiltInBlocked(sha)
            } catch (t: Throwable) {
                log("cannot tell whether the built-in model is blocked", t)
                true
            }
        return if (blocked) BUNDLED_BLOCKED_NOTE else null
    }

    /**
     * Loads the bundled model of the orientation with the crash marker `Pending(LOAD, sha256)` around the load, as for
     * an imported model (the marker names the model, the app moves a crashed one to the blocked list). A marker that
     * cannot be stored throws, so the square fallback runs instead.
     */
    private fun loadBundledGuarded(): BundledDetector {
        val sha = bundledSha() ?: return loadBuiltIn()
        check(store(ImportGuard.begin(ImportStage.LOAD, sha))) { BUNDLED_MARKER_NOTE }
        try {
            return loadBuiltIn()
        } finally {
            // A caught failure is not a crash: the marker goes back to Idle either way.
            storeAfterLoad()
        }
    }

    /** [setGuard] that cannot throw: a failure to store counts as "not stored". */
    private fun store(state: ImportGuardState): Boolean =
        try {
            setGuard(state)
        } catch (t: Throwable) {
            log("storing the crash marker failed", t)
            false
        }

    private fun BuiltInReason.toFallback(): ModelFallback =
        when (this) {
            BuiltInReason.FILE_MISSING -> ModelFallback.FILE_MISSING
            BuiltInReason.GUARD_TRIPPED_PROBE -> ModelFallback.GUARD_TRIPPED_PROBE
            BuiltInReason.GUARD_TRIPPED_LOAD -> ModelFallback.GUARD_TRIPPED_LOAD
        }

    private companion object {
        const val BUNDLED_BLOCKED_NOTE = "built-in model skipped: it crashed earlier"
        const val BUNDLED_MARKER_NOTE = "built-in model skipped: its crash marker could not be stored"
    }
}
