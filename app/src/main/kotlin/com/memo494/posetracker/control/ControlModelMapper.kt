package com.memo494.posetracker.control

import androidx.annotation.StringRes
import com.memo494.posetracker.R
import com.memo494.posetracker.core.model.ModelInputText
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.SlotChoice
import com.memo494.posetracker.core.modelimport.SniffRejection
import com.memo494.posetracker.modelimport.BuiltInReason
import com.memo494.posetracker.modelimport.FailureKind
import com.memo494.posetracker.modelimport.ImportOutcome
import com.memo494.posetracker.modelimport.LibraryState
import com.memo494.posetracker.modelimport.ModelChoice
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.RejectionKind
import com.memo494.posetracker.modelimport.StoredModelState
import java.util.Locale

/**
 * Pure bridges between the model-import types and the control screen: outcomes and stored state become the small
 * data of [ControlState], and every enum value gets exactly one text resource. The enum mappings are exhaustive
 * `when`s without an `else`, so a new enum value is a compile error here until it has a reason or a message.
 */
internal object ControlModelMapper {
    /** The importer's [outcome] as pure data for the reducer. */
    fun summary(outcome: ImportOutcome): ImportSummary =
        when (outcome) {
            is ImportOutcome.Accepted -> {
                ImportSummary.Accepted(
                    outcome.meta.displayName,
                    inputText(outcome.meta),
                    outcome.assignedSlot,
                    outcome.duplicate,
                )
            }

            is ImportOutcome.Rejected -> {
                ImportSummary.Rejected(reason(outcome.kind), outcome.details)
            }

            is ImportOutcome.Failed -> {
                ImportSummary.Failed(failure(outcome.kind), outcome.message)
            }
        }

    /** The input size of [meta] as the screens, the HUD and the diagnostics print it (`640` or `832x384`). */
    private fun inputText(meta: ImportedModelMeta): String = ModelInputText.text(meta.inputWidth, meta.inputHeight)

    /** The one [ImportFailure] of a failure kind. */
    fun failure(kind: FailureKind): ImportFailure =
        when (kind) {
            FailureKind.BUSY -> ImportFailure.BUSY
            FailureKind.GUARD_NOT_STORED -> ImportFailure.GUARD_NOT_STORED
            FailureKind.LIBRARY_FULL -> ImportFailure.LIBRARY_FULL
            FailureKind.IO -> ImportFailure.IO
        }

    /** The one [ModelRejectionReason] of a refusal. */
    fun reason(kind: RejectionKind): ModelRejectionReason =
        when (kind) {
            is RejectionKind.Sniffed -> sniffReason(kind.reason)
            RejectionKind.Unreadable -> ModelRejectionReason.UNREADABLE
            is RejectionKind.Layout -> layoutReason(kind.reason)
        }

    private fun sniffReason(reason: SniffRejection): ModelRejectionReason =
        when (reason) {
            SniffRejection.TOO_SMALL -> ModelRejectionReason.TOO_SMALL
            SniffRejection.TOO_LARGE -> ModelRejectionReason.TOO_LARGE
            SniffRejection.HEADER_TOO_SHORT -> ModelRejectionReason.HEADER_TOO_SHORT
            SniffRejection.NOT_TFLITE -> ModelRejectionReason.NOT_TFLITE
        }

    private fun layoutReason(reason: ImportRejection): ModelRejectionReason =
        when (reason) {
            ImportRejection.INPUT_COUNT -> ModelRejectionReason.INPUT_COUNT
            ImportRejection.INPUT_TYPE -> ModelRejectionReason.INPUT_TYPE
            ImportRejection.INPUT_NOT_NCHW -> ModelRejectionReason.INPUT_NOT_NCHW
            ImportRejection.INPUT_NHWC -> ModelRejectionReason.INPUT_NHWC
            ImportRejection.INPUT_ASPECT -> ModelRejectionReason.INPUT_ASPECT
            ImportRejection.INPUT_SIZE_NOT_MULTIPLE -> ModelRejectionReason.INPUT_SIZE_NOT_MULTIPLE
            ImportRejection.INPUT_SIZE_OUT_OF_RANGE -> ModelRejectionReason.INPUT_SIZE_OUT_OF_RANGE
            ImportRejection.OUTPUT_COUNT -> ModelRejectionReason.OUTPUT_COUNT
            ImportRejection.OUTPUT_TYPE -> ModelRejectionReason.OUTPUT_TYPE
            ImportRejection.OUTPUT_CLASSIC_HEAD -> ModelRejectionReason.OUTPUT_CLASSIC_HEAD
            ImportRejection.OUTPUT_ROWS -> ModelRejectionReason.OUTPUT_ROWS
            ImportRejection.OUTPUT_SHAPE -> ModelRejectionReason.OUTPUT_SHAPE
        }

