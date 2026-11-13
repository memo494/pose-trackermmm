package com.memo494.posetracker.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.detector.PoseDetector
import com.memo494.posetracker.detector.PoseDetectors
import com.memo494.posetracker.overlay.OverlayWindow
import java.util.concurrent.TimeUnit

/**
 * One screen-capture session: the `MediaProjection`, the overlay window and the capture thread with
 * everything that lives on it. Glue only: every decision is made by the tested classes
 * ([CapturePlanner], [FramePipeline], [CaptureTeardown]).
 *
 * Threads (ARCHITECTURE §5):
 * - **main**: [start], [onConfigurationChanged], the overlay window ([overlayWindow], created in the
 *   constructor and used on main only) and the first and last teardown phases. [stop] may be called from
 *   any thread.
 * - **capture** (`pose-capture` [HandlerThread], created in [start]): the `ImageReader` listener, the
 *   projection callback, the display listener and the HUD tick all run on it. These fields are created,
 *   used and closed on it only and need no lock: `pipeline`, `detector`, `imageReader`, `virtualDisplay`,
 *   `currentSize`, `contentSizeKnown`, `contentWidthPx`, `contentHeightPx`, `imageFrame`.
 * - **shared**: `captureThread` and `captureHandler` are written once by [start] on main and read by the
 *   teardown from any thread (`@Volatile`); `teardown` is thread-safe by itself.
 *
 * Teardown (starts once, never blocks main; see [CaptureTeardown]):
 * 1. main: remove the overlay;
 * 2. capture: stop the HUD tick, unregister the display listener, clear the image listener, release the
 *    virtual display, close the reader, close the detector;
 * 3. main: unregister the projection callback, stop the projection, quit the capture thread, then
 *    [onFinished].
 *
 * @param context the service; it adds the overlay window and is the source for the detector's assets.
 * @param projection the consented projection; this session stops it.
 * @param config settings (defaults for now).
 * @param onFinished called on the main thread when the teardown is complete.
 */
