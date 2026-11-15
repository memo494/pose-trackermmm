package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.PoseDetector
import com.memo494.posetracker.overlay.OverlayState
import org.junit.Test
import java.nio.ByteBuffer

private const val WIDTH = 4
private const val HEIGHT = 2
private const val BYTES_PER_PIXEL = TrackerConfig.RGBA_BYTES_PER_PIXEL
private const val NOW_NS = 10_000_000_000L
private const val MS_NS = 1_000_000L
private const val PIXEL_PATTERN_PERIOD = 251
private const val DISPLAY_WIDTH_PX = 8
private const val DISPLAY_HEIGHT_PX = 4
private const val HEAD_KEYPOINTS = TrackerConfig.HEAD_KEYPOINT_COUNT

/** An image that records its reads and closes in [events]. */
private class FakeFrameImage(
    private val events: MutableList<String>,
    override val widthImg: Int,
    override val heightImg: Int,
    override val timestampNs: Long,
    override val rowStride: Int,
    private val pixelsError: Throwable?,
    private val closeError: Throwable?,
    private val onPixelsRead: (() -> Unit)? = null,
) : FrameImage {
    override val pixelStride: Int = BYTES_PER_PIXEL
    val buffer: ByteBuffer = ByteBuffer.allocate(rowStride * heightImg)
    var closeCount = 0

    init {
        for (i in 0 until buffer.capacity()) buffer.put(i, (i % PIXEL_PATTERN_PERIOD).toByte())
    }

    override val pixels: ByteBuffer
        get() {
            events.add("read")
            onPixelsRead?.invoke()
            if (pixelsError != null) throw pixelsError
            return buffer
        }

    override fun close() {
        events.add("close")
        closeCount++
        if (closeError != null) throw closeError
    }
}

/** A detector that records what it saw and fails on demand. */
private class ScriptedDetector(
    private val events: MutableList<String>,
    override val inputWidth: Int = 320,
    override val inputHeight: Int = 320,
) : PoseDetector {
    override val accelerator: Accelerator = Accelerator.GPU
    override var lastInferenceMs: Float = 7.5f
    override var lastMaxRawScore: Float = PoseDetector.NOT_MEASURED.toFloat()
    override var lastRawRowsAboveThreshold: Int = PoseDetector.NOT_MEASURED
    override var lastNanRawRows: Int = PoseDetector.NOT_MEASURED
    override var lastPreMs: Float = PoseDetector.NOT_MEASURED.toFloat()
    override var lastPostMs: Float = PoseDetector.NOT_MEASURED.toFloat()
    var onDetect: (() -> Unit)? = null
    var people: List<PoseDetection> = emptyList()
    var failure: Throwable? = null
    var calls = 0
    var seenRgba: ByteArray? = null
    var seenRgbaCopy: ByteArray? = null
    var seenTimestampNs = 0L
    var seenSource: TimestampSource? = null
    var lastResult: DetectionFrame? = null

    override fun detect(frame: CaptureFrame): DetectionFrame {
        events.add("detect")
        calls++
        onDetect?.invoke()
        seenRgba = frame.rgba
        seenRgbaCopy = frame.rgba.copyOf()
        seenTimestampNs = frame.timestampNs
        seenSource = frame.timestampSource
        val error = failure
        if (error != null) throw error
        val result =
            DetectionFrame(
                frame.timestampNs,
                frame.timestampSource,
                frame.widthImg,
                frame.heightImg,
                people,
            )
        lastResult = result
        return result
    }

    override fun close() = Unit
}

private class SettableClock(
    var currentNs: Long,
) : NanoClock {
    override fun nowNs(): Long = currentNs
}

class FramePipelineTest {
    private val events = mutableListOf<String>()
    private val states = mutableListOf<OverlayState>()
    private val logs = mutableListOf<String>()
    private val loggedErrors = mutableListOf<Throwable?>()
    private val clock = SettableClock(NOW_NS)
    private val detector = ScriptedDetector(events)
    private var publisherError: Throwable? = null
    private var loggerError: Throwable? = null

