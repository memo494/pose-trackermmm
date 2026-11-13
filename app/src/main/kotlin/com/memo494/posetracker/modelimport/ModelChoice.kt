package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta

/** Why the built-in model is used although the owner may expect an imported one. */
enum class BuiltInReason {
    /** A metadata record exists but the model file is gone. */
    FILE_MISSING,

    /** The crash marker found at app start says LiteRT died while opening an imported file (the probe). */
    GUARD_TRIPPED_PROBE,

    /** The crash marker found at app start says LiteRT died while loading the imported model for a session. */
    GUARD_TRIPPED_LOAD,
}

/** Which model a capture session starts with (M0c D4). */
sealed interface ModelChoice {
    /** The imported model described by [meta] is present and was never suspected of crashing LiteRT. */
    data class Imported(
        val meta: ImportedModelMeta,
    ) : ModelChoice

    /** The model bundled with the app; [reason] is `null` when nothing was imported. */
    data class BuiltIn(
        val reason: BuiltInReason?,
    ) : ModelChoice

    companion object {
        /**
         * Pure. A [ImportGuardState.Tripped] guard wins over everything else (even when nothing is stored: a probe
         * of a new file may have crashed LiteRT, and the owner is told). Otherwise [Imported] only when
         * there is a meta and its file exists; a meta without a file is [BuiltInReason.FILE_MISSING]; no meta is
         * `BuiltIn(null)`. A [ImportGuardState.Pending] guard is not a trip (the app-start step turns a stale one
         * into Tripped before this is called).
         */
        fun resolve(state: StoredModelState): ModelChoice {
            val guard = state.guard
            if (guard is ImportGuardState.Tripped) {
                return BuiltIn(
                    when (guard.stage) {
                        ImportStage.PROBE -> BuiltInReason.GUARD_TRIPPED_PROBE
                        ImportStage.LOAD -> BuiltInReason.GUARD_TRIPPED_LOAD
                    },
                )
            }
            val meta = state.meta ?: return BuiltIn(null)
            return if (state.fileExists) Imported(meta) else BuiltIn(BuiltInReason.FILE_MISSING)
        }
    }
}
