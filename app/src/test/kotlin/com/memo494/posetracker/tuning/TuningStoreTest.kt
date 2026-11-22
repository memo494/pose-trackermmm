package com.memo494.posetracker.tuning

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.tuning.TuningKey
import com.memo494.posetracker.core.tuning.TuningOverrides
import com.memo494.posetracker.modelimport.FakeMainThread
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TuningStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val thread = FakeMainThread()
    private val logs = mutableListOf<String>()
    private var failRename = false
    private var failRead = false

    private val directory get() = File(tmp.root, "tuning")
    private val file get() = File(directory, TuningStore.FILE_NAME)

    private val gate = TuningSaveGate()

    private fun store(maxWaitMs: Long = 2_000L) =
        TuningStore(
            gate = gate,
            maxWaitMs = maxWaitMs,
            directory = directory,
            threadCheck = thread::check,
            rename = { from, to -> if (failRename) false else from.renameTo(to) },
            readFile = { if (failRead) throw IOException("read refused") else it.readText(Charsets.UTF_8) },
            log = { message, _ -> logs += message },
        )

    @Test
    fun read_noFile_isNoOverridesAndNoLogLine() {
        assertThat(store().read()).isEqualTo(TuningOverrides.NONE)
        assertThat(logs).isEmpty()
    }

    @Test
    fun write_thenRead_roundTrips() {
        val overrides = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.3f).with(TuningKey.RELOCK_JUDGE_FRAMES, 20f)

        assertThat(store().write(overrides)).isTrue()

        assertThat(store().read()).isEqualTo(overrides)
        assertThat(logs).isEmpty()
    }

    @Test
    fun write_leavesNoScratchFileBehind() {
        store().write(TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f))

        assertThat(directory.list()?.toList()).containsExactly(TuningStore.FILE_NAME)
    }

    @Test
    fun write_replacesAnEarlierFile() {
        store().write(TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f))
        store().write(TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.5f))

        val read = store().read()

        assertThat(read.count).isEqualTo(1)
        assertThat(read.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.5f)
    }

    @Test
    fun write_aFailedRenameKeepsTheOldFileLogsOnceAndRemovesTheScratchFile() {
        val old = TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f)
        store().write(old)
        failRename = true

        val written = store().write(TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.5f))

        assertThat(written).isFalse()
        assertThat(logs).hasSize(1)
        assertThat(directory.list()?.toList()).containsExactly(TuningStore.FILE_NAME)
        failRename = false
        assertThat(store().read()).isEqualTo(old)
    }

    @Test
    fun write_noOverridesWritesAFileThatReadsAsNone() {
        store().write(TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f))

        store().write(TuningOverrides.NONE)

        assertThat(store().read()).isEqualTo(TuningOverrides.NONE)
        assertThat(logs).isEmpty()
    }

    @Test
    fun read_garbageIsAllDefaultsWithOneLogLine() {
        directory.mkdirs()
        file.writeBytes(byteArrayOf(0, 1, 2, 3, -1, -2))

        assertThat(store().read()).isEqualTo(TuningOverrides.NONE)
        assertThat(logs).hasSize(1)
    }

    @Test
    fun read_anIoErrorIsAllDefaultsWithOneLogLine() {
        store().write(TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f))
        failRead = true

        assertThat(store().read()).isEqualTo(TuningOverrides.NONE)
        assertThat(logs).hasSize(1)
    }

    @Test
    fun read_aValueOutsideTheCurrentRangeIsIgnoredAndTheRestKept() {
        directory.mkdirs()
        file.writeText("${TuningOverrides.HEADER}\ntrackMaxLostMs=99999999\nscoreThreshold=0.4\n")

        val read = store().read()

        assertThat(read.valueOf(TuningKey.TRACK_MAX_LOST_MS)).isEqualTo(TuningKey.TRACK_MAX_LOST_MS.defaultValue)
        assertThat(read.valueOf(TuningKey.SCORE_THRESHOLD)).isEqualTo(0.4f)
        assertThat(logs).isEmpty()
    }

    @Test
    fun read_aDirectoryInThePlaceOfTheFileIsAllDefaultsWithOneLogLine() {
        File(directory, TuningStore.FILE_NAME).mkdirs()

        assertThat(store().read()).isEqualTo(TuningOverrides.NONE)
        assertThat(logs).hasSize(1)
    }

    @Test
    fun readAndWrite_refuseTheMainThread() {
        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) { store().read() }
        assertThrows(IllegalStateException::class.java) { store().write(TuningOverrides.NONE) }
    }

    @Test
    fun write_aScratchFileThatIsADirectoryDoesNotStopTheSave() {
        File(directory, TuningStore.FILE_NAME + ".tmp").mkdirs()
        val overrides = TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f)

        assertThat(store().write(overrides)).isTrue()

        assertThat(store().read()).isEqualTo(overrides)
        assertThat(directory.list()?.toList()).containsExactly(TuningStore.FILE_NAME)
    }

    @Test
    fun write_aSaveReservedEarlierThatArrivesLateIsSkippedAndTheNewerValueStays() {
        val first = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.2f)
        val second = TuningOverrides.NONE.with(TuningKey.SCORE_THRESHOLD, 0.6f)
        val older = store().reserve()
        val newer = store().reserve()

        assertThat(store().write(second, newer)).isTrue()
        assertThat(store().write(first, older)).isTrue()

        assertThat(store().read()).isEqualTo(second)
    }

    @Test
    fun write_savesInTheOrderReservedWhateverTheOrderTheyRun() {
        val values = (1..20).map { TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, it.toFloat()) }
        val tickets = values.map { store().reserve() }

        val workers = values.indices.shuffled().map { i -> Thread { store().write(values[i], tickets[i]) } }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertThat(store().read()).isEqualTo(values.last())
    }

    @Test
    fun read_waitsForASaveThatIsStillPending() {
        val saved = TuningOverrides.NONE.with(TuningKey.SMOOTH_BETA, 9f)
        val ticket = store().reserve()
        var read: TuningOverrides? = null
        val reader = Thread { read = store().read() }
        reader.start()
        Thread.sleep(100)

        store().write(saved, ticket)
        reader.join(5_000)

        assertThat(read).isEqualTo(saved)
    }

    @Test
    fun read_givesUpWaitingForASaveThatNeverComes() {
        store().reserve()

        val read = store(maxWaitMs = 50L).read()

        assertThat(read).isEqualTo(TuningOverrides.NONE)
    }
}