    private fun pipeline(
        detector: PoseDetector? = this.detector,
        modelImported: Boolean = false,
        importedModelNotUsed: Boolean = false,
        config: TrackerConfig = TrackerConfig(),
    ): FramePipeline {
        val pipeline =
            FramePipeline(
                detector,
                config,
                clock,
                { state ->
                    val error = publisherError
                    if (error != null) throw error
                    states.add(state)
                },
                { message, error ->
                    val failure = loggerError
                    if (failure != null) throw failure
                    logs.add(message)
                    loggedErrors.add(error)
                },
                modelImported,
                importedModelNotUsed,
            )
        pipeline.setExpectedSize(CaptureSize(WIDTH, HEIGHT))
        return pipeline
    }

    private fun image(
        widthImg: Int = WIDTH,
        heightImg: Int = HEIGHT,
        timestampNs: Long = NOW_NS - 5 * MS_NS,
        rowStride: Int = widthImg * BYTES_PER_PIXEL,
        pixelsError: Throwable? = null,
        closeError: Throwable? = null,
        onPixelsRead: (() -> Unit)? = null,
    ): FakeFrameImage = FakeFrameImage(events, widthImg, heightImg, timestampNs, rowStride, pixelsError, closeError, onPixelsRead)

    private fun acquireNs(): Long = NOW_NS - MS_NS

    @Test
    fun frame_copiesThenClosesTheImageThenDetects() {
        val pipeline = pipeline()

        pipeline.onFrame(image(), acquireNs())

        assertThat(events).containsExactly("read", "close", "detect").inOrder()
    }

    @Test
    fun frame_handsTheDetectorTheTightPixelsWithoutRowPadding() {
        val pipeline = pipeline()
        val rowStride = WIDTH * BYTES_PER_PIXEL + 8
        val image = image(rowStride = rowStride)
        val rowBytes = WIDTH * BYTES_PER_PIXEL
        val expected = ByteArray(rowBytes * HEIGHT)
        for (row in 0 until HEIGHT) {
            for (col in 0 until rowBytes) expected[row * rowBytes + col] = image.buffer.get(row * rowStride + col)
        }

        pipeline.onFrame(image, acquireNs())

        assertThat(detector.seenRgbaCopy).isEqualTo(expected)
    }

    @Test
    fun frame_publishesTheDetectionAndTheStats() {
        val pipeline = pipeline()

        pipeline.onFrame(image(), acquireNs())

        val state = states.single()
        assertThat(state.frame).isSameInstanceAs(detector.lastResult)
        assertThat(state.accelerator).isEqualTo(Accelerator.GPU)
        assertThat(state.captureFps).isWithin(0.001f).of(1f)
        assertThat(state.inferenceMs).isEqualTo(7.5f)
        assertThat(state.captureWidthImg).isEqualTo(WIDTH)
        assertThat(state.captureHeightImg).isEqualTo(HEIGHT)
        assertThat(state.timestampSource).isEqualTo(TimestampSource.IMAGE)
        assertThat(state.dotsVisible).isTrue()
        assertThat(state.warning).isNull()
        assertThat(pipeline.frames).isEqualTo(1L)
    }

    @Test
    fun frame_ofAnotherSize_isClosedCountedAndNotDetected() {
        val pipeline = pipeline()
        val image = image(widthImg = WIDTH + 2)

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(detector.calls).isEqualTo(0)
        assertThat(pipeline.droppedSizeMismatch).isEqualTo(1L)
        assertThat(pipeline.frames).isEqualTo(0L)
        assertThat(events).containsExactly("close")
        assertThat(states).isEmpty()
    }

    @Test
    fun frame_withUnknownExpectedSize_isDropped() {
        val pipeline = pipeline()
        pipeline.setExpectedSize(null)
        val image = image()

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(detector.calls).isEqualTo(0)
        assertThat(pipeline.droppedSizeMismatch).isEqualTo(1L)
    }

    @Test
    fun frame_withARowStrideSmallerThanARow_isClosedAndCountedAsCopyFailure() {
        val pipeline = pipeline()
        val image = image(rowStride = WIDTH * BYTES_PER_PIXEL - 1)

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(detector.calls).isEqualTo(0)
        assertThat(pipeline.copyFailures).isEqualTo(1L)
        assertThat(pipeline.frames).isEqualTo(0L)
        assertThat(states).isEmpty()
    }

    @Test
    fun frame_withAValidImageStamp_usesItAndSaysImage() {
        val pipeline = pipeline()
        val stamp = NOW_NS - 20 * MS_NS

        pipeline.onFrame(image(timestampNs = stamp), acquireNs())

        assertThat(detector.seenTimestampNs).isEqualTo(stamp)
        assertThat(detector.seenSource).isEqualTo(TimestampSource.IMAGE)
        assertThat(pipeline.arrivalFallbacks).isEqualTo(0L)
    }

