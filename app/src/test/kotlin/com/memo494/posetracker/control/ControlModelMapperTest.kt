package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.SniffRejection
import com.memo494.posetracker.modelimport.FailureKind
import com.memo494.posetracker.modelimport.ImportOutcome
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.RejectionKind
import com.memo494.posetracker.modelimport.StoredModelState
import org.junit.Test
import java.util.Locale

class ControlModelMapperTest {
    private val meta =
        ImportedModelMeta(
            displayName = "yolo26n-pose_640.tflite",
            sha256 = "a".repeat(64),
            bytes = 1_000L,
            inputWidth = 640,
            inputHeight = 640,
            outputRows = 300,
        )

    private fun stored(
        meta: ImportedModelMeta? = null,
        fileExists: Boolean = false,
        guard: ImportGuardState = ImportGuardState.Idle,
        anyFileStored: Boolean = fileExists,
    ) = StoredModelState(meta, fileExists, guard, anyFileStored)

    private fun status(
        fallback: ModelFallback?,
        meta: ImportedModelMeta? = null,
        message: String? = null,
    ) = ModelStatus(ModelSource.BUILT_IN, meta, fallback, message, 416, 416)

    // Outcomes to pure summaries.

    @Test
    fun everySniffRejection_hasItsOwnReason() {
        val reasons = SniffRejection.entries.map { ControlModelMapper.reason(RejectionKind.Sniffed(it)) }

        assertThat(reasons).containsNoDuplicates()
    }

    @Test
    fun everyImportRejection_hasItsOwnReason() {
        val reasons = ImportRejection.entries.map { ControlModelMapper.reason(RejectionKind.Layout(it)) }

        assertThat(reasons).containsNoDuplicates()
    }

    @Test
    fun everyReason_isUsedByExactlyOneRejection() {
        val sniffed = SniffRejection.entries.map { ControlModelMapper.reason(RejectionKind.Sniffed(it)) }
        val layout = ImportRejection.entries.map { ControlModelMapper.reason(RejectionKind.Layout(it)) }
        val all = sniffed + layout + ControlModelMapper.reason(RejectionKind.Unreadable)

        assertThat(all).containsNoDuplicates()
        assertThat(all).containsExactlyElementsIn(ModelRejectionReason.entries)
    }

    @Test
    fun everyReason_hasItsOwnMessage() {
        val ids = ModelRejectionReason.entries.map { ControlModelMapper.messageRes(it) }

        assertThat(ids).doesNotContain(0)
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun aspectLimitText_wholeNumberHasNoDecimal() {
        assertThat(ControlModelMapper.aspectLimitText(4.0f)).isEqualTo("4")
        assertThat(ControlModelMapper.aspectLimitText(1.0f)).isEqualTo("1")
        assertThat(ControlModelMapper.aspectLimitText(16.0f)).isEqualTo("16")
    }

    @Test
    fun aspectLimitText_otherNumberHasOneDecimalAndADot() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)

