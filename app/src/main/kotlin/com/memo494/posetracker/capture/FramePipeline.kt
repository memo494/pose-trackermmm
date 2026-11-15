package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.capture.FrameTimestamp
import com.memo494.posetracker.core.capture.RowCopy
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.core.stats.TimingWindow
import com.memo494.posetracker.detector.PoseDetector
import com.memo494.posetracker.overlay.OverlayState

/**
 * Per-frame work of the screen capture, free of `android.*`: size check, row copy, timestamp, image
 * close, detection, fps and the published [OverlayState].
 *
 * Thread-confined to the capture thread (documented, not enforced): every method must be called from
 * that thread; only the read-only counters may be read from elsewhere.
 *
 * [onFrame] never throws and closes every image exactly once, whatever happens (including an
 * `OutOfMemoryError`), so a bad frame can never crash the service or leak an image that would stall the
 * `ImageReader`. Our own per-frame allocations are the published [OverlayState] only (pooled in M1).
 *
 * Timing: every **processed** frame (detected and published) adds one sample to each of six [TimingWindow]s:
 * copy, pre, inference, post, whole frame (acquire to publish) and result age (publish time minus the image
 * timestamp, only when that stamp was accepted). Dropped and drained frames add nothing. The windows have one
 * owner, the capture thread; [timingSummary] computes the percentiles on that thread (at the HUD tick) and returns
 * an immutable snapshot, so no other thread touches a window.
 *
 * @param detector the pose detector, or `null` when it could not be created: frames are still drained and
 *   closed and the HUD shows [OverlayWarning.DETECTOR_UNAVAILABLE].
 * @param config source of the timestamp guard.
 * @param clock `System.nanoTime()` source.
 * @param publisher receives every new state (the overlay's holder); may throw, the pipeline contains it.
 * @param logger receives a message and the failure, if any; may throw, the pipeline contains it.
 * @param modelImported whether [detector] runs the owner's imported model; every state carries it for the HUD.
 * @param importedModelNotUsed whether an imported model exists that this session does not use (a fallback to the
 *   built-in one); it adds the note [OverlayWarning.IMPORTED_MODEL_NOT_USED] with the lowest priority.
 */
