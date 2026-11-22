package com.memo494.posetracker.capture

import com.google.common.truth.Truth.assertThat
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.stats.LeadSummary
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.detector.Accelerator
import com.memo494.posetracker.detector.PoseDetector
import com.memo494.posetracker.overlay.OverlayState
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

private const val WIDTH = 4
private const val HEIGHT = 2
private const val BYTES_PER_PIXEL = TrackerConfig.RGBA_BYTES_PER_PIXEL
private const val NOW_NS = 10_000_000_000L
private const val MS_NS = 1_000_000L
private const val PIXEL_PATTERN_PERIOD = 251
private const val DISPLAY_WIDTH_PX = 8
private const val DISPLAY_HEIGHT_PX = 4
private const val HEAD_KEYPOINTS = TrackerConfig.HEAD_KEYPOINT_COUNT
private const val TRACK_WIDTH = 320
private const val TRACK_HEIGHT = 240
private const val FRAME_STEP_MS = 50L
private const val LEAD_FRAMES = 30
private const val FAST_STEP_IMG = 20f
private const val LEAD_EXPECTED_IMG = 15f
private const val LEAD_TOLERANCE_IMG = 6f
private const val LEAD_EXPECTED_MS = 37f
private const val LEAD_MS_TOLERANCE = 4f
private const val SLOW_DETECT_MS = 300L
private const val NEW_PERSON_SHIFT_X_IMG = 150f
private const val HEAD_TOLERANCE_IMG = 3f
private const val SHAKE_IMG = 6f
private const val SHAKY_FRAMES = 40
private const val SAME_HEAD_TOLERANCE_IMG = 0.01f
private const val SMOOTHING_MAX_TRAVEL_RATIO = 0.5f
private const val IDLE_MS = 400L
private const val SMALL_IDLE_STEP_MS = 10L
private const val BIG_IDLE_STEP_MS = 150L
private const val REMOVAL_IDLE_MS = 2000L
private const val LOST_MS_TOLERANCE = 1f
private const val RED_SHIRT = 0xC81E1E
private const val BLUE_SHIRT = 0x1E1EC8
private const val OPAQUE_BYTE: Byte = -1
private const val BYTE_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val DEPARTURE_STEP_MS = 300L
private const val MEMORY_STEP_MS = 5000L
private const val RIGHT_GROUP_MIN_X_IMG = 200f
private const val STEPS_BEYOND_THE_MEMORY = 13
private const val STEPS_INSIDE_THE_MEMORY = 11
private const val HUD_TICK_AFTER_LONG_IDLE_MS = 200_000L

/** Three frames to be shown and ten judged frames: a new track's judge window ends with the twelfth frame. */
private const val WINDOW_END_FRAMES = 12
private const val NO_TORSO_CAP_FRAMES = 40
private const val LONG_ENTRY_STEP_X_IMG = 2
private const val LONG_ENTRY_CLIPPED_FRAMES = 13
private const val LONG_ENTRY_FRAMES = 18
private const val SIGNATURE_RED_FRAMES = 50
private const val OCCLUDED_FRAMES = 10
private const val OCCLUDER_SHIFT_X_IMG = 20f
private const val SIGNATURE_FRAMES = 5
private const val ENTRY_START_SHIFT_X_IMG = -140
private const val ENTRY_STEP_X_IMG = 5
private const val ENTRY_CLIPPED_FRAMES = 5
private const val ENTRY_FRAMES = 10
private const val EDGE_SHIFT_X_IMG = -130f
private const val EDGE_WALK_STEP_X_IMG = 10f
private const val EDGE_WALK_STEPS = 12
private const val EDGE_CLIPPED_SHIFT_X_IMG = -120f
private const val EDGE_STAY_FRAMES = 11
private const val FRAMES_WHILE_HIDDEN = 2
private const val FRAMES_UNTIL_REMOVED = 22
private const val MORE_FRAMES_HIDDEN = 8

