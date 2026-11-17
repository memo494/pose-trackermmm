package com.memo494.posetracker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.memo494.posetracker.BuildConfig
import com.memo494.posetracker.BuildLabel
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.overlay.LockArming
import kotlin.math.roundToInt

/**
 * The overlay window: adds and removes a [PoseOverlayView] through the `WindowManager` (ARCHITECTURE
 * §6.5). All calls run on the main thread; the capture thread talks only to [stateHolder].
 *
 * The window is `TYPE_APPLICATION_OVERLAY` (needs the "draw over other apps" permission, checked by the
 * caller), not touchable and not focusable, so touches reach the app below. Its alpha is capped at the
 * platform's maximum obscuring opacity: above it Android 12+ blocks touches to the app below
 * (Consult C1-A). Starting it from a service is the capture service's job (T5).
 *
 * Lock handle (ARCHITECTURE §6.6): with a [lockTarget] a second, small touchable window (the handle) is added and
 * removed together with the overlay. A tap on it unlocks when a person is locked, else arms the overlay: the overlay
 * window loses `FLAG_NOT_TOUCHABLE` (`updateViewLayout`) for exactly one tap, which locks the nearest person
 * ([LockController] holds the flow); after that tap, after `lockArmTimeoutMs` and when the window is removed it is not
 * touchable again. If the handle cannot be added the overlay still works, without locking. Display-only: no input is
 * ever sent to another app.
 *
 * @param context a service or application context that may add overlay windows.
 * @param config strokes, thresholds, text size and the alpha cap fallback.
 * @param versionLabel version + git SHA line of the HUD.
 * @param lockTarget where lock and unlock go; `null` = no handle.
 */
class OverlayWindow(
    private val context: Context,
    private val config: TrackerConfig = TrackerConfig(),
    private val versionLabel: String = BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
    private val lockTarget: LockTarget? = null,
) {
    /** Where the capture thread publishes [OverlayState]; safe to call from any non-main thread. */
    val stateHolder = OverlayStateHolder(config)

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var overlayView: PoseOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var handleView: LockHandleView? = null
    private var controller: LockController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { controller?.onTimeout() }

    /** What the lock flow changes on screen (main thread). */
    private val lockUi =
        object : LockUi {
            override fun setOverlayTouchable(touchable: Boolean) = updateOverlayTouchable(touchable)

            override fun showArmed(armed: Boolean) {
                overlayView?.armed = armed
                handleView?.armed = armed
            }

            override fun scheduleTimeout(delayMs: Long) {
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, delayMs)
            }

            override fun cancelTimeout() = mainHandler.removeCallbacks(timeoutRunnable)
        }

    /**
     * Adds the overlay if it is not shown yet. Returns `true` when the overlay is shown afterwards and
     * `false` when the system refused it (permission revoked, bad token); the caller then reports it.
     */
    fun show(): Boolean {
        checkMainThread()
        if (overlayView != null) return true
        val manager = windowManager ?: return false
        val view = PoseOverlayView(context, stateHolder, config, versionLabel)
        val params = createLayoutParams()
        try {
            manager.addView(view, params)
        } catch (e: RuntimeException) {
            // WindowManager.BadTokenException / InvalidDisplayException: fail closed, nothing is shown.
            return false
        }
        overlayView = view
        overlayParams = params
        showHandle(manager, view)
        return true
    }

    /**
     * Adds the lock handle next to the overlay and wires the lock flow. Without a [lockTarget], or when the system
     * refuses the window, there is no handle and no locking; the overlay itself is unaffected.
     */
    private fun showHandle(
        manager: WindowManager,
        view: PoseOverlayView,
    ) {
        val target = lockTarget ?: return
        val handle = LockHandleView(context, config)
        val flow = LockController(LockArming(config.lockArmTimeoutNs), config.lockArmTimeoutMs, System::nanoTime, target, lockUi)
        handle.setOnClickListener { flow.onHandleTap() }
        try {
            manager.addView(handle, createHandleLayoutParams())
        } catch (e: RuntimeException) {
            // Same refusals as for the overlay: no handle, no locking.
            return
        }
        view.armedTapListener = PoseOverlayView.ArmedTapListener { trackId -> flow.onArmedTap(trackId) }
        handleView = handle
        controller = flow
    }

    /** Removes the overlay; safe to call repeatedly or when it was never shown. */
    fun remove() {
        checkMainThread()
        if (overlayView == null && handleView == null) return
        // First, while the window still exists: the arming ends and the touchable flag is restored.
        controller?.stop()
        controller = null
        mainHandler.removeCallbacks(timeoutRunnable)
        overlayView?.armedTapListener = null
        removeQuietly(handleView)
        handleView = null
        removeQuietly(overlayView)
        overlayView = null
        overlayParams = null
    }

    private fun removeQuietly(view: View?) {
        if (view == null) return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            // Already gone (for example the system removed it when the permission was revoked).
        }
    }

    /** Adds or removes `FLAG_NOT_TOUCHABLE` on the overlay window; taps reach the app below again when it is set. */
    private fun updateOverlayTouchable(touchable: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.flags =
            if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            // Fail closed: a window that cannot be made untouchable again must not stay (it would swallow the taps
            // of the app below), so the overlay and its handle are removed; the session itself goes on.
            if (!touchable) dropWindows(view)
        }
    }

    /** Removes the overlay [view] and the handle without a word and forgets them (a failed `updateViewLayout`). */
    private fun dropWindows(view: View) {
        overlayView?.armedTapListener = null
        removeQuietly(handleView)
        handleView = null
        removeQuietly(view)
        overlayView = null
        overlayParams = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val params =
            WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START
        applyDisplayParams(params)
        return params
    }

    /**
     * The lock handle's window: [TrackerConfig.lockHandleSizeDp] square, touchable but never focusable, at the right
     * screen edge in the vertical middle.
     */
    private fun createHandleLayoutParams(): WindowManager.LayoutParams {
        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val sizePx = (config.lockHandleSizeDp * context.resources.displayMetrics.density).roundToInt()
        val params =
            WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        applyDisplayParams(params)
        return params
    }

    /** Alpha cap, display cutout mode and no inset types: what both windows share (C1-A). */
    private fun applyDisplayParams(params: WindowManager.LayoutParams) {
        params.alpha = overlayAlpha()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // no inset types: draw over the whole display (C1-A)
            params.setFitInsetsTypes(0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Window alpha: the platform's maximum obscuring opacity (API 31+), never above the config cap. */
    private fun overlayAlpha(): Float {
        val cap = config.overlayAlphaCapFallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val inputManager = context.getSystemService(InputManager::class.java)
            if (inputManager != null) return minOf(cap, inputManager.maximumObscuringOpacityForTouch)
        }
        return cap
    }

    private fun checkMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "OverlayWindow must be used on the main thread" }
    }
}
