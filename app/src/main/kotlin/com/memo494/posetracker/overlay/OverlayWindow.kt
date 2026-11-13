package com.memo494.posetracker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import com.memo494.posetracker.BuildConfig
import com.memo494.posetracker.BuildLabel
import com.memo494.posetracker.core.TrackerConfig

/**
 * The overlay window: adds and removes a [PoseOverlayView] through the `WindowManager` (ARCHITECTURE
 * §6.5). All calls run on the main thread; the capture thread talks only to [stateHolder].
 *
 * The window is `TYPE_APPLICATION_OVERLAY` (needs the "draw over other apps" permission, checked by the
 * caller), not touchable and not focusable, so touches reach the app below. Its alpha is capped at the
 * platform's maximum obscuring opacity: above it Android 12+ blocks touches to the app below
 * (Consult C1-A). Starting it from a service is the capture service's job (T5).
 *
 * @param context a service or application context that may add overlay windows.
 * @param config strokes, thresholds, text size and the alpha cap fallback.
 * @param versionLabel version + git SHA line of the HUD.
 */
class OverlayWindow(
    private val context: Context,
    private val config: TrackerConfig = TrackerConfig(),
    private val versionLabel: String = BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
) {
    /** Where the capture thread publishes [OverlayState]; safe to call from any non-main thread. */
    val stateHolder = OverlayStateHolder(config)

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var overlayView: PoseOverlayView? = null

    /**
     * Adds the overlay if it is not shown yet. Returns `true` when the overlay is shown afterwards and
     * `false` when the system refused it (permission revoked, bad token); the caller then reports it.
     */
    fun show(): Boolean {
        checkMainThread()
        if (overlayView != null) return true
        val manager = windowManager ?: return false
        val view = PoseOverlayView(context, stateHolder, config, versionLabel)
        try {
            manager.addView(view, createLayoutParams())
        } catch (e: RuntimeException) {
            // WindowManager.BadTokenException / InvalidDisplayException: fail closed, nothing is shown.
            return false
        }
        overlayView = view
        return true
    }

    /** Removes the overlay; safe to call repeatedly or when it was never shown. */
    fun remove() {
        checkMainThread()
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            // Already gone (for example the system removed it when the permission was revoked).
        }
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
        params.alpha = overlayAlpha()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // no inset types: draw over the whole display (C1-A)
            params.setFitInsetsTypes(0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
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
