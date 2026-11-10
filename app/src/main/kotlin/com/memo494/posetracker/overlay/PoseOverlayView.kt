package com.memo494.posetracker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import com.memo494.posetracker.R
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.geometry.FitCenter
import com.memo494.posetracker.core.model.PoseDetection
import com.memo494.posetracker.detector.Accelerator
import kotlin.math.max

/**
 * The overlay: a plain `View` (no Compose) that draws the newest [OverlayState] over other apps
 * (ARCHITECTURE §6.5). Hollow head rings, thin boxes and small keypoint dots, plus a corner HUD strip
 * and a red banner when the detector runs on the CPU.
 *
 * It never schedules its own redraw: [OverlayStateHolder] calls `postInvalidateOnAnimation()` only when
 * the picture changed (Consult C1-B; the overlay is part of the capture, so a redraw loop would feed
 * itself). `onDraw` reads the newest state once and allocates nothing: paints, text buffers and the
 * Img -> View mapping are prepared up front or cached until their inputs change.
 *
 * Img -> View ([FitCenter.forDisplay]) is recomputed only when the capture size, the display size, the
 * view size or the view's position on screen change. Detections are fail-closed: a frame whose size is not
 * the capture size, or a mapping that cannot be built, draws nothing.
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
    private val paddingPx = TrackerConfig.HUD_PADDING_DP * density

    private val boxPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = config.boxStrokeDp * density
            color = context.getColor(R.color.overlay_box_green)
        }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = config.dotStrokeDp * density
            color = context.getColor(R.color.overlay_keypoint_green)
        }
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
    private val warningLine = CharArray(TrackerConfig.HUD_LINE_CAPACITY_CHARS)
    private val ascentPx: Float
    private val lineHeightPx: Float

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val legacyMetrics = DisplayMetrics()
    private val locationOnScreen = IntArray(2)
    private val redrawRunnable = Runnable { postInvalidateOnAnimation() }

    private var displayWidthPx = 0
    private var displayHeightPx = 0

    // Inputs the cached mapping was built from; any change rebuilds it.
    private var mapCaptureWidthImg = 0
    private var mapCaptureHeightImg = 0
    private var mapDisplayWidthPx = 0
    private var mapDisplayHeightPx = 0
    private var mapViewWidthView = 0
    private var mapViewHeightView = 0
    private var mapLeftOnScreenPx = 0
    private var mapTopOnScreenPx = 0
    private var fitCenter: FitCenter? = null

    init {
        val metrics = Paint.FontMetrics()
        hudTextPaint.getFontMetrics(metrics)
        ascentPx = -metrics.ascent
        lineHeightPx = metrics.descent - metrics.ascent + metrics.leading
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshDisplaySize()
        holder.redrawRequester = redrawRunnable
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (holder.redrawRequester === redrawRunnable) holder.redrawRequester = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshDisplaySize()
    }

    override fun onDraw(canvas: Canvas) {
        drawDetections(canvas, holder.latest())
        drawHud(canvas, holder.hud())
    }

    private fun drawDetections(
        canvas: Canvas,
        state: OverlayState,
    ) {
        val frame = state.frame ?: return
        if (!state.detectionsShown) return
        if (frame.widthImg != state.captureWidthImg || frame.heightImg != state.captureHeightImg) return
        val fit = mappingFor(state.captureWidthImg, state.captureHeightImg) ?: return
        val detections = frame.detections
        for (i in 0 until detections.size) {
            drawPerson(canvas, fit, detections[i])
        }
    }

    private fun drawPerson(
        canvas: Canvas,
        fit: FitCenter,
        person: PoseDetection,
    ) {
        val leftView = fit.imgToViewX(person.boxLeftImg)
        val rightView = fit.imgToViewX(person.boxRightImg)
        canvas.drawRect(leftView, fit.imgToViewY(person.boxTopImg), rightView, fit.imgToViewY(person.boxBottomImg), boxPaint)

        val minScore = config.keypointMinScore
        val headCount = minOf(person.keypointCount, TrackerConfig.HEAD_KEYPOINT_COUNT)
        var sumXImg = 0f
        var sumYImg = 0f
        var used = 0
        for (k in 0 until headCount) {
            if (person.keypointScores[k] >= minScore) {
                sumXImg += person.keypointXImg(k)
                sumYImg += person.keypointYImg(k)
                used++
            }
        }
        val headXImg: Float
        val headYImg: Float
        if (used > 0) {
            headXImg = sumXImg / used
            headYImg = sumYImg / used
        } else {
            headXImg = (person.boxLeftImg + person.boxRightImg) / 2f
            headYImg = person.boxTopImg
        }
        val ringRadiusView = (rightView - leftView) * config.headRingRadiusRatio
        canvas.drawCircle(fit.imgToViewX(headXImg), fit.imgToViewY(headYImg), ringRadiusView, ringPaint)

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

    private fun drawHud(
        canvas: Canvas,
        hud: OverlayState,
    ) {
        val statsLength = HudFormatter.formatStats(hud, statsLine)
        val warningLength = HudFormatter.formatWarning(hud, warningLine)
        var widest = max(hudTextPaint.measureText(versionLine, 0, versionLine.size), hudTextPaint.measureText(statsLine, 0, statsLength))
        var lines = 2
        if (warningLength > 0) {
            widest = max(widest, hudTextPaint.measureText(warningLine, 0, warningLength))
            lines = 3
        }
        val stripBottomView = paddingPx + lines * lineHeightPx + 2f * paddingPx
        canvas.drawRect(paddingPx, paddingPx, paddingPx + widest + 2f * paddingPx, stripBottomView, hudBackgroundPaint)
        drawHudLine(canvas, versionLine, versionLine.size, 0)
        drawHudLine(canvas, statsLine, statsLength, 1)
        if (warningLength > 0) drawHudLine(canvas, warningLine, warningLength, 2)

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
     * The Img -> View mapping for a capture buffer of the given size, or `null` (fail closed) when none can
     * be built. Rebuilt only when one of its inputs changed; allocation-free otherwise.
     */
    private fun mappingFor(
        captureWidthImg: Int,
        captureHeightImg: Int,
    ): FitCenter? {
        getLocationOnScreen(locationOnScreen)
        val leftOnScreenPx = locationOnScreen[0]
        val topOnScreenPx = locationOnScreen[1]
        val unchanged =
            captureWidthImg == mapCaptureWidthImg &&
                captureHeightImg == mapCaptureHeightImg &&
                displayWidthPx == mapDisplayWidthPx &&
                displayHeightPx == mapDisplayHeightPx &&
                width == mapViewWidthView &&
                height == mapViewHeightView &&
                leftOnScreenPx == mapLeftOnScreenPx &&
                topOnScreenPx == mapTopOnScreenPx
        if (unchanged) return fitCenter

        mapCaptureWidthImg = captureWidthImg
        mapCaptureHeightImg = captureHeightImg
        mapDisplayWidthPx = displayWidthPx
        mapDisplayHeightPx = displayHeightPx
        mapViewWidthView = width
        mapViewHeightView = height
        mapLeftOnScreenPx = leftOnScreenPx
        mapTopOnScreenPx = topOnScreenPx
        val contentRectImg = FitCenter.contentRectImg(displayWidthPx, displayHeightPx, captureWidthImg, captureHeightImg)
        val fit =
            if (contentRectImg == null) null else FitCenter.forDisplay(contentRectImg, displayWidthPx, leftOnScreenPx, topOnScreenPx)
        fitCenter = fit
        if (fit != null) holder.imgToViewScale = fit.scale
        return fit
    }

    private fun refreshDisplaySize() {
        val manager = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.maximumWindowMetrics.bounds
            displayWidthPx = bounds.width()
            displayHeightPx = bounds.height()
        } else {
            readLegacyDisplaySize(manager)
        }
    }

    // Only reached on API 26-29. There `WindowManager.getDefaultDisplay()` and `Display.getRealMetrics()` are
    // the only way to read the real display size and are not deprecated; they are deprecated only from API
    // 30 / 31, where `maximumWindowMetrics` is used instead. This is the one allowed deprecation exception.
    @Suppress("DEPRECATION")
    private fun readLegacyDisplaySize(manager: WindowManager) {
        manager.defaultDisplay.getRealMetrics(legacyMetrics)
        displayWidthPx = legacyMetrics.widthPixels
        displayHeightPx = legacyMetrics.heightPixels
    }
}