    /**
     * The model state to show, from what the store holds ([stored]) and the newest session's [status] (`null` =
     * none, or one the owner has already acted on). The stored state decides the model and its own notices
     * (tripped guard, missing file). The session adds only what the store cannot know: a failed load or an unsaved
     * crash marker of the SAME imported model, or unreadable stored state when nothing is imported. Anything
     * else in [status] is stale or already explained, and fails closed to "no notice".
     *
     * A guard tripped at the probe of a new file keeps a stored model that still matches its meta ([storedFileMatches],
     * disk work, so call this off the main thread): that model is active and the "model kept" notice is shown,
     * unless the last session's failed load of the same model has something newer to say.
     */
    fun stateLoaded(
        stored: StoredModelState,
        status: ModelStatus?,
        storedFileMatches: (ImportedModelMeta) -> Boolean = { false },
    ): ControlEvent.ModelStateLoaded =
        when (val choice = ModelChoice.resolve(stored, storedFileMatches)) {
            is ModelChoice.Imported -> {
                val active = ActiveModel.Imported(choice.meta.displayName, inputText(choice.meta))
                val sameModel = status != null && status.meta == choice.meta
                val failed = if (sameModel) status?.fallback else null
                val notice =
                    when (failed) {
                        ModelFallback.LOAD_FAILED -> ModelNotice(ModelNoticeKind.LOAD_FAILED, status?.message)
                        ModelFallback.GUARD_NOT_STORED -> ModelNotice(ModelNoticeKind.GUARD_NOT_STORED)
                        else -> probeTripNotice(stored.guard)
                    }
                ControlEvent.ModelStateLoaded(active, notice, stored.anyFileStored)
            }

            is ModelChoice.BuiltIn -> {
                val notice =
                    when (choice.reason) {
                        BuiltInReason.FILE_MISSING -> ModelNotice(ModelNoticeKind.FILE_MISSING)
                        BuiltInReason.GUARD_TRIPPED_PROBE -> ModelNotice(ModelNoticeKind.GUARD_TRIPPED_PROBE)
                        BuiltInReason.GUARD_TRIPPED_LOAD -> ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD)
                        null -> unreadableNotice(status)
                    }
                ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, notice, stored.anyFileStored)
            }
        }

    /**
     * The whole model block from the library ([library]) and the newest session's [status]: the library list, the
     * two choices, and the model and notice of the slot of [orientation] (the screen's current orientation, which is
     * the one the next start captures), read exactly as [stateLoaded] reads one stored model. A choice that could not
     * be read shows the built-in model and a "could not be read" notice. [maxModels] is `TrackerConfig.modelLibraryMax`.
     */
    fun libraryLoaded(
        library: LibraryState,
        status: ModelStatus?,
        orientation: ModelOrientation,
        maxModels: Int,
        storedFileMatches: (ImportedModelMeta) -> Boolean = { false },
    ): ControlEvent.ModelStateLoaded {
        val slot =
            if (orientation in library.slotsUnreadable) {
                val notice = ModelNotice(ModelNoticeKind.STATE_UNREADABLE, "${orientation.logName}: the stored choice is unreadable")
                ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, notice, library.anyFileStored)
            } else {
                stateLoaded(library.viewFor(orientation), status, storedFileMatches)
            }
        val items =
            library.entries.map {
                LibraryItem(it.meta.sha256, it.meta.displayName, inputText(it.meta), library.isBlocked(it.meta.sha256))
            }
        val noticed =
            if (library.blockedUnreadable && slot.notice == null) {
                slot.copy(notice = ModelNotice(ModelNoticeKind.BLOCKED_UNREADABLE))
            } else {
                slot
            }
        return noticed.copy(
            library = items,
            portraitSha = shaOf(library.slots.portrait),
            landscapeSha = shaOf(library.slots.landscape),
            libraryFull = library.entries.size >= maxModels,
        )
    }

    private fun shaOf(choice: SlotChoice): String? = if (choice is SlotChoice.Library) choice.sha256 else null

    /** Only a trip at the probe reaches here with an imported model active (a load trip is always built-in). */
    private fun probeTripNotice(guard: ImportGuardState): ModelNotice? =
        if (guard == ImportGuardState.Tripped(ImportStage.PROBE)) ModelNotice(ModelNoticeKind.PROBE_TRIP_MODEL_KEPT) else null

    private fun unreadableNotice(status: ModelStatus?): ModelNotice? =
        if (status?.fallback == ModelFallback.STATE_UNREADABLE) {
            ModelNotice(ModelNoticeKind.STATE_UNREADABLE, status?.message)
        } else {
            null
        }

    /** The sentence that tells why a file was refused. */
    @StringRes
    fun messageRes(reason: ModelRejectionReason): Int =
        when (reason) {
            ModelRejectionReason.TOO_SMALL -> R.string.model_reject_too_small
            ModelRejectionReason.TOO_LARGE -> R.string.model_reject_too_large
            ModelRejectionReason.HEADER_TOO_SHORT -> R.string.model_reject_header_too_short
            ModelRejectionReason.NOT_TFLITE -> R.string.model_reject_not_tflite
            ModelRejectionReason.UNREADABLE -> R.string.model_reject_unreadable
            ModelRejectionReason.INPUT_COUNT -> R.string.model_reject_input_count
            ModelRejectionReason.INPUT_TYPE -> R.string.model_reject_input_type
            ModelRejectionReason.INPUT_NOT_NCHW -> R.string.model_reject_input_not_nchw
            ModelRejectionReason.INPUT_NHWC -> R.string.model_reject_input_nhwc
            ModelRejectionReason.INPUT_ASPECT -> R.string.model_reject_input_aspect
            ModelRejectionReason.INPUT_SIZE_NOT_MULTIPLE -> R.string.model_reject_input_size_not_multiple
            ModelRejectionReason.INPUT_SIZE_OUT_OF_RANGE -> R.string.model_reject_input_size_out_of_range
            ModelRejectionReason.OUTPUT_COUNT -> R.string.model_reject_output_count
            ModelRejectionReason.OUTPUT_TYPE -> R.string.model_reject_output_type
            ModelRejectionReason.OUTPUT_CLASSIC_HEAD -> R.string.model_reject_output_classic_head
            ModelRejectionReason.OUTPUT_ROWS -> R.string.model_reject_output_rows
            ModelRejectionReason.OUTPUT_SHAPE -> R.string.model_reject_output_shape
        }

    /**
     * The aspect limit [limit] (`TrackerConfig.importMaxInputAspect`) as the sentence of
     * `model_reject_input_aspect` prints it: no `.0` for a whole number (`4`), otherwise one decimal with a dot.
     */
    fun aspectLimitText(limit: Float): String =
        if (limit % 1f == 0f) limit.toInt().toString() else String.format(Locale.ROOT, "%.1f", limit)

    /** The sentence of a failed import; the one for [ImportFailure.IO] takes the technical message as its argument. */
    @StringRes
    fun failureRes(failure: ImportFailure): Int =
        when (failure) {
            ImportFailure.BUSY -> R.string.model_result_busy
            ImportFailure.GUARD_NOT_STORED -> R.string.model_result_guard_not_stored
            ImportFailure.LIBRARY_FULL -> R.string.model_result_library_full
            ImportFailure.IO -> R.string.model_result_failed
        }

    /** The sentence of a model notice. */
    @StringRes
    fun noticeRes(kind: ModelNoticeKind): Int =
        when (kind) {
            ModelNoticeKind.FILE_MISSING -> R.string.model_notice_file_missing
            ModelNoticeKind.GUARD_TRIPPED_PROBE -> R.string.model_notice_guard_probe
            ModelNoticeKind.PROBE_TRIP_MODEL_KEPT -> R.string.model_notice_probe_trip_kept
            ModelNoticeKind.GUARD_TRIPPED_LOAD -> R.string.model_notice_guard_load
            ModelNoticeKind.GUARD_NOT_STORED -> R.string.model_notice_guard_not_stored
            ModelNoticeKind.LOAD_FAILED -> R.string.model_notice_load_failed
            ModelNoticeKind.STATE_UNREADABLE -> R.string.model_notice_state_unreadable
            ModelNoticeKind.BLOCKED_UNREADABLE -> R.string.model_notice_blocked_unreadable
        }
}
