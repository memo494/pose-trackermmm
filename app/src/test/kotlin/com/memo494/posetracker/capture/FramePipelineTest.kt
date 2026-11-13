package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
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

/** An image that records its reads and closes in [events]. */
private class FakeFrameImage(
    private val events: MutableList<String>,
    override val widthImg: Int,
    override val heightImg: Int,
    override val timestampNs: Long,
    override val rowStride: Int,
    private val pixelsError: Throwable?,
    private val closeError: Throwable?,
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
) : PoseDetector {
    override val inputSize: Int = 320
    override val accelerator: Accelerator = Accelerator.GPU
    override var lastInferenceMs: Float = 7.5f
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
                emptyList(),
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

    private fun pipeline(detector: PoseDetector? = this.detector): FramePipeline {
        val pipeline =
            FramePipeline(
                detector,
                TrackerConfig(),
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
    ): FakeFrameImage = FakeFrameImage(events, widthImg, heightImg, timestampNs, rowStride, pixelsError, closeError)

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
}
