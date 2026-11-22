package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelimport.SniffRejection
import org.junit.Test

/** M1b-fix-import: an accepted import writes exactly one log line, whatever else the outcome is. */
class AcceptedImportLogOnceTest {
    private val sha = "ab".repeat(32)
    private val meta = ImportedModelMeta("wide.tflite", sha, 400L, 832, 384, 300)
    private val lines = mutableListOf<String>()

    @Test
    fun acceptedNew_logsExactlyOneLineAndReturnsTheOutcome() {
        val outcome = ImportOutcome.Accepted(meta, ModelOrientation.LANDSCAPE, duplicate = false, libraryCount = 1)

        val returned = AcceptedImportLog.logIfAccepted(outcome) { lines.add(it) }

        assertThat(returned).isSameInstanceAs(outcome)
        assertThat(lines).containsExactly(AcceptedImportLog.line(outcome))
        assertThat(lines.single()).contains(" new ")
    }

    @Test
    fun acceptedDuplicate_logsExactlyOneLine() {
        val outcome = ImportOutcome.Accepted(meta, null, duplicate = true, libraryCount = 1)

        AcceptedImportLog.logIfAccepted(outcome) { lines.add(it) }

        assertThat(lines).hasSize(1)
        assertThat(lines.single()).contains(" duplicate ")
    }

    @Test
    fun rejected_logsNothing() {
        val outcome = ImportOutcome.Rejected(RejectionKind.Sniffed(SniffRejection.TOO_SMALL), "12 bytes")

        val returned = AcceptedImportLog.logIfAccepted(outcome) { lines.add(it) }

        assertThat(returned).isSameInstanceAs(outcome)
        assertThat(lines).isEmpty()
    }

    @Test
    fun failed_logsNothing() {
        AcceptedImportLog.logIfAccepted(ImportOutcome.Failed(FailureKind.BUSY)) { lines.add(it) }

        assertThat(lines).isEmpty()
    }
}