/** Inside the default early radius of 0.6 heights (120 px for the 200 px walker); [NEW_PERSON_SHIFT_X_IMG] is outside it. */
private const val EARLY_NEAR_SHIFT_X_IMG = 90f
private const val EARLY_JUST_OUTSIDE_SHIFT_X_IMG = 130f
private const val FAR_GROUP_MIN_X_IMG = 250f

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
    paint: ((ByteBuffer) -> Unit)? = null,
) : FrameImage {
    override val pixelStride: Int = BYTES_PER_PIXEL
    val buffer: ByteBuffer = ByteBuffer.allocate(rowStride * heightImg)
    var closeCount = 0

    init {
        for (i in 0 until buffer.capacity()) buffer.put(i, (i % PIXEL_PATTERN_PERIOD).toByte())
        paint?.invoke(buffer)
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
        builtInModelFailed: Boolean = false,
        config: TrackerConfig = TrackerConfig(),
        modelWrongShape: Boolean = false,
        boxOnly: Boolean = false,
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
                builtInModelFailed,
                modelWrongShape,
                boxOnly = boxOnly,
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
        paint: ((ByteBuffer) -> Unit)? = null,
    ): FakeFrameImage = FakeFrameImage(events, widthImg, heightImg, timestampNs, rowStride, pixelsError, closeError, onPixelsRead, paint)

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

    // ---- the tracker (M3b T2) ----

    /** A pipeline expecting [TRACK_WIDTH] x [TRACK_HEIGHT] frames; the states of the set-up are forgotten. */
    private fun trackingPipeline(
        config: TrackerConfig = TrackerConfig(),
        boxOnly: Boolean = false,
    ): FramePipeline {
        val pipeline = pipeline(config = config, boxOnly = boxOnly)
        pipeline.setExpectedSize(CaptureSize(TRACK_WIDTH, TRACK_HEIGHT))
        states.clear()
        return pipeline
    }

    private val headXsImg = floatArrayOf(130f, 124f, 136f, 118f, 142f)
    private val headYsImg = floatArrayOf(40f, 36f, 36f, 40f, 40f)

    /** A person 200 px high whose five head keypoints (confidence 0.9) scatter around a known mean, [shiftXImg] to the right. */
    private fun walker(
        boxTopImg: Float = 20f,
        shiftXImg: Float = 0f,
    ): PoseDetection {
        val xy = FloatArray(HEAD_KEYPOINTS * 2)
        for (k in 0 until HEAD_KEYPOINTS) {
            xy[k * 2] = headXsImg[k] + shiftXImg
            xy[k * 2 + 1] = headYsImg[k]
        }
        return PoseDetection(100f + shiftXImg, boxTopImg, 160f + shiftXImg, 220f, 0.9f, xy, FloatArray(HEAD_KEYPOINTS) { 0.9f })
    }

    /** Sets the clock to [atMs] after the start and feeds one frame of the given size that arrives then. */
    private fun frameAt(
        pipeline: FramePipeline,
        atMs: Long,
        widthImg: Int = TRACK_WIDTH,
        heightImg: Int = TRACK_HEIGHT,
    ) {
        clock.currentNs = NOW_NS + atMs * MS_NS
        pipeline.onFrame(image(widthImg = widthImg, heightImg = heightImg, timestampNs = clock.currentNs - 2 * MS_NS), clock.currentNs)
    }

    private fun tickAt(
        pipeline: FramePipeline,
        atMs: Long,
    ) {
        clock.currentNs = NOW_NS + atMs * MS_NS
        pipeline.onHudTick()
    }

    /** Feeds [count] frames [FRAME_STEP_MS] apart from [startMs]; returns the time of the last one. */
    private fun framesFrom(
        pipeline: FramePipeline,
        startMs: Long,
        count: Int,
        widthImg: Int = TRACK_WIDTH,
        heightImg: Int = TRACK_HEIGHT,
    ): Long {
        var atMs = startMs
        for (i in 0 until count) {
            frameAt(pipeline, atMs, widthImg, heightImg)
            if (i < count - 1) atMs += FRAME_STEP_MS
        }
        return atMs
    }

    // ---- the display lead (F4) ----

    /** Feeds [count] frames of a person who moves [stepXImg] to the right every frame; returns the pipeline's last state. */
    private fun movingFrames(
        pipeline: FramePipeline,
        count: Int,
        stepXImg: Float,
    ): OverlayState {
        for (i in 0 until count) {
            detector.people = listOf(walker(shiftXImg = stepXImg * i))
            frameAt(pipeline, i * FRAME_STEP_MS)
        }
        return states.last()
    }

    @Test
    fun lead_aFastWalker_isDrawnAheadOfTheSameWalkerWithoutTheLead_andIsCountedInTheSummary() {
        val ledPipeline = trackingPipeline()
        val led = movingFrames(ledPipeline, LEAD_FRAMES, FAST_STEP_IMG)
        states.clear()
        val plainPipeline = trackingPipeline(TrackerConfig(displayLeadEnabled = false))
        val plain = movingFrames(plainPipeline, LEAD_FRAMES, FAST_STEP_IMG)

        // The result age is 2 ms here: L = 2 + 10 + 25 = 37 ms at 400 px/s is about 15 px.
        assertThat(led.tracks.headXImg(0) - plain.tracks.headXImg(0)).isWithin(LEAD_TOLERANCE_IMG).of(LEAD_EXPECTED_IMG)
        assertThat(led.tracks.leadMs(0)).isGreaterThan(0f)
        val lead = ledPipeline.timingSummary().lead
        assertThat(lead.appliedFraction).isGreaterThan(0f)
        assertThat(lead.leadMsP50).isWithin(LEAD_MS_TOLERANCE).of(LEAD_EXPECTED_MS)
        assertThat(lead.speedHpsP99).isGreaterThan(1f)
        assertThat(plainPipeline.timingSummary().lead.appliedFraction).isEqualTo(0f)
    }

    @Test
    fun lead_aStillPerson_hasNoLeadAndAlmostNoSpeed() {
        val pipeline = trackingPipeline()
        val state = movingFrames(pipeline, LEAD_FRAMES, 0f)

        val lead = pipeline.timingSummary().lead
        assertThat(state.tracks.leadMs(0)).isEqualTo(0f)
        assertThat(lead.appliedFraction).isEqualTo(0f)
        assertThat(lead.leadMsP50.isNaN()).isTrue()
        assertThat(lead.speedHpsP99).isLessThan(0.5f)
    }

    @Test
    fun lead_beforeAnyTrackedFrame_isUnknown() {
        assertThat(trackingPipeline().timingSummary().lead).isEqualTo(LeadSummary.NONE)
    }

    @Test
    fun threeFramesOfOnePerson_giveATrackOnlyInTheThirdState() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())

        frameAt(pipeline, 0)
        assertThat(states.last().tracks.count).isEqualTo(0)
        frameAt(pipeline, FRAME_STEP_MS)
        assertThat(states.last().tracks.count).isEqualTo(0)
        frameAt(pipeline, 2 * FRAME_STEP_MS)

        val tracks = states.last().tracks
        assertThat(tracks.count).isEqualTo(1)
        assertThat(tracks.headXImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(headXsImg.average().toFloat())
        assertThat(tracks.headYImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(headYsImg.average().toFloat())
        assertThat(pipeline.failures).isEqualTo(0L)
    }

    @Test
    fun trackCounters_areZeroBeforeTheFirstFrame() {
        val pipeline = trackingPipeline()

        assertThat(pipeline.trackBirths).isEqualTo(0)
        assertThat(pipeline.trackConfirmations).isEqualTo(0)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(0)
        assertThat(pipeline.trackRecoveries).isEqualTo(0)
        assertThat(pipeline.trackRemovals).isEqualTo(0)
        assertThat(pipeline.tracksMostAtOnce).isEqualTo(0)
    }

    @Test
    fun threeFramesOfOnePerson_countOneBirthAndOneConfirmation() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())

        framesFrom(pipeline, 0, 3)

        assertThat(pipeline.trackBirths).isEqualTo(1)
        assertThat(pipeline.trackConfirmations).isEqualTo(1)
        assertThat(pipeline.tracksMostAtOnce).isEqualTo(1)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(0)
        assertThat(pipeline.trackRecoveries).isEqualTo(0)
        assertThat(pipeline.trackRemovals).isEqualTo(0)
        assertThat(states.last().tracks.count).isEqualTo(1)
    }

    @Test
    fun trackCounters_surviveTheResetOfANewSize() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        framesFrom(pipeline, 0, 3)

        pipeline.setExpectedSize(CaptureSize(TRACK_WIDTH + 2, TRACK_HEIGHT))

        assertThat(pipeline.trackBirths).isEqualTo(1)
        assertThat(pipeline.trackConfirmations).isEqualTo(1)
    }

    @Test
    fun aNewExpectedSize_emptiesTheTracksAndTheNextTrackGetsAHigherId() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        val lastMs = framesFrom(pipeline, 0, 3)
        val oldId = states.last().tracks.id(0)

        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))

        assertThat(states.last().frame).isNull()
        assertThat(states.last().tracks.count).isEqualTo(0)
        framesFrom(pipeline, lastMs + FRAME_STEP_MS, 3, widthImg = TRACK_HEIGHT, heightImg = TRACK_WIDTH)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.id(0)).isGreaterThan(oldId)
    }

    @Test
    fun aNewDisplaySize_emptiesTheTracksAndTheNextTrackGetsAHigherId() {
        val pipeline = trackingPipeline()
        pipeline.setDisplaySizePx(DISPLAY_WIDTH_PX, DISPLAY_HEIGHT_PX)
        detector.people = listOf(walker())
        val lastMs = framesFrom(pipeline, 0, 3)
        val oldId = states.last().tracks.id(0)

        pipeline.setDisplaySizePx(DISPLAY_HEIGHT_PX, DISPLAY_WIDTH_PX)

        assertThat(states.last().frame).isNull()
        assertThat(states.last().tracks.count).isEqualTo(0)
        framesFrom(pipeline, lastMs + FRAME_STEP_MS, 3)
        assertThat(states.last().tracks.id(0)).isGreaterThan(oldId)
    }

    @Test
    fun aChangeOfDisplaySized_emptiesTheTracksAndTheNextTrackGetsAHigherId() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        val lastMs = framesFrom(pipeline, 0, 3)
        val oldId = states.last().tracks.id(0)

        pipeline.setDisplaySized(false)

        assertThat(states.last().frame).isNull()
        assertThat(states.last().tracks.count).isEqualTo(0)
        pipeline.setDisplaySized(true)
        framesFrom(pipeline, lastMs + FRAME_STEP_MS, 3)
        assertThat(states.last().tracks.id(0)).isGreaterThan(oldId)
    }

    @Test
    fun aFrameWhileNotDisplaySized_publishesNoTracksAndLeavesTheTrackerAlone() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        pipeline.setDisplaySized(false)
        val calls = detector.calls

        val lastMs = framesFrom(pipeline, 0, 4)
        tickAt(pipeline, lastMs + 2 * TrackerConfig().trackIdlePromoteMs)

        assertThat(detector.calls).isEqualTo(calls)
        assertThat(states.last().tracks.count).isEqualTo(0)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED)
    }

    @Test
    fun hudTick_before400msOfIdleTime_promotesNothing() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        frameAt(pipeline, 0)

        tickAt(pipeline, 399)

        assertThat(states.last().tracks.count).isEqualTo(0)
    }

    @Test
    fun hudTick_at400msOfIdleTime_publishesTheTrackAndASecondTickChangesNothing() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        frameAt(pipeline, 0)

        tickAt(pipeline, 400)

        val promoted = states.last().tracks
        assertThat(promoted.count).isEqualTo(1)
        assertThat(promoted.headXImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(headXsImg.average().toFloat())
        val statesBefore = states.size
        tickAt(pipeline, 1000)
        assertThat(states).hasSize(statesBefore + 1)
        assertThat(states.last().tracks).isSameInstanceAs(promoted)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(0L)
    }

    @Test
    fun hudTick_countsTheIdleTimeFromTheEndOfProcessing_notFromTheArrival() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        detector.onDetect = { clock.currentNs += SLOW_DETECT_MS * MS_NS }
        clock.currentNs = NOW_NS
        pipeline.onFrame(image(widthImg = TRACK_WIDTH, heightImg = TRACK_HEIGHT), NOW_NS)
        val endMs = SLOW_DETECT_MS

        tickAt(pipeline, endMs + 399)
        assertThat(states.last().tracks.count).isEqualTo(0)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(0)

        tickAt(pipeline, endMs + 400)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(1)
    }

    @Test
    fun hudTick_afterOnePromotionAndOneRemoval_publishesTheNewTrackAlthoughTheCountStaysOne() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        val confirmedAtMs = framesFrom(pipeline, 0, 3)
        assertThat(states.last().tracks.count).isEqualTo(1)
        val leftHeadXImg = states.last().tracks.headXImg(0)
        detector.people = listOf(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG))
        val newestMs = confirmedAtMs + FRAME_STEP_MS
        frameAt(pipeline, newestMs)
        assertThat(states.last().tracks.headXImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(leftHeadXImg)

        tickAt(pipeline, newestMs + TrackerConfig().trackMaxLostMs.toLong() + 1)

        val tracks = states.last().tracks
        assertThat(tracks.count).isEqualTo(1)
        assertThat(tracks.headXImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(headXsImg.average().toFloat() + NEW_PERSON_SHIFT_X_IMG)
        assertThat(pipeline.trackRemovals).isEqualTo(1)
        assertThat(pipeline.trackIdlePromotions).isEqualTo(1)
    }

    @Test
    fun aTrackedFrame_publishesTracksWithTheSizeOfTheFrame() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())

        framesFrom(pipeline, 0, 3)

        val state = states.last()
        assertThat(state.tracks.count).isEqualTo(1)
        assertThat(state.tracks.widthImg).isEqualTo(TRACK_WIDTH)
        assertThat(state.tracks.heightImg).isEqualTo(TRACK_HEIGHT)
        assertThat(state.tracksMatchCapture).isTrue()
    }

    @Test
    fun aPromotionByIdleTime_publishesTracksWithTheSizeOfTheFrame() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        frameAt(pipeline, 0)

        tickAt(pipeline, TrackerConfig().trackIdlePromoteMs)

        val state = states.last()
        assertThat(state.tracks.count).isEqualTo(1)
        assertThat(state.tracks.widthImg).isEqualTo(TRACK_WIDTH)
        assertThat(state.tracks.heightImg).isEqualTo(TRACK_HEIGHT)
        assertThat(state.tracksMatchCapture).isTrue()
    }

    @Test
    fun hudTick_beforeAnyFrame_publishesNoTrackAndDoesNotFail() {
        val pipeline = trackingPipeline()

        tickAt(pipeline, 5_000)

        assertThat(states.single().tracks.count).isEqualTo(0)
        assertThat(logs).isEmpty()
    }

    @Test
    fun hudTick_afterAReset_publishesNoTrackAndDoesNotFail() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        frameAt(pipeline, 0)
        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))

        tickAt(pipeline, 1_000)

        assertThat(states.last().tracks.count).isEqualTo(0)
        assertThat(logs).isEmpty()
    }

    @Test
    fun framesTheTrackerNeverSaw_doNotDisturbIt() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        framesFrom(pipeline, 0, 3)
        val id = states.last().tracks.id(0)

        frameAt(pipeline, 3 * FRAME_STEP_MS, widthImg = WIDTH, heightImg = HEIGHT)
        detector.failure = IllegalStateException("test")
        frameAt(pipeline, 4 * FRAME_STEP_MS)
        detector.failure = null
        frameAt(pipeline, 5 * FRAME_STEP_MS)

        assertThat(pipeline.droppedSizeMismatch).isEqualTo(1L)
        assertThat(pipeline.failures).isEqualTo(1L)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.id(0)).isEqualTo(id)
    }

    @Test
    fun aDetectionWithANanBox_doesNotCrashAndGivesNoTrack() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker(boxTopImg = Float.NaN))

        framesFrom(pipeline, 0, 4)

        assertThat(pipeline.failures).isEqualTo(0L)
        assertThat(pipeline.frames).isEqualTo(4L)
        assertThat(states.last().tracks.count).isEqualTo(0)
    }

    @Test
    fun setContentVisible_false_keepsTheTracksInTheStateAndHidesThemWithTheFlag() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        framesFrom(pipeline, 0, 3)
        val tracks = states.last().tracks

        pipeline.setContentVisible(false)

        assertThat(states.last().tracks).isSameInstanceAs(tracks)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().detectionsShown).isFalse()
    }

    // ---- the display smoother (M4a T3) ----

    /** A person like [walker] (box 60 x 200 px) whose five head keypoints sit [offsetXImg] / [offsetYImg] off their mean. */
    private fun shaky(
        offsetXImg: Float,
        offsetYImg: Float,
    ): PoseDetection {
        val xy = FloatArray(HEAD_KEYPOINTS * 2)
        for (k in 0 until HEAD_KEYPOINTS) {
            xy[k * 2] = headXsImg.average().toFloat() + offsetXImg
            xy[k * 2 + 1] = headYsImg.average().toFloat() + offsetYImg
        }
        return PoseDetection(100f, 20f, 160f, 220f, 0.9f, xy, FloatArray(HEAD_KEYPOINTS) { 0.9f })
    }

    /** Feeds one shaky frame at [atMs]; [index] decides the sign of the shake (even: plus). */
    private fun shakyFrameAt(
        pipeline: FramePipeline,
        atMs: Long,
        index: Int,
    ) {
        val sign = if (index % 2 == 0) 1f else -1f
        detector.people = listOf(shaky(sign * SHAKE_IMG, sign * SHAKE_IMG))
        frameAt(pipeline, atMs)
    }

    /** Head x published after each of [SHAKY_FRAMES] shaky frames of one still person; NaN while no track is shown. */
    private fun publishedHeadsOfAShakyPerson(config: TrackerConfig): FloatArray {
        val pipeline = trackingPipeline(config)
        val heads = FloatArray(SHAKY_FRAMES) { Float.NaN }
        for (i in 0 until SHAKY_FRAMES) {
            shakyFrameAt(pipeline, i * FRAME_STEP_MS, i)
            val tracks = states.last().tracks
            if (tracks.count == 1) heads[i] = tracks.headXImg(0)
        }
        return heads
    }

    /** Sum of the frame-to-frame moves of the shown heads in [heads], from the first shown one on. */
    private fun travelOf(heads: FloatArray): Float {
        var travel = 0f
        for (i in 1 until heads.size) {
            if (!heads[i].isNaN() && !heads[i - 1].isNaN()) travel += abs(heads[i] - heads[i - 1])
        }
        return travel
    }

    @Test
    fun aJitteringStillPerson_hasAPublishedHeadThatMovesLessThanTheTrackersOwn() {
        val smoothed = publishedHeadsOfAShakyPerson(TrackerConfig())
        val trackers = publishedHeadsOfAShakyPerson(TrackerConfig(smoothEnabled = false))

        assertThat(travelOf(trackers)).isGreaterThan(0f)
        assertThat(travelOf(smoothed)).isLessThan(SMOOTHING_MAX_TRAVEL_RATIO * travelOf(trackers))
        assertThat(smoothed[2]).isEqualTo(trackers[2])
    }

    @Test
    fun afterAReset_theFirstFrameOfTheNewTrackPassesThroughUnsmoothed() {
        val trackers = publishedHeadsOfAShakyPerson(TrackerConfig(smoothEnabled = false))
        val pipeline = trackingPipeline()
        for (i in 0 until 10) shakyFrameAt(pipeline, i * FRAME_STEP_MS, i)
        assertThat(states.last().tracks.count).isEqualTo(1)

        assertThat(pipeline.smootherActiveCount).isEqualTo(1)
        pipeline.setDisplaySized(false)
        assertThat(pipeline.smootherActiveCount).isEqualTo(0)
        pipeline.setDisplaySized(true)
        assertThat(states.last().tracks.count).isEqualTo(0)
        for (i in 0 until 3) shakyFrameAt(pipeline, (20 + i) * FRAME_STEP_MS, i)

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.headXImg(0)).isWithin(SAME_HEAD_TOLERANCE_IMG).of(trackers[2])
    }

    @Test
    fun hudTick_withinTheIdleLimit_doesNotMoveASmoothedPoint() {
        val pipeline = trackingPipeline()
        for (i in 0 until 10) shakyFrameAt(pipeline, i * FRAME_STEP_MS, i)
        val published = states.last().tracks
        val lastFrameMs = 9 * FRAME_STEP_MS

        tickAt(pipeline, lastFrameMs + FRAME_STEP_MS)
        tickAt(pipeline, lastFrameMs + 2 * FRAME_STEP_MS)

        assertThat(states.last().tracks).isSameInstanceAs(published)
        assertThat(published.count).isEqualTo(1)
    }

    @Test
    fun aTrackPromotedByIdleTime_isPublishedUnsmoothed() {
        val unsmoothed = trackingPipeline(TrackerConfig(smoothEnabled = false))
        shakyFrameAt(unsmoothed, 0, 0)
        tickAt(unsmoothed, TrackerConfig().trackIdlePromoteMs)
        val trackersHeadXImg = states.last().tracks.headXImg(0)
        val smoothing = trackingPipeline()
        shakyFrameAt(smoothing, 0, 0)

        tickAt(smoothing, TrackerConfig().trackIdlePromoteMs)

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.headXImg(0)).isEqualTo(trackersHeadXImg)
    }

    /** Three frames with a person (a confirmed track), then one frame without: the track is LOST; returns the time of that frame. */
    private fun lostAfterOneMiss(pipeline: FramePipeline): Long {
        detector.people = listOf(walker())
        val confirmedAtMs = framesFrom(pipeline, 0, 3)
        detector.people = emptyList()
        val missAtMs = confirmedAtMs + FRAME_STEP_MS
        frameAt(pipeline, missAtMs)
        return missAtMs
    }

    @Test
    fun aFrameThatLosesTheTrack_publishesItAsLostWithTheTrackersLostMs() {
        val pipeline = trackingPipeline()

        lostAfterOneMiss(pipeline)

        val tracks = states.last().tracks
        assertThat(tracks.count).isEqualTo(1)
        assertThat(tracks.isLost(0)).isTrue()
        assertThat(tracks.lostMs(0)).isWithin(LOST_MS_TOLERANCE).of(FRAME_STEP_MS.toFloat())
    }

    @Test
    fun hudTick_onALostTrack_publishesTheLostMsPlusTheIdleTime() {
        val pipeline = trackingPipeline()
        val missAtMs = lostAfterOneMiss(pipeline)

        tickAt(pipeline, missAtMs + IDLE_MS)

        val tracks = states.last().tracks
        assertThat(tracks.count).isEqualTo(1)
        assertThat(tracks.isLost(0)).isTrue()
        assertThat(tracks.lostMs(0)).isWithin(LOST_MS_TOLERANCE).of(FRAME_STEP_MS + IDLE_MS.toFloat())
    }

    @Test
    fun hudTick_onALostTrack_republishesOnlyWhenTheFadeMovedByAStep() {
        val pipeline = trackingPipeline()
        val missAtMs = lostAfterOneMiss(pipeline)
        tickAt(pipeline, missAtMs + IDLE_MS)
        val first = states.last().tracks

        tickAt(pipeline, missAtMs + IDLE_MS + SMALL_IDLE_STEP_MS)
        assertThat(states.last().tracks).isSameInstanceAs(first)

        tickAt(pipeline, missAtMs + IDLE_MS + BIG_IDLE_STEP_MS)
        val second = states.last().tracks
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.lostMs(0)).isGreaterThan(first.lostMs(0) + BIG_IDLE_STEP_MS - LOST_MS_TOLERANCE)
    }

    @Test
    fun hudTick_onAConfirmedTrack_leavesItsLostMsAtZero() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        val lastMs = framesFrom(pipeline, 0, 3)

        tickAt(pipeline, lastMs + IDLE_MS)

        assertThat(states.last().tracks.lostMs(0)).isEqualTo(0f)
    }

    // ---- the lock (M4b T2) ----

    /** Three frames of one person: a shown track; returns its id and the time of the last frame. */
    private fun shownTrack(pipeline: FramePipeline): Pair<Int, Long> {
        detector.people = listOf(walker())
        val lastMs = framesFrom(pipeline, 0, 3)
        return Pair(states.last().tracks.id(0), lastMs)
    }

    @Test
    fun lock_isOffAtTheStart() {
        val pipeline = trackingPipeline()
        shownTrack(pipeline)

        assertThat(pipeline.hasLock).isFalse()
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
    }

    @Test
    fun requestLock_onAShownTrack_isCarriedByTheNextFrame() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)

        pipeline.requestLock(id)
        frameAt(pipeline, lastMs + FRAME_STEP_MS)

        assertThat(states.last().lockedId).isEqualTo(id)
        assertThat(pipeline.hasLock).isTrue()
    }

    @Test
    fun requestLock_isCarriedByTheNextHudTickToo() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)

        pipeline.requestLock(id)
        tickAt(pipeline, lastMs + SMALL_IDLE_STEP_MS)

        assertThat(states.last().lockedId).isEqualTo(id)
    }

    @Test
    fun theLock_staysOnWhileTheTrackStaysShown() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)
        pipeline.requestLock(id)

        framesFrom(pipeline, lastMs + FRAME_STEP_MS, 5)

        assertThat(states.takeLast(5).map { it.lockedId }).containsExactly(id, id, id, id, id)
    }

    @Test
    fun theLock_staysOnWhileTheTrackIsLost() {
        val pipeline = trackingPipeline()
        val missAtMs = lostAfterOneMiss(pipeline)
        val id = states.last().tracks.id(0)

        pipeline.requestLock(id)
        tickAt(pipeline, missAtMs + IDLE_MS)

        assertThat(states.last().tracks.isLost(0)).isTrue()
        assertThat(states.last().lockedId).isEqualTo(id)
    }

    @Test
    fun clearLock_endsItAtTheNextState() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)
        pipeline.requestLock(id)
        frameAt(pipeline, lastMs + FRAME_STEP_MS)

        pipeline.clearLock()
        frameAt(pipeline, lastMs + 2 * FRAME_STEP_MS)

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isFalse()
    }

    @Test
    fun requestLock_forAnIdThatIsNotShown_isDroppedAtTheNextState() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)

        pipeline.requestLock(id + 100)
        frameAt(pipeline, lastMs + FRAME_STEP_MS)

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isFalse()
    }

    @Test
    fun theLock_endsWhenItsTrackIsRemoved_andDoesNotComeBack() {
        val pipeline = trackingPipeline()
        val missAtMs = lostAfterOneMiss(pipeline)
        val id = states.last().tracks.id(0)
        pipeline.requestLock(id)
        tickAt(pipeline, missAtMs + IDLE_MS)
        assertThat(states.last().lockedId).isEqualTo(id)

        tickAt(pipeline, missAtMs + REMOVAL_IDLE_MS)

        assertThat(states.last().tracks.count).isEqualTo(0)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isFalse()
        detector.people = listOf(walker())
        framesFrom(pipeline, missAtMs + REMOVAL_IDLE_MS + FRAME_STEP_MS, 3)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
    }

    @Test
    fun theLock_endsOnAReset() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)
        pipeline.requestLock(id)
        frameAt(pipeline, lastMs + FRAME_STEP_MS)
        assertThat(states.last().lockedId).isEqualTo(id)

        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isFalse()
    }

    @Test
    fun aLockRequestedAfterAReset_forAnOldId_neverReachesANewTrack() {
        val pipeline = trackingPipeline()
        val (id, lastMs) = shownTrack(pipeline)
        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))

        pipeline.requestLock(id)
        framesFrom(pipeline, lastMs + FRAME_STEP_MS, 3, widthImg = TRACK_HEIGHT, heightImg = TRACK_WIDTH)

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.id(0)).isNotEqualTo(id)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
    }

    @Test
    fun aFrameWithoutDetections_carriesNoLock() {
        val pipeline = pipeline(detector = null)
        pipeline.requestLock(1)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isFalse()
    }

    @Test
    fun aNegativeLockRequest_isNoLock() {
        val pipeline = trackingPipeline()
        shownTrack(pipeline)

        pipeline.requestLock(OverlayState.NO_LOCK)

        assertThat(pipeline.hasLock).isFalse()
    }

    // ---- re-lock by clothing colours (M4c T2) ----

    /** A person and the colour of the pixels of their whole box (0xRRGGBB); the rest of the frame keeps the test pattern. */
    private class Look(
        val person: PoseDetection,
        val colour: Int,
    )

    /** Feeds one frame at [atMs] in which each look's box is painted in its colour and the detector reports the people. */
    private fun lookFrameAt(
        pipeline: FramePipeline,
        atMs: Long,
        vararg looks: Look,
    ) {
        detector.people = looks.map { it.person }
        clock.currentNs = NOW_NS + atMs * MS_NS
        val paint = { buffer: ByteBuffer ->
            for (look in looks) paintBox(buffer, look.person, look.colour)
        }
        pipeline.onFrame(
            image(widthImg = TRACK_WIDTH, heightImg = TRACK_HEIGHT, timestampNs = clock.currentNs - 2 * MS_NS, paint = paint),
            clock.currentNs,
        )
    }

    private fun paintBox(
        buffer: ByteBuffer,
        person: PoseDetection,
        colour: Int,
    ) {
        for (y in person.boxTopImg.toInt().coerceAtLeast(0) until person.boxBottomImg.toInt().coerceAtMost(TRACK_HEIGHT)) {
            for (x in person.boxLeftImg.toInt().coerceAtLeast(0) until person.boxRightImg.toInt().coerceAtMost(TRACK_WIDTH)) {
                val index = (y * TRACK_WIDTH + x) * BYTES_PER_PIXEL
                buffer.put(index, ((colour shr RED_SHIFT) and BYTE_MASK).toByte())
                buffer.put(index + 1, ((colour shr GREEN_SHIFT) and BYTE_MASK).toByte())
                buffer.put(index + 2, (colour and BYTE_MASK).toByte())
                buffer.put(index + 3, OPAQUE_BYTE)
            }
        }
    }

    /** Frames [FRAME_STEP_MS] apart of [looks], from [startMs]; returns the time of the last one. */
    private fun lookFramesFrom(
        pipeline: FramePipeline,
        startMs: Long,
        count: Int,
        vararg looks: Look,
    ): Long {
        var atMs = startMs
        for (i in 0 until count) {
            lookFrameAt(pipeline, atMs, *looks)
            if (i < count - 1) atMs += FRAME_STEP_MS
        }
        return atMs
    }

    /** The lock messages of the log, in order. */
    private fun lockLogs(): List<String> = logs.filter { it.startsWith("lock:") }

    /**
     * One person in [colour] stands in the left of the picture for three frames and is locked; three more frames
     * build the signature; then the picture has nobody for four frames [DEPARTURE_STEP_MS] apart, so the track is
     * removed and the lock waits. Returns the time of the last frame; the person's id is 1.
     */
    private fun lockedPersonWhoLeaves(
        pipeline: FramePipeline,
        colour: Int,
    ): Long {
        val first = Look(walker(), colour)
        lookFramesFrom(pipeline, 0, 3, first)
        assertThat(states.last().tracks.id(0)).isEqualTo(1)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, first)
        assertThat(states.last().lockedId).isEqualTo(1)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(states.last().tracks.count).isEqualTo(0)
        return atMs
    }

    /** The id of the shown track of the newest state whose head is right of [xImg] (the new, right-hand person). */
    private fun idOfTheTrackRightOf(xImg: Float): Int {
        val tracks = states.last().tracks
        for (i in 0 until tracks.count) {
            if (tracks.headXImg(i) > xImg) return tracks.id(i)
        }
        return -1
    }

    /** The id of the shown track of the newest state whose head is left of [xImg]. */
    private fun idOfTheTrackLeftOf(xImg: Float): Int {
        val tracks = states.last().tracks
        for (i in 0 until tracks.count) {
            if (tracks.headXImg(i) < xImg) return tracks.id(i)
        }
        return -1
    }

    @Test
    fun relock_aPersonWhoLeavesAndComesBackInTheSameColours_getsTheLockAgain() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isTrue()

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.id(0)).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(2)
        assertThat(pipeline.hasLock).isTrue()
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(pipeline.lockCount).isEqualTo(1)
    }

    @Test
    fun relock_logsEachStepOfTheLock() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(lockLogs())
            .containsExactly(
                "lock: set id=1",
                "lock: pending id=1 frames=3",
                "lock: relocked id=1 -> 2 d=0.00 next=none min=0.00 src=box sig=3",
            ).inOrder()
    }

    @Test
    fun relock_aPendingLock_drawsNoRingButStillCountsAsALock() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFrameAt(pipeline, leftAtMs + FRAME_STEP_MS)
        tickAt(pipeline, leftAtMs + 2 * FRAME_STEP_MS)

        assertThat(states.takeLast(2).map { it.lockedId }).containsExactly(OverlayState.NO_LOCK, OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isTrue()
    }

    @Test
    fun relock_aDifferentlyColouredPerson_doesNotGetTheLock() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isTrue()
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: candidate id=2 d=1.00 min=1.00 rejected (too far) src=box sig=3")
    }

    @Test
    fun relock_twoNewPeopleInTheSameColours_getNoLockBecauseItIsAmbiguous() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(
            pipeline,
            leftAtMs + FRAME_STEP_MS,
            WINDOW_END_FRAMES,
            Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT),
            Look(walker(), RED_SHIRT),
        )

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(2)
        assertThat(lockLogs().last()).contains("rejected (ambiguous, next=0.00)")
    }

    @Test
    fun relock_amongTwoNewPeople_goesToTheOneInTheRememberedColours() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(
            pipeline,
            leftAtMs + FRAME_STEP_MS,
            3,
            Look(walker(), BLUE_SHIRT),
            Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT),
        )

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(idOfTheTrackRightOf(RIGHT_GROUP_MIN_X_IMG))
        assertThat(states.last().lockedId).isNotEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        // The blue one is judged at most once before the red one is accepted: its window never ends.
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    @Test
    fun relock_aBystanderInTheSameColours_makesTheReturnAmbiguous() {
        val pipeline = trackingPipeline()
        val bystander = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)
        val locked = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, locked, bystander)
        val lockedId = idOfTheTrackLeftOf(RIGHT_GROUP_MIN_X_IMG)
        pipeline.requestLock(lockedId)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, locked, bystander)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs, bystander)
        }
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: pending id=$lockedId frames=3")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, bystander, Look(walker(), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
    }

    @Test
    fun relock_aLockOnATrackThatWasNeverMatched_endsWithoutWaiting() {
        val pipeline = trackingPipeline()
        val missAtMs = lostAfterOneMiss(pipeline)
        val id = states.last().tracks.id(0)
        pipeline.requestLock(id)
        tickAt(pipeline, missAtMs + IDLE_MS)

        tickAt(pipeline, missAtMs + REMOVAL_IDLE_MS)

        assertThat(pipeline.hasLock).isFalse()
        assertThat(lockLogs()).containsExactly("lock: set id=$id", "lock: cleared (no signature)").inOrder()
    }

    @Test
    fun relock_returningInsideTheMemory_stillGetsTheLock() {
        val pipeline = trackingPipeline()
        var atMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        for (i in 0 until STEPS_INSIDE_THE_MEMORY) {
            atMs += MEMORY_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(pipeline.hasLock).isTrue()

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isNotEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
    }

    @Test
    fun relock_afterTheMemory_theLockIsGoneAndAReturnIsNotRecognised() {
        val pipeline = trackingPipeline()
        var atMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        for (i in 0 until STEPS_BEYOND_THE_MEMORY) {
            atMs += MEMORY_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(pipeline.hasLock).isFalse()
        assertThat(pipeline.lockExpiredCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: expired")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    @Test
    fun relock_hudTicksWithoutFrames_doNotUseUpTheMemory() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        tickAt(pipeline, leftAtMs + HUD_TICK_AFTER_LONG_IDLE_MS)

        assertThat(pipeline.hasLock).isTrue()
        assertThat(pipeline.lockExpiredCount).isEqualTo(0)
    }

    @Test
    fun relock_aResetEndsTheWaiting() {
        val pipeline = trackingPipeline()
        lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))

        assertThat(pipeline.hasLock).isFalse()
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(lockLogs().last()).isEqualTo("lock: cleared (reset)")
    }

    @Test
    fun relock_afterAReset_aPersonInTheOldColoursIsNotRelocked() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        pipeline.setExpectedSize(CaptureSize(TRACK_HEIGHT, TRACK_WIDTH))
        pipeline.setExpectedSize(CaptureSize(TRACK_WIDTH, TRACK_HEIGHT))

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, 3, Look(walker(), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    @Test
    fun relock_aTapOnTheHandle_endsTheWaiting() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        pipeline.clearLock()
        assertThat(pipeline.hasLock).isFalse()
        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).contains("lock: cleared (handle)")
    }

    @Test
    fun relock_endingTheSession_endsTheWaiting() {
        val pipeline = trackingPipeline()
        lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        pipeline.onStop()

        assertThat(pipeline.hasLock).isFalse()
        assertThat(lockLogs().last()).isEqualTo("lock: cleared (stop)")
    }

    @Test
    fun relock_endingTheSessionWithoutALock_logsNothing() {
        val pipeline = trackingPipeline()
        shownTrack(pipeline)

        pipeline.onStop()

        assertThat(lockLogs()).isEmpty()
    }

    @Test
    fun relock_aSecondLockRequest_replacesTheFirstAndStartsANewSignature() {
        val pipeline = trackingPipeline()
        val first = Look(walker(), RED_SHIRT)
        val second = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first, second)
        val firstId = idOfTheTrackLeftOf(RIGHT_GROUP_MIN_X_IMG)
        val secondId = idOfTheTrackRightOf(RIGHT_GROUP_MIN_X_IMG)
        pipeline.requestLock(firstId)
        lookFrameAt(pipeline, 3 * FRAME_STEP_MS, first, second)

        pipeline.requestLock(secondId)
        lookFrameAt(pipeline, 4 * FRAME_STEP_MS, first, second)

        assertThat(states.last().lockedId).isEqualTo(secondId)
        assertThat(pipeline.lockCount).isEqualTo(2)
        assertThat(lockLogs()).containsExactly("lock: set id=$firstId", "lock: cleared (handle)", "lock: set id=$secondId").inOrder()
    }

    @Test
    fun relock_aFailingLogger_doesNotStopTheLock() {
        val pipeline = trackingPipeline()
        loggerError = RuntimeException("log failed")

        lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        assertThat(pipeline.hasLock).isTrue()
        assertThat(states.last().tracks.count).isEqualTo(0)
    }

    /** Red person 1 is locked and seen for six frames, then hidden for [FRAMES_WHILE_HIDDEN] frames; returns the time of the last one. */
    private fun lockedPersonHiddenForAWhile(pipeline: FramePipeline): Long {
        val first = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, first)
        for (i in 0 until FRAMES_WHILE_HIDDEN) {
            atMs += FRAME_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        return atMs
    }

    @Test
    fun relock_early_aPersonBornUnderANewIdWhileTheOldTrackIsStillLost_getsTheLockAtOnce() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val returned = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4, returned)

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().tracks.isLost(0)).isTrue()
        assertThat(states.last().tracks.id(0)).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(2)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(pipeline.lockCount).isEqualTo(1)
        assertThat(
            lockLogs(),
        ).containsExactly("lock: set id=1", "lock: relocked id=1 -> 2 d=0.00 next=none early min=0.00 src=box sig=3").inOrder()
    }

    @Test
    fun relock_early_theOldTrackBeingRemovedLater_changesNothing() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val returned = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)
        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4, returned)
        assertThat(states.last().lockedId).isEqualTo(2)

        lookFramesFrom(pipeline, atMs + 5 * FRAME_STEP_MS, FRAMES_UNTIL_REMOVED, returned)

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().tracks.id(0)).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(2)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).hasSize(2)
    }

    @Test
    fun relock_early_aDifferentlyColouredPersonWhileTheOldTrackIsLost_doesNotMoveTheLock() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 5, Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_theOldTrackMatchedAgainBeforeTheNewOneIsShown_keepsTheLock() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val back = Look(walker(), RED_SHIRT)
        val other = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)

        // The new person is only tentative (born in the first frame) when the old track is matched again.
        lookFrameAt(pipeline, atMs + FRAME_STEP_MS, other)
        lookFramesFrom(pipeline, atMs + 2 * FRAME_STEP_MS, WINDOW_END_FRAMES, back, other)

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_aTrackThatWasThereWhenTheHeldTrackWasLastMatched_isNeverACandidate() {
        val pipeline = trackingPipeline()
        val left = Look(walker(), RED_SHIRT)
        val right = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, left, right)
        val leftId = idOfTheTrackLeftOf(RIGHT_GROUP_MIN_X_IMG)
        pipeline.requestLock(leftId)
        val atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, left, right)
        assertThat(states.last().lockedId).isEqualTo(leftId)

        // The locked person leaves; the other one, in the same colours and with a lower or equal id, stays.
        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, right)

        assertThat(states.last().lockedId).isEqualTo(leftId)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=$leftId")
    }

    @Test
    fun relock_early_aWindowThatStartedWhileTheOldTrackWasLost_goesOnAfterItsRemoval() {
        val pipeline = trackingPipeline()
        var atMs = lockedPersonHiddenForAWhile(pipeline)
        for (i in 0 until MORE_FRAMES_HIDDEN) {
            atMs += FRAME_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        val blue = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), BLUE_SHIRT)

        // The candidate is shown and judged a few frames before the old track is removed, and its window ends after.
        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES + 2, blue)

        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().filter { it.contains("rejected") }).hasSize(1)
        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.hasLock).isTrue()
    }

    @Test
    fun relock_early_aLookAlikeFarFromTheLostTrack_isNotAcceptedBeforeTheLockedPersonReturns() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val lookAlike = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)
        val back = Look(walker(), RED_SHIRT)

        val shownAtMs = lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4, lookAlike)
        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(1)
        lookFramesFrom(pipeline, shownAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, back, lookAlike)

        assertThat(states.none { it.lockedId > 1 }).isTrue()
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_theLockedPersonReturningAsANewTrackNearTheOldOne_neverGivesTheLockToAFarLookAlike() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val lookAlike = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)
        val returned = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)

        val shownAtMs = lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4, lookAlike)
        val lookAlikeId = idOfTheTrackRightOf(FAR_GROUP_MIN_X_IMG)
        lookFramesFrom(pipeline, shownAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, returned, lookAlike)

        assertThat(lookAlikeId).isGreaterThan(1)
        assertThat(states.none { it.lockedId == lookAlikeId }).isTrue()
        // The returned person (id 3) is judged on the old track's frames, then, once that track is removed, in pending:
        // the look-alike in the same colours makes it ambiguous, so nobody gets the lock and the window ends once.
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs())
            .containsExactly(
                "lock: set id=1",
                "lock: pending id=1 frames=3",
                "lock: candidate id=3 d=0.00 min=0.00 rejected (ambiguous, next=0.00) src=box sig=3",
            ).inOrder()
    }

    @Test
    fun relock_early_aLookAlikeJustBeyondSixTenthsOfAHeight_isNotAcceptedEarly() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 6, Look(walker(shiftXImg = EARLY_JUST_OUTSIDE_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_aSecondPersonNearTheLostTrackThatIsStillTentative_stopsTheLookAlikeFromGettingTheLock() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val lookAlike = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)
        val second = Look(walker(shiftXImg = -EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)

        // The look-alike is shown (third frame) while the second person, born one frame later, is still tentative.
        lookFrameAt(pipeline, atMs + FRAME_STEP_MS, lookAlike)
        lookFramesFrom(pipeline, atMs + 2 * FRAME_STEP_MS, 6, lookAlike, second)

        assertThat(states.last().tracks.count).isEqualTo(3)
        assertThat(states.none { it.lockedId > 1 }).isTrue()
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_aCandidateNearTheLostTracksPredictedHead_isStillAcceptedEarly() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4, Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().tracks.count).isEqualTo(2)
        assertThat(states.last().tracks.isLost(0)).isTrue()
        assertThat(states.last().lockedId).isEqualTo(2)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: relocked id=1 -> 2 d=0.00 next=none early min=0.00 src=box sig=3")
    }

    @Test
    fun relock_early_twoShownTracksNearTheLostTrack_giveNoEarlyAccept() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val sameColours = Look(walker(shiftXImg = EARLY_NEAR_SHIFT_X_IMG), RED_SHIRT)
        val otherPerson = Look(walker(shiftXImg = -EARLY_NEAR_SHIFT_X_IMG), BLUE_SHIRT)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 6, sameColours, otherPerson)

        assertThat(states.last().tracks.count).isEqualTo(3)
        assertThat(states.last().lockedId).isEqualTo(1)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs()).containsExactly("lock: set id=1")
    }

    @Test
    fun relock_early_aCandidateThatFailedOnlyTheSpatialTest_isStillJudgedAndAcceptedWhenTheOldTrackIsRemoved() {
        val pipeline = trackingPipeline()
        val atMs = lockedPersonHiddenForAWhile(pipeline)
        val lookAlike = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 4 + FRAMES_UNTIL_REMOVED, lookAlike)

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(2)
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(lockLogs())
            .containsExactly(
                "lock: set id=1",
                "lock: pending id=1 frames=3",
                "lock: relocked id=1 -> 2 d=0.00 next=none min=0.00 src=box sig=3",
            ).inOrder()
    }

    @Test
    fun relock_aCandidateJudgedOnSeveralFrames_isAcceptedWhenItsFirstFramesWereHalfOutOfThePicture() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        for (k in 0 until ENTRY_FRAMES) {
            val shift = (ENTRY_START_SHIFT_X_IMG + ENTRY_STEP_X_IMG * k).toFloat()
            val colour = if (k < ENTRY_CLIPPED_FRAMES) BLUE_SHIRT else RED_SHIRT
            lookFrameAt(pipeline, leftAtMs + (k + 1) * FRAME_STEP_MS, Look(walker(shiftXImg = shift), colour))
        }

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    @Test
    fun relock_aCandidateThatStaysHalfOutOfThePicture_isRejectedOnceWithNoTorso() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, NO_TORSO_CAP_FRAMES, Look(walker(shiftXImg = EDGE_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: candidate id=2 rejected (no torso) src=box sig=3")
    }

    @Test
    fun relock_aCandidateClippedForMoreThanTheJudgeWindow_isStillAcceptedOnceItsShirtIsSeen() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        for (k in 0 until LONG_ENTRY_FRAMES) {
            val shift = (ENTRY_START_SHIFT_X_IMG + LONG_ENTRY_STEP_X_IMG * k).toFloat()
            val colour = if (k < LONG_ENTRY_CLIPPED_FRAMES) BLUE_SHIRT else RED_SHIRT
            lookFrameAt(pipeline, leftAtMs + (k + 1) * FRAME_STEP_MS, Look(walker(shiftXImg = shift), colour))
        }

        assertThat(states.last().tracks.count).isEqualTo(1)
        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    /**
     * The red person is locked and seen alone for [SIGNATURE_RED_FRAMES] frames, then for [OCCLUDED_FRAMES] frames a blue
     * person stands over half of their shirt; then both leave. Returns the time of the last frame.
     */
    private fun lockedRedPersonWhoWasOccludedByABluePerson(pipeline: FramePipeline): Long {
        val red = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, red)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, SIGNATURE_RED_FRAMES, red)
        val occluder = Look(walker(shiftXImg = OCCLUDER_SHIFT_X_IMG), BLUE_SHIRT)
        atMs = lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, OCCLUDED_FRAMES, red, occluder)
        assertThat(states.last().lockedId).isEqualTo(1)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(states.last().tracks.count).isEqualTo(0)
        return atMs
    }

    @Test
    fun relock_aSignatureThatAnOccluderStoodOver_stillAcceptsTheRedPersonWhoComesBack() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedRedPersonWhoWasOccludedByABluePerson(pipeline)

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
    }

    @Test
    fun relock_aSignatureThatAnOccluderStoodOver_stillRejectsABlueStranger() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedRedPersonWhoWasOccludedByABluePerson(pipeline)

        lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
    }

    @Test
    fun relock_aSignatureOfTheUpperBody_isNeverMatchedWithAWholeBodyTorsoInTheSameColour() {
        val pipeline = trackingPipeline()
        val sitting = Look(deskPerson(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, sitting)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, sitting)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, NO_TORSO_CAP_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: candidate id=2 rejected (no comparable torso) src=box sig=3")
    }

    @Test
    fun relock_aBystanderWhoseTorsoTheSignatureCannotCompare_makesTheCandidateAmbiguous() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFramesFrom(
            pipeline,
            leftAtMs + FRAME_STEP_MS,
            NO_TORSO_CAP_FRAMES,
            Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT),
            Look(deskPerson(), RED_SHIRT),
        )

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(2)
        assertThat(lockLogs().any { it.contains("rejected (ambiguous, next=unsampled)") }).isTrue()
        assertThat(lockLogs().any { it.contains("rejected (no comparable torso)") }).isTrue()
    }

    /** A whole-body person ([walker]'s box and head) whose shoulder and hip keypoints are confident: the keypoint torso. */
    private fun keypointWalker(shiftXImg: Float = 0f): PoseDetection {
        val base = walker(shiftXImg = shiftXImg)
        val xy = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2)
        val scores = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT)
        for (k in 0 until HEAD_KEYPOINTS) {
            xy[k * 2] = headXsImg[k] + shiftXImg
            xy[k * 2 + 1] = headYsImg[k]
            scores[k] = 0.9f
        }

        fun put(
            keypoint: Int,
            xImg: Float,
            yImg: Float,
        ) {
            xy[keypoint * 2] = xImg + shiftXImg
            xy[keypoint * 2 + 1] = yImg
            scores[keypoint] = 0.9f
        }
        put(TrackerConfig.KEYPOINT_LEFT_SHOULDER, 110f, 70f)
        put(TrackerConfig.KEYPOINT_RIGHT_SHOULDER, 150f, 70f)
        put(TrackerConfig.KEYPOINT_LEFT_HIP, 115f, 130f)
        put(TrackerConfig.KEYPOINT_RIGHT_HIP, 145f, 130f)
        return PoseDetection(base.boxLeftImg, base.boxTopImg, base.boxRightImg, base.boxBottomImg, 0.9f, xy, scores)
    }

    @Test
    fun relock_aSignatureBuiltFromKeypoints_isComparableToAReturningPersonWhoseTorsoComesFromTheBox() {
        val pipeline = trackingPipeline()
        val standing = Look(keypointWalker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, standing)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, standing)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: relocked id=1 -> 2 d=0.00 next=none min=0.00 src=box sig=3")
    }

    @Test
    fun relock_aBystanderInAnotherBucketThanTheCandidate_makesTheCandidateAmbiguousEvenWhenItIsFarAway() {
        val pipeline = trackingPipeline()
        // Both buckets are built: the person stands (box torso), then sits (upper-body torso), all in red.
        val standing = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, standing)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 4, standing)
        atMs = lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 5, Look(deskPerson(), RED_SHIRT))
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(lockLogs().last()).isEqualTo("lock: pending id=1 frames=9")

        // The red walker is the candidate (whole-torso d about 0); the blue seated bystander is far from the red
        // upper-body histogram, a distance on another scale that must not give the candidate a margin.
        lookFramesFrom(
            pipeline,
            atMs + FRAME_STEP_MS,
            WINDOW_END_FRAMES,
            Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT),
            Look(deskPerson(), BLUE_SHIRT),
        )

        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(lockLogs().any { it.contains("d=0.00 min=0.00 rejected (ambiguous, next=unsampled)") }).isTrue()
    }

    @Test
    fun relock_aSignatureOfASingleFrame_isNotUsedAsTheOnlyReferenceAndAcceptsNoStranger() {
        val pipeline = trackingPipeline()
        val first = Look(walker(), BLUE_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 1, first)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(lockLogs().last()).isEqualTo("lock: pending id=1 frames=1")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, NO_TORSO_CAP_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: candidate id=2 rejected (no comparable torso) src=box sig=1")
    }

    @Test
    fun relock_aSignatureOfASingleFrame_acceptsAStrangerWhenTheLeastSamplesAreSetToOne() {
        val pipeline = trackingPipeline(TrackerConfig(relockMinSourceSamples = 1))
        val first = Look(walker(), BLUE_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 1, first)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(pipeline.lockRelockCount).isEqualTo(1)
    }

    @Test
    fun relock_aClippedFrameOfTheLockedPerson_isNotAddedToTheirSignature() {
        val pipeline = trackingPipeline()
        val standing = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, standing)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, standing)
        // The person walks to the left edge in steps of 10 px and stays there in blue: what the edge leaves of them
        // is not their torso, and a signature that took it in would not match the red person who comes back.
        val shifts = (1..EDGE_WALK_STEPS).map { -EDGE_WALK_STEP_X_IMG * it } + List(EDGE_STAY_FRAMES) { EDGE_SHIFT_X_IMG }
        for (shift in shifts) {
            atMs += FRAME_STEP_MS
            lookFrameAt(pipeline, atMs, Look(walker(shiftXImg = shift), if (shift <= EDGE_CLIPPED_SHIFT_X_IMG) BLUE_SHIRT else RED_SHIRT))
        }
        assertThat(states.last().tracks.count).isEqualTo(1)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(states.last().tracks.count).isEqualTo(0)

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
    }

    @Test
    fun relock_anotherTrackThatCannotBeSampled_makesTheCandidateAmbiguous() {
        val pipeline = trackingPipeline()
        val atTheEdge = Look(walker(shiftXImg = EDGE_SHIFT_X_IMG), RED_SHIRT)
        val locked = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, atTheEdge, locked)
        val lockedId = idOfTheTrackRightOf(RIGHT_GROUP_MIN_X_IMG)
        pipeline.requestLock(lockedId)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, atTheEdge, locked)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs, atTheEdge)
        }
        assertThat(lockLogs().last()).isEqualTo("lock: pending id=$lockedId frames=3")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, atTheEdge, Look(walker(), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs().last()).endsWith("d=0.00 min=0.00 rejected (ambiguous, next=unsampled) src=box sig=3")
    }

    @Test
    fun relock_aTapOnAnotherPersonWhilePending_replacesTheWaitingLock() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        val other = Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT)
        val shownAtMs = lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, 3, other)
        val otherId = states.last().tracks.id(0)

        pipeline.requestLock(otherId)
        lookFrameAt(pipeline, shownAtMs + FRAME_STEP_MS, other)

        assertThat(states.last().lockedId).isEqualTo(otherId)
        val expected = listOf("lock: pending id=1 frames=3", "lock: cleared (replaced)", "lock: set id=$otherId")
        assertThat(lockLogs().takeLast(expected.size)).containsExactlyElementsIn(expected).inOrder()
        assertThat(pipeline.lockCount).isEqualTo(2)
    }

    @Test
    fun relock_thePendingLineSaysHowManyFramesFilledTheSignature() {
        val pipeline = trackingPipeline()
        val standing = Look(walker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, standing)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, SIGNATURE_FRAMES, standing)

        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        assertThat(lockLogs()).containsExactly("lock: set id=1", "lock: pending id=1 frames=$SIGNATURE_FRAMES").inOrder()
    }

    @Test
    fun relock_thePendingLineIsLoggedOnceWhileTheLockWaits() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)

        lookFrameAt(pipeline, leftAtMs + FRAME_STEP_MS)
        tickAt(pipeline, leftAtMs + 2 * FRAME_STEP_MS)
        lookFrameAt(pipeline, leftAtMs + 3 * FRAME_STEP_MS)

        assertThat(lockLogs().count { it.startsWith("lock: pending") }).isEqualTo(1)
    }

    @Test
    fun relock_aSignatureThatStartedEmptyAtTheEdge_isFilledOnceThePersonWalksIn() {
        val pipeline = trackingPipeline()
        val atTheEdge = Look(walker(shiftXImg = EDGE_SHIFT_X_IMG), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, atTheEdge)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, atTheEdge)
        // Every frame so far showed only a clipped torso, so nothing was blended; now the person walks in.
        for (step in 1..EDGE_WALK_STEPS + 1) {
            atMs += FRAME_STEP_MS
            lookFrameAt(pipeline, atMs, Look(walker(shiftXImg = EDGE_SHIFT_X_IMG + EDGE_WALK_STEP_X_IMG * step), RED_SHIRT))
        }
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(lockLogs().last()).startsWith("lock: pending id=1 frames=")
        assertThat(lockLogs().last()).isNotEqualTo("lock: pending id=1 frames=0")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(pipeline.lockRelockCount).isEqualTo(1)
    }

    @Test
    fun relock_aLockThatNeverSawAWholeTorso_endsAtOnceWhenItsTrackLeaves() {
        val pipeline = trackingPipeline()
        val atTheEdge = Look(walker(shiftXImg = EDGE_SHIFT_X_IMG), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, atTheEdge)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, atTheEdge)

        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(lockLogs()).containsExactly("lock: set id=1", "lock: cleared (no signature)").inOrder()
        assertThat(pipeline.hasLock).isFalse()
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(0)
        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
    }

    @Test
    fun relock_aCandidateWhoseWindowEnded_isNeverJudgedAgain() {
        val pipeline = trackingPipeline()
        val leftAtMs = lockedPersonWhoLeaves(pipeline, RED_SHIRT)
        val place = walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG)
        val atMs = lookFramesFrom(pipeline, leftAtMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(place, BLUE_SHIRT))
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        val logged = lockLogs().size

        // The same track now wears the remembered colours: its window is over, so it is not compared again.
        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, WINDOW_END_FRAMES, Look(place, RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
        assertThat(pipeline.lockRelockRejectCount).isEqualTo(1)
        assertThat(lockLogs()).hasSize(logged)
    }

    /**
     * A person at a desk: the [walker]'s head, both shoulders confident (40 px apart, 32 px under the box top), no
     * other keypoint confident, and a box 72 px high, 60 wide: not a whole body, so only the shoulders give a torso.
     */
    private fun deskPerson(shiftXImg: Float = 0f): PoseDetection {
        val xy = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT * 2)
        val scores = FloatArray(TrackerConfig.POSE_KEYPOINT_COUNT)
        for (k in 0 until HEAD_KEYPOINTS) {
            xy[k * 2] = headXsImg[k] + shiftXImg
            xy[k * 2 + 1] = headYsImg[k]
            scores[k] = 0.9f
        }
        val shoulderYImg = 52f
        xy[TrackerConfig.KEYPOINT_LEFT_SHOULDER * 2] = 110f + shiftXImg
        xy[TrackerConfig.KEYPOINT_LEFT_SHOULDER * 2 + 1] = shoulderYImg
        xy[TrackerConfig.KEYPOINT_RIGHT_SHOULDER * 2] = 150f + shiftXImg
        xy[TrackerConfig.KEYPOINT_RIGHT_SHOULDER * 2 + 1] = shoulderYImg
        scores[TrackerConfig.KEYPOINT_LEFT_SHOULDER] = 0.9f
        scores[TrackerConfig.KEYPOINT_RIGHT_SHOULDER] = 0.9f
        return PoseDetection(100f + shiftXImg, 20f, 160f + shiftXImg, 92f, 0.9f, xy, scores)
    }

    @Test
    fun relock_aPersonAtADeskWhoseHipsAreNotSeen_getsTheLockAgainByTheirShoulders() {
        val pipeline = trackingPipeline()
        val sitting = Look(deskPerson(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, sitting)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, sitting)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }
        assertThat(lockLogs().last()).isEqualTo("lock: pending id=1 frames=3")

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(deskPerson(NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(lockLogs().last()).isEqualTo("lock: relocked id=1 -> 2 d=0.00 next=none min=0.00 src=upper sig=3")
    }

    // ---- association misses (M4e T1) ----

    private fun missLogs(): List<String> = logs.filter { it.startsWith("track: miss") }

    /** A walker confirmed in three frames, then a person [NEW_PERSON_SHIFT_X_IMG] to the right and nobody at the old place. */
    private fun confirmedWalkerThenANewPersonNextToIt(pipeline: FramePipeline) {
        detector.people = listOf(walker())
        val confirmedAtMs = framesFrom(pipeline, 0, 3)
        detector.people = listOf(walker(shiftXImg = NEW_PERSON_SHIFT_X_IMG))
        frameAt(pipeline, confirmedAtMs + FRAME_STEP_MS)
    }

    @Test
    fun aTrackBornNextToALostOne_writesOneMissLineAndCountsIt() {
        val pipeline = trackingPipeline()

        confirmedWalkerThenANewPersonNextToIt(pipeline)

        assertThat(missLogs()).hasSize(1)
        assertThat(missLogs()[0]).startsWith("track: miss id=1 -> new id=2 lostMs=50 ")
        assertThat(missLogs()[0]).contains(" stage=high ")
        assertThat(missLogs()[0]).contains(" iou=0.00 ")
        assertThat(missLogs()[0]).endsWith(" reason=gate")
        assertThat(pipeline.assocMisses).isEqualTo(1)
        assertThat(pipeline.assocMissGate).isEqualTo(1)
        assertThat(pipeline.assocMissCost).isEqualTo(0)
        assertThat(pipeline.assocMissTaken).isEqualTo(0)
        assertThat(pipeline.assocMissLowBirth).isEqualTo(0)
    }

    @Test
    fun withZeroLinesPerMinute_theMissIsCountedButNotWritten() {
        val pipeline = trackingPipeline(TrackerConfig(assocMissLogPerMin = 0))

        confirmedWalkerThenANewPersonNextToIt(pipeline)

        assertThat(missLogs()).isEmpty()
        assertThat(pipeline.assocMisses).isEqualTo(1)
    }

    @Test
    fun aFailingLogger_doesNotStopTheMissFromBeingCounted() {
        val pipeline = trackingPipeline()
        loggerError = IllegalStateException("log broke")

        confirmedWalkerThenANewPersonNextToIt(pipeline)

        assertThat(pipeline.assocMisses).isEqualTo(1)
        assertThat(pipeline.failures).isEqualTo(0L)
    }

    @Test
    fun withoutAMiss_theCountersAreZeroAndNothingIsWritten() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())

        framesFrom(pipeline, 0, 5)

        assertThat(missLogs()).isEmpty()
        assertThat(pipeline.assocMisses).isEqualTo(0)
    }

    // ---- the model changes with the orientation (M1b-1) ----

    @Test
    fun modelChanged_publishesTheNewInputSizeAndTheImportedMark() {
        val pipeline = trackingPipeline()
        val next = ScriptedDetector(events, 832, 384)

        pipeline.onModelChanged(next, imported = true, notUsed = false)

        assertThat(states.last().modelInputWidth).isEqualTo(832)
        assertThat(states.last().modelInputHeight).isEqualTo(384)
        assertThat(states.last().modelImported).isTrue()
        assertThat(states.last().warning).isNull()
    }

    @Test
    fun modelChanged_dropsDetectionsAndTracks_andResetsTheSmootherAndTheLock() {
        val pipeline = trackingPipeline()
        detector.people = listOf(walker())
        framesFrom(pipeline, 0, 8)
        assertThat(states.last().tracks.count).isAtLeast(1)
        pipeline.requestLock(1)

        pipeline.onModelChanged(ScriptedDetector(events, 832, 384), imported = true, notUsed = false)

        assertThat(states.last().frame).isNull()
        assertThat(states.last().tracks.count).isEqualTo(0)
        assertThat(pipeline.smootherActiveCount).isEqualTo(0)
        assertThat(pipeline.hasLock).isFalse()
    }

    @Test
    fun modelChanged_laterFramesGoToTheNewDetectorOnly() {
        val pipeline = trackingPipeline()
        val next = ScriptedDetector(events, 832, 384)
        framesFrom(pipeline, 0, 2)
        val oldCalls = detector.calls

        pipeline.onModelChanged(next, imported = false, notUsed = false)
        framesFrom(pipeline, 200, 3)

        assertThat(detector.calls).isEqualTo(oldCalls)
        assertThat(next.calls).isEqualTo(3)
    }

    @Test
    fun modelChanged_toNoDetector_showsTheWarningAndStillDrainsFrames() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(null, imported = false, notUsed = false)
        framesFrom(pipeline, 0, 2)

        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
        assertThat(states.last().modelInputWidth).isEqualTo(0)
        assertThat(pipeline.failures).isEqualTo(0L)
    }

    @Test
    fun modelChanged_withAnImportedModelNotUsed_addsTheNote() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = true)

        assertThat(states.last().warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)
    }

    @Test
    fun modelChanged_withASlotModelOfTheWrongShape_addsItsOwnNote_andKeepsTheDotsShown() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = true, wrongShape = true)

        assertThat(states.last().warning).isEqualTo(OverlayWarning.MODEL_WRONG_SHAPE)
        assertThat(states.last().detectionsShown).isTrue()

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = true)

        assertThat(states.last().warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)
    }

    @Test
    fun wrongShapeAtStart_isTheNoteOfADetectedFrame_andLosesToTheMissingDetector() {
        val pipeline = pipeline(importedModelNotUsed = true, modelWrongShape = true)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().warning).isEqualTo(OverlayWarning.MODEL_WRONG_SHAPE)

        pipeline(detector = null, importedModelNotUsed = true, modelWrongShape = true).onFrame(image(), acquireNs())

        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun modelChanged_withTheThermalStepDown_addsTheNote_andKeepsTheDotsShown() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(ScriptedDetector(events, 320, 320), imported = false, notUsed = false, thermalStepDown = true)

        assertThat(states.last().warning).isEqualTo(OverlayWarning.THERMAL_STEPPED_DOWN)
        assertThat(states.last().detectionsShown).isTrue()
        assertThat(states.last().modelInputWidth).isEqualTo(320)
    }

    @Test
    fun modelChanged_thermalNote_losesToTheOtherNotesAndWarnings() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = true, thermalStepDown = true)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.IMPORTED_MODEL_NOT_USED)

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false, builtInFailed = true, thermalStepDown = true)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.BUILT_IN_MODEL_FAILED)

        pipeline.onModelChanged(null, imported = false, notUsed = false, thermalStepDown = true)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun modelChanged_backToNormal_removesTheThermalNote() {
        val pipeline = trackingPipeline()
        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false, thermalStepDown = true)

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false)

        assertThat(states.last().warning).isNull()
    }

    @Test
    fun modelChanged_whenThePublisherFails_isContained() {
        val pipeline = trackingPipeline()
        publisherError = IllegalStateException("publish broke")

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false)

        assertThat(pipeline.failures).isEqualTo(0L)
    }

    @Test
    fun modelChanged_forgetsTheTimingOfTheOldModel() {
        val pipeline = trackingPipeline()
        framesFrom(pipeline, 0, 5)
        assertThat(pipeline.timingSummary().samples).isGreaterThan(0)

        pipeline.onModelChanged(ScriptedDetector(events, 832, 384), imported = false, notUsed = false)

        assertThat(pipeline.timingSummary().samples).isEqualTo(0)
        framesFrom(pipeline, 200, 2)
        assertThat(pipeline.timingSummary().samples).isEqualTo(2)
    }

    @Test
    fun holdDetector_null_stopsDetection_andPuttingItBackResumesIt_withoutRepublishing() {
        val pipeline = trackingPipeline()
        framesFrom(pipeline, 0, 2)
        val calls = detector.calls
        val published = states.size

        pipeline.holdDetector(null)
        assertThat(states.size).isEqualTo(published)
        framesFrom(pipeline, 200, 2)
        assertThat(detector.calls).isEqualTo(calls)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)

        pipeline.holdDetector(detector)
        framesFrom(pipeline, 400, 2)
        assertThat(detector.calls).isEqualTo(calls + 2)
    }

    @Test
    fun builtInModelFailed_isTheNoteOfADetectedFrameAndKeepsTheDotsShown() {
        val pipeline = pipeline(builtInModelFailed = true)

        pipeline.onFrame(image(), acquireNs())

        val state = states.last()
        assertThat(state.warning).isEqualTo(OverlayWarning.BUILT_IN_MODEL_FAILED)
        assertThat(state.detectionsShown).isTrue()
    }

    @Test
    fun builtInModelFailed_losesToTheMissingDetector() {
        val pipeline = pipeline(detector = null, builtInModelFailed = true)

        pipeline.onFrame(image(), acquireNs())

        assertThat(states.last().warning).isEqualTo(OverlayWarning.DETECTOR_UNAVAILABLE)
    }

    @Test
    fun modelChanged_withTheSquareFallback_addsTheBuiltInModelFailedNote_andLaterRemovesIt() {
        val pipeline = trackingPipeline()

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false, builtInFailed = true)
        assertThat(states.last().warning).isEqualTo(OverlayWarning.BUILT_IN_MODEL_FAILED)

        pipeline.onModelChanged(ScriptedDetector(events), imported = false, notUsed = false)
        assertThat(states.last().warning).isNull()
    }

    // ---- box-only models (M-legacy T3) ----

    /** [walker] as a detect-only model emits it: the same box, 17 keypoints at (0, 0) with confidence 0. */
    private fun boxOnlyWalker(shiftXImg: Float = 0f): PoseDetection {
        val full = walker(shiftXImg = shiftXImg)
        return PoseDetection(
            full.boxLeftImg,
            full.boxTopImg,
            full.boxRightImg,
            full.boxBottomImg,
            full.score,
            FloatArray(HEAD_KEYPOINTS * 2),
            FloatArray(HEAD_KEYPOINTS),
        )
    }

    @Test
    fun boxOnly_statesCarryThePersonClass_andAPoseModelCarriesNone() {
        val box = trackingPipeline(TrackerConfig(detectPersonClass = 1), boxOnly = true)
        detector.people = listOf(boxOnlyWalker())
        frameAt(box, 0)
        assertThat(states.last().boxOnlyClass).isEqualTo(1)

        states.clear()
        val pose = trackingPipeline()
        frameAt(pose, 0)
        assertThat(states.last().boxOnlyClass).isEqualTo(OverlayState.NOT_BOX_ONLY)
    }

    @Test
    fun boxOnly_aPersonGetsATrackWithTheDotOnTheBoxHead_andTapToLockWorks() {
        val pipeline = trackingPipeline(boxOnly = true)
        detector.people = listOf(boxOnlyWalker())
        val lastMs = framesFrom(pipeline, 0, 3)
        val tracks = states.last().tracks
        assertThat(tracks.count).isEqualTo(1)
        // Box x 100..160, top 20, 200 high: dot at the centre x and 0.10 of the height below the top.
        assertThat(tracks.headXImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(130f)
        assertThat(tracks.headYImg(0)).isWithin(HEAD_TOLERANCE_IMG).of(40f)
        assertThat(tracks.isHeadEstimated(0)).isFalse()

        pipeline.requestLock(tracks.id(0))
        frameAt(pipeline, lastMs + FRAME_STEP_MS)

        assertThat(states.last().lockedId).isEqualTo(tracks.id(0))
    }

    @Test
    fun boxOnly_aLockedPersonWhoLeavesAndComesBackInTheSameColours_getsTheLockAgainThroughTheBoxTorso() {
        val pipeline = trackingPipeline(boxOnly = true)
        val first = Look(boxOnlyWalker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, first)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 3, Look(boxOnlyWalker(NEW_PERSON_SHIFT_X_IMG), RED_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(states.last().tracks.id(0))
        assertThat(pipeline.lockRelockCount).isEqualTo(1)
        assertThat(lockLogs().last()).contains("src=box")
    }

    @Test
    fun boxOnly_aDifferentlyColouredStranger_doesNotGetTheLock() {
        val pipeline = trackingPipeline(boxOnly = true)
        val first = Look(boxOnlyWalker(), RED_SHIRT)
        lookFramesFrom(pipeline, 0, 3, first)
        pipeline.requestLock(1)
        var atMs = lookFramesFrom(pipeline, 3 * FRAME_STEP_MS, 3, first)
        for (i in 0 until 4) {
            atMs += DEPARTURE_STEP_MS
            lookFrameAt(pipeline, atMs)
        }

        lookFramesFrom(pipeline, atMs + FRAME_STEP_MS, 15, Look(boxOnlyWalker(NEW_PERSON_SHIFT_X_IMG), BLUE_SHIRT))

        assertThat(states.last().lockedId).isEqualTo(OverlayState.NO_LOCK)
        assertThat(pipeline.lockRelockCount).isEqualTo(0)
    }

    @Test
    fun boxOnly_changingTheModelKind_updatesTheNoteAndResetsTheTracks() {
        val pipeline = trackingPipeline()
        detector.people = listOf(boxOnlyWalker())
        framesFrom(pipeline, 0, 3)
        assertThat(states.last().tracks.count).isEqualTo(1)

        pipeline.onModelChanged(detector, imported = true, notUsed = false, boxOnly = true)

        assertThat(states.last().boxOnlyClass).isEqualTo(0)
        assertThat(states.last().tracks.count).isEqualTo(0)

        pipeline.onModelChanged(detector, imported = false, notUsed = false)

        assertThat(states.last().boxOnlyClass).isEqualTo(OverlayState.NOT_BOX_ONLY)
    }
}