            assertThat(ControlModelMapper.aspectLimitText(2.5f)).isEqualTo("2.5")
            assertThat(ControlModelMapper.aspectLimitText(1.5f)).isEqualTo("1.5")
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun everyNoticeKind_hasItsOwnMessage() {
        val ids = ModelNoticeKind.entries.map { ControlModelMapper.noticeRes(it) }

        assertThat(ids).doesNotContain(0)
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun summary_accepted_keepsTheNameAndTheInputSize() {
        val summary = ControlModelMapper.summary(ImportOutcome.Accepted(meta))

        assertThat(summary).isEqualTo(ImportSummary.Accepted("yolo26n-pose_640.tflite", "640"))
    }

    @Test
    fun summary_accepted_ofARectangularModel_hasTheWidthXHeightText() {
        val rectangular = meta.copy(displayName = "yolo26n-pose_832x384.tflite", inputWidth = 832, inputHeight = 384)

        val summary = ControlModelMapper.summary(ImportOutcome.Accepted(rectangular))

        assertThat(summary).isEqualTo(ImportSummary.Accepted("yolo26n-pose_832x384.tflite", "832x384"))
    }

    @Test
    fun loaded_importedRectangularModel_isActiveWithTheWidthXHeightText() {
        val rectangular = meta.copy(inputWidth = 832, inputHeight = 384)

        val event = ControlModelMapper.stateLoaded(stored(rectangular, fileExists = true), null)

        assertThat(event.active).isEqualTo(ActiveModel.Imported("yolo26n-pose_640.tflite", "832x384"))
    }

    @Test
    fun summary_rejected_keepsTheReasonAndTheDetails() {
        val outcome = ImportOutcome.Rejected(RejectionKind.Layout(ImportRejection.INPUT_NHWC), "input [1,640,640,3] float32")

        val summary = ControlModelMapper.summary(outcome)

        assertThat(summary).isEqualTo(ImportSummary.Rejected(ModelRejectionReason.INPUT_NHWC, "input [1,640,640,3] float32"))
    }

    @Test
    fun summary_failed_keepsTheKindAndTheMessage() {
        val summary = ControlModelMapper.summary(ImportOutcome.Failed(FailureKind.IO, "IOException: boom"))

        assertThat(summary).isEqualTo(ImportSummary.Failed(ImportFailure.IO, "IOException: boom"))
    }

    @Test
    fun summary_failedWithoutMessage_keepsTheKind() {
        assertThat(ControlModelMapper.summary(ImportOutcome.Failed(FailureKind.BUSY)))
            .isEqualTo(ImportSummary.Failed(ImportFailure.BUSY, null))
    }

    @Test
    fun everyFailureKind_hasItsOwnFailureAndItsOwnMessage() {
        val failures = FailureKind.entries.map { ControlModelMapper.failure(it) }

        assertThat(failures).containsNoDuplicates()
        assertThat(failures).containsExactlyElementsIn(ImportFailure.entries)
        val messages = ImportFailure.entries.map { ControlModelMapper.failureRes(it) }
        assertThat(messages).containsNoDuplicates()
        assertThat(messages).doesNotContain(0)
    }

    // Stored state and the last session to the model block.

    @Test
    fun loaded_nothingImported_isBuiltInWithoutNotice() {
        val event = ControlModelMapper.stateLoaded(stored(), null)

        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null))
    }

    @Test
    fun loaded_orphanFileWithoutMetaOrNotice_stillReportsAStoredFile() {
        val event = ControlModelMapper.stateLoaded(stored(anyFileStored = true), null)

        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null, fileStored = true))
    }

    @Test
    fun loaded_nothingOnDisk_reportsNoStoredFile() {
        assertThat(ControlModelMapper.stateLoaded(stored(), null).fileStored).isFalse()
    }

    @Test
    fun loaded_importedModelPresent_isImportedWithoutNotice() {
        val event = ControlModelMapper.stateLoaded(stored(meta, fileExists = true), null)

        val expected = ControlEvent.ModelStateLoaded(ActiveModel.Imported("yolo26n-pose_640.tflite", "640"), null, fileStored = true)
        assertThat(event).isEqualTo(expected)
    }

    @Test
    fun loaded_trippedGuard_isBuiltInWithTheStageNotice() {
        val probe = ControlModelMapper.stateLoaded(stored(meta, true, ImportGuardState.Tripped(ImportStage.PROBE)), null)
        val load = ControlModelMapper.stateLoaded(stored(meta, true, ImportGuardState.Tripped(ImportStage.LOAD)), null)

        val probeNotice = ModelNotice(ModelNoticeKind.GUARD_TRIPPED_PROBE)
        val loadNotice = ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD)
        assertThat(probe).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, probeNotice, fileStored = true))
        assertThat(load).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, loadNotice, fileStored = true))
    }

    @Test
    fun loaded_probeTripWithAStoredModelThatMatches_keepsTheModelAndShowsTheKeptNotice() {
        val tripped = stored(meta, true, ImportGuardState.Tripped(ImportStage.PROBE))

        val event = ControlModelMapper.stateLoaded(tripped, null) { true }

        val active = ActiveModel.Imported("yolo26n-pose_640.tflite", "640")
        val notice = ModelNotice(ModelNoticeKind.PROBE_TRIP_MODEL_KEPT)
        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(active, notice, fileStored = true))
    }

    @Test
    fun loaded_probeTripWithAStoredModelThatDoesNotMatch_isBuiltInWithTheNotice() {
        val tripped = stored(meta, true, ImportGuardState.Tripped(ImportStage.PROBE))

        val event = ControlModelMapper.stateLoaded(tripped, null) { false }

        val notice = ModelNotice(ModelNoticeKind.GUARD_TRIPPED_PROBE)
        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, notice, fileStored = true))
    }

    @Test
    fun loaded_loadTripWithAMatchingFile_isStillBuiltIn() {
        val tripped = stored(meta, true, ImportGuardState.Tripped(ImportStage.LOAD))

        val event = ControlModelMapper.stateLoaded(tripped, null) { true }

        val notice = ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD)
        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, notice, fileStored = true))
    }

    @Test
    fun loaded_probeTripKeptModel_aFailedLoadOfThatModelWinsOverTheTripNotice() {
        val tripped = stored(meta, true, ImportGuardState.Tripped(ImportStage.PROBE))
        val last = status(ModelFallback.LOAD_FAILED, meta, "boom")

        val event = ControlModelMapper.stateLoaded(tripped, last) { true }

        assertThat(event.notice).isEqualTo(ModelNotice(ModelNoticeKind.LOAD_FAILED, "boom"))
    }

    @Test
    fun loaded_fileMissing_isBuiltInWithTheMissingNotice() {
        val event = ControlModelMapper.stateLoaded(stored(meta, fileExists = false), null)

        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, ModelNotice(ModelNoticeKind.FILE_MISSING)))
    }

    @Test
    fun loaded_lastSessionCouldNotLoadTheSameModel_showsTheMessage() {
        val last = status(ModelFallback.LOAD_FAILED, meta, "IllegalStateException: bad flatbuffer")

        val event = ControlModelMapper.stateLoaded(stored(meta, fileExists = true), last)

        val notice = ModelNotice(ModelNoticeKind.LOAD_FAILED, "IllegalStateException: bad flatbuffer")
        val imported = ActiveModel.Imported("yolo26n-pose_640.tflite", "640")
        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(imported, notice, fileStored = true))
    }

    @Test
    fun loaded_lastSessionMarkerNotStored_showsTheNotice() {
        val last = status(ModelFallback.GUARD_NOT_STORED, meta)

        val event = ControlModelMapper.stateLoaded(stored(meta, fileExists = true), last)

        assertThat(event.notice).isEqualTo(ModelNotice(ModelNoticeKind.GUARD_NOT_STORED))
    }

    @Test
    fun loaded_lastSessionFailureOfAnotherModel_isNotShown() {
        val other = meta.copy(sha256 = "b".repeat(64))
        val last = status(ModelFallback.LOAD_FAILED, other, "x")

        val event = ControlModelMapper.stateLoaded(stored(meta, fileExists = true), last)

        assertThat(event.notice).isNull()
    }

    @Test
    fun loaded_lastSessionFailureAfterTheModelWasCleared_isNotShown() {
        val last = status(ModelFallback.LOAD_FAILED, meta, "x")

        val event = ControlModelMapper.stateLoaded(stored(), last)

        assertThat(event).isEqualTo(ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null))
    }

    @Test
    fun loaded_lastSessionCouldNotReadTheStoredState_showsTheMessage() {
        val last = status(ModelFallback.STATE_UNREADABLE, null, "IOException: disk")

        val event = ControlModelMapper.stateLoaded(stored(), last)

        assertThat(event.notice).isEqualTo(ModelNotice(ModelNoticeKind.STATE_UNREADABLE, "IOException: disk"))
    }

    @Test
    fun loaded_aTrippedGuardWinsOverTheLastSession() {
        val last = status(ModelFallback.STATE_UNREADABLE, null, "x")

        val event = ControlModelMapper.stateLoaded(stored(meta, true, ImportGuardState.Tripped(ImportStage.LOAD)), last)

        assertThat(event.notice).isEqualTo(ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD))
    }

    @Test
    fun loaded_sessionFallbacksTheStoredStateAlreadyExplains_addNothing() {
        val repeated = listOf(ModelFallback.FILE_MISSING, ModelFallback.GUARD_TRIPPED_PROBE, ModelFallback.GUARD_TRIPPED_LOAD)
        for (fallback in repeated) {
            val event = ControlModelMapper.stateLoaded(stored(), status(fallback, meta))

            assertThat(event.notice).isNull()
        }
    }

    @Test
    fun loaded_theSquareFallbackRuns_saysTheBuiltInModelFailed() {
        val last =
            status(
                ModelFallback.BUILT_IN_FAILED,
                null,
                "built-in model failed: IllegalStateException: bad",
            ).copy(builtInFailed = true)

        val event = ControlModelMapper.stateLoaded(stored(), last)

        assertThat(event.active).isEqualTo(ActiveModel.BuiltIn)
        assertThat(
            event.notice,
        ).isEqualTo(ModelNotice(ModelNoticeKind.BUILT_IN_FAILED, "built-in model failed: IllegalStateException: bad"))
    }

    @Test
    fun loaded_builtInDidNotFail_showsNoSuchNotice() {
        assertThat(ControlModelMapper.stateLoaded(stored(), status(null)).notice).isNull()
    }
}
