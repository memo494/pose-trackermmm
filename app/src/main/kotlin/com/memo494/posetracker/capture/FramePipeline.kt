package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.capture.FrameTimestamp
import com.memo494.posetracker.core.capture.RowCopy
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
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
 * @param detector the pose detector, or `null` when it could not be created: frames are still drained and
 *   closed and the HUD shows [OverlayWarning.DETECTOR_UNAVAILABLE].
 * @param config source of the timestamp guard.
 * @param clock `System.nanoTime()` source.
 * @param publisher receives every new state (the overlay's holder); may throw, the pipeline contains it.
 * @param logger receives a message and the failure, if any; may throw, the pipeline contains it.
 */
internal class FramePipeline(
    private val detector: PoseDetector?,
    private val config: TrackerConfig,
    private val clock: NanoClock,
    private val publisher: (OverlayState) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val captureFrame = CaptureFrame()
    private val fpsMeter = FpsMeter()
    private var expectedSize: CaptureSize? = null
    private var buffer: ByteArray? = null
    private var contentVisible = true
    private var displaySized = true
    private var lastState = OverlayState.EMPTY
    private var imageClosed = false
    private var failureStreak = 0L

    /** Frames that went through detection and were published. */
    @Volatile
    var frames: Long = 0L
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
     */
    fun setExpectedSize(size: CaptureSize?) {
        expectedSize = size
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
                warning = null,
            ),
        )
        frames++
        failureStreak = 0L
    }

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
            ),
        )
    }

    /** The fail-closed warning for the current flags; a missing detector wins over a wrong capture size. */
    private fun currentWarning(): OverlayWarning? =
        when {
            detector == null -> OverlayWarning.DETECTOR_UNAVAILABLE
            !displaySized -> OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED
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
