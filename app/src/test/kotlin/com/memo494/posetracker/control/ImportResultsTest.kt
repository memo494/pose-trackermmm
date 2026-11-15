package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportResultsTest {
    @Test
    fun publish_raisesTheSequenceAndKeepsTheNewestSummary() {
        val before = ImportResults.latest.value.sequence
        val first = ImportSummary.Failed(ImportFailure.BUSY, null)
        val second = ImportSummary.Accepted("a.tflite", 640)

        ImportResults.publish(first)
        val afterFirst = ImportResults.latest.value
        ImportResults.publish(second)
        val afterSecond = ImportResults.latest.value

        assertThat(afterFirst.sequence).isEqualTo(before + 1)
        assertThat(afterFirst.summary).isEqualTo(first)
        assertThat(afterSecond.sequence).isEqualTo(before + 2)
        assertThat(afterSecond.summary).isEqualTo(second)
    }

    @Test
    fun newCollector_seesTheLastResultItMissed() {
        val seen = ImportResults.latest.value.sequence
        ImportResults.publish(ImportSummary.Accepted("late.tflite", 416))

        val latest = ImportResults.latest.value

        assertThat(latest.sequence).isGreaterThan(seen)
        assertThat(latest.summary).isEqualTo(ImportSummary.Accepted("late.tflite", 416))
    }
}
