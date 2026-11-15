package com.memo494.posetracker.modelimport

import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta

/** Why the built-in model is used although the owner may expect an imported one. */
enum class BuiltInReason {
    /** A metadata record exists but the model file is gone. */
    FILE_MISSING,

    /**
     * The crash marker found at app start says LiteRT died while opening a new file (the probe), and no stored model
     * could be kept: nothing is stored, or the stored file does not match its meta (or that could not be checked).
     */
    GUARD_TRIPPED_PROBE,

    /** The crash marker found at app start says LiteRT died while loading the imported model for a session. */
    GUARD_TRIPPED_LOAD,
}

/** Which model a capture session starts with (M0c D4). */
sealed interface ModelChoice {
    /**
     * The imported model described by [meta] is present and was never suspected of crashing LiteRT. (A probe trip
     * suspects only the new file that was being probed, which was never stored, so a stored model that still
     * matches its meta stays here; see [resolve].)
     */
    data class Imported(
        val meta: ImportedModelMeta,
    ) : ModelChoice

    /** The model bundled with the app; [reason] is `null` when nothing was imported. */
    data class BuiltIn(
        val reason: BuiltInReason?,
    ) : ModelChoice

    companion object {
        /**
         * Pure apart from [storedFileMatches]. A [ImportGuardState.Tripped] guard wins over everything else (even
         * when nothing is stored: a probe of a new file may have crashed LiteRT, and the owner is told), with one
         * exception: a trip at [ImportStage.PROBE] suspects only the new file that was being probed (it was never
         * stored), so a stored model with a meta, an existing file and a positive [storedFileMatches] stays
         * [Imported]. A trip at [ImportStage.LOAD] suspects the stored model itself and is always built-in.
         * Otherwise [Imported] only when there is a meta and its file exists; a meta without a file is
         * [BuiltInReason.FILE_MISSING]; no meta is `BuiltIn(null)`. A [ImportGuardState.Pending] guard is not a
         * trip (the app-start step turns a stale one into Tripped before this is called).
         *
         * @param storedFileMatches whether the stored file still has the length and sha256 of its meta (disk
         *   work: worker thread only). It is asked only for a probe trip with a meta and a file. Fail closed: the
         *   default says no, and a check that throws counts as no.
         */
        fun resolve(
            state: StoredModelState,
            storedFileMatches: (ImportedModelMeta) -> Boolean = { false },
        ): ModelChoice {
            val guard = state.guard
            if (guard is ImportGuardState.Tripped) {
                val meta = state.meta
                if (guard.stage == ImportStage.PROBE && meta != null && state.fileExists && matches(meta, storedFileMatches)) {
                    return Imported(meta)
                }
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

        /** The check may fail in any way (even an `Error` from a huge or damaged file): that is "does not match". */
        @Suppress("TooGenericExceptionCaught")
        private fun matches(
            meta: ImportedModelMeta,
            check: (ImportedModelMeta) -> Boolean,
        ): Boolean =
            try {
                check(meta)
            } catch (t: Throwable) {
                false
            }
    }
}
