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
import com.memo494.posetracker.diagnostics.CaptureStatsHolder
import com.memo494.posetracker.modelimport.ImportedModelStore
import com.memo494.posetracker.modelimport.LoadedModel
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatusHolder
import com.memo494.posetracker.modelimport.SessionModelLoader
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
 * @param onFinished called on the main thread when the teardown is complete; its argument is `true` when the
 *   session ended because of a failure of its own (overlay refused, capture setup failed), `false` when the
 *   user or the system stopped it. A session without a detector is not a failure (the HUD shows a warning).
 */
internal class CaptureSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: TrackerConfig,
    private val onFinished: (failed: Boolean) -> Unit,
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

    @Volatile
    private var failed = false

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
    private var appliedPlan: CapturePlan? = null
    private var appliedNoUsablePlan = false

    private val applyGeometryTask = Runnable { guarded("applying the geometry") { applyGeometry() } }

    private val hudTick: Runnable =
        object : Runnable {
            override fun run() {
                guarded("HUD tick") {
                    if (teardown.isStarted) return
                    // Scheduled first so one failing tick does not end the HUD refresh for good.
                    captureHandler?.postDelayed(this, hudIntervalMs)
                    val activePipeline = pipeline
                    if (activePipeline != null) {
                        activePipeline.onHudTick()
                        CaptureStatsHolder.publish(overlayWindow.stateHolder.latest(), activePipeline)
                    }
                }
            }
        }

    private val imageListener = ImageReader.OnImageAvailableListener { reader -> onImageAvailable(reader) }

    private val displayListener: DisplayManager.DisplayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                guarded("display change") {
                    if (displayId == Display.DEFAULT_DISPLAY) applyGeometry()
                }
            }
        }

    // Runs on the capture handler.
    private val projectionCallback: MediaProjection.Callback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                guarded("projection stop callback") { stop() }
            }

            override fun onCapturedContentResize(
                width: Int,
                height: Int,
            ) {
                guarded("content resize callback") {
                    contentSizeKnown = true
                    contentWidthPx = width
                    contentHeightPx = height
                    applyGeometry()
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                guarded("content visibility callback") { pipeline?.setContentVisible(isVisible) }
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
                    TeardownStep("finish") { onFinished(failed) },
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
                failAndStop()
                return
            }
            handler.post { startOnCaptureThread() }
        } catch (e: Throwable) {
            CaptureLog.log("capture session failed to start", e)
            failAndStop()
        }
    }

    /** Ends the session. Any thread; only starts the teardown, which is idempotent and never blocks. */
    fun stop() {
        teardown.start()
    }

    /** Ends the session because of a failure of its own: [onFinished] then gets `failed = true`. Any thread. */
    private fun failAndStop() {
        failed = true
        stop()
    }

    /** The configuration (rotation, size) changed. Main thread; the work happens on the capture thread. */
    fun onConfigurationChanged() {
        captureHandler?.post(applyGeometryTask)
    }

    /** Capture thread. Any failure ends the session instead of reaching the `HandlerThread`. */
    private fun startOnCaptureThread() {
        try {
            startCapture()
        } catch (e: Throwable) {
            CaptureLog.log("starting the capture failed", e)
            failAndStop()
        }
    }

    private fun startCapture() {
        if (teardown.isStarted) return
        val loaded = loadModel()
        val newDetector = loaded.detector
        detector = newDetector
        ModelStatusHolder.publish(loaded.status())
        CaptureLog.log(loaded.logLine(), null)
        pipeline =
            FramePipeline(
                newDetector,
                config,
                NanoClock { System.nanoTime() },
                overlayWindow.stateHolder::publish,
                CaptureLog::log,
                modelImported = loaded.source == ModelSource.IMPORTED,
                importedModelNotUsed = loaded.importedModelNotUsed,
            )
        displayManager?.registerDisplayListener(displayListener, captureHandler)
        applyGeometry()
        captureHandler?.postDelayed(hudTick, hudIntervalMs)
    }

    /**
     * Capture thread. Chooses and loads the model of this session: the imported one when it is present and not
     * blocked, else the built-in one (see [SessionModelLoader]). A session without a detector is not fatal: the HUD
     * shows DETECTOR_UNAVAILABLE and frames are still drained. The detector belongs to this thread and is closed
     * by the teardown.
     */
    private fun loadModel(): LoadedModel {
        val store = ImportedModelStore(context)
        return SessionModelLoader(
            readState = store::state,
            setGuard = store::setGuard,
            loadImported = { meta -> PoseDetectors.fromImported(context, store.modelFileFor(meta), meta, config) },
            loadBuiltIn = { PoseDetectors.fromAssets(context, config) },
            log = CaptureLog::log,
            verifyStored = store::storedFileMatches,
        ).load()
    }

    /**
     * Capture thread. Brings the reader and the virtual display in line with the display and content size.
     * Does nothing (and logs nothing) when the plan equals the last applied one: `onDisplayChanged` fires for
     * many unrelated display changes. A changed plan is logged in one line (the geometry history for the
     * diagnostics zip).
     */
    private fun applyGeometry() {
        val activePipeline = pipeline
        if (teardown.isStarted || activePipeline == null) return
        val displayPx = displaySizePx()
        val contentWidth = if (contentSizeKnown) contentWidthPx else displayPx.x
        val contentHeight = if (contentSizeKnown) contentHeightPx else displayPx.y
        val plan = CapturePlanner.plan(displayPx.x, displayPx.y, contentWidth, contentHeight, config.captureScale)
        if (plan == null) {
            if (appliedNoUsablePlan) return
            appliedNoUsablePlan = true
            appliedPlan = null
            activePipeline.setExpectedSize(null)
            CaptureLog.log("no usable capture size: display $displayPx, content ${contentWidth}x$contentHeight", null)
            return
        }
        if (!appliedNoUsablePlan && plan == appliedPlan) return
        val contentText = if (contentSizeKnown) "${contentWidth}x$contentHeight" else "unknown"
        CaptureLog.log(
            "capture geometry: display ${displayPx.x}x${displayPx.y}, content $contentText, " +
                "capture ${plan.size.widthImg}x${plan.size.heightImg}, displaySized ${plan.displaySized}",
            null,
        )
        if (plan.size != currentSize) {
            try {
                createOrResize(plan.size)
            } catch (e: Throwable) {
                CaptureLog.log("creating or resizing the capture failed", e)
                failAndStop()
                return
            }
        }
        appliedPlan = plan
        appliedNoUsablePlan = false
        activePipeline.setDisplaySizePx(displayPx.x, displayPx.y)
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
        guarded("image callback") {
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
    }

    /**
     * Runs [block] and contains every failure, `Error`s such as `OutOfMemoryError` included: an exception that
     * reaches the capture `HandlerThread` kills the process. Inline, so no lambda object is made per call; the
     * message is built only when [block] failed.
     */
    private inline fun guarded(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            CaptureLog.log("$what failed", e)
        }
    }

    /** The newest image, or `null` when there is none or the reader is closed or exhausted. */
    private fun acquireOrNull(reader: ImageReader): Image? =
        try {
            reader.acquireLatestImage()
        } catch (e: Throwable) {
            null
        }

    private fun closeQuietly(image: Image) {
        try {
            image.close()
        } catch (e: Throwable) {
            CaptureLog.log("closing the image failed", e)
        }
    }

    private companion object {
        const val THREAD_NAME = "pose-capture"
        const val VIRTUAL_DISPLAY_NAME = "pose-capture"
    }
}