    @Test
    fun frame_withAStaleImageStamp_usesTheArrivalTime() {
        val pipeline = pipeline()
        val stale = NOW_NS - TrackerConfig().timestampGuardNs - 1L

        pipeline.onFrame(image(timestampNs = stale), acquireNs())

        assertThat(detector.seenTimestampNs).isEqualTo(acquireNs())
        assertThat(detector.seenSource).isEqualTo(TimestampSource.ARRIVAL)
        assertThat(states.single().timestampSource).isEqualTo(TimestampSource.ARRIVAL)
        assertThat(pipeline.arrivalFallbacks).isEqualTo(1L)
    }

    @Test
    fun frame_withAFutureImageStamp_usesTheArrivalTime() {
        val pipeline = pipeline()

        pipeline.onFrame(image(timestampNs = NOW_NS + 1L), acquireNs())

        assertThat(detector.seenTimestampNs).isEqualTo(acquireNs())
        assertThat(detector.seenSource).isEqualTo(TimestampSource.ARRIVAL)
        assertThat(pipeline.arrivalFallbacks).isEqualTo(1L)
    }

    @Test
    fun detectorThrowingOutOfMemoryError_isContainedAndTheNextFrameWorks() {
        val pipeline = pipeline()
        detector.failure = OutOfMemoryError("test")
        val failing = image()

        pipeline.onFrame(failing, acquireNs())

        assertThat(failing.closeCount).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(1L)
        assertThat(pipeline.frames).isEqualTo(0L)
        assertThat(logs).hasSize(1)
        assertThat(loggedErrors.single()).isInstanceOf(OutOfMemoryError::class.java)

        detector.failure = null
        val next = image()
        pipeline.onFrame(next, acquireNs())

        assertThat(next.closeCount).isEqualTo(1)
        assertThat(pipeline.frames).isEqualTo(1L)
        assertThat(states).hasSize(1)
    }

    @Test
    fun detectorThrowingARuntimeException_isContainedAndTheNextFrameWorks() {
        val pipeline = pipeline()
        detector.failure = IllegalStateException("test")
        val failing = image()

        pipeline.onFrame(failing, acquireNs())

        assertThat(failing.closeCount).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(1L)
        assertThat(logs).hasSize(1)

        detector.failure = null
        pipeline.onFrame(image(), acquireNs())

        assertThat(pipeline.frames).isEqualTo(1L)
    }

    @Test
    fun repeatedFailures_areLoggedForTheFirstOfTheStreakAndThenEveryNth() {
        val pipeline = pipeline()
        detector.failure = IllegalStateException("test")

        repeat(TrackerConfig.FAILURE_LOG_INTERVAL + 1) { pipeline.onFrame(image(), acquireNs()) }

        assertThat(pipeline.failures).isEqualTo(TrackerConfig.FAILURE_LOG_INTERVAL + 1L)
        assertThat(logs).hasSize(2)
    }

    @Test
    fun aSuccessfulFrame_startsANewFailureStreakThatIsLoggedAgain() {
        val pipeline = pipeline()
        detector.failure = IllegalStateException("test")
        pipeline.onFrame(image(), acquireNs())
        pipeline.onFrame(image(), acquireNs())
        detector.failure = null
        pipeline.onFrame(image(), acquireNs())
        detector.failure = IllegalStateException("again")

        pipeline.onFrame(image(), acquireNs())

        assertThat(pipeline.failures).isEqualTo(3L)
        assertThat(logs).hasSize(2)
    }

    @Test
    fun aFailureBeforeTheClose_stillClosesTheImageOnce() {
        val pipeline = pipeline()
        val image = image(pixelsError = IllegalStateException("plane gone"))

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(1L)
        assertThat(detector.calls).isEqualTo(0)
    }

    @Test
    fun anImageThatFailsToClose_doesNotStopTheFrameOrEscape() {
        val pipeline = pipeline()
        val image = image(closeError = IllegalStateException("already closed"))

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(detector.calls).isEqualTo(1)
        assertThat(pipeline.frames).isEqualTo(1L)
    }

