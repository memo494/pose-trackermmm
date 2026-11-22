package com.memo494.posetracker.capture

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.capture.CaptureSize
import com.memo494.posetracker.core.capture.FrameTimestamp
import com.memo494.posetracker.core.capture.RowCopy
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.core.overlay.RedrawPolicy
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.core.stats.TimingSummary
import com.memo494.posetracker.core.stats.TimingWindow
import com.memo494.posetracker.core.tracking.HeadSmoother
import com.memo494.posetracker.core.tracking.TrackList
import com.memo494.posetracker.core.tracking.TrackSnapshot
import com.memo494.posetracker.core.tracking.Tracker
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
 * `ImageReader`. Our own per-frame allocations are the published [OverlayState] and the [TrackSnapshot] in it.
 *
 * Tracking (M3b): the pipeline owns one [Tracker] (so one per Start), touched on the capture thread only. A frame
 * the detector saw goes to `Tracker.update` and the shown tracks are copied into an immutable [TrackSnapshot] at
 * once, before any other tracker call; the snapshot is published in [OverlayState.tracks]. A frame the detector
 * did not see (no detector, not display-sized, size mismatch, failed copy) is never given to the tracker: no
 * information is not "nobody there". Whenever the pipeline drops the detections from the state (a new capture or
 * display size), the tracker is reset and the state carries no tracks, so positions of an old geometry are never
 * mapped with a new one. The HUD tick tells the tracker how long ago the newest tracked frame was published
 * (`Tracker.onIdle`; copy and inference time are not idle time), so a still picture still gets its marks and a
 * track that stays lost is removed.
 *
 * Smoothing (M4a): the snapshot of a frame carries head points smoothed by a [HeadSmoother] (a OneEuro filter per
 * track, run with the frame's timestamp, the one the tracker used) and boxes moved by the same delta. Every tracker
 * reset also resets the smoother. The idle republish never advances it: it reuses the last smoothed point of a track
 * the smoother knows and passes a newly promoted track through unsmoothed.
 *
 * Lock (M4b, M4c): [requestLock] and [clearLock] may be called from the UI thread; the [LockKeeper] holds the lock
 * ([OverlayState.NO_LOCK] = none). Every state the pipeline publishes carries it only while that id is among the
 * state's shown tracks; an id that never was shown is dropped, a reset ends the lock, and an id is never given to
 * another track. While the locked person is tracked, the colours of their torso are kept as a signature (pixels read
 * right after `detect`, on the capture thread); when the track is removed the lock waits (pending, nothing drawn) and
 * moves to a track that appears later only if its colours match clearly and unambiguously ([LockKeeper]).
 *
 * Timing: every **processed** frame (detected and published) adds one sample to each of six [TimingWindow]s:
 * copy, pre, inference, post, whole frame (acquire to publish) and result age (publish time minus the image
 * timestamp, only when that stamp was accepted). Dropped and drained frames add nothing. The windows have one
 * owner, the capture thread; [timingSummary] computes the percentiles on that thread (at the HUD tick) and returns
 * an immutable snapshot, so no other thread touches a window.
 *
 * @param detector the pose detector, or `null` when it could not be created: frames are still drained and
 *   closed and the HUD shows [OverlayWarning.DETECTOR_UNAVAILABLE]. [onModelChanged] replaces it when the capture
 *   changes orientation and the orientation's model differs.
 * @param config source of the timestamp guard.
 * @param clock `System.nanoTime()` source.
 * @param publisher receives every new state (the overlay's holder); may throw, the pipeline contains it.
 * @param logger receives a message and the failure, if any; may throw, the pipeline contains it.
 * @param modelImported whether [detector] runs the owner's imported model; every state carries it for the HUD.
 * @param importedModelNotUsed whether an imported model exists that this session does not use (a fallback to the
 *   built-in one); it adds the note [OverlayWarning.IMPORTED_MODEL_NOT_USED] with the lowest priority.
 */
internal class FramePipeline(
    detector: PoseDetector?,
    private val config: TrackerConfig,
    private val clock: NanoClock,
    private val publisher: (OverlayState) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
    modelImported: Boolean = false,
    importedModelNotUsed: Boolean = false,
) {
    // The model can change when the orientation changes (M1b-1): see onModelChanged. Capture thread only.
    private var detector: PoseDetector? = detector
    private var modelImported: Boolean = modelImported
    private var importedModelNotUsed: Boolean = importedModelNotUsed

    private val captureFrame = CaptureFrame()
    private val fpsMeter = FpsMeter()
    private var expectedSize: CaptureSize? = null
    private var displayWidthPx = 0
    private var displayHeightPx = 0
    private var buffer: ByteArray? = null
    private var contentVisible = true
    private var displaySized = true
    private var modelInputWidth = detector?.inputWidth ?: 0
    private var modelInputHeight = detector?.inputHeight ?: 0
    private var lastState =
        OverlayState(modelInputWidth = modelInputWidth, modelInputHeight = modelInputHeight, modelImported = modelImported)
    private val tracker = Tracker(config)
    private val smoother = HeadSmoother(config)

    /** Number of ids the display smoother holds (read-only, for tests). */
    internal val smootherActiveCount: Int
        get() = smoother.activeCount
    private var trackedFrameNewest = false
    private val lockKeeper = LockKeeper(config) { message -> safeLog(message, null) }
    private val missLog = AssociationMissLog(config.assocMissLogPerMin) { message -> safeLog(message, null) }

    /** Clock reading after the newest tracked frame was published (not its arrival): idle time starts here. */
    private var trackedArrivalNs = 0L
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
     * Tracker counters since this pipeline (so this Start) began; a reset of the tracker keeps them. Plain reads of
     * the [Tracker]'s fields: it is written and these are read on the capture thread only (the HUD tick that
     * publishes the diagnostics), so they need no `@Volatile`. Zero before the first frame.
     */
    val trackBirths: Int get() = tracker.birthCount

    /** Tentative tracks that became shown after enough hits, count; see [trackBirths]. */
    val trackConfirmations: Int get() = tracker.confirmCount

    /** Tentative tracks promoted by the HUD tick on a still picture, count; see [trackBirths]. */
    val trackIdlePromotions: Int get() = tracker.idlePromoteCount

    /** Lost tracks that were matched again, count; see [trackBirths]. */
    val trackRecoveries: Int get() = tracker.recoverCount

    /** Shown (confirmed or lost) tracks that were removed, count; see [trackBirths]. */
    val trackRemovals: Int get() = tracker.removeCount

    /** Most tracks shown at once since the start; see [trackBirths]. */
    val tracksMostAtOnce: Int get() = tracker.mostShownAtOnce

    /** Tracks born next to a LOST track (association misses), count; see [trackBirths]. */
    val assocMisses: Int get() = tracker.assocMisses

    /** Association misses whose pair was outside the gate, count; see [trackBirths]. */
    val assocMissGate: Int get() = tracker.assocMissGate

    /** Association misses whose pair passed the gate but cost too much, count; see [trackBirths]. */
    val assocMissCost: Int get() = tracker.assocMissCost

    /** Association misses whose pair the matcher would have accepted, count; see [trackBirths]. */
    val assocMissTaken: Int get() = tracker.assocMissTaken

    /** Unmatched detections below the birth score next to a LOST track, count; see [trackBirths]. */
    val assocMissLowBirth: Int get() = tracker.assocMissLowBirth

    /** Births next to a track that is not LOST, count; see [trackBirths]. */
    val assocBirthNearLive: Int get() = tracker.assocBirthNearLive

    /** Births next to a track removed in the same frame, count; see [trackBirths]. */
    val assocBirthAfterRemoval: Int get() = tracker.assocBirthAfterRemoval

    /** Unmatched detections not given a track because they overlap a confirmed or lost track's detection, count. */
    val assocBirthSuppressed: Int get() = tracker.assocBirthSuppressed

    /**
     * Whether a lock exists: a track is locked, a lock waits for its person to come back (pending), or a lock was
     * requested and the next state has not dropped it yet. Any thread.
     */
    val hasLock: Boolean
        get() = lockKeeper.hasLock

    /** Locks set since this pipeline (so this Start) began, count; read on the capture thread (the HUD tick). */
    val lockCount: Int get() = lockKeeper.lockCount

    /** Pending locks that moved to a returning person, count; see [lockCount]. */
    val lockRelockCount: Int get() = lockKeeper.relockCount

    /** New tracks compared with a pending lock and not accepted, count; see [lockCount]. */
    val lockRelockRejectCount: Int get() = lockKeeper.relockRejectCount

    /** Pending locks whose memory ran out, count; see [lockCount]. */
    val lockExpiredCount: Int get() = lockKeeper.expiredCount

    /**
     * Locks the track with [id] (the next published state carries it while that track is shown; an id that is not
     * shown is dropped there). A negative [id] clears the lock. Any thread, meant for the UI thread.
     */
    fun requestLock(id: Int) {
        lockKeeper.request(id)
    }

    /** Ends the lock (a pending one too); the next published state carries none. Any thread, meant for the UI thread. */
    fun clearLock() {
        lockKeeper.clear()
    }

    /** The session ends: ends the lock and drops its signature (the log line says `stop`). Capture thread. */
    fun onStop() {
        try {
            lockKeeper.onStop()
        } catch (e: Throwable) {
            safeLog("ending the lock failed", e)
        }
    }

    /**
     * Sets the size every frame must have (Img pixels). `null` = unknown: every frame is dropped as a
     * size mismatch (fail closed). The copy buffer is replaced by the next frame of a new size.
     * Leaving a known size (rotation) republishes the state at once without its detections and tracks, which
     * are positions of the old size and must never be mapped with the new geometry; the tracker is reset.
     * Capture thread.
     */
    fun setExpectedSize(size: CaptureSize?) {
        val previous = expectedSize
        expectedSize = size
        if (previous != null && previous != size) republish(dropFrame = true)
    }

    /**
     * Sets the display size, in pixels, that the capture size was planned for. Every published state carries
     * it and the overlay maps Img to View with it, so the mapping always belongs to the same rotation as the
     * capture size. Leaving a known size drops the old detections and tracks like [setExpectedSize] does. Call it before
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
     * old detections and tracks from the state (and resets the tracker), so marks of the other size can never be
     * shown again.
     */
    fun setDisplaySized(displaySized: Boolean) {
        val changed = this.displaySized != displaySized
        this.displaySized = displaySized
        republish(dropFrame = changed)
    }

    /**
     * Capture thread. Sets only the detector the next frame uses, with no republish and no reset. The session calls it
     * with `null` before the model switcher may close the running detector (so a closed detector is never used even if
     * the switch dies half-way) and puts the running one back when the switcher changed nothing.
     */
    fun holdDetector(held: PoseDetector?) {
        detector = held
    }

    /**
     * The model changed because the orientation changed (M1b-1): [newDetector] (`null` = none could be loaded) now
     * runs. The caller has closed the old detector. The positions of the old model's frames must not feed the new
     * model's tracks, so the state drops its detections and tracks, and the tracker, the smoother and the lock are
     * reset like for any geometry change (`republish`); the state carries the new model's input size and the
     * imported mark. Never throws. Capture thread.
     *
     * @param imported whether [newDetector] runs an imported model (the HUD mark `imp`).
     * @param notUsed whether an imported model is expected but the built-in one runs (the lowest-priority warning).
     */
    fun onModelChanged(
        newDetector: PoseDetector?,
        imported: Boolean,
        notUsed: Boolean,
    ) {
        try {
            detector = newDetector
            modelImported = imported
            importedModelNotUsed = notUsed
            modelInputWidth = newDetector?.inputWidth ?: 0
            modelInputHeight = newDetector?.inputHeight ?: 0
            lastState = lastState.copy(modelInputWidth = modelInputWidth, modelInputHeight = modelInputHeight, modelImported = imported)
            // The timing of the old model must not mix into the new model's percentiles.
            copyWindow.clear()
            preWindow.clear()
            inferWindow.clear()
            postWindow.clear()
            frameWindow.clear()
            resultAgeWindow.clear()
            republish(dropFrame = true)
        } catch (e: Throwable) {
            safeLog("changing the model failed", e)
        }
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
     * fps. Called at the HUD rate, on the capture thread. When the newest published state came from a tracked
     * frame, the tracker is told how long ago that frame was published; if the shown tracks then differ (a
     * tentative track was promoted, a lost one removed), the state carries a new [TrackSnapshot]. Never throws.
     */
    fun onHudTick() {
        try {
            val nowNs = clock.nowNs()
            val ticked = lastState.copy(captureFps = fpsMeter.fps(nowNs))
            publishSafely(if (trackedFrameNewest) withIdleTracks(ticked, nowNs) else ticked)
        } catch (e: Throwable) {
            safeLog("HUD tick failed", e)
        }
    }

    /** [state] with a fresh snapshot when the idle time changed the number of shown tracks, else [state]. */
    private fun withIdleTracks(
        state: OverlayState,
        nowNs: Long,
    ): OverlayState {
        val list = tracker.onIdle(nowNs - trackedArrivalNs)
        val idleMs = (nowNs - trackedArrivalNs).toFloat() / TrackerConfig.NANOS_PER_MILLI.toFloat()
        val maxLostMs = config.trackMaxLostMs
        val idle = TrackSnapshot.ofIdle(list, state.captureWidthImg, state.captureHeightImg, smoother, idleMs, maxLostMs)
        val changed = RedrawPolicy.tracksChanged(state.tracks, idle, 0f, config.fadeStepAlpha, maxLostMs, config.fadeMinAlpha)
        return if (changed) state.copy(tracks = idle) else state
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
        val list = tracker.update(result)
        logMisses(nowNs)
        val tracks = TrackSnapshot.ofSmoothed(list, widthImg, heightImg, smoother, result.timestampNs)
        followLock(list, tracks, result)
        trackedFrameNewest = true
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
                tracks = tracks,
            ),
        )
        trackedArrivalNs = clock.nowNs()
        recordTimings(detector, copyStartNs, nowNs, acquireNs, timestampNs, source == TimestampSource.IMAGE)
        frames++
        failureStreak = 0L
    }

    /** Writes the association-miss lines of the newest tracker update (at most `assocMissLogPerMin` a minute). */
    private fun logMisses(nowNs: Long) {
        try {
            for (i in 0 until tracker.missCount) missLog.report(tracker.miss(i), nowNs)
        } catch (e: Throwable) {
            safeLog("logging association misses failed", e)
        }
    }

    /**
     * Gives the lock keeper this frame (it reads the frame's pixels, which must still be intact: right after `detect`).
     * A failure is logged and never stops the frame from being published.
     */
    private fun followLock(
        list: TrackList,
        tracks: TrackSnapshot,
        result: DetectionFrame,
    ) {
        try {
            lockKeeper.onFrame(list, tracks, result.detections, captureFrame)
        } catch (e: Throwable) {
            safeLog("following the lock failed", e)
        }
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
        trackedFrameNewest = false
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
            if (dropFrame) {
                tracker.reset()
                smoother.reset()
                lockKeeper.onReset()
                trackedFrameNewest = false
            }
            val base = lastState
            publishSafely(
                base.copy(
                    frame = if (dropFrame) null else base.frame,
                    tracks = if (dropFrame) TrackSnapshot.EMPTY else base.tracks,
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

    /**
     * Publishes [state], carrying the lock only while its id is among the state's tracks (a pending lock carries
     * none). Every state of the pipeline goes through here, so the lock keeper sees every change of the shown tracks,
     * whichever path made it.
     */
    private fun publish(state: OverlayState) {
        val locked = lockKeeper.resolve(state.tracks)
        val out = if (state.lockedId == locked) state else state.copy(lockedId = locked)
        lastState = out
        publisher(out)
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
