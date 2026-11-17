package com.memo494.posetracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.memo494.posetracker.R
import com.memo494.posetracker.core.TrackerConfig

/**
 * The lock handle (ARCHITECTURE §6.6): a small round button, drawn in its own `TYPE_APPLICATION_OVERLAY` window at
 * the right screen edge. A tap is reported through the normal click listener ([OverlayWindow] sets it); the view
 * has a content description for accessibility. While [armed] a filled dot shows that the next tap picks a person.
 * It draws only when created and when [armed] changes, never on its own, and allocates nothing in `onDraw`.
 *
 * @param context a context that may add overlay windows.
 * @param config source of the stroke width.
 */
@SuppressLint("ViewConstructor")
internal class LockHandleView(
    context: Context,
    config: TrackerConfig,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val strokePx = config.dotStrokeDp * density

    private val backgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_hud_background)
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = context.getColor(R.color.overlay_keypoint_green)
        }
    private val dotPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_keypoint_green)
        }

    /** Whether the overlay waits for the tap on a person; changing it redraws the handle once. */
    var armed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.lock_handle_description)
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        canvas.drawCircle(centerX, centerY, radius * RING_RATIO, ringPaint)
        if (armed) canvas.drawCircle(centerX, centerY, radius * DOT_RATIO, dotPaint)
    }

    private companion object {
        /** Ring radius as a fraction of the handle's radius (leaves room for the stroke). */
        const val RING_RATIO = 0.6f

        /** Radius of the dot shown while armed, as a fraction of the handle's radius. */
        const val DOT_RATIO = 0.3f
    }
}
