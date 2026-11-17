package com.memo494.posetracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.memo494.posetracker.R
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.geometry.FitCenter
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.core.overlay.CornerBrackets
import com.memo494.posetracker.core.overlay.HeadRing
import com.memo494.posetracker.core.overlay.LabelPlacement
import com.memo494.posetracker.core.overlay.LockPicker
import com.memo494.posetracker.core.overlay.MarkFade
import com.memo494.posetracker.core.tracking.TrackSnapshot
import com.memo494.posetracker.detector.Accelerator
import kotlin.math.max

/**
 * The overlay: a plain `View` (no Compose) that draws the newest [OverlayState] over other apps
 * (ARCHITECTURE §6.5). The marks come from the tracker, not from the raw detections: per shown track a
 * hollow head ring at the tracked head point (never smaller than `minHeadRingRadiusDp`) and short corner
 * brackets at the track's box. The overlay is part of the capture, so every other mark lowers the model's
 * score of the person on the next frame and makes the marks blink. With `config.debugMarks` the raw
 * detections are drawn too (full box and keypoint dots, no ring) and each track's id is written above its
 * box, so raw and tracked marks can be compared. A corner HUD strip and a red banner when the detector runs
 * on the CPU are always drawn.
 *
 * Lock (ARCHITECTURE §6.6): the track named by [OverlayState.lockedId] gets a thicker ring (`lockRingStrokeDp`) and a
 * second ring at `lockOuterRingRatio` times the radius. While [armed] (set by [OverlayWindow] after a tap on the lock
 * handle; the window is touchable then) the HUD shows `tap a person` and the next tap is mapped with the Img -> View
 * mapping of the state that was drawn last, picked with [LockPicker] and reported to [armedTapListener].
 *
 * It never schedules its own redraw: [OverlayStateHolder] calls `postInvalidateOnAnimation()` only when
 * the picture changed (Consult C1-B; the overlay is part of the capture, so a redraw loop would feed
 * itself). `onDraw` reads the newest state once and allocates nothing: paints, text buffers and the
 * Img -> View mapping are prepared up front or cached until their inputs change.
 *
 * Img -> View ([OverlayMapping.forState]) is recomputed only when the capture size, the display size of
 * the capture plan (carried by the state, never read by the view itself), or the view's position on screen
 * change. Marks are fail-closed: a mapping that cannot be built draws nothing, and raw detections of a frame
 * whose size is not the capture size are not drawn.
 *
 * @param holder source of the state; this view registers itself as its redraw requester while attached.
 * @param config strokes, thresholds and text size.
 * @param versionLabel version + git SHA line of the HUD (`BuildLabel.line`).
 */
