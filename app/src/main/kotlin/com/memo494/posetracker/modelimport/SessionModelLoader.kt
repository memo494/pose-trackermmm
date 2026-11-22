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
 * Runs the model creation it is given (the risky LiteRT step: `CompiledModel.create`, the GPU compile) inside the crash
 * marker `Pending(LOAD, sha256)` of the bundled model, and returns what it returned. Staging the file (copy and hash) is
 * not part of it, so it runs before the guard is used: a kill during the first-run copy must not block the model.
 */
typealias CreateGuard = (() -> PoseDetector) -> PoseDetector

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
 * @property builtInBlocked see [ModelStatus.builtInBlocked].
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
    val builtInBlocked: Boolean = false,
) {
    /** Whether the HUD shows the "imported model not used" note: an imported model's fallback happened. */
    val importedModelNotUsed: Boolean
        get() = fallback != null && fallback != ModelFallback.BUILT_IN_FAILED

    /** Whether the running model is an imported box-only (detect-only) one; a built-in fallback is always a pose model. */
    val boxOnly: Boolean
        get() = detector != null && source == ModelSource.IMPORTED && meta?.outputLayout?.isDetectOnly == true

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
            builtInBlocked = builtInBlocked,
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
 *   (`PoseDetectors.fromAssets`: 448x960 portrait, 960x448 landscape). It stages the file first, then wraps only the
 *   model creation in the [CreateGuard] it is given.
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
    private val loadBuiltIn: (CreateGuard) -> BundledDetector,
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
     *   nothing is imported. A model refused for its shape ([ModelFallback.WRONG_SHAPE]) is never loaded and is named in
     *   [LoadedModel.meta].
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
            is ModelChoice.BuiltIn -> builtIn(choice.refused, choice.reason?.toFallback(), null)
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
        var blocked = false
        var bundled: BundledDetector? = null
        val sha = bundledSha()
        val skip = if (sha == null) null else bundledSkip(sha)
        if (skip != null) {
            log(skip.note, null)
            failed = true
            blocked = skip.crashed
            note = note ?: skip.note
        } else {
            try {
                bundled = loadBuiltIn(guardFor(sha))
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
        return LoadedModel(
            bundled?.detector,
            ModelSource.BUILT_IN,
            attempted,
            shown,
            note,
            bundled?.fileName,
            bundled?.sha256,
            squareRuns,
            squareRuns && blocked,
        )
    }

    /** Why the bundled model is not loaded: [crashed] = on the blocked list, else the record could not be read. */
    private class Skip(
        val note: String,
        val crashed: Boolean,
    )

    /** The hash of the bundled model about to load, read once per load; `null` when unknown (then no marker names it). */
    private fun bundledSha(): String? =
        try {
            builtInSha256()
        } catch (t: Throwable) {
            log("the hash of the built-in model is unknown", t)
            null
        }

    /**
     * Why the bundled model with [sha] must not be loaded now (fail closed), or `null`: the process died while loading
     * it before (it is on the blocked list), or the crash record cannot be read (the check throws).
     */
    private fun bundledSkip(sha: String): Skip? =
        try {
            if (isBuiltInBlocked(sha)) Skip(BUNDLED_BLOCKED_NOTE, crashed = true) else null
        } catch (t: Throwable) {
            log("cannot read the crash record of the built-in model", t)
            Skip(BUNDLED_UNREADABLE_NOTE, crashed = false)
        }

    /**
     * The guard handed to [loadBuiltIn]: the marker `Pending(LOAD, sha256)` goes around the creation it is given, as for
     * an imported model (the marker names the model, the app moves a crashed one to the blocked list), and back to Idle
     * after, also when it threw. A marker that cannot be stored throws before the creation runs, so the square fallback
     * runs instead. Without a known hash there is no marker.
     */
    private fun guardFor(sha: String?): CreateGuard =
        { create ->
            if (sha == null) {
                create()
            } else {
                check(store(ImportGuard.begin(ImportStage.LOAD, sha))) { BUNDLED_MARKER_NOTE }
                try {
                    create()
                } finally {
                    // A caught failure is not a crash: the marker goes back to Idle either way.
                    storeAfterLoad()
                }
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
            BuiltInReason.WRONG_SHAPE -> ModelFallback.WRONG_SHAPE
        }

    private companion object {
        const val BUNDLED_BLOCKED_NOTE = "built-in model skipped: it crashed earlier"
        const val BUNDLED_UNREADABLE_NOTE = "built-in model skipped: crash record unreadable"
        const val BUNDLED_MARKER_NOTE = "built-in model skipped: its crash marker could not be stored"
    }
}
