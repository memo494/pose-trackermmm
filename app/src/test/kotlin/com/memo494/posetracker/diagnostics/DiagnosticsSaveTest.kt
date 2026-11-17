package com.memo494.posetracker.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class DiagnosticsSaveTest {
    private fun clockAt(
        iso: String,
        zone: ZoneId,
    ): Clock = Clock.fixed(Instant.parse(iso), zone)

    @Test
    fun fileName_usesTheClockInTheGivenZone() {
        val clock = clockAt("2026-11-17T09:05:07Z", ZoneOffset.UTC)

        assertThat(DiagnosticsSave.fileName(clock)).isEqualTo("pose-tracker-diagnostics-20261117-090507.zip")
    }

    @Test
    fun fileName_followsTheTimeZoneOffset() {
        val clock = clockAt("2026-11-17T23:30:00Z", ZoneOffset.ofHours(3))

        assertThat(DiagnosticsSave.fileName(clock)).isEqualTo("pose-tracker-diagnostics-20261118-023000.zip")
    }

    @Test
    fun fileName_padsSingleDigitFields() {
        val clock = clockAt("2026-01-02T03:04:05Z", ZoneOffset.UTC)

        assertThat(DiagnosticsSave.fileName(clock)).isEqualTo("pose-tracker-diagnostics-20260102-030405.zip")
    }

    @Test
    fun copy_writesAllBytesAndReturnsTrue() {
        val data = ByteArray(20_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val ok = DiagnosticsSave.copy(ByteArrayInputStream(data), output)

        assertThat(ok).isTrue()
        assertThat(output.toByteArray()).isEqualTo(data)
    }

    @Test
    fun copy_closesBothStreams() {
        val input = TrackingInput(ByteArray(10))
        val output = TrackingOutput()

        val ok = DiagnosticsSave.copy(input, output)

        assertThat(ok).isTrue()
        assertThat(input.closed).isTrue()
        assertThat(output.closed).isTrue()
    }

    @Test
    fun copy_whenClosingTheOutputThrows_returnsFalse() {
        val output =
            object : ByteArrayOutputStream() {
                override fun close(): Unit = throw IOException("close failed")
            }

        assertThat(DiagnosticsSave.copy(ByteArrayInputStream(ByteArray(10)), output)).isFalse()
    }

    @Test
    fun copy_withNullOutput_returnsFalseAndClosesTheInput() {
        val input = TrackingInput(ByteArray(10))

        val ok = DiagnosticsSave.copy(input, null)

        assertThat(ok).isFalse()
        assertThat(input.closed).isTrue()
    }

    @Test
    fun copy_withNullInput_returnsFalseAndClosesTheOutput() {
        val output = TrackingOutput()

        val ok = DiagnosticsSave.copy(null, output)

        assertThat(ok).isFalse()
        assertThat(output.closed).isTrue()
    }

    @Test
    fun copy_whenReadingThrows_returnsFalse() {
        val input =
            object : InputStream() {
                override fun read(): Int = throw IOException("read failed")
            }

        assertThat(DiagnosticsSave.copy(input, ByteArrayOutputStream())).isFalse()
    }

    @Test
    fun copy_whenWritingThrows_returnsFalse() {
        val output =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("disk full")
            }

        assertThat(DiagnosticsSave.copy(ByteArrayInputStream(ByteArray(10)), output)).isFalse()
    }

    @Test
    fun copy_whenAnErrorIsThrown_returnsFalse() {
        val output =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw OutOfMemoryError("test")
            }

        assertThat(DiagnosticsSave.copy(ByteArrayInputStream(ByteArray(10)), output)).isFalse()
    }

    private class TrackingInput(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingOutput : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