@SuppressLint("ViewConstructor")
class PoseOverlayView(
    context: Context,
    private val holder: OverlayStateHolder,
    private val config: TrackerConfig,
    versionLabel: String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val keypointRadiusPx = TrackerConfig.KEYPOINT_RADIUS_DP * density
    private val minHeadRingRadiusPx = config.minHeadRingRadiusDp * density
    private val paddingPx = TrackerConfig.HUD_PADDING_DP * density

    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = config.boxStrokeDp * density
            // Square caps close the notch where the two strokes of a bracket meet at the box corner.
            strokeCap = Paint.Cap.SQUARE
            color = context.getColor(R.color.overlay_box_green)
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = config.dotStrokeDp * density
            color = context.getColor(R.color.overlay_keypoint_green)
        }
    private val lockRingPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = config.lockRingStrokeDp * density
            color = context.getColor(R.color.overlay_keypoint_green)
        }
    private val boxBaseAlpha = boxPaint.alpha
    private val ringBaseAlpha = ringPaint.alpha
    private val lockRingBaseAlpha = lockRingPaint.alpha
    private val keypointPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_keypoint_green)
        }
    private val hudBackgroundPaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_hud_background)
        }
    private val bannerBackgroundPaint =
        Paint().apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_cpu_banner_red)
        }
    private val hudTextPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.overlay_hud_text)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, config.hudTextSizeSp, resources.displayMetrics)
        }

    private val versionLine = versionLabel.toCharArray()
    private val bannerLine = HudFormatter.CPU_BANNER.toCharArray()
    private val statsLine = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    private val detectionsLine = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    private val warningLine = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    private val idLabel = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    private val ascentPx: Float
    private val descentPx: Float
    private val lineHeightPx: Float

    private val locationOnScreen = IntArray(2)
    private val redrawRunnable = Runnable { postInvalidateOnAnimation() }

    // Inputs the cached mapping was built from; any change rebuilds it.
    private var mapCaptureWidthImg = 0
    private var mapCaptureHeightImg = 0
    private var mapDisplayWidthPx = 0
    private var mapDisplayHeightPx = 0
    private var mapLeftOnScreenPx = 0
    private var mapTopOnScreenPx = 0
    private var fitCenter: FitCenter? = null

    // The state and mapping of the last draw that drew marks, for mapping a tap (cleared when nothing was drawn).
    private var tapState: OverlayState? = null
    private var tapFit: FitCenter? = null

    /** Receives the track id picked by the one tap while [armed] (-1 = nobody close enough). Main thread. */
    var armedTapListener: ArmedTapListener? = null

    /** Whether the overlay waits for the tap that locks a person: shows the HUD hint and takes the next tap. Main thread. */
    var armed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Called with the track id a tap picked; see [armedTapListener]. */
    fun interface ArmedTapListener {
        /** [trackId] is the id of the track nearest to the tap within the lock distance, or -1. */
        fun onArmedTap(trackId: Int)
    }

    init {
        val metrics = Paint.FontMetrics()
        hudTextPaint.getFontMetrics(metrics)
        ascentPx = -metrics.ascent
        descentPx = metrics.descent
        lineHeightPx = metrics.descent - metrics.ascent + metrics.leading
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        holder.redrawRequester = redrawRunnable
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (holder.redrawRequester === redrawRunnable) holder.redrawRequester = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        drawMarks(canvas, holder.latest())
        drawHud(canvas, holder.hud(), armed)
    }

    /**
     * While [armed] the window is touchable: the finger-up of the tap picks a track (or nobody) and reports it; every
     * touch is consumed then. Not armed, the view takes nothing (and the window is not touchable anyway).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val trackId = pickTrackAt(event.x, event.y)
            performClick()
            armedTapListener?.onArmedTap(trackId)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The track nearest to the view point ([xView], [yView]) in the state drawn last, or -1 (fail closed). */
    private fun pickTrackAt(
        xView: Float,
        yView: Float,
    ): Int {
        val fit = tapFit ?: return LockPicker.NONE
        val state = tapState ?: return LockPicker.NONE
        if (!state.tracksMatchCapture) return LockPicker.NONE
        return LockPicker.pick(state.tracks, fit.viewToImgX(xView), fit.viewToImgY(yView), config.lockMaxTapDistanceRatio)
    }

    /**
     * The marks of [state]: per track the corner brackets and the head ring; with `debugMarks` also, first,
     * every raw detection (full box and keypoints) and, with each track, its id. Nothing is drawn while the
     * detections are hidden or no Img -> View mapping can be built; tracks only when their image size is the
     * capture size ([OverlayState.tracksMatchCapture]).
     */
    private fun drawMarks(
        canvas: Canvas,
        state: OverlayState,
    ) {
        tapState = null
        tapFit = null
        if (!state.detectionsShown) return
        val fit = mappingFor(state) ?: return
        tapState = state
        tapFit = fit
        if (config.debugMarks) drawRawDetections(canvas, fit, state)
        if (!state.tracksMatchCapture) return
        val tracks = state.tracks
        for (i in 0 until tracks.count) {
            drawTrack(canvas, fit, tracks, i, state.lockedId)
        }
    }

    /** Debug only: every detection of the newest frame, if that frame belongs to the capture size. */
    private fun drawRawDetections(
        canvas: Canvas,
        fit: FitCenter,
        state: OverlayState,
    ) {
        val frame = state.frame ?: return
        if (frame.widthImg != state.captureWidthImg || frame.heightImg != state.captureHeightImg) return
        val detections = frame.detections
        for (i in 0 until detections.size) {
            drawRawDetection(canvas, fit, detections[i])
        }
    }

    /** One raw detection, debug only: the full box and the keypoint dots; the head ring belongs to the track. */
    private fun drawRawDetection(
        canvas: Canvas,
        fit: FitCenter,
        person: PoseDetection,
    ) {
        canvas.drawRect(
            fit.imgToViewX(person.boxLeftImg),
            fit.imgToViewY(person.boxTopImg),
            fit.imgToViewX(person.boxRightImg),
            fit.imgToViewY(person.boxBottomImg),
            boxPaint,
        )
        val minScore = config.keypointMinScore
        for (k in 0 until person.keypointCount) {
            if (person.keypointScores[k] >= minScore) {
                canvas.drawCircle(
                    fit.imgToViewX(person.keypointXImg(k)),
                    fit.imgToViewY(person.keypointYImg(k)),
                    keypointRadiusPx,
                    keypointPaint,
                )
            }
        }
    }

    /**
     * Track [index] of [tracks]: corner brackets at its box, the ring at its head point (the double ring when its id is
     * [lockedId]) and, in debug, its id.
     */
    private fun drawTrack(
        canvas: Canvas,
        fit: FitCenter,
        tracks: TrackSnapshot,
        index: Int,
        lockedId: Int,
    ) {
        val leftView = fit.imgToViewX(tracks.boxLeftImg(index))
        val rightView = fit.imgToViewX(tracks.boxRightImg(index))
        val topView = fit.imgToViewY(tracks.boxTopImg(index))
        val bottomView = fit.imgToViewY(tracks.boxBottomImg(index))
        val lost = tracks.isLost(index)
        if (lost) setMarkAlpha(MarkFade.alpha(tracks.lostMs(index), config.trackMaxLostMs, config.fadeMinAlpha))
        drawCornerBrackets(canvas, leftView, topView, rightView, bottomView)

        val ringRadiusView = HeadRing.radius(rightView - leftView, config.headRingRadiusRatio, minHeadRingRadiusPx)
        val headXView = fit.imgToViewX(tracks.headXImg(index))
        val headYView = fit.imgToViewY(tracks.headYImg(index))
        if (tracks.id(index) == lockedId) {
            // the locked person: a thicker ring and a second one around it
            canvas.drawCircle(headXView, headYView, ringRadiusView, lockRingPaint)
            canvas.drawCircle(headXView, headYView, ringRadiusView * config.lockOuterRingRatio, lockRingPaint)
        } else {
            canvas.drawCircle(headXView, headYView, ringRadiusView, ringPaint)
        }

        if (lost) setMarkAlpha(1f)
        if (config.debugMarks) drawTrackId(canvas, tracks.id(index), leftView, topView)
    }

    /** Scales the opacity of the box, ring and lock ring paints to [alpha] (0..1) of their own; 1 restores them. No allocation. */
    private fun setMarkAlpha(alpha: Float) {
        boxPaint.alpha = (boxBaseAlpha * alpha + ALPHA_ROUNDING).toInt()
        ringPaint.alpha = (ringBaseAlpha * alpha + ALPHA_ROUNDING).toInt()
        lockRingPaint.alpha = (lockRingBaseAlpha * alpha + ALPHA_ROUNDING).toInt()
    }

    /** Debug only: the [trackId] as a number above the top-left corner of its box, kept inside the view. */
    private fun drawTrackId(
        canvas: Canvas,
        trackId: Int,
        leftView: Float,
        topView: Float,
    ) {
        val length = HudFormatter.putWhole(idLabel, 0, trackId)
        val labelWidth = hudTextPaint.measureText(idLabel, 0, length)
        val xView = LabelPlacement.leftX(leftView, labelWidth, width.toFloat())
        val baselineView = LabelPlacement.baselineY(topView, ascentPx, descentPx, paddingPx, height.toFloat())
        if (xView.isNaN() || baselineView.isNaN()) return
        canvas.drawText(idLabel, 0, length, xView, baselineView, hudTextPaint)
    }

    /** Two strokes along the box edges at each of the four corners, each `boxCornerRatio` of its side long. */
    private fun drawCornerBrackets(
        canvas: Canvas,
        leftView: Float,
        topView: Float,
        rightView: Float,
        bottomView: Float,
    ) {
        val lengthX = CornerBrackets.length(rightView - leftView, config.boxCornerRatio)
        val lengthY = CornerBrackets.length(bottomView - topView, config.boxCornerRatio)
        drawCorner(canvas, leftView, topView, lengthX, lengthY)
        drawCorner(canvas, rightView, topView, -lengthX, lengthY)
        drawCorner(canvas, leftView, bottomView, lengthX, -lengthY)
        drawCorner(canvas, rightView, bottomView, -lengthX, -lengthY)
    }

    /** One bracket: from the corner ([xView], [yView]) [dxView] along x and [dyView] along y (signed, inward). */
    private fun drawCorner(
        canvas: Canvas,
        xView: Float,
        yView: Float,
        dxView: Float,
        dyView: Float,
    ) {
        canvas.drawLine(xView, yView, xView + dxView, yView, boxPaint)
        canvas.drawLine(xView, yView, xView, yView + dyView, boxPaint)
    }

    private fun drawHud(
        canvas: Canvas,
        hud: OverlayState,
        armed: Boolean,
    ) {
        val statsLength = HudFormatter.formatStats(hud, statsLine, config.hudIdleFps)
        val detectionsLength = HudFormatter.formatDetections(hud, detectionsLine)
        val warningLength = HudFormatter.formatWarning(hud, warningLine, armed)
        var widest = max(hudTextPaint.measureText(versionLine, 0, versionLine.size), hudTextPaint.measureText(statsLine, 0, statsLength))
        widest = max(widest, hudTextPaint.measureText(detectionsLine, 0, detectionsLength))
        var lines = HUD_BASE_LINES
        if (warningLength > 0) {
            widest = max(widest, hudTextPaint.measureText(warningLine, 0, warningLength))
            lines++
        }
        val stripBottomView = paddingPx + lines * lineHeightPx + 2f * paddingPx
        canvas.drawRect(paddingPx, paddingPx, paddingPx + widest + 2f * paddingPx, stripBottomView, hudBackgroundPaint)
        drawHudLine(canvas, versionLine, versionLine.size, 0)
        drawHudLine(canvas, statsLine, statsLength, 1)
        drawHudLine(canvas, detectionsLine, detectionsLength, 2)
        if (warningLength > 0) drawHudLine(canvas, warningLine, warningLength, HUD_BASE_LINES)

        if (hud.accelerator == Accelerator.CPU) {
            val bannerWidth = hudTextPaint.measureText(bannerLine, 0, bannerLine.size)
            val bannerBottomView = stripBottomView + lineHeightPx + 2f * paddingPx
            canvas.drawRect(paddingPx, stripBottomView, paddingPx + bannerWidth + 2f * paddingPx, bannerBottomView, bannerBackgroundPaint)
            canvas.drawText(bannerLine, 0, bannerLine.size, 2f * paddingPx, stripBottomView + paddingPx + ascentPx, hudTextPaint)
        }
    }

    private fun drawHudLine(
        canvas: Canvas,
        text: CharArray,
        length: Int,
        lineIndex: Int,
    ) {
        val baselineView = paddingPx + paddingPx + lineIndex * lineHeightPx + ascentPx
        canvas.drawText(text, 0, length, 2f * paddingPx, baselineView, hudTextPaint)
    }

    /**
     * The Img -> View mapping for [state] ([OverlayMapping.forState]), or `null` (fail closed) when none can
     * be built. Rebuilt only when one of its inputs changed; allocation-free otherwise.
     */
    private fun mappingFor(state: OverlayState): FitCenter? {
        getLocationOnScreen(locationOnScreen)
        val leftOnScreenPx = locationOnScreen[0]
        val topOnScreenPx = locationOnScreen[1]
        val unchanged =
            state.captureWidthImg == mapCaptureWidthImg &&
                state.captureHeightImg == mapCaptureHeightImg &&
                state.displayWidthPx == mapDisplayWidthPx &&
                state.displayHeightPx == mapDisplayHeightPx &&
                leftOnScreenPx == mapLeftOnScreenPx &&
                topOnScreenPx == mapTopOnScreenPx
        if (unchanged) return fitCenter

        mapCaptureWidthImg = state.captureWidthImg
        mapCaptureHeightImg = state.captureHeightImg
        mapDisplayWidthPx = state.displayWidthPx
        mapDisplayHeightPx = state.displayHeightPx
        mapLeftOnScreenPx = leftOnScreenPx
        mapTopOnScreenPx = topOnScreenPx
        val fit = OverlayMapping.forState(state, leftOnScreenPx, topOnScreenPx)
        fitCenter = fit
        if (fit != null) holder.imgToViewScale = fit.scale
        return fit
    }

    private companion object {
        /** HUD lines always shown: version, stats, detections; a warning adds one more. */
        const val HUD_BASE_LINES = 3

        /** Added before truncating a scaled 0..255 alpha to an Int, so it rounds to the nearest. */
        const val ALPHA_ROUNDING = 0.5f
    }
}
