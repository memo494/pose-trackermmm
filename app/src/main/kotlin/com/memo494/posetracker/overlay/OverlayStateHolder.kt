package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.overlay.RedrawPolicy
import java.util.concurrent.atomic.AtomicReference

/**
 * Hand-over point between the capture thread and the overlay view (ARCHITECTURE §5, §6.5).
 *
 * The capture thread (or any non-main thread) calls [publish] with an immutable [OverlayState]. The
 * holder keeps the newest one for the view's `onDraw` ([latest]) and asks the view to redraw only when
 * [RedrawPolicy] says the picture changed (Consult C1-B: the overlay is part of the capture, so a
 * redraw loop would feed itself). The view's redraw request is `postInvalidateOnAnimation()`, which is
 * safe to call from any thread.
 *
 * The HUD is a second, slower view of the same data: [hud] only moves to the newest state when the HUD
 * text changed and `TrackerConfig.hudIntervalNs` has elapsed, or at once when a warning or the content
 * visibility changed. A static screen produces no frames, so the publisher must also send a state
 * (with its fps at about 0) at the HUD rate for the HUD to show that.
 *
 * @param config source of the deadband, the HUD interval and the keypoint score threshold.
 * @param clockNs `System.nanoTime()` timebase; injectable for tests.
 */
class OverlayStateHolder(
    private val config: TrackerConfig = TrackerConfig(),
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val latestRef = AtomicReference(OverlayState.EMPTY)

    @Volatile
    private var hudState = OverlayState.EMPTY

    private var drawnState = OverlayState.EMPTY
    private var lastHudNs = clockNs() - config.hudIntervalNs

    /**
     * Requests a redraw of the view; set by the view when attached (and cleared when detached). Called on
     * the publishing thread, so it must only do thread-safe work such as `postInvalidateOnAnimation()`.
     */
    @Volatile
    var redrawRequester: Runnable? = null

    /**
     * Current Img -> View scale (unitless, View pixels per Img pixel), written by the view whenever it
     * recomputes its mapping. Converts the View-pixel deadband to Img pixels; a value that is not
     * positive counts as 1.
     */
    @Volatile
    var imgToViewScale: Float = 1f

    /** The newest published state; read by the view in `onDraw`. Never `null`. */
    fun latest(): OverlayState = latestRef.get()

    /** The state whose HUD fields the view prints; refreshed at most at the HUD rate. Never `null`. */
    fun hud(): OverlayState = hudState

    /** Publishes [state] from any non-main thread; may request one view redraw. */
    @Synchronized
    fun publish(state: OverlayState) {
        latestRef.set(state)
        val nowNs = clockNs()
        val shownHud = hudState
        val urgent = state.warning != shownHud.warning || state.dotsVisible != shownHud.dotsVisible
        val changed = HudFormatter.hudChanged(shownHud, state, config.hudIdleFps)
        val hudDue = urgent || RedrawPolicy.hudDue(nowNs, lastHudNs, config.hudIntervalNs, changed)
        if (hudDue) {
            hudState = state
            lastHudNs = nowNs
        }
        val drawn = drawnState
        val redraw =
            RedrawPolicy.shouldRedrawMarks(
                previousTracks = drawn.tracks,
                currentTracks = state.tracks,
                previousFrame = drawn.frame,
                currentFrame = state.frame,
                previousShown = drawn.detectionsShown,
                currentShown = state.detectionsShown,
                previousWarning = drawn.warning,
                currentWarning = state.warning,
                deadbandImg = deadbandImg(),
                keypointMinScore = config.keypointMinScore,
                debugMarks = config.debugMarks,
                hudDue = hudDue,
                fadeStepAlpha = config.fadeStepAlpha,
                maxLostMs = config.trackMaxLostMs,
                fadeMinAlpha = config.fadeMinAlpha,
            )
        if (redraw) {
            drawnState = state
            redrawRequester?.run()
        }
    }

    private fun deadbandImg(): Float {
        val scale = imgToViewScale
        return config.redrawDeadbandPx / if (scale > 0f) scale else 1f
    }
}
