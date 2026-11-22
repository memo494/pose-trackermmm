package com.memo494.posetracker.core.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

private const val INTERVAL_NS = 2_000_000_000L
private const val START_NS = 5_000_000_000L

class ThermalSamplerTest {
    private class FakeReader(
        var status: Int = 0,
        var headroom: Float = Float.NaN,
        var failure: Throwable? = null,
    ) : ThermalReader {
        var statusCalls = 0
        var headroomCalls = 0

        override fun status(): Int {
            statusCalls++
            failure?.let { throw it }
            return status
        }

        override fun headroom(): Float {
            headroomCalls++
            failure?.let { throw it }
            return headroom
        }
    }

    private val logs = mutableListOf<String>()
    private val loggedErrors = mutableListOf<Throwable?>()

    private fun sampler(reader: ThermalReader) =
        ThermalSampler(reader, INTERVAL_NS) { message, error ->
            logs.add(message)
            loggedErrors.add(error)
        }

    @Test
    fun beforeTheFirstSample_everythingIsNotAvailable() {
        val reader = FakeReader(status = 1, headroom = 0.5f)
        val sampler = sampler(reader)

        assertThat(sampler.status).isEqualTo("n/a")
        assertThat(sampler.headroom).isNaN()
        assertThat(reader.statusCalls).isEqualTo(0)
    }

    @Test
    fun theFirstSample_readsAtOnce() {
        val reader = FakeReader(status = 2, headroom = 0.75f)
        val sampler = sampler(reader)

        sampler.sample(START_NS)

        assertThat(sampler.status).isEqualTo("MODERATE")
        assertThat(sampler.headroom).isEqualTo(0.75f)
        assertThat(reader.statusCalls).isEqualTo(1)
        assertThat(reader.headroomCalls).isEqualTo(1)
    }

    @Test
    fun withinTheInterval_theReaderIsNotAskedAgain() {
        val reader = FakeReader(status = 0, headroom = 0.5f)
        val sampler = sampler(reader)
        sampler.sample(START_NS)
        reader.status = 3
        reader.headroom = 0.9f

        sampler.sample(START_NS + 1)
        sampler.sample(START_NS + INTERVAL_NS - 1)

        assertThat(reader.statusCalls).isEqualTo(1)
        assertThat(reader.headroomCalls).isEqualTo(1)
        assertThat(sampler.status).isEqualTo("NONE")
        assertThat(sampler.headroom).isEqualTo(0.5f)
    }

    @Test
    fun atOrAfterTheInterval_theReaderIsAskedAgain() {
        val reader = FakeReader(status = 0, headroom = 0.5f)
        val sampler = sampler(reader)
        sampler.sample(START_NS)
        reader.status = 3
        reader.headroom = 0.9f

        sampler.sample(START_NS + INTERVAL_NS)

        assertThat(reader.statusCalls).isEqualTo(2)
        assertThat(sampler.status).isEqualTo("SEVERE")
        assertThat(sampler.headroom).isEqualTo(0.9f)

        sampler.sample(START_NS + INTERVAL_NS + INTERVAL_NS - 1)

        assertThat(reader.statusCalls).isEqualTo(2)
    }

    @Test
    fun anUnavailableStatus_isNotAvailable() {
        val reader = FakeReader(status = ThermalReader.UNAVAILABLE, headroom = Float.NaN)
        val sampler = sampler(reader)

        sampler.sample(START_NS)

        assertThat(sampler.status).isEqualTo("n/a")
        assertThat(sampler.headroom).isNaN()
        assertThat(logs).isEmpty()
    }

    @Test
    fun aHeadroomThatIsNotAFiniteNonNegativeNumber_isNotAvailable() {
        val reader = FakeReader(status = 1)
        val sampler = sampler(reader)
        var now = START_NS

        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.5f)) {
            reader.headroom = bad
            sampler.sample(now)
            assertThat(sampler.headroom).isNaN()
            assertThat(sampler.status).isEqualTo("LIGHT")
            now += INTERVAL_NS
        }
    }

    @Test
    fun aFailingReader_givesNotAvailableAndOneLogLine() {
        val reader = FakeReader(failure = IllegalStateException("no thermal service"))
        val sampler = sampler(reader)

        sampler.sample(START_NS)
        sampler.sample(START_NS + INTERVAL_NS)
        sampler.sample(START_NS + 2 * INTERVAL_NS)

        assertThat(sampler.status).isEqualTo("n/a")
        assertThat(sampler.headroom).isNaN()
        assertThat(logs).hasSize(1)
        assertThat(loggedErrors.single()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun anErrorFromTheReader_isContainedToo() {
        val reader = FakeReader(failure = OutOfMemoryError("test"))
        val sampler = sampler(reader)

        sampler.sample(START_NS)

        assertThat(sampler.status).isEqualTo("n/a")
        assertThat(logs).hasSize(1)
    }

    @Test
    fun aReaderThatRecovers_isReadAgainAfterTheInterval() {
        val reader = FakeReader(status = 4, headroom = 1.1f, failure = IllegalStateException("x"))
        val sampler = sampler(reader)
        sampler.sample(START_NS)
        reader.failure = null

        sampler.sample(START_NS + INTERVAL_NS)

        assertThat(sampler.status).isEqualTo("CRITICAL")
        assertThat(sampler.headroom).isEqualTo(1.1f)
    }

    @Test
    fun aFailingLogger_doesNotEscape() {
        val reader = FakeReader(failure = IllegalStateException("x"))
        val sampler = ThermalSampler(reader, INTERVAL_NS) { _, _ -> throw IllegalStateException("logger") }

        sampler.sample(START_NS)

        assertThat(sampler.status).isEqualTo("n/a")
    }

    @Test
    fun sample_saysWhetherItReadAndKeepsTheStatusCode() {
        val sampler = sampler(FakeReader(status = 2, headroom = 0.5f))

        assertThat(sampler.statusCode).isEqualTo(ThermalReader.UNAVAILABLE)
        assertThat(sampler.sample(START_NS)).isTrue()
        assertThat(sampler.statusCode).isEqualTo(2)
        assertThat(sampler.sample(START_NS + 1)).isFalse()
        assertThat(sampler.sample(START_NS + INTERVAL_NS)).isTrue()
    }

    @Test
    fun aStatusThatCannotBeRead_hasTheUnavailableCode() {
        val sampler = sampler(FakeReader(status = ThermalReader.UNAVAILABLE))
        sampler.sample(START_NS)
        assertThat(sampler.statusCode).isEqualTo(ThermalReader.UNAVAILABLE)

        val failing = sampler(FakeReader(failure = IllegalStateException("boom")))
        failing.sample(START_NS)
        assertThat(failing.statusCode).isEqualTo(ThermalReader.UNAVAILABLE)
    }
}
