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
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.modelimport.BuiltInModels
import com.memo494.posetracker.core.modelimport.ModelOrientation
import com.memo494.posetracker.core.modelpack.ModelEntry
import com.memo494.posetracker.core.modelpack.ModelInfo
import com.memo494.posetracker.core.stats.ThermalSampler
import com.memo494.posetracker.core.stats.ThermalStepDown
import com.memo494.posetracker.detector.PoseDetector
import com.memo494.posetracker.detector.PoseDetectors
import com.memo494.posetracker.diagnostics.CaptureStatsHolder
import com.memo494.posetracker.modelimport.ModelFallback
import com.memo494.posetracker.modelimport.ModelLibraryStore
import com.memo494.posetracker.modelimport.ModelSelection
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.ModelStatusHolder
import com.memo494.posetracker.modelimport.ModelSwitcher
import com.memo494.posetracker.overlay.LockTarget
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
 *   used and closed on it only and need no lock: `pipeline` (also read on main for the lock handle, `@Volatile`),
 *   `thermalSampler`, `thermalStepDown`, `detector`, `imageReader`, `virtualDisplay`,
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
    private val lockTarget =
        object : LockTarget {
            override val hasLock: Boolean get() = pipeline?.hasLock == true

            override fun lock(id: Int) {
                pipeline?.requestLock(id)
            }

            override fun unlock() {
                pipeline?.clearLock()
            }
        }
    private val overlayWindow = OverlayWindow(context, config, lockTarget = lockTarget)
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val hudIntervalMs = TimeUnit.NANOSECONDS.toMillis(config.hudIntervalNs)
    private val clock = NanoClock { System.nanoTime() }

    @Volatile
    private var captureThread: HandlerThread? = null

    @Volatile
    private var captureHandler: Handler? = null

    @Volatile
    private var failed = false

    /**
     * The frame pipeline: created and used on the capture thread, except that the lock handle on the main thread calls
     * its thread-safe lock methods through [lockTarget] (hence `@Volatile`).
     */
    @Volatile
    private var pipeline: FramePipeline? = null

    // Capture thread only (see the class KDoc).
    private val imageFrame = ImageFrame()
    private var thermalSampler: ThermalSampler? = null
    private var thermalStepDown: ThermalStepDown? = null
    private var detector: PoseDetector? = null
    private var modelSwitcher: ModelSwitcher? = null
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
                        val thermal = thermalSampler
                        val nowNs = clock.nowNs()
                        if (thermal != null && thermal.sample(nowNs)) onThermalSample(thermal, activePipeline, nowNs)
                        CaptureStatsHolder.publish(
                            overlayWindow.stateHolder.latest(),
                            activePipeline,
                            thermal,
                            thermalStepDown,
                            nowNs,
                            modelSwitcher?.thermalSmallModel ?: "no",
                        )
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
                    TeardownStep("end the lock") { pipeline?.onStop() },
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
        val switcher = createModelSwitcher()
        modelSwitcher = switcher
        detector = null
        val selection = switcher.select(startOrientation())
        val loaded = selection?.loaded
        detector = loaded?.detector
        publishModelStatus(switcher)
        if (selection != null) {
            CaptureLog.log(selection.loaded.logLine(), null)
            CaptureLog.log(selection.logLine(), null)
        }
        pipeline =
            FramePipeline(
                loaded?.detector,
                config,
                clock,
                overlayWindow.stateHolder::publish,
                CaptureLog::log,
                modelImported = loaded?.source == ModelSource.IMPORTED,
                importedModelNotUsed = loaded?.importedModelNotUsed == true,
                builtInModelFailed = loaded?.builtInFailed == true,
                modelWrongShape = loaded?.fallback == ModelFallback.WRONG_SHAPE,
            )
        thermalSampler =
            ThermalSampler(
                PowerManagerThermalReader(context.getSystemService(PowerManager::class.java)),
                config.thermalSampleIntervalNs,
                CaptureLog::log,
            )
        thermalStepDown = ThermalStepDown(config)
        displayManager?.registerDisplayListener(displayListener, captureHandler)
        applyGeometry()
        captureHandler?.postDelayed(hudTick, hudIntervalMs)
    }

    /**
     * Capture thread. The switcher that chooses and loads the model of each orientation (M1b-1): the library model of
     * the orientation's slot when it is present and not blocked, else the built-in one (see [ModelSwitcher] and
     * [com.memo494.posetracker.modelimport.SessionModelLoader]). A session without a detector is not fatal: the HUD
     * shows DETECTOR_UNAVAILABLE and frames are still drained. The detector belongs to this thread and is closed by
     * the switcher when the model changes and by the teardown at the end.
     */
    private fun createModelSwitcher(): ModelSwitcher {
        val store = ModelLibraryStore(context, config)
        // model_info.json is read once per session (a failed read is tried again at the next use).
        val info = lazy { PoseDetectors.bundledInfo(context) }
        return ModelSwitcher(
            readLibrary = store::state,
            setGuard = store::setGuard,
            loadImported = { meta -> PoseDetectors.fromImported(context, store.modelFileFor(meta), meta, config) },
            builtInSha256 = { orientation ->
                try {
                    bundledEntry(info.value, orientation).sha256
                } catch (e: Exception) {
                    CaptureLog.log("the hash of the built-in model is unknown (model_info.json not readable or without it)", e)
                    null
                }
            },
            loadBuiltIn = {
                orientation,
                guarded,
                ->
                PoseDetectors.fromAssets(context, config, bundledEntry(info.value, orientation), guarded)
            },
            smallBuiltInSha256 = {
                try {
                    info.value.entryFor(TrackerConfig.MODEL_INPUT_SIZE_SMALL).sha256
                } catch (e: Exception) {
                    CaptureLog.log("the hash of the small model is unknown (model_info.json not readable or without it)", e)
                    null
                }
            },
            loadSmallBuiltIn = { guarded ->
                PoseDetectors.fromAssets(context, config, info.value.entryFor(TrackerConfig.MODEL_INPUT_SIZE_SMALL), guarded)
            },
            loadFallbackBuiltIn = {
                PoseDetectors.fromAssets(context, config, info.value.entryFor(config.modelInputSize))
            },
            verifyStored = store::storedFileMatches,
            nowNs = clock::nowNs,
            log = CaptureLog::log,
        )
    }

    private fun bundledEntry(
        info: ModelInfo,
        orientation: ModelOrientation,
    ): ModelEntry = info.entryFor(BuiltInModels.widthFor(orientation), BuiltInModels.heightFor(orientation))

    /** The orientation of the capture this session is about to start, from the display (the content is not known yet). */
    private fun startOrientation(): ModelOrientation {
        val displayPx = displaySizePx()
        val size = CapturePlanner.plan(displayPx.x, displayPx.y, displayPx.x, displayPx.y, config.captureScale)?.size
        return if (size != null) ModelOrientation.of(size.widthImg, size.heightImg) else ModelOrientation.of(displayPx.x, displayPx.y)
    }

    /**
     * Capture thread, once per new thermal reading (HUD tick): feeds the thermal step-down policy and, when its state
     * changed, logs one line and lets the switcher load the small model (HOT) or the slot's model again (NORMAL).
     * Allocates on a transition only.
     */
    private fun onThermalSample(
        sampler: ThermalSampler,
        activePipeline: FramePipeline,
        nowNs: Long,
    ) {
        val policy = thermalStepDown ?: return
        if (!policy.update(sampler.statusCode, sampler.headroom, nowNs)) return
        CaptureLog.log(ThermalStepDown.transitionLine(policy.state, sampler.status, sampler.headroom), null)
        modelSwitcher?.setThermalHot(policy.state == ThermalStepDown.State.HOT)
        // No capture size yet (before the first geometry): applyGeometry's switchModelFor then picks the model, hot flag included.
        val size = currentSize ?: return
        switchModelFor(size, activePipeline)
    }

    private fun publishModelStatus(switcher: ModelSwitcher) {
        ModelStatusHolder.publish(switcher.status() ?: ModelStatus.chooseFailed())
    }

    /**
     * Capture thread. Makes the model of the capture's orientation the running one (M1b-1): when the orientation of
     * [size] asks for another model than the loaded one, the switcher closes the old detector and loads the new one
     * (never stops the session; a failing model falls back to the built-in one), and the pipeline drops the old
     * model's detections and resets the tracker.
     */
    private fun switchModelFor(
        size: CaptureSize,
        activePipeline: FramePipeline,
    ) {
        val switcher = modelSwitcher ?: return
        // The switcher closes the running detector first. Nothing may keep using it, even if the switch dies half-way
        // (an Error that escapes the switcher): both holders are cleared before, and put back when nothing changed.
        val previous = detector
        detector = null
        activePipeline.holdDetector(null)
        val selection: ModelSelection? = switcher.select(ModelOrientation.of(size.widthImg, size.heightImg))
        if (selection == null) {
            detector = previous
            activePipeline.holdDetector(previous)
            return
        }
        val loaded = selection.loaded
        detector = loaded.detector
        publishModelStatus(switcher)
        CaptureLog.log(selection.logLine(), null)
        activePipeline.onModelChanged(
            loaded.detector,
            loaded.source == ModelSource.IMPORTED,
            loaded.importedModelNotUsed,
            loaded.builtInFailed,
            thermalStepDown = selection.thermal,
            wrongShape = loaded.fallback == ModelFallback.WRONG_SHAPE,
        )
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
        switchModelFor(plan.size, activePipeline)
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
        val newReader = ImageReader.newInstance(widthImg, heightImg, PixelFormat.RGBA_8888, TrackerConfig.CAPTURE_MAX_IMAGES)
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
