package com.memo494.posetracker.modelimport

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportCheck
import com.memo494.posetracker.core.modelimport.ImportGuardState
import com.memo494.posetracker.core.modelimport.ImportRejection
import com.memo494.posetracker.core.modelimport.ImportStage
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelimport.ImportedModelRules
import com.memo494.posetracker.core.modelimport.SniffRejection
import com.memo494.posetracker.core.modelimport.TensorDescription
import com.memo494.posetracker.core.modelpack.Sha256
import com.memo494.posetracker.detector.ProbeResult
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class ModelImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val config = TrackerConfig(importMinBytes = 100L, importMaxBytes = 10_000L)
    private val prefs = FakeGuardPrefs()
    private val thread = FakeMainThread()

    /** What the fake probe answers; set it before importing. */
    private var probeResult: ProbeResult = ProbeResult.Tensors(listOf(tensor(1, 3, 640, 640)), listOf(tensor(1, 300, 57)))

    /** When set, the fake probe throws it instead of answering. */
    private var probeThrows: Throwable? = null
    private var probeCalls = 0
    private var guardDuringProbe: ImportGuardState? = null
    private var failRenameOf: String? = null

    /** Runs inside the fake probe, on the importing thread, while the import is still in flight. */
    private var duringProbe: ((File) -> Unit)? = null
    private var probedFileName: String? = null

    private val directory get() = File(tmp.root, "imported")

    // Lazy: the TemporaryFolder rule creates the folder after the constructor ran.
    private val store by lazy {
        ImportedModelStore(directory, prefs, AtomicBoolean(false), thread::check) { from, to ->
            to.name != failRenameOf && from.renameTo(to)
        }
    }

    private val importer by lazy {
        ModelImporter(store, config, thread::check) { file ->
            probeCalls++
            guardDuringProbe = store.state().guard
            probedFileName = file.name
            assertThat(file.exists()).isTrue()
            duringProbe?.invoke(file)
            probeThrows?.let { throw it }
            probeResult
        }
    }

    private fun tensor(
        vararg shape: Int,
        type: String = "float32",
    ) = TensorDescription(shape.toList(), type)

    /** A plausible model file: [size] bytes with the TFLite identifier at bytes 4 to 7. */
    private fun tfliteBytes(
        size: Int,
        seed: Int = 1,
    ): ByteArray =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }.also {
            "TFL3".forEachIndexed { index, c -> it[4 + index] = c.code.toByte() }
        }

    private fun runImport(
        bytes: ByteArray,
        name: String? = "model.tflite",
        declared: Long? = bytes.size.toLong(),
    ): ImportOutcome = importer.import({ ByteArrayInputStream(bytes) }, name, declared)

    private val previousBytes = tfliteBytes(500, seed = 9)

    /** The stored file name of a model with these bytes: `model-<first 16 hex of sha256>.tflite`. */
    private fun storedName(bytes: ByteArray) = "model-" + Sha256.hex(ByteArrayInputStream(bytes)).take(16) + ".tflite"

    private fun importPreviousModel(): ImportedModelMeta {
        val outcome = runImport(previousBytes, name = "previous.tflite")
        assertThat(outcome).isInstanceOf(ImportOutcome.Accepted::class.java)
        probeCalls = 0
        prefs.writes.clear()
        return (outcome as ImportOutcome.Accepted).meta
    }

    private fun assertPreviousSurvives(previous: ImportedModelMeta) {
        val state = store.state()
        assertThat(state.meta).isEqualTo(previous)
        assertThat(state.fileExists).isTrue()
        assertThat(store.modelFileFor(previous).readBytes()).isEqualTo(previousBytes)
        assertThat(File(directory, "import.tflite").exists()).isFalse()
        assertThat(directory.list()!!.toSet()).containsExactly(storedName(previousBytes), "meta.txt")
    }

    private fun assertRejected(
        outcome: ImportOutcome,
        kind: RejectionKind,
    ): ImportOutcome.Rejected {
        assertThat(outcome).isInstanceOf(ImportOutcome.Rejected::class.java)
        val rejected = outcome as ImportOutcome.Rejected
        assertThat(rejected.kind).isEqualTo(kind)
        assertThat(rejected.details).isNotEmpty()
        return rejected
    }

    // ---- accepted ----

    @Test
    fun goodModel_isStoredWithItsMeta() {
        val bytes = tfliteBytes(2_000)

        val outcome = runImport(bytes, name = "yolo26n pose.tflite")

        val expected =
            ImportedModelMeta(
                displayName = "yolo26n pose.tflite",
                sha256 = Sha256.hex(ByteArrayInputStream(bytes)),
                bytes = 2_000L,
                inputSize = 640,
                outputRows = 300,
            )
        assertThat(outcome).isEqualTo(ImportOutcome.Accepted(expected))
        assertThat(store.state()).isEqualTo(StoredModelState(expected, fileExists = true, guard = ImportGuardState.Idle))
        assertThat(store.modelFileFor(expected).readBytes()).isEqualTo(bytes)
        assertThat(directory.list()!!.toSet()).containsExactly(storedName(bytes), "meta.txt")
    }

    @Test
    fun modelLargerThanOneBuffer_isCopiedAndHashedCompletely() {
        val bytes = tfliteBytes(TrackerConfig.MODEL_IO_BUFFER_BYTES * 2 + 123)
        val bigConfig = TrackerConfig(importMinBytes = 100L, importMaxBytes = 500_000L)
        val bigImporter = ModelImporter(store, bigConfig, thread::check) { probeResult }

        val outcome = bigImporter.import({ ByteArrayInputStream(bytes) }, "big.tflite", null)

        val meta = (outcome as ImportOutcome.Accepted).meta
        assertThat(meta.sha256).isEqualTo(Sha256.hex(ByteArrayInputStream(bytes)))
        assertThat(store.modelFileFor(meta).readBytes()).isEqualTo(bytes)
    }

    @Test
    fun unusableDisplayName_becomesTheSanitisedFallback() {
        val outcome = runImport(tfliteBytes(1_000), name = null)

        assertThat((outcome as ImportOutcome.Accepted).meta.displayName).isEqualTo(ImportedModelMeta.sanitiseName(null))
    }

    @Test
    fun secondGoodImport_replacesTheFirst() {
        importPreviousModel()
        val bytes = tfliteBytes(800, seed = 3)

        val outcome = runImport(bytes, name = "second.tflite")

        val meta = (outcome as ImportOutcome.Accepted).meta
        assertThat(store.state().meta).isEqualTo(meta)
        assertThat(store.modelFileFor(meta).readBytes()).isEqualTo(bytes)
    }

    // ---- size ----

    @Test
    fun tooLargeStream_stopsReadingAtTheCap() {
        val previous = importPreviousModel()
        val stream = EndlessStream(chunk = 1_000)

        val outcome = importer.import({ stream }, "huge.tflite", declaredBytes = 1L)

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.TOO_LARGE))
        // The cap is 10_000 bytes: the 11th chunk crosses it and the loop stops; nothing past it is read.
        assertThat(stream.bytesRead).isEqualTo(11_000L)
        assertThat(stream.closed).isTrue()
        assertThat(probeCalls).isEqualTo(0)
        assertPreviousSurvives(previous)
    }

    @Test
    fun declaredSizeAboveTheCap_isRefusedWithoutOpeningTheStream() {
        var opened = false

        val outcome =
            importer.import(
                {
                    opened = true
                    ByteArrayInputStream(ByteArray(0))
                },
                "huge.tflite",
                declaredBytes = 10_001L,
            )

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.TOO_LARGE))
        assertThat(opened).isFalse()
    }

    @Test
    fun exactlyTheCap_isAllowed() {
        val outcome = runImport(tfliteBytes(10_000))

        assertThat(outcome).isInstanceOf(ImportOutcome.Accepted::class.java)
    }

    @Test
    fun tooSmallFile_isRefusedBeforeTheProbe() {
        val previous = importPreviousModel()

        val outcome = runImport(tfliteBytes(99))

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.TOO_SMALL))
        assertThat(probeCalls).isEqualTo(0)
        assertPreviousSurvives(previous)
    }

    @Test
    fun emptyFile_isTooSmall() {
        assertRejected(runImport(ByteArray(0)), RejectionKind.Sniffed(SniffRejection.TOO_SMALL))
    }

    // ---- not a model ----

    @Test
    fun notTflite_isRefusedBeforeTheProbe_andTheGuardIsNeverTouched() {
        val previous = importPreviousModel()

        val outcome = runImport(ByteArray(1_000))

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.NOT_TFLITE))
        assertThat(probeCalls).isEqualTo(0)
        assertThat(prefs.writes).isEmpty()
        assertPreviousSurvives(previous)
    }

    @Test
    fun probeFailed_isUnreadable_withTheProbeMessage() {
        val previous = importPreviousModel()
        probeResult = ProbeResult.Failed("RuntimeException: bad flatbuffer")

        val outcome = runImport(tfliteBytes(1_000))

        val rejected = assertRejected(outcome, RejectionKind.Unreadable)
        assertThat(rejected.details).isEqualTo("RuntimeException: bad flatbuffer")
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        assertPreviousSurvives(previous)
    }

    @Test
    fun probeThatThrows_isUnreadable_andTheGuardIsNotLeftPending() {
        val previous = importPreviousModel()
        probeThrows = IllegalStateException("native layer said no")

        val outcome = runImport(tfliteBytes(1_000))

        val rejected = assertRejected(outcome, RejectionKind.Unreadable)
        assertThat(rejected.details).contains("native layer said no")
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        assertPreviousSurvives(previous)
    }

    // ---- layout rules ----

    @Test
    fun everyRulesRejection_passesThroughWithItsDetails() {
        val goodIn = tensor(1, 3, 640, 640)
        val goodOut = tensor(1, 300, 57)
        val cases =
            mapOf(
                ImportRejection.INPUT_COUNT to (listOf(goodIn, goodIn) to listOf(goodOut)),
                ImportRejection.INPUT_TYPE to (listOf(tensor(1, 3, 640, 640, type = "uint8")) to listOf(goodOut)),
                ImportRejection.INPUT_NOT_NCHW to (listOf(tensor(1, 640, 640)) to listOf(goodOut)),
                ImportRejection.INPUT_NHWC to (listOf(tensor(1, 640, 640, 3)) to listOf(goodOut)),
                ImportRejection.INPUT_NOT_SQUARE to (listOf(tensor(1, 3, 640, 480)) to listOf(goodOut)),
                ImportRejection.INPUT_SIZE_NOT_MULTIPLE to (listOf(tensor(1, 3, 650, 650)) to listOf(goodOut)),
                ImportRejection.INPUT_SIZE_OUT_OF_RANGE to (listOf(tensor(1, 3, 32, 32)) to listOf(goodOut)),
                ImportRejection.OUTPUT_COUNT to (listOf(goodIn) to listOf(goodOut, goodOut)),
                ImportRejection.OUTPUT_TYPE to (listOf(goodIn) to listOf(tensor(1, 300, 57, type = "float16"))),
                ImportRejection.OUTPUT_CLASSIC_HEAD to (listOf(goodIn) to listOf(tensor(1, 56, 8400))),
                ImportRejection.OUTPUT_ROWS to (listOf(goodIn) to listOf(tensor(1, 301, 57))),
                ImportRejection.OUTPUT_SHAPE to (listOf(goodIn) to listOf(tensor(1, 300, 51))),
            )
        assertThat(cases.keys).containsExactlyElementsIn(ImportRejection.entries)
        val previous = importPreviousModel()

        for ((reason, tensors) in cases) {
            probeResult = ProbeResult.Tensors(tensors.first, tensors.second)
            val expected = ImportedModelRules.check(tensors.first, tensors.second, config) as ImportCheck.Rejected

            val outcome = runImport(tfliteBytes(1_000))

            assertThat(outcome).isEqualTo(ImportOutcome.Rejected(RejectionKind.Layout(reason), expected.details))
            assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
            assertPreviousSurvives(previous)
        }
    }

    // ---- early stop on a file that is not a model ----

    @Test
    fun notTfliteStream_stopsReadingAsSoonAsTheHeaderIsKnown() {
        val previous = importPreviousModel()
        val stream = SlowZeroStream(total = 8_000, chunk = 50)

        val outcome = importer.import({ stream }, "photo.jpg", null)

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.NOT_TFLITE))
        // The minimum is 100 bytes: the second read of 50 reaches it with a full header and the loop stops there.
        assertThat(stream.bytesRead).isEqualTo(100L)
        assertThat(stream.closed).isTrue()
        assertThat(probeCalls).isEqualTo(0)
        assertThat(prefs.writes).isEmpty()
        assertPreviousSurvives(previous)
    }

    @Test
    fun smallNotTfliteStream_isStillTooSmall() {
        assertRejected(runImport(ByteArray(50)), RejectionKind.Sniffed(SniffRejection.TOO_SMALL))
    }

    @Test
    fun notTfliteStreamLargerThanTheCap_stillStopsAtTheHeader_notAtTheCap() {
        val stream = SlowZeroStream(total = 1_000_000, chunk = 1_000)

        val outcome = importer.import({ stream }, "huge.bin", null)

        assertRejected(outcome, RejectionKind.Sniffed(SniffRejection.NOT_TFLITE))
        assertThat(stream.bytesRead).isEqualTo(1_000L)
    }

    // ---- I/O failures ----

    @Test
    fun exceptionWhileReading_isFailed_andNothingIsLeftBehind() {
        val previous = importPreviousModel()
        val stream = FailingStream(failAfter = 2_000, error = IOException("disk went away"))

        val outcome = importer.import({ stream }, "x.tflite", null)

        assertThat(outcome).isInstanceOf(ImportOutcome.Failed::class.java)
        assertThat((outcome as ImportOutcome.Failed).kind).isEqualTo(FailureKind.IO)
        assertThat(outcome.message).contains("disk went away")
        assertThat(stream.closed).isTrue()
        assertThat(probeCalls).isEqualTo(0)
        assertPreviousSurvives(previous)
    }

    @Test
    fun errorWhileReading_isFailedToo_notCrashed() {
        val previous = importPreviousModel()
        val stream = FailingStream(failAfter = 500, error = OutOfMemoryError("pretend"))

        val outcome = importer.import({ stream }, "x.tflite", null)

        assertThat(outcome).isInstanceOf(ImportOutcome.Failed::class.java)
        assertPreviousSurvives(previous)
    }

    @Test
    fun sourceThatCannotBeOpened_isFailed() {
        val previous = importPreviousModel()

        val outcome = importer.import({ throw SecurityException("permission revoked") }, "x.tflite", null)

        assertThat((outcome as ImportOutcome.Failed).kind).isEqualTo(FailureKind.IO)
        assertThat(outcome.message).contains("permission revoked")
        assertPreviousSurvives(previous)
    }

    @Test
    fun storeThatCannotMoveTheFile_isFailed_andThePreviousModelStays() {
        val previous = importPreviousModel()
        val bytes = tfliteBytes(1_000)
        failRenameOf = storedName(bytes)

        val outcome = runImport(bytes)

        assertThat(outcome).isEqualTo(ImportOutcome.Failed(FailureKind.IO, "The model could not be stored"))
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        assertPreviousSurvives(previous)
    }

    @Test
    fun modelWithMoreRowsThanTheDecoderTakes_isRejectedAsOutputRows_andThePreviousModelStays() {
        val previous = importPreviousModel()
        val rows = TrackerConfig.IMPORT_MAX_OUTPUT_ROWS + 1
        probeResult = ProbeResult.Tensors(listOf(tensor(1, 3, 640, 640)), listOf(tensor(1, rows, 57)))

        val outcome = runImport(tfliteBytes(1_000))

        assertRejected(outcome, RejectionKind.Layout(ImportRejection.OUTPUT_ROWS))
        assertPreviousSurvives(previous)
    }

    @Test
    fun modelWithExactlyTheMostRows_isAccepted() {
        probeResult =
            ProbeResult.Tensors(listOf(tensor(1, 3, 640, 640)), listOf(tensor(1, TrackerConfig.IMPORT_MAX_OUTPUT_ROWS, 57)))

        val outcome = runImport(tfliteBytes(1_000))

        assertThat((outcome as ImportOutcome.Accepted).meta.outputRows).isEqualTo(TrackerConfig.IMPORT_MAX_OUTPUT_ROWS)
    }

    // ---- temp file name, single flight ----

    @Test
    fun theProbedTempFile_endsInTflite() {
        runImport(tfliteBytes(1_000))

        assertThat(probedFileName).endsWith(".tflite")
    }

    @Test
    fun secondImportDuringTheProbe_isBusy_andTheFirstOnesBytesAreKept() {
        val firstBytes = tfliteBytes(1_000, seed = 5)
        val secondBytes = tfliteBytes(1_200, seed = 6)
        var secondOutcome: ImportOutcome? = null
        var runningDuringProbe = false
        var tempBytesAfterSecond: ByteArray? = null
        var secondOpened = false
        duringProbe = { temp ->
            runningDuringProbe = ModelImporter.isImporting()
            secondOutcome =
                importer.import(
                    {
                        secondOpened = true
                        ByteArrayInputStream(secondBytes)
                    },
                    "second.tflite",
                    secondBytes.size.toLong(),
                )
            tempBytesAfterSecond = temp.readBytes()
        }

        val first = runImport(firstBytes, name = "first.tflite")

        assertThat(runningDuringProbe).isTrue()
        assertThat(secondOutcome).isEqualTo(ImportOutcome.Failed(FailureKind.BUSY))
        assertThat(secondOpened).isFalse()
        assertThat(tempBytesAfterSecond).isEqualTo(firstBytes)
        assertThat(probeCalls).isEqualTo(1)
        val meta = (first as ImportOutcome.Accepted).meta
        assertThat(store.modelFileFor(meta).readBytes()).isEqualTo(firstBytes)
        assertThat(meta.sha256).isEqualTo(Sha256.hex(ByteArrayInputStream(firstBytes)))
    }

    @Test
    fun busyImport_doesNotClearTheFlagOfTheRunningOne() {
        var stillRunningAfterBusy = false
        duringProbe = {
            importer.import({ ByteArrayInputStream(ByteArray(0)) }, "second.tflite", null)
            stillRunningAfterBusy = ModelImporter.isImporting()
        }

        runImport(tfliteBytes(1_000))

        assertThat(stillRunningAfterBusy).isTrue()
        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isClearedAfterAnAccept() {
        runImport(tfliteBytes(1_000))

        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isClearedAfterARefusal() {
        probeResult = ProbeResult.Failed("nope")

        assertThat(runImport(tfliteBytes(1_000))).isInstanceOf(ImportOutcome.Rejected::class.java)

        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isClearedAfterAFailure() {
        prefs.failWrites = true

        assertThat(runImport(tfliteBytes(1_000))).isInstanceOf(ImportOutcome.Failed::class.java)

        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isClearedWhenTheSourceThrows() {
        val outcome = importer.import({ throw SecurityException("revoked") }, "x.tflite", null)

        assertThat(outcome).isInstanceOf(ImportOutcome.Failed::class.java)
        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isClearedWhenTheProbeThrowsAnError() {
        probeThrows = OutOfMemoryError("pretend")

        runImport(tfliteBytes(1_000))

        assertThat(ModelImporter.isImporting()).isFalse()
    }

    @Test
    fun singleFlightFlag_isNotSetByAMainThreadRefusal() {
        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) { importer.import({ ByteArrayInputStream(ByteArray(0)) }, "x.tflite", null) }

        assertThat(ModelImporter.isImporting()).isFalse()
    }

    // ---- the guard ----

    @Test
    fun guardIsPendingProbe_exactlyWhileTheProbeRuns() {
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)

        runImport(tfliteBytes(1_000))

        assertThat(guardDuringProbe).isEqualTo(ImportGuardState.Pending(ImportStage.PROBE))
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        // The last "idle" is the accept's own write under the store lock (R1-7), after the one that ends the probe.
        assertThat(prefs.writes).containsExactly("pending:probe", "idle", "idle").inOrder()
    }

    @Test
    fun guardThatCannotBeStored_isFailed_andTheProbeNeverRuns() {
        prefs.failWrites = true

        val outcome = runImport(tfliteBytes(1_000))

        assertThat(outcome).isEqualTo(ImportOutcome.Failed(FailureKind.GUARD_NOT_STORED))
        assertThat(probeCalls).isEqualTo(0)
        assertThat(
            directory
                .list()
                ?.toList()
                .orEmpty()
                .filter { it.startsWith("model") },
        ).isEmpty()
    }

    @Test
    fun trippedGuard_isKeptByARefusal_andClearedOnlyByAnAccept() {
        prefs.text = "tripped:load"
        probeResult = ProbeResult.Failed("nope")

        val refused = runImport(tfliteBytes(1_000))

        assertRejected(refused, RejectionKind.Unreadable)
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))

        probeResult = ProbeResult.Tensors(listOf(tensor(1, 3, 640, 640)), listOf(tensor(1, 56, 8400)))
        val refusedByRules = runImport(tfliteBytes(1_000))

        assertThat(refusedByRules).isInstanceOf(ImportOutcome.Rejected::class.java)
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.LOAD))

        probeResult = ProbeResult.Tensors(listOf(tensor(1, 3, 640, 640)), listOf(tensor(1, 300, 57)))
        val accepted = runImport(tfliteBytes(1_000))

        assertThat(accepted).isInstanceOf(ImportOutcome.Accepted::class.java)
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Idle)
        assertThat(prefs.text).isEqualTo("idle")
    }

    @Test
    fun trippedGuard_staysWhenTheStoreFailsToAccept() {
        prefs.text = "tripped:probe"
        val bytes = tfliteBytes(1_000)
        failRenameOf = storedName(bytes)

        val outcome = runImport(bytes)

        assertThat(outcome).isInstanceOf(ImportOutcome.Failed::class.java)
        assertThat(store.state().guard).isEqualTo(ImportGuardState.Tripped(ImportStage.PROBE))
    }

    @Test
    fun pendingGuardOfAnotherOperation_isFailed_andTheProbeNeverRuns() {
        store.state()
        prefs.text = "pending:load"

        val outcome = runImport(tfliteBytes(1_000))

        assertThat(outcome).isEqualTo(ImportOutcome.Failed(FailureKind.BUSY))
        assertThat(probeCalls).isEqualTo(0)
        assertThat(prefs.text).isEqualTo("pending:load")
    }

    // ---- streams ----

    /** Hands out [chunk] bytes per read, forever; they start like a TFLite file (identifier at bytes 4 to 7). */
    private class EndlessStream(
        private val chunk: Int,
    ) : InputStream() {
        var bytesRead = 0L
        var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = minOf(chunk, len)
            fillLikeTflite(b, off, n, bytesRead.toInt())
            bytesRead += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun onTheMainThread_importRefusesBeforeReadingAnything() {
        var opened = false
        thread.onMainThread = true

        assertThrows(IllegalStateException::class.java) {
            importer.import(
                {
                    opened = true
                    ByteArrayInputStream(ByteArray(0))
                },
                "model.tflite",
                null,
            )
        }

        assertThat(opened).isFalse()
        assertThat(probeCalls).isEqualTo(0)
        assertThat(prefs.writes).isEmpty()
    }

    /** Hands out [total] zero bytes in reads of at most [chunk] bytes, then ends; it counts what was read. */
    private class SlowZeroStream(
        private val total: Int,
        private val chunk: Int,
    ) : InputStream() {
        var bytesRead = 0L
        var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (bytesRead >= total) return -1
            val n = minOf(chunk, len, total - bytesRead.toInt())
            b.fill(0, off, off + n)
            bytesRead += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    /** Serves [failAfter] bytes, then throws [error]. */
    private class FailingStream(
        private val failAfter: Int,
        private val error: Throwable,
    ) : InputStream() {
        var closed = false
        private var served = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (served >= failAfter) throw error
            val n = minOf(len, failAfter - served)
            // Looks like a TFLite file from the first bytes, so only the read error ends the copy.
            fillLikeTflite(b, off, n, served)
            served += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val IDENTIFIER_OFFSET = 4
        val IDENTIFIER_BYTES = "TFL3".map { it.code.toByte() }

        /** Writes [n] bytes of a stream from position [from] on: zeros, except the TFLite identifier at bytes 4 to 7. */
        fun fillLikeTflite(
            b: ByteArray,
            off: Int,
            n: Int,
            from: Int,
        ) {
            for (i in 0 until n) b[off + i] = IDENTIFIER_BYTES.getOrElse(from + i - IDENTIFIER_OFFSET) { 0 }
        }
    }
}