internal class FramePipeline(
    private val detector: PoseDetector?,
    private val config: TrackerConfig,
    private val clock: NanoClock,
    private val publisher: (OverlayState) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
    private val modelImported: Boolean = false,
    private val importedModelNotUsed: Boolean = false,
) {
    private val captureFrame = CaptureFrame()
    private val fpsMeter = FpsMeter()
    private var expectedSize: CaptureSize? = null
    private var displayWidthPx = 0
    private var displayHeightPx = 0
    private var buffer: ByteArray? = null
    private var contentVisible = true
    private var displaySized = true
    private val modelInputWidth = detector?.inputWidth ?: 0
    private val modelInputHeight = detector?.inputHeight ?: 0
    private var lastState =
        OverlayState(modelInputWidth = modelInputWidth, modelInputHeight = modelInputHeight, modelImported = modelImported)
    private var imageClosed = false
    private var failureStreak = 0L
    private val copyWindow = TimingWindow(config.timingWindowFrames)
    private val preWindow = TimingWindow(config.timingWindowFrames)
    private val inferWindow = TimingWindow(config.timingWindowFrames)
    private val postWindow = TimingWindow(config.timingWindowFrames)
    private val frameWindow = TimingWindow(config.timingWindowFrames)
    private val resultAgeWindow = TimingWindow(config.timingWindowFrames)

    /** Frames that went through detection and were published. */
    @Volatile
    var frames: Long = 0L
        private set

    /** Frames whose detection returned at least one person. */
    @Volatile
    var framesWithDetections: Long = 0L
        private set

    /** People in the newest detected frame, count; [OverlayState.NOT_MEASURED_COUNT] before the first. */
    @Volatile
    var lastDetections: Int = OverlayState.NOT_MEASURED_COUNT
        private set

    /** Highest [OverlayState.maxRawScore] seen in this session; negative = never measured. */
    @Volatile
    var peakMaxRawScore: Float = OverlayState.NOT_MEASURED_SCORE
        private set

    /** Model output rows with a NaN score in the newest frame (garbage output), count; negative = not measured. */
    @Volatile
    var lastNanRawRows: Int = OverlayState.NOT_MEASURED_COUNT
        private set

    /** Head point of the first person of the newest frame with people, Img pixels; NaN = none yet. */
    @Volatile
    var lastHeadXImg: Float = Float.NaN
        private set

    /** Head point y, see [lastHeadXImg]. */
    @Volatile
    var lastHeadYImg: Float = Float.NaN
        private set

    /** Frames dropped because their size was not the expected capture size (or none was set). */
    @Volatile
    var droppedSizeMismatch: Long = 0L
        private set

    /** Frames dropped because the pixel copy failed (bad strides or buffer). */
    @Volatile
    var copyFailures: Long = 0L
        private set

    /** Frames whose image stamp failed the guard, so the arrival time was used. */
    @Volatile
    var arrivalFallbacks: Long = 0L
        private set

    /** Frames that ended with a thrown [Throwable] (contained and logged). */
    @Volatile
    var failures: Long = 0L
        private set

    /**
     * Sets the size every frame must have (Img pixels). `null` = unknown: every frame is dropped as a
     * size mismatch (fail closed). The copy buffer is replaced by the next frame of a new size.
     * Leaving a known size (rotation) republishes the state at once without its detections, which are
     * positions of the old size and must never be mapped with the new geometry.
     */
    fun setExpectedSize(size: CaptureSize?) {
        val previous = expectedSize
        expectedSize = size
        if (previous != null && previous != size) republish(dropFrame = true)
    }

    /**
     * Sets the display size, in pixels, that the capture size was planned for. Every published state carries
     * it and the overlay maps Img to View with it, so the mapping always belongs to the same rotation as the
     * capture size. Leaving a known size drops the old detections like [setExpectedSize] does. Call it before
     * [setExpectedSize] when both change.
     */
    fun setDisplaySizePx(
        widthPx: Int,
        heightPx: Int,
    ) {
        val wasKnown = displayWidthPx > 0 && displayHeightPx > 0
        val changed = widthPx != displayWidthPx || heightPx != displayHeightPx
        displayWidthPx = widthPx
        displayHeightPx = heightPx
        if (wasKnown && changed) republish(dropFrame = true)
    }

    /** Stores whether the captured content is visible and republishes the state at once. */
    fun setContentVisible(visible: Boolean) {
        contentVisible = visible
        republish(dropFrame = false)
    }

    /**
     * Stores whether the capture is display-sized and republishes the state at once. A change drops the
     * old detections from the state, so detections of the other size can never be shown again.
     */
    fun setDisplaySized(displaySized: Boolean) {
        val changed = this.displaySized != displaySized
        this.displaySized = displaySized
        republish(dropFrame = changed)
    }

    /**
     * Handles one captured image. Never throws. Closes [image] exactly once: right after the pixels were
     * copied (before inference), or in the `finally` when an earlier step ended the frame.
     *
     * @param acquireNs arrival time of the image (System.nanoTime(), nanoseconds): the fallback frame
     *   time and the time recorded for the fps.
     */
    fun onFrame(
        image: FrameImage,
        acquireNs: Long,
    ) {
        imageClosed = false
        try {
            process(image, acquireNs)
        } catch (e: Throwable) {
            onFailure(e)
        } finally {
            if (!imageClosed) closeImage(image)
        }
    }

    /**
     * Republishes the last state with the fps of now, so a static screen (no frames) decays to about 0
     * fps. Called at the HUD rate. Never throws.
     */
    fun onHudTick() {
        try {
            publishSafely(lastState.copy(captureFps = fpsMeter.fps(clock.nowNs())))
        } catch (e: Throwable) {
            safeLog("HUD tick failed", e)
        }
    }

    /**
     * The percentiles of the timing windows as an immutable snapshot. Capture thread only (the windows are not
     * thread-safe); called at the HUD tick, not per frame. Allocates the snapshot, so never call it per frame.
     */
    fun timingSummary(): TimingSummary =
        TimingSummary(
            samples = frameWindow.count,
            copyMs = Percentiles.of(copyWindow),
            preMs = Percentiles.of(preWindow),
            inferMs = Percentiles.of(inferWindow),
            postMs = Percentiles.of(postWindow),
            frameMs = Percentiles.of(frameWindow),
            resultAgeMs = Percentiles.of(resultAgeWindow),
        )

    private fun process(
        image: FrameImage,
        acquireNs: Long,
    ) {
        val widthImg = image.widthImg
        val heightImg = image.heightImg
        val expected = expectedSize
        if (expected == null || expected.widthImg != widthImg || expected.heightImg != heightImg) {
            droppedSizeMismatch++
            return
        }
        val detector = detector
        if (detector == null || !displaySized) {
            publishWithoutDetections(widthImg, heightImg, acquireNs)
            return
        }
        val copyStartNs = clock.nowNs()
        val rgba = RowCopy.ensureBuffer(buffer, widthImg, heightImg)
        buffer = rgba
        if (!RowCopy.copyRgba(image.pixels, widthImg, heightImg, image.rowStride, image.pixelStride, rgba)) {
            copyFailures++
            return
        }
        val nowNs = clock.nowNs()
        val guardNs = config.timestampGuardNs
        val timestampNs = FrameTimestamp.resolveNs(image.timestampNs, nowNs, acquireNs, guardNs)
        val source = FrameTimestamp.sourceOf(image.timestampNs, nowNs, guardNs)
        if (source == TimestampSource.ARRIVAL) arrivalFallbacks++
        closeImage(image)
        if (!captureFrame.set(rgba, widthImg, heightImg, timestampNs, source)) error("capture frame rejected the copy")
        val result = detector.detect(captureFrame)
        fpsMeter.onFrame(acquireNs)
        val maxRawScore = detector.lastMaxRawScore
        recordDetections(result, maxRawScore, detector.lastNanRawRows)
        publish(
            OverlayState(
                frame = result,
                accelerator = detector.accelerator,
                captureFps = fpsMeter.fps(nowNs),
                inferenceMs = detector.lastInferenceMs,
                captureWidthImg = widthImg,
                captureHeightImg = heightImg,
                timestampSource = source,
                dotsVisible = contentVisible,
                warning = currentWarning(),
                displayWidthPx = displayWidthPx,
                displayHeightPx = displayHeightPx,
                maxRawScore = maxRawScore,
                rawRowsAboveThreshold = detector.lastRawRowsAboveThreshold,
                modelInputWidth = modelInputWidth,
                modelInputHeight = modelInputHeight,
                modelImported = modelImported,
            ),
        )
        recordTimings(detector, copyStartNs, nowNs, acquireNs, timestampNs, source == TimestampSource.IMAGE)
        frames++
        failureStreak = 0L
    }

    /**
     * Adds one sample per window for the frame that was just published. Allocation-free (primitives only).
     * [copyEndNs] is the time the copy ended; the whole frame ends now. [timestampNs] is the frame's timestamp and
     * [timestampIsImage] says it is the accepted image timestamp (not the arrival time): only then there is a
     * result age. No nullable `Long`, which would box.
     */
    private fun recordTimings(
        detector: PoseDetector,
        copyStartNs: Long,
        copyEndNs: Long,
        acquireNs: Long,
        timestampNs: Long,
        timestampIsImage: Boolean,
    ) {
        val publishedNs = clock.nowNs()
        copyWindow.add(msOf(copyEndNs - copyStartNs))
        preWindow.add(detector.lastPreMs)
        inferWindow.add(detector.lastInferenceMs)
        postWindow.add(detector.lastPostMs)
        frameWindow.add(msOf(publishedNs - acquireNs))
        if (timestampIsImage) resultAgeWindow.add(msOf(publishedNs - timestampNs))
    }

    private fun msOf(durationNs: Long): Float = durationNs.toFloat() / TrackerConfig.NANOS_PER_MILLI

    private fun publishWithoutDetections(
        widthImg: Int,
        heightImg: Int,
        acquireNs: Long,
    ) {
        fpsMeter.onFrame(acquireNs)
        publish(
            OverlayState(
                frame = null,
                accelerator = detector?.accelerator,
                captureFps = fpsMeter.fps(clock.nowNs()),
                inferenceMs = 0f,
                captureWidthImg = widthImg,
                captureHeightImg = heightImg,
                timestampSource = null,
                dotsVisible = contentVisible,
                warning = currentWarning(),
                displayWidthPx = displayWidthPx,
                displayHeightPx = displayHeightPx,
                modelInputWidth = modelInputWidth,
                modelInputHeight = modelInputHeight,
                modelImported = modelImported,
            ),
        )
    }

    /** Updates the detection counters from one detected frame. Allocation-free. */
    private fun recordDetections(
        result: DetectionFrame,
        maxRawScore: Float,
        nanRawRows: Int,
    ) {
        val people = result.detections.size
        lastDetections = people
        lastNanRawRows = nanRawRows
        if (maxRawScore > peakMaxRawScore) peakMaxRawScore = maxRawScore
        if (people == 0) return
        framesWithDetections++
        val first = result.detections[0]
        lastHeadXImg = first.headXImg(config.keypointMinScore)
        lastHeadYImg = first.headYImg(config.keypointMinScore)
    }

    /**
     * The warning for the current flags: a missing detector wins over a wrong capture size, which wins over the
     * note that an imported model is not used (that one does not hide the detections).
     */
    private fun currentWarning(): OverlayWarning? =
        when {
            detector == null -> OverlayWarning.DETECTOR_UNAVAILABLE
            !displaySized -> OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED
            importedModelNotUsed -> OverlayWarning.IMPORTED_MODEL_NOT_USED
            else -> null
        }

    private fun republish(dropFrame: Boolean) {
        try {
            val base = lastState
            publishSafely(
                base.copy(
                    frame = if (dropFrame) null else base.frame,
                    dotsVisible = contentVisible,
                    warning = currentWarning(),
                    displayWidthPx = displayWidthPx,
                    displayHeightPx = displayHeightPx,
                ),
            )
        } catch (e: Throwable) {
            safeLog("republishing the overlay state failed", e)
        }
    }

    private fun publish(state: OverlayState) {
        lastState = state
        publisher(state)
    }

    private fun publishSafely(state: OverlayState) {
        try {
            publish(state)
        } catch (e: Throwable) {
            safeLog("publishing the overlay state failed", e)
        }
    }

    private fun closeImage(image: FrameImage) {
        imageClosed = true
        try {
            image.close()
        } catch (e: Throwable) {
            safeLog("closing the image failed", e)
        }
    }

    /** Counts the failure and logs the first of a streak and then every `FAILURE_LOG_INTERVAL`-th. */
    private fun onFailure(error: Throwable) {
        failures++
        failureStreak++
        if (failureStreak == 1L || failureStreak % TrackerConfig.FAILURE_LOG_INTERVAL == 0L) {
            safeLog("frame failed (failure $failureStreak in a row, $failures in total)", error)
        }
    }

    private fun safeLog(
        message: String,
        error: Throwable?,
    ) {
        try {
            logger(message, error)
        } catch (e: Throwable) {
            // A failing logger must not break the frame loop; there is nowhere left to report it.
        }
    }
}
