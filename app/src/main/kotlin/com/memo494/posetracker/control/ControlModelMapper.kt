package com.memo494.posetracker.control

import androidx.annotation.StringRes
import com.memo494.posetracker.R
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.SniffRejection
import com.memo494.posetracker.modelimport.BuiltInReason
import com.memo494.posetracker.modelimport.ImportOutcome
import com.memo494.posetracker.modelimport.ModelChoice
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.RejectionKind
import com.memo494.posetracker.modelimport.StoredModelState

/**
 * Pure bridges between the model-import types and the control screen: outcomes and stored state become the small
 * data of [ControlState], and every enum value gets exactly one text resource. The enum mappings are exhaustive
 * `when`s without an `else`, so a new enum value is a compile error here until it has a reason or a message.
 */
internal object ControlModelMapper {
    /** The importer's [outcome] as pure data for the reducer. */
    fun summary(outcome: ImportOutcome): ImportSummary =
        when (outcome) {
            is ImportOutcome.Accepted -> ImportSummary.Accepted(outcome.meta.displayName, outcome.meta.inputSize)
            is ImportOutcome.Rejected -> ImportSummary.Rejected(reason(outcome.kind), outcome.details)
            is ImportOutcome.Failed -> ImportSummary.Failed(outcome.message)
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
            ImportRejection.INPUT_NOT_SQUARE -> ModelRejectionReason.INPUT_NOT_SQUARE
            ImportRejection.INPUT_SIZE_NOT_MULTIPLE -> ModelRejectionReason.INPUT_SIZE_NOT_MULTIPLE
            ImportRejection.INPUT_SIZE_OUT_OF_RANGE -> ModelRejectionReason.INPUT_SIZE_OUT_OF_RANGE
            ImportRejection.OUTPUT_COUNT -> ModelRejectionReason.OUTPUT_COUNT
            ImportRejection.OUTPUT_TYPE -> ModelRejectionReason.OUTPUT_TYPE
            ImportRejection.OUTPUT_CLASSIC_HEAD -> ModelRejectionReason.OUTPUT_CLASSIC_HEAD
            ImportRejection.OUTPUT_SHAPE -> ModelRejectionReason.OUTPUT_SHAPE
        }

    /**
     * The model state to show, from what the store holds ([stored]) and the newest session's [status] (`null` =
     * none, or one the owner has already acted on). The stored state decides the model and its own notices
     * (tripped guard, missing file). The session adds only what the store cannot know: a failed load or an unsaved
     * crash marker of the SAME imported model, or unreadable stored state when nothing is imported. Anything
     * else in [status] is stale or already explained, and fails closed to "no notice".
     */
    fun stateLoaded(
        stored: StoredModelState,
        status: ModelStatus?,
    ): ControlEvent.ModelStateLoaded =
        when (val choice = ModelChoice.resolve(stored)) {
            is ModelChoice.Imported -> {
                val active = ActiveModel.Imported(choice.meta.displayName, choice.meta.inputSize)
                val sameModel = status != null && status.meta == choice.meta
                val failed = if (sameModel) status?.fallback else null
                val notice =
                    when (failed) {
                        ModelFallback.LOAD_FAILED -> ModelNotice(ModelNoticeKind.LOAD_FAILED, status?.message)
                        ModelFallback.GUARD_NOT_STORED -> ModelNotice(ModelNoticeKind.GUARD_NOT_STORED)
                        else -> null
                    }
                ControlEvent.ModelStateLoaded(active, notice)
            }
            is ModelChoice.BuiltIn -> {
                val notice =
                    when (choice.reason) {
                        BuiltInReason.FILE_MISSING -> ModelNotice(ModelNoticeKind.FILE_MISSING)
                        BuiltInReason.GUARD_TRIPPED_PROBE -> ModelNotice(ModelNoticeKind.GUARD_TRIPPED_PROBE)
                        BuiltInReason.GUARD_TRIPPED_LOAD -> ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD)
                        null -> unreadableNotice(status)
                    }
                ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, notice)
            }
        }

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
            ModelRejectionReason.INPUT_NOT_SQUARE -> R.string.model_reject_input_not_square
            ModelRejectionReason.INPUT_SIZE_NOT_MULTIPLE -> R.string.model_reject_input_size_not_multiple
            ModelRejectionReason.INPUT_SIZE_OUT_OF_RANGE -> R.string.model_reject_input_size_out_of_range
            ModelRejectionReason.OUTPUT_COUNT -> R.string.model_reject_output_count
            ModelRejectionReason.OUTPUT_TYPE -> R.string.model_reject_output_type
            ModelRejectionReason.OUTPUT_CLASSIC_HEAD -> R.string.model_reject_output_classic_head
            ModelRejectionReason.OUTPUT_SHAPE -> R.string.model_reject_output_shape
        }

    /** The sentence of a model notice. */
    @StringRes
    fun noticeRes(kind: ModelNoticeKind): Int =
        when (kind) {
            ModelNoticeKind.FILE_MISSING -> R.string.model_notice_file_missing
            ModelNoticeKind.GUARD_TRIPPED_PROBE -> R.string.model_notice_guard_probe
            ModelNoticeKind.GUARD_TRIPPED_LOAD -> R.string.model_notice_guard_load
            ModelNoticeKind.GUARD_NOT_STORED -> R.string.model_notice_guard_not_stored
            ModelNoticeKind.LOAD_FAILED -> R.string.model_notice_load_failed
            ModelNoticeKind.STATE_UNREADABLE -> R.string.model_notice_state_unreadable
        }
}
