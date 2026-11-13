package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.modelimport.ImportGuard
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
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
 */
data class LoadedModel(
    val detector: PoseDetector?,
    val source: ModelSource,
    val meta: ImportedModelMeta?,
    val fallback: ModelFallback?,
    val message: String? = null,
) {
    /** Whether the HUD shows the "imported model not used" note: some fallback happened. */
    val importedModelNotUsed: Boolean
        get() = fallback != null

    /** The status for [ModelStatusHolder]; the input size is the running detector's, 0 without one. */
    fun status(): ModelStatus = ModelStatus(source, meta, fallback, message, detector?.inputSize ?: 0)
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
 * @param loadBuiltIn builds the built-in detector (`PoseDetectors.fromAssets`).
 * @param log receives a message and the failure, if any; it must not throw.
 */
class SessionModelLoader(
    private val readState: () -> StoredModelState,
    private val setGuard: (ImportGuardState) -> Boolean,
    private val loadImported: (ImportedModelMeta) -> PoseDetector,
    private val loadBuiltIn: () -> PoseDetector,
    private val log: (String, Throwable?) -> Unit,
) {
    /**
     * Never throws (`Error`s such as `OutOfMemoryError` included).
     *
     * - An imported model that is present and not blocked: the marker `Pending(LOAD)` is stored, the model loads, the
     *   marker goes back to `Idle`. If the marker cannot be stored, the imported model is not loaded (the crash guard
     *   would be blind): built-in with [ModelFallback.GUARD_NOT_STORED].
     * - A catchable load failure is not a crash: the marker goes back to `Idle` and the built-in model runs with
     *   [ModelFallback.LOAD_FAILED] and a short message.
     * - Otherwise the built-in model; the fallback mirrors the reason of `ModelChoice.BuiltIn`, and is `null` when
     *   nothing is imported.
     * - The built-in model failing too gives `detector == null` (logged), keeping the other fields.
     */
    fun load(): LoadedModel {
        val choice =
            try {
                ModelChoice.resolve(readState())
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
        if (!store(ImportGuard.begin(ImportStage.LOAD))) {
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
        val detector =
            try {
                loadBuiltIn()
            } catch (t: Throwable) {
                // No detector is not fatal: the HUD shows DETECTOR_UNAVAILABLE and frames are still drained.
                log("detector unavailable", t)
                null
            }
        return LoadedModel(detector, ModelSource.BUILT_IN, attempted, fallback, message)
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
}