    @Test
    fun aThrowingPublisher_isContainedImageClosedOnceAndTheNextFrameWorks() {
        val pipeline = pipeline()
        publisherError = OutOfMemoryError("test")
        val failing = image()

        pipeline.onFrame(failing, acquireNs())

        assertThat(failing.closeCount).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(1L)

        publisherError = null
        pipeline.onFrame(image(), acquireNs())

        assertThat(states).hasSize(1)
        assertThat(pipeline.frames).isEqualTo(1L)
    }

    @Test
    fun aThrowingLogger_doesNotEscape() {
        val pipeline = pipeline()
        detector.failure = IllegalStateException("test")
        loggerError = IllegalStateException("logger broke")
        val image = image()

        pipeline.onFrame(image, acquireNs())

        assertThat(image.closeCount).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(1L)
    }

    @Test
    fun withoutADetector_theFrameIsClosedNotCopiedAndTheWarningIsPublished() {
        val pipeline = pipeline(detector = null)
        val image = image()

        pipeline.onFrame(image, acquireNs())

        assertThat(events).containsExactly("close")
        assertThat(image.closeCount).isEqualTo(1)
        val state = states.last()
        assertThat(state.frame).isNull()
        assertThat(state.accelerator).isNull()
        assertThat(state.warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
        assertThat(state.captureWidthImg).isEqualTo(WIDTH)
        assertThat(state.captureHeightImg).isEqualTo(HEIGHT)
        assertThat(state.captureFps).isWithin(0.001f).of(1f)
        assertThat(pipeline.frames).isEqualTo(0L)
    }

    @Test
    fun withoutADetector_aSizeMismatchIsStillDropped() {
        val pipeline = pipeline(detector = null)

        pipeline.onFrame(image(widthImg = WIDTH + 2), acquireNs())

        assertThat(pipeline.droppedSizeMismatch).isEqualTo(1L)
    }

    @Test
    fun whenNotDisplaySized_theDetectorIsNotRunAndTheWarningIsPublished() {
        val pipeline = pipeline()
        pipeline.setDisplaySized(false)
        states.clear()
        val image = image()

        pipeline.onFrame(image, acquireNs())

        assertThat(events).containsExactly("close")
        assertThat(detector.calls).isEqualTo(0)
        assertThat(image.closeCount).isEqualTo(1)
        val state = states.single()
        assertThat(state.frame).isNull()
        assertThat(state.warning).isEqualTo(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)
        assertThat(state.accelerator).isEqualTo(Accelerator.GPU)
    }

    @Test
    fun withoutADetectorAndNotDisplaySized_theMissingModelWins() {
        val pipeline = pipeline(detector = null)
        pipeline.setDisplaySized(false)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun noDetectorFrames_countForTheFps() {
        val pipeline = pipeline(detector = null)

        repeat(3) { pipeline.onFrame(image(), acquireNs()) }

        assertThat(states.last().captureFps).isWithin(0.001f).of(3f)
    }

    @Test
    fun setDisplaySized_republishesTheWarningAtOnce() {
        val pipeline = pipeline()

        pipeline.setDisplaySized(false)

        assertThat(states.single().warning).isEqualTo(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)

        pipeline.setDisplaySized(true)

        assertThat(states.last().warning).isNull()
    }

    @Test
    fun setDisplaySized_dropsTheOldDetectionsSoStaleOnesAreNeverShownAgain() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        assertThat(states.last().frame).isNotNull()

        pipeline.setDisplaySized(false)
        pipeline.setDisplaySized(true)

        assertThat(states.last().frame).isNull()
    }

    @Test
    fun setExpectedSize_toAnotherSize_republishesWithoutTheOldDetections() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        assertThat(states.last().frame).isNotNull()
        val before = states.size

        pipeline.setExpectedSize(CaptureSize(HEIGHT, WIDTH))

        assertThat(states).hasSize(before + 1)
        assertThat(states.last().frame).isNull()
    }

    @Test
    fun setExpectedSize_toTheSameSize_publishesNothing() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        val before = states.size

        pipeline.setExpectedSize(CaptureSize(WIDTH, HEIGHT))