internal class CaptureSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: TrackerConfig,
    private val onFinished: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayWindow = OverlayWindow(context, config)
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val hudIntervalMs = TimeUnit.NANOSECONDS.toMillis(config.hudIntervalNs)

    @Volatile
    private var captureThread: HandlerThread? = null

    @Volatile
    private var captureHandler: Handler? = null

    // Capture thread only (see the class KDoc).
    private val imageFrame = ImageFrame()
    private var pipeline: FramePipeline? = null
    private var detector: PoseDetector? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentSize: CaptureSize? = null
    private var contentSizeKnown = false
    private var contentWidthPx = 0
    private var contentHeightPx = 0

    private val applyGeometryTask = Runnable { applyGeometry() }

    private val hudTick: Runnable =
        object : Runnable {
            override fun run() {
                if (teardown.isStarted) return
                pipeline?.onHudTick()
                captureHandler?.postDelayed(this, hudIntervalMs)
            }
        }

    private val imageListener = ImageReader.OnImageAvailableListener { reader -> onImageAvailable(reader) }

    private val displayListener: DisplayManager.DisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) applyGeometry()
            }
        }

    // Runs on the capture handler.
    private val projectionCallback: MediaProjection.Callback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }

            override fun onCapturedContentResize(
                width: Int,
                height: Int,
            ) {
                contentSizeKnown = true
                contentWidthPx = width
                contentHeightPx = height
                applyGeometry()
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                pipeline?.setContentVisible(isVisible)
            }
        }

    private val teardown: CaptureTeardown =
        CaptureTeardown(
            mainFirst = listOf(TeardownStep("remove overlay") { overlayWindow.remove() }),
            onCapture =
                listOf(
                    TeardownStep("stop HUD tick") { captureHandler?.removeCallbacks(hudTick) },
                    TeardownStep("unregister display listener") {
                        displayManager?.unregisterDisplayListener(displayListener)
                    },
                    TeardownStep("clear image listener") { imageReader?.setOnImageAvailableListener(null, null) },
                    TeardownStep("release virtual display") { virtualDisplay?.release() },
                    TeardownStep("close image reader") { imageReader?.close() },
                    TeardownStep("close detector") { detector?.close() },
                ),
            mainLast =
                listOf(
                    TeardownStep("unregister projection callback") {
                        projection.unregisterCallback(projectionCallback)
                    },
                    TeardownStep("stop projection") { projection.stop() },
                    TeardownStep("quit capture thread") { captureThread?.quitSafely() },
                    TeardownStep("finish") { onFinished() },
                ),
            postToMain = { runnable -> mainHandler.post(runnable) },
            postToCapture = { runnable -> captureHandler?.post(runnable) ?: false },
            isMainThread = { Looper.myLooper() === Looper.getMainLooper() },
            logger = CaptureLog::log,
        )

    /**
     * Starts the session. Main thread. The projection callback is registered before any virtual display
     * exists (required since Android 14); a refused overlay or any failure ends the session.
     */
    fun start() {
        try {
            val thread = HandlerThread(THREAD_NAME)
            thread.start()
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler
            projection.registerCallback(projectionCallback, handler)
            if (!overlayWindow.show()) {
                CaptureLog.log("overlay refused (permission revoked?), ending the session", null)
                stop()
                return
            }
            handler.post { startOnCaptureThread() }
        } catch (e: Throwable) {
            CaptureLog.log("capture session failed to start", e)
            stop()
        }
    }

    /** Ends the session. Any thread; only starts the teardown, which is idempotent and never blocks. */
    fun stop() {
        teardown.start()
    }

    /** The configuration (rotation, size) changed. Main thread; the work happens on the capture thread. */
    fun onConfigurationChanged() {
        captureHandler?.post(applyGeometryTask)
    }

    private fun startOnCaptureThread() {
        if (teardown.isStarted) return
        val newDetector =
            try {
                PoseDetectors.fromAssets(context, config)
            } catch (e: Throwable) {
                // No detector is not fatal: the HUD shows DETECTOR_UNAVAILABLE and frames are still drained.
                CaptureLog.log("detector unavailable", e)
                null
            }
        detector = newDetector
        pipeline =
            FramePipeline(
                newDetector,
                config,
                NanoClock { System.nanoTime() },
                overlayWindow.stateHolder::publish,
                CaptureLog::log,
            )
        displayManager?.registerDisplayListener(displayListener, captureHandler)
        applyGeometry()
        captureHandler?.postDelayed(hudTick, hudIntervalMs)
    }

    /** Capture thread. Brings the reader and the virtual display in line with the display and content size. */
    private fun applyGeometry() {
        val activePipeline = pipeline
        if (teardown.isStarted || activePipeline == null) return
        val displayPx = displaySizePx()
        val contentWidth = if (contentSizeKnown) contentWidthPx else displayPx.x
        val contentHeight = if (contentSizeKnown) contentHeightPx else displayPx.y
        val plan = CapturePlanner.plan(displayPx.x, displayPx.y, contentWidth, contentHeight, config.captureScale)
        if (plan == null) {
            activePipeline.setExpectedSize(null)
            CaptureLog.log("no usable capture size: display $displayPx, content ${contentWidth}x$contentHeight", null)
            return
        }
        if (plan.size != currentSize) {
            try {
                createOrResize(plan.size)
            } catch (e: Throwable) {
                CaptureLog.log("creating or resizing the capture failed", e)
                stop()
                return
            }
        }
        activePipeline.setExpectedSize(plan.size)
        activePipeline.setDisplaySized(plan.displaySized)
    }

    /** Size of the display in its current rotation, pixels; (0, 0) when it cannot be read (the planner declines). */
    private fun displaySizePx(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.maximumWindowMetrics?.bounds ?: return Point()
            return Point(bounds.width(), bounds.height())
        }
        return realSizePx()
    }

    @Suppress("DEPRECATION") // Display.getRealSize is the only way below API 30; deprecated from API 31
    private fun realSizePx(): Point {
        val size = Point()
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        return size
    }

    /**
     * Capture thread. Creates the reader (and, the first time only, the virtual display) for [size], or
     * moves the existing virtual display to a new reader and closes the old reader. A second virtual
     * display is never created.
     */
    @SuppressLint("WrongConstant") // ImageReader accepts PixelFormat.RGBA_8888; lint only knows ImageFormat constants
    private fun createOrResize(size: CaptureSize) {
        val widthImg = size.widthImg
        val heightImg = size.heightImg
        val densityDpi = context.resources.displayMetrics.densityDpi
        val newReader =
            ImageReader.newInstance(widthImg, heightImg, PixelFormat.RGBA_8888, TrackerConfig.CAPTURE_MAX_IMAGES)
        try {
            newReader.setOnImageAvailableListener(imageListener, captureHandler)
            val display = virtualDisplay
            if (display == null) {
                val created =
                    projection.createVirtualDisplay(
                        VIRTUAL_DISPLAY_NAME,
                        widthImg,
                        heightImg,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newReader.surface,
                        null,
                        captureHandler,
                    )
                virtualDisplay = created ?: error("createVirtualDisplay returned null")
            } else {
                display.resize(widthImg, heightImg, densityDpi)
                display.surface = newReader.surface
            }
        } catch (e: Throwable) {
            newReader.close()
            throw e
        }
        val oldReader = imageReader
        imageReader = newReader
        currentSize = size
        oldReader?.close()
    }

    /** Capture thread. Hands the newest image to the pipeline, which closes it. */
    private fun onImageAvailable(reader: ImageReader) {
        val activePipeline = pipeline ?: return
        val image = acquireOrNull(reader) ?: return
        val acquireNs = System.nanoTime()
        try {
            imageFrame.attach(image)
        } catch (e: Throwable) {
            CaptureLog.log("reading the image failed", e)
            closeQuietly(image)
            return
        }
        activePipeline.onFrame(imageFrame, acquireNs)
    }

    /** The newest image, or `null` when there is none or the reader is closed or exhausted. */
    private fun acquireOrNull(reader: ImageReader): Image? =
        try {
            reader.acquireLatestImage()
        } catch (e: RuntimeException) {
            null
        }

    private fun closeQuietly(image: Image) {
        try {
            image.close()
        } catch (e: RuntimeException) {
            CaptureLog.log("closing the image failed", e)
        }
    }

    private companion object {
        const val THREAD_NAME = "pose-capture"
        const val VIRTUAL_DISPLAY_NAME = "pose-capture"
    }
}
