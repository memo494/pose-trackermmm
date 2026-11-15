package com.memo494.posetracker.detector

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteRtPoseDetectorTest {
    /** Scriptable stand-in for the model: returns [detections] and counts the calls it receives. */
    private class FakeEngine(
        override val accelerator: Accelerator = Accelerator.GPU,
        override val inputWidth: Int = INPUT_SIZE,
        override val inputHeight: Int = INPUT_SIZE,
        var detections: List<PoseDetection> = emptyList(),
        override var lastInferenceMs: Float = 0f,
        override var lastMaxRawScore: Float = PoseDetector.NOT_MEASURED.toFloat(),
        override var lastRawRowsAboveThreshold: Int = PoseDetector.NOT_MEASURED,
        override var lastNanRawRows: Int = PoseDetector.NOT_MEASURED,
    ) : PoseEngine {
        var detectCalls = 0
        var closeCalls = 0

        override fun detect(frame: CaptureFrame): List<PoseDetection> {
            detectCalls++
            return detections
        }

        override fun close() {
            closeCalls++
        }
    }

    /** Records which accelerators were requested and answers each request with [onCreate]. */
    private class RecordingFactory(
        private val onCreate: (Accelerator) -> PoseEngine,
    ) : PoseEngineFactory {
        val requested = mutableListOf<Accelerator>()

        override fun create(accelerator: Accelerator): PoseEngine {
            requested.add(accelerator)
            return onCreate(accelerator)
        }
    }

    private fun detectionWithScore(score: Float) =
        PoseDetection(
            boxLeftImg = 0f,
            boxTopImg = 0f,
            boxRightImg = 10f,
            boxBottomImg = 10f,
            score = score,
            keypointsXyImg = FloatArray(0),
            keypointScores = FloatArray(0),
        )

    private fun captureFrame(
        widthImg: Int = FRAME_WIDTH,
        heightImg: Int = FRAME_HEIGHT,
        timestampNs: Long = TIMESTAMP_NS,
        timestampSource: TimestampSource = TimestampSource.IMAGE,
    ): CaptureFrame {
        val frame = CaptureFrame()
        val rgba = ByteArray(widthImg * heightImg * TrackerConfig.RGBA_BYTES_PER_PIXEL)
        val accepted = frame.set(rgba, widthImg, heightImg, timestampNs, timestampSource)
        assertThat(accepted).isTrue()
        return frame
    }

    private fun detectorOver(
        engine: FakeEngine,
        scoreThreshold: Float = THRESHOLD,
    ): LiteRtPoseDetector = LiteRtPoseDetector.create(RecordingFactory { engine }, scoreThreshold, INPUT_SIZE, INPUT_SIZE)

    /** Runs [block] on a new thread and returns how it ended. */
    private fun <T> onOtherThread(block: () -> T): Result<T> {
        var outcome: Result<T>? = null
        val thread = Thread { outcome = runCatching(block) }
        thread.start()
        thread.join()
        return checkNotNull(outcome)
    }

    @Test
    fun gpuFactoryThrowingFallsBackToCpuAndReportsCpu() {
        val cpuEngine = FakeEngine(accelerator = Accelerator.CPU)
        val factory =
            RecordingFactory { accelerator ->
                check(accelerator != Accelerator.GPU) { "no gpu" }
                cpuEngine
            }

        val detector = LiteRtPoseDetector.create(factory, THRESHOLD, INPUT_SIZE, INPUT_SIZE)

        assertThat(factory.requested).containsExactly(Accelerator.GPU, Accelerator.CPU).inOrder()
        assertThat(detector.accelerator).isEqualTo(Accelerator.CPU)
    }

    @Test
    fun workingGpuFactoryIsUsedAndCpuIsNeverRequested() {
        val factory = RecordingFactory { FakeEngine(accelerator = Accelerator.GPU) }

        val detector = LiteRtPoseDetector.create(factory, THRESHOLD, INPUT_SIZE, INPUT_SIZE)

        assertThat(factory.requested).containsExactly(Accelerator.GPU)
        assertThat(detector.accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    fun acceleratorIsWhatTheEngineReportsNotWhatWasAsked() {
        val factory = RecordingFactory { FakeEngine(accelerator = Accelerator.CPU) }

        val detector = LiteRtPoseDetector.create(factory, THRESHOLD, INPUT_SIZE, INPUT_SIZE)

        assertThat(factory.requested).containsExactly(Accelerator.GPU)
        assertThat(detector.accelerator).isEqualTo(Accelerator.CPU)
    }

    @Test
    fun whenBothAttemptsThrowTheCpuExceptionPropagates() {
        val factory =
            RecordingFactory { accelerator ->
                throw IllegalStateException("failed on $accelerator")
            }

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                LiteRtPoseDetector.create(factory, THRESHOLD, INPUT_SIZE, INPUT_SIZE)
            }

        assertThat(thrown).hasMessageThat().isEqualTo("failed on CPU")
        assertThat(factory.requested).containsExactly(Accelerator.GPU, Accelerator.CPU).inOrder()
    }

    @Test
    fun wrongEngineInputSizeClosesTheEngineAndFails() {
        val engine = FakeEngine(inputWidth = WRONG_INPUT_SIZE, inputHeight = WRONG_INPUT_SIZE)

        assertThrows(IllegalStateException::class.java) { detectorOver(engine) }

        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun importedSizeIsReportedWhenTheEngineAgrees() {
        val engine = FakeEngine(inputWidth = IMPORTED_INPUT_SIZE, inputHeight = IMPORTED_INPUT_SIZE)

        val detector =
            LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, IMPORTED_INPUT_SIZE, IMPORTED_INPUT_SIZE)

        assertThat(detector.inputWidth).isEqualTo(IMPORTED_INPUT_SIZE)
        assertThat(detector.inputHeight).isEqualTo(IMPORTED_INPUT_SIZE)
        assertThat(engine.closeCalls).isEqualTo(0)
    }

    @Test
    fun engineSmallerThanTheImportedSizeIsClosedAndRefused() {
        val engine = FakeEngine(inputWidth = INPUT_SIZE, inputHeight = INPUT_SIZE)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, IMPORTED_INPUT_SIZE, IMPORTED_INPUT_SIZE)
            }

        assertThat(thrown).hasMessageThat().contains("expected 640x640 but the model has 416x416")
        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun rectangularEngineIsAcceptedWhenBothSidesMatch() {
        val engine = FakeEngine(inputWidth = RECT_WIDTH, inputHeight = RECT_HEIGHT)

        val detector = LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, RECT_WIDTH, RECT_HEIGHT)

        assertThat(detector.inputWidth).isEqualTo(RECT_WIDTH)
        assertThat(detector.inputHeight).isEqualTo(RECT_HEIGHT)
        assertThat(engine.closeCalls).isEqualTo(0)
    }

    @Test
    fun rectangularEngineWithAnotherHeightIsClosedAndRefused() {
        val engine = FakeEngine(inputWidth = RECT_WIDTH, inputHeight = RECT_HEIGHT + 32)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, RECT_WIDTH, RECT_HEIGHT)
            }

        assertThat(thrown).hasMessageThat().contains("expected 832x384 but the model has 832x416")
        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun rectangularEngineWithAnotherWidthIsClosedAndRefused() {
        val engine = FakeEngine(inputWidth = RECT_WIDTH - 32, inputHeight = RECT_HEIGHT)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, RECT_WIDTH, RECT_HEIGHT)
            }

        assertThat(thrown).hasMessageThat().contains("expected 832x384 but the model has 800x384")
        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun squareEngineIsRefusedWhenARectangularInputWasExpected() {
        val engine = FakeEngine(inputWidth = IMPORTED_INPUT_SIZE, inputHeight = IMPORTED_INPUT_SIZE)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                LiteRtPoseDetector.create(RecordingFactory { engine }, THRESHOLD, RECT_WIDTH, RECT_HEIGHT)
            }

        assertThat(thrown).hasMessageThat().contains("expected 832x384 but the model has 640x640")
        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun inputSizeAndInferenceTimeComeFromTheEngine() {
        val engine = FakeEngine(lastInferenceMs = 0f)
        val detector = detectorOver(engine)
        assertThat(detector.inputWidth).isEqualTo(INPUT_SIZE)
        assertThat(detector.inputHeight).isEqualTo(INPUT_SIZE)
        assertThat(detector.lastInferenceMs).isEqualTo(0f)

        engine.lastInferenceMs = INFERENCE_MS
        detector.detect(captureFrame())

        assertThat(detector.lastInferenceMs).isEqualTo(INFERENCE_MS)
    }

    @Test
    fun detectionsBelowTheThresholdAreDroppedAndEqualOnesKept() {
        val engine =
            FakeEngine(
                detections = listOf(detectionWithScore(0.3f), detectionWithScore(0.5f), detectionWithScore(0.7f)),
            )
        val detector = detectorOver(engine, scoreThreshold = 0.5f)

        val result = detector.detect(captureFrame())

        assertThat(result.detections.map { it.score }).containsExactly(0.5f, 0.7f).inOrder()
    }

    @Test
    fun nothingDroppedReusesTheEnginesList() {
        val fromEngine = listOf(detectionWithScore(0.6f), detectionWithScore(0.9f))
        val detector = detectorOver(FakeEngine(detections = fromEngine))

        val result = detector.detect(captureFrame())

        assertThat(result.detections).isSameInstanceAs(fromEngine)
    }

    @Test
    fun atMostMaxDetectionsPerFrameAreKeptInEngineOrder() {
        val total = TrackerConfig.MAX_DETECTIONS_PER_FRAME + 5
        val fromEngine = List(total) { index -> detectionWithScore(0.5f + index * 0.001f) }
        val detector = detectorOver(FakeEngine(detections = fromEngine))

        val result = detector.detect(captureFrame())

        assertThat(result.detections).hasSize(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
        val expected = fromEngine.take(TrackerConfig.MAX_DETECTIONS_PER_FRAME)
        assertThat(result.detections).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun theCapCountsOnlyDetectionsThatPassedTheThreshold() {
        val low = List(TrackerConfig.MAX_DETECTIONS_PER_FRAME) { detectionWithScore(0.1f) }
        val high = List(TrackerConfig.MAX_DETECTIONS_PER_FRAME) { detectionWithScore(0.9f) }
        val detector = detectorOver(FakeEngine(detections = low + high))

        val result = detector.detect(captureFrame())

        assertThat(result.detections).containsExactlyElementsIn(high).inOrder()
    }

    @Test
    fun outputFrameCopiesTimestampSourceAndSizeFromTheCaptureFrame() {
        val detector = detectorOver(FakeEngine())
        val frame =
            captureFrame(
                widthImg = 30,
                heightImg = 20,
                timestampNs = 123_456_789L,
                timestampSource = TimestampSource.ARRIVAL,
            )

        val result = detector.detect(frame)

        assertThat(result.timestampNs).isEqualTo(123_456_789L)
        assertThat(result.timestampSource).isEqualTo(TimestampSource.ARRIVAL)
        assertThat(result.widthImg).isEqualTo(30)
        assertThat(result.heightImg).isEqualTo(20)
        assertThat(result.detections).isEmpty()
    }

    @Test
    fun emptyFrameIsRejectedWithoutCallingTheEngine() {
        val engine = FakeEngine()
        val detector = detectorOver(engine)

        assertThrows(IllegalArgumentException::class.java) { detector.detect(CaptureFrame()) }

        assertThat(engine.detectCalls).isEqualTo(0)
    }

    @Test
    fun closingTwiceClosesTheEngineOnce() {
        val engine = FakeEngine()
        val detector = detectorOver(engine)

        detector.close()
        detector.close()

        assertThat(engine.closeCalls).isEqualTo(1)
    }

    @Test
    fun detectAfterCloseThrows() {
        val engine = FakeEngine()
        val detector = detectorOver(engine)
        detector.close()

        assertThrows(IllegalStateException::class.java) { detector.detect(captureFrame()) }

        assertThat(engine.detectCalls).isEqualTo(0)
    }

    @Test
    fun detectFromAnotherThreadThrows() {
        val engine = FakeEngine()
        val detector = detectorOver(engine)

        val outcome = onOtherThread { detector.detect(captureFrame()) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(engine.detectCalls).isEqualTo(0)
    }

    @Test
    fun closeFromAnotherThreadThrowsAndLeavesTheEngineOpen() {
        val engine = FakeEngine()
        val detector = detectorOver(engine)

        val outcome = onOtherThread { detector.close() }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(engine.closeCalls).isEqualTo(0)
        assertThat(detector.detect(captureFrame()).detections).isEmpty()
    }

    @Test
    fun rawScoreDiagnosticsComeFromTheEngine() {
        val engine = FakeEngine(lastMaxRawScore = 0.89f, lastRawRowsAboveThreshold = 3, lastNanRawRows = 0)
        val detector = detectorOver(engine)

        assertThat(detector.lastMaxRawScore).isEqualTo(0.89f)
        assertThat(detector.lastRawRowsAboveThreshold).isEqualTo(3)
        assertThat(detector.lastNanRawRows).isEqualTo(0)
    }

    @Test
    fun aDetectorThatDoesNotMeasureRawScoresSaysNotMeasured() {
        val fake = FakePoseDetector(emptyList(), INPUT_SIZE, INPUT_SIZE, Accelerator.GPU)

        assertThat(fake.lastMaxRawScore).isLessThan(0f)
        assertThat(fake.lastRawRowsAboveThreshold).isEqualTo(PoseDetector.NOT_MEASURED)
        assertThat(fake.lastNanRawRows).isEqualTo(PoseDetector.NOT_MEASURED)
    }

    private companion object {
        const val INPUT_SIZE = 416
        const val WRONG_INPUT_SIZE = 320
        const val IMPORTED_INPUT_SIZE = 640
        const val RECT_WIDTH = 832
        const val RECT_HEIGHT = 384
        const val THRESHOLD = 0.15f
        const val INFERENCE_MS = 12.5f
        const val FRAME_WIDTH = 8
        const val FRAME_HEIGHT = 6
        const val TIMESTAMP_NS = 1_000L
    }
}