        assertThat(states).hasSize(before)
        assertThat(states.last().frame).isNotNull()
    }

    @Test
    fun setDisplaySized_withoutADetector_keepsTheMissingModelWarning() {
        val pipeline = pipeline(detector = null)

        pipeline.setDisplaySized(true)

        assertThat(states.single().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun setContentVisible_republishesWithDotsVisibleOff() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        val before = states.last()

        pipeline.setContentVisible(false)

        val after = states.last()
        assertThat(states).hasSize(2)
        assertThat(after.dotsVisible).isFalse()
        assertThat(after.frame).isSameInstanceAs(before.frame)
        assertThat(after.detectionsShown).isFalse()
    }

    @Test
    fun theContentVisibleFlag_goesIntoTheNextFrame() {
        val pipeline = pipeline()
        pipeline.setContentVisible(false)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().dotsVisible).isFalse()

        pipeline.setContentVisible(true)

        assertThat(states.last().dotsVisible).isTrue()
    }

    @Test
    fun hudTick_afterAnIdleGap_publishesZeroFpsAndKeepsTheRest() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        val before = states.last()
        assertThat(before.captureFps).isGreaterThan(0f)
        clock.currentNs = NOW_NS + 5_000L * MS_NS

        pipeline.onHudTick()

        val after = states.last()
        assertThat(states).hasSize(2)
        assertThat(after.captureFps).isEqualTo(0f)
        assertThat(after.frame).isSameInstanceAs(before.frame)
        assertThat(after.inferenceMs).isEqualTo(before.inferenceMs)
        assertThat(after.captureWidthImg).isEqualTo(before.captureWidthImg)
    }

    @Test
    fun hudTick_withAThrowingPublisher_doesNotEscape() {
        val pipeline = pipeline()
        publisherError = IllegalStateException("test")

        pipeline.onHudTick()
        pipeline.setContentVisible(false)
        pipeline.setDisplaySized(false)

        assertThat(states).isEmpty()
    }

    @Test
    fun theBufferIsReusedForEqualSizesAndReplacedForANewSize() {
        val pipeline = pipeline()
        pipeline.onFrame(image(), acquireNs())
        val first = detector.seenRgba
        pipeline.onFrame(image(), acquireNs())

        assertThat(detector.seenRgba).isSameInstanceAs(first)

        pipeline.setExpectedSize(CaptureSize(WIDTH + 2, HEIGHT))
        pipeline.onFrame(image(widthImg = WIDTH + 2), acquireNs())

        assertThat(detector.seenRgba).isNotSameInstanceAs(first)
        assertThat(detector.seenRgba!!.size).isEqualTo((WIDTH + 2) * HEIGHT * BYTES_PER_PIXEL)
    }

    // Phone bug "nothing drawn in landscape": the overlay maps with the display size of the capture plan,
    // so every published state must carry the display size that belongs to its capture size.

    @Test
    fun publishedFrames_carryTheDisplaySizeOfTheCapturePlan() {
        val pipeline = pipeline()
        pipeline.setDisplaySizePx(DISPLAY_WIDTH_PX, DISPLAY_HEIGHT_PX)

        pipeline.onFrame(image(), acquireNs())

        val state = states.last()
        assertThat(state.displayWidthPx).isEqualTo(DISPLAY_WIDTH_PX)
        assertThat(state.displayHeightPx).isEqualTo(DISPLAY_HEIGHT_PX)
    }

    @Test
    fun afterARotation_framesCarryTheNewDisplaySizeAndTheOldDetectionsAreDropped() {
        val pipeline = pipeline()
        pipeline.setDisplaySizePx(DISPLAY_WIDTH_PX, DISPLAY_HEIGHT_PX)
        detector.people = listOf(person(headXImg = 1f, headYImg = 1f))
        pipeline.onFrame(image(), acquireNs())

        pipeline.setDisplaySizePx(DISPLAY_HEIGHT_PX, DISPLAY_WIDTH_PX)
        val republished = states.last()
        pipeline.setExpectedSize(CaptureSize(HEIGHT, WIDTH))
        pipeline.onFrame(image(widthImg = HEIGHT, heightImg = WIDTH), acquireNs())

        assertThat(republished.frame).isNull()
        val rotated = states.last()
        assertThat(rotated.captureWidthImg).isEqualTo(HEIGHT)
        assertThat(rotated.displayWidthPx).isEqualTo(DISPLAY_HEIGHT_PX)
        assertThat(rotated.displayHeightPx).isEqualTo(DISPLAY_WIDTH_PX)
    }

    @Test
    fun theFirstDisplaySize_publishesNothing() {
        val pipeline = pipeline()

        pipeline.setDisplaySizePx(DISPLAY_WIDTH_PX, DISPLAY_HEIGHT_PX)

        assertThat(states).isEmpty()
    }

    @Test
    fun withoutADetector_theStateStillCarriesTheDisplaySize() {
        val pipeline = pipeline(detector = null)
        pipeline.setDisplaySizePx(DISPLAY_WIDTH_PX, DISPLAY_HEIGHT_PX)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().displayWidthPx).isEqualTo(DISPLAY_WIDTH_PX)
    }

    // Diagnostics counters (phone bug: the zip had no detection count or score).

    @Test
    fun detectionCounters_beforeAnyFrame_sayNotMeasured() {
        val pipeline = pipeline()

        assertThat(pipeline.lastDetections).isEqualTo(OverlayState.NOT_MEASURED_COUNT)
        assertThat(pipeline.framesWithDetections).isEqualTo(0L)
        assertThat(pipeline.peakMaxRawScore).isEqualTo(OverlayState.NOT_MEASURED_SCORE)
        assertThat(pipeline.lastHeadXImg.isNaN()).isTrue()
    }

    @Test
    fun aFrameWithPeople_updatesTheCountersAndTheHeadPoint() {
        val pipeline = pipeline()
        detector.people = listOf(person(headXImg = 3f, headYImg = 1f), person(headXImg = 1f, headYImg = 0f))
        detector.lastMaxRawScore = 0.89f
        detector.lastRawRowsAboveThreshold = 3
        detector.lastNanRawRows = 0

        pipeline.onFrame(image(), acquireNs())

        val state = states.last()
        assertThat(state.maxRawScore).isEqualTo(0.89f)
        assertThat(state.rawRowsAboveThreshold).isEqualTo(3)
        assertThat(pipeline.lastDetections).isEqualTo(2)
        assertThat(pipeline.framesWithDetections).isEqualTo(1L)
        assertThat(pipeline.peakMaxRawScore).isEqualTo(0.89f)
        assertThat(pipeline.lastNanRawRows).isEqualTo(0)
        assertThat(pipeline.lastHeadXImg).isEqualTo(3f)
        assertThat(pipeline.lastHeadYImg).isEqualTo(1f)
    }

    @Test
    fun anEmptyFrame_countsZeroPeopleKeepsThePeakAndTheLastHeadPoint() {
        val pipeline = pipeline()
        detector.people = listOf(person(headXImg = 3f, headYImg = 1f))
        detector.lastMaxRawScore = 0.89f
        pipeline.onFrame(image(), acquireNs())
        detector.people = emptyList()
        detector.lastMaxRawScore = 0.01f
        detector.lastNanRawRows = 300

        pipeline.onFrame(image(), acquireNs())

        assertThat(pipeline.lastDetections).isEqualTo(0)
        assertThat(pipeline.framesWithDetections).isEqualTo(1L)
        assertThat(pipeline.peakMaxRawScore).isEqualTo(0.89f)
        assertThat(states.last().maxRawScore).isEqualTo(0.01f)
        assertThat(pipeline.lastNanRawRows).isEqualTo(300)
        assertThat(pipeline.lastHeadXImg).isEqualTo(3f)
    }

    /** A person whose five head keypoints all sit at ([headXImg], [headYImg]) with full confidence. */
    private fun person(
        headXImg: Float,
        headYImg: Float,
    ): PoseDetection {
        val xy = FloatArray(HEAD_KEYPOINTS * 2)
        for (k in 0 until HEAD_KEYPOINTS) {
            xy[k * 2] = headXImg
            xy[k * 2 + 1] = headYImg
        }
        return PoseDetection(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 0.9f, xy, FloatArray(HEAD_KEYPOINTS) { 1f })
    }

    @Test
    fun publishedStates_carryTheModelInputSizeAndTheBuiltInFlag() {
        val pipeline = pipeline()

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().modelInputWidth).isEqualTo(detector.inputWidth)
        assertThat(states.last().modelInputHeight).isEqualTo(detector.inputHeight)
        assertThat(states.last().modelImported).isFalse()
        assertThat(states.last().warning).isNull()
    }

    @Test
    fun publishedStates_carryTheImportedFlag() {
        val pipeline = pipeline(modelImported = true)

        pipeline.onFrame(image(), acquireNs())
        pipeline.setContentVisible(false)

        assertThat(states).hasSize(2)
        assertThat(states.all { it.modelImported }).isTrue()
        assertThat(states.all { it.modelInputWidth == detector.inputWidth && it.modelInputHeight == detector.inputHeight }).isTrue()
    }

    @Test
    fun republishBeforeTheFirstFrame_alreadyCarriesTheModel() {
        val pipeline = pipeline(modelImported = true)

        pipeline.setContentVisible(true)

        assertThat(states.single().modelInputWidth).isEqualTo(detector.inputWidth)
        assertThat(states.single().modelInputHeight).isEqualTo(detector.inputHeight)
        assertThat(states.single().modelImported).isTrue()
    }

    @Test
    fun publishedStates_carryWidthAndHeightOfARectangularModelApart() {
        val rectangular = ScriptedDetector(events, inputWidth = 832, inputHeight = 384)
        val pipeline = pipeline(detector = rectangular, modelImported = true)

        pipeline.onFrame(image(), acquireNs())
        pipeline.setContentVisible(false)

        assertThat(states).hasSize(2)
        assertThat(states.all { it.modelInputWidth == 832 && it.modelInputHeight == 384 }).isTrue()
    }

    @Test
    fun withoutADetector_theModelInputSizeIsZero() {
        val pipeline = pipeline(detector = null)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().modelInputWidth).isEqualTo(0)
        assertThat(states.last().modelInputHeight).isEqualTo(0)
        assertThat(states.last().modelImported).isFalse()
    }

    @Test
    fun importedModelNotUsed_isTheNoteOfADetectedFrameAndKeepsTheDotsShown() {
        val pipeline = pipeline(importedModelNotUsed = true)

        pipeline.onFrame(image(), acquireNs())

        val state = states.last()
        assertThat(state.frame).isNotNull()
        assertThat(state.warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)
        assertThat(state.detectionsShown).isTrue()
        assertThat(pipeline.frames).isEqualTo(1L)
    }

    @Test
    fun importedModelNotUsed_losesToTheMissingDetector() {
        val pipeline = pipeline(detector = null, importedModelNotUsed = true)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun importedModelNotUsed_losesToTheCaptureSizeProblemAndComesBack() {
        val pipeline = pipeline(importedModelNotUsed = true)

        pipeline.setDisplaySized(false)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)

        pipeline.setDisplaySized(true)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)
    }

    @Test
    fun withoutAFallback_thereIsNoWarning() {
        val pipeline = pipeline()

        pipeline.setContentVisible(true)
        pipeline.onFrame(image(), acquireNs())

        assertThat(states.all { it.warning == null }).isTrue()
    }

    // M1a T5: timing statistics. The clock is moved by hooks: 2 ms while the pixels are read (the copy), 10 ms inside
    // detect. Per frame: acquired 1 ms before NOW, image stamp 5 ms before NOW, published at NOW + 12 ms.

    private fun timedFrame(pipeline: FramePipeline) {
        clock.currentNs = NOW_NS
        pipeline.onFrame(image(onPixelsRead = { clock.currentNs += 2 * MS_NS }), acquireNs())
    }

    private fun scriptTheTimings() {
        detector.onDetect = { clock.currentNs += 10 * MS_NS }
        detector.lastPreMs = 1.5f
        detector.lastPostMs = 0.5f
    }

    @Test
    fun timing_recordsOneSamplePerProcessedFrame() {
        scriptTheTimings()
        val pipeline = pipeline()

        repeat(3) { timedFrame(pipeline) }

        val timing = pipeline.timingSummary()
        assertThat(timing.samples).isEqualTo(3)
        assertThat(timing.copyMs.p50).isWithin(0.001f).of(2f)
        assertThat(timing.copyMs.p95).isWithin(0.001f).of(2f)
        assertThat(timing.preMs.p50).isWithin(0.001f).of(1.5f)
        assertThat(timing.inferMs.p50).isWithin(0.001f).of(7.5f)
        assertThat(timing.postMs.p50).isWithin(0.001f).of(0.5f)
        assertThat(timing.frameMs.p50).isWithin(0.001f).of(13f)
        assertThat(timing.frameMs.p95).isWithin(0.001f).of(13f)
        assertThat(timing.resultAgeMs.p50).isWithin(0.001f).of(17f)
        assertThat(timing.resultAgeMs.p95).isWithin(0.001f).of(17f)
    }

    @Test
    fun timing_beforeAnyFrame_isEmpty() {
        assertThat(pipeline().timingSummary()).isEqualTo(TimingSummary.EMPTY)
    }

    @Test
    fun timing_framesThatAreDrainedOrDropped_addNothing() {
        scriptTheTimings()
        val pipeline = pipeline()
        pipeline.onFrame(image(widthImg = WIDTH + 2), acquireNs())
        pipeline.onFrame(image(rowStride = WIDTH * BYTES_PER_PIXEL - 1), acquireNs())
        detector.failure = IllegalStateException("test")
        pipeline.onFrame(image(), acquireNs())
        detector.failure = null
        pipeline.setDisplaySized(false)
        pipeline.onFrame(image(), acquireNs())

        assertThat(pipeline.timingSummary()).isEqualTo(TimingSummary.EMPTY)

        pipeline.setDisplaySized(true)
        timedFrame(pipeline)

        assertThat(pipeline.timingSummary().samples).isEqualTo(1)
    }

    @Test
    fun timing_aDetectorlessPipeline_hasNoSamples() {
        val pipeline = pipeline(detector = null)

        pipeline.onFrame(image(), acquireNs())

        assertThat(pipeline.timingSummary()).isEqualTo(TimingSummary.EMPTY)
    }

    @Test
    fun timing_withAnArrivalTimestamp_hasNoResultAge() {
        scriptTheTimings()
        val pipeline = pipeline()
        clock.currentNs = NOW_NS

        pipeline.onFrame(image(timestampNs = 0L, onPixelsRead = { clock.currentNs += 2 * MS_NS }), acquireNs())

        val timing = pipeline.timingSummary()
        assertThat(pipeline.arrivalFallbacks).isEqualTo(1L)
        assertThat(timing.samples).isEqualTo(1)
        assertThat(timing.resultAgeMs).isEqualTo(Percentiles.NONE)
        assertThat(timing.frameMs.p50).isWithin(0.001f).of(13f)
    }

    @Test
    fun timing_aDetectorThatDoesNotMeasurePreAndPost_leavesThemUnknown() {
        detector.onDetect = { clock.currentNs += 10 * MS_NS }
        val pipeline = pipeline()

        timedFrame(pipeline)

        val timing = pipeline.timingSummary()
        assertThat(timing.preMs).isEqualTo(Percentiles.NONE)
        assertThat(timing.postMs).isEqualTo(Percentiles.NONE)
        assertThat(timing.inferMs.p50).isWithin(0.001f).of(7.5f)
        assertThat(timing.samples).isEqualTo(1)
    }

    @Test
    fun timing_windowLengthComesFromTheConfig() {
        scriptTheTimings()
        val pipeline = pipeline(config = TrackerConfig(timingWindowFrames = TrackerConfig.MIN_TIMING_WINDOW_FRAMES))

        repeat(TrackerConfig.MIN_TIMING_WINDOW_FRAMES + 4) { timedFrame(pipeline) }

        assertThat(pipeline.timingSummary().samples).isEqualTo(TrackerConfig.MIN_TIMING_WINDOW_FRAMES)
    }

    @Test
    fun timing_differentFrames_giveMedianAndP95FromTheWindow() {
        scriptTheTimings()
        val pipeline = pipeline()
        // detect takes 10 ms for 19 frames and 50 ms for one: the frame time p50 is 13 ms, p95 is still 13 ms
        // (rank 19 of 20) and the maximum would be 53 ms.
        repeat(19) { timedFrame(pipeline) }
        detector.onDetect = { clock.currentNs += 50 * MS_NS }
        timedFrame(pipeline)

        val timing = pipeline.timingSummary()
        assertThat(timing.samples).isEqualTo(20)
        assertThat(timing.frameMs.p50).isWithin(0.001f).of(13f)
        assertThat(timing.frameMs.p95).isWithin(0.001f).of(13f)
    }

    @Test
    fun timing_aSlowFrameShowsInTheP95WhenItIsMoreThanFivePercent() {
        scriptTheTimings()
        val pipeline = pipeline()
        repeat(18) { timedFrame(pipeline) }
        detector.onDetect = { clock.currentNs += 50 * MS_NS }
        repeat(2) { timedFrame(pipeline) }

        // 20 samples, 2 of them slow (53 ms): rank ceil(0.95 * 20) = 19 is a slow one
        assertThat(pipeline.timingSummary().frameMs.p95).isWithin(0.001f).of(53f)
        assertThat(pipeline.timingSummary().frameMs.p50).isWithin(0.001f).of(13f)
    }
}
