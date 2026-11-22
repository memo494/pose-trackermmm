package com.memo494.posetracker.overlay

import com.memo494.posetracker.core.model.ModelInputText
import com.memo494.posetracker.core.model.TimestampSource
import com.memo494.posetracker.core.overlay.OverlayWarning
import com.memo494.posetracker.detector.Accelerator
import kotlin.math.roundToInt

/**
 * Text of the debug HUD (ARCHITECTURE §6.5). Pure Kotlin and allocation-free: every function writes into
 * a caller-owned [CharArray] and returns the number of characters written, so the draw phase can keep one
 * buffer per line and call `Canvas.drawText(char[], …)`. Text longer than the buffer is cut, never thrown.
 *
 * The version + git SHA line comes from `BuildLabel` and is converted once by the view.
 */
object HudFormatter {
    /** Text of the red banner shown when the detector runs on the CPU (ARCHITECTURE §6.1). */
    const val CPU_BANNER = "Running on CPU"

    private const val WARNING_CAPTURE_SIZE = "Capture not display-sized: dots hidden"
    private const val WARNING_DETECTOR_UNAVAILABLE = "Model not loaded: no detection"
    private const val WARNING_NOT_VISIBLE = "Content not visible: dots hidden"
    private const val WARNING_BUILT_IN_FAILED = "Built-in model failed: "
    private const val WARNING_IMPORTED_NOT_USED = "Imported model not used: built-in model"
    private const val WARNING_WRONG_SHAPE = "Model wrong shape: built-in runs"
    private const val NOTE_THERMAL_STEPPED_DOWN = "Hot: smaller model (320)"
    private const val HINT_TAP_A_PERSON = "tap a person"
    private const val TRACKS_PREFIX = " trk "
    private const val MODEL_SIZE_PREFIX = " in "
    private const val IMPORTED_MARK = " imp"

    private const val UNKNOWN = "--"
    private const val NOT_AVAILABLE = -1
    private const val DECIMAL_BASE = 10
    private const val TENTHS_PER_UNIT = 10f
    private const val HUNDREDTHS_PER_UNIT = 100f
    private const val WHOLE_PER_UNIT = 1f

    /** Largest number shown; anything above (or non-finite, or negative) prints as [UNKNOWN]. */
    private const val MAX_SHOWN_VALUE = 9999f

    /**
     * Writes the stats line, e.g. `GPU 12.3 ms 58 fps 610x1356 ts=img`: accelerator, inference time (one
     * decimal), capture fps (rounded), capture size, timestamp source. Unknown values print as `--`.
     * A capture fps below [idleFps] prints as `0` (the capture is idle, see [hudChanged]).
     * Returns the number of characters written to [out].
     */
    fun formatStats(
        state: OverlayState,
        out: CharArray,
        idleFps: Float,
    ): Int {
        var pos = 0
        pos = putText(out, pos, acceleratorText(state.accelerator))
        pos = putText(out, pos, " ")
        pos = putTenths(out, pos, quantize(state.inferenceMs, TENTHS_PER_UNIT))
        pos = putText(out, pos, " ms ")
        pos = putWhole(out, pos, shownFps(state, idleFps))
        pos = putText(out, pos, " fps ")
        pos = putWhole(out, pos, state.captureWidthImg)
        pos = putText(out, pos, "x")
        pos = putWhole(out, pos, state.captureHeightImg)
        pos = putText(out, pos, " ts=")
        pos = putText(out, pos, timestampSourceText(state.timestampSource))
        return pos
    }

    /**
     * Writes the detection line, e.g. `det 2/3 trk 2 max 0.89 in 416` (built-in model), `det 2/3 trk 2 max 0.89 in
     * 640 imp` or `det 2/3 trk 2 max 0.89 in 832x384 imp` (imported model): people in the newest frame after
     * filtering, model output rows whose raw score passed the threshold, the number of shown tracks (always a
     * known number, 0 when nobody is tracked), the highest raw score (two decimals), then the model's input size
     * (one number when square, else width x height, see `ModelInputText`) and the mark ` imp` when the model is
     * imported. Unknown values print as `--`; without a model (a side of 0) the ` in ...` part is left out.
     * Returns the number of characters written to [out].
     */
    fun formatDetections(
        state: OverlayState,
        out: CharArray,
    ): Int {
        var pos = putText(out, 0, "det ")
        pos = putWhole(out, pos, detectionCount(state))
        pos = putText(out, pos, "/")
        pos = putWhole(out, pos, state.rawRowsAboveThreshold)
        pos = putText(out, pos, TRACKS_PREFIX)
        pos = putWhole(out, pos, state.tracks.count)
        pos = putText(out, pos, " max ")
        pos = putHundredths(out, pos, quantize(state.maxRawScore, HUNDREDTHS_PER_UNIT))
        if (state.modelInputWidth <= 0 || state.modelInputHeight <= 0) return pos
        pos = putText(out, pos, MODEL_SIZE_PREFIX)
        pos = ModelInputText.write(state.modelInputWidth, state.modelInputHeight, out, pos)
        return if (state.modelImported) putText(out, pos, IMPORTED_MARK) else pos
    }

    /**
     * Writes the warning line into [out], by priority: a missing detector, a capture that is not display-sized,
     * content that is not visible, then the note that the built-in model failed (with the input size of the fallback that
     * runs), then the note that the slot's model has the wrong shape or that an imported model is not used, then the thermal note `Hot: smaller model (320)`, and last, while [armed] (the
     * overlay waits for the tap that locks a person), the hint `tap a person`. Returns the number of characters
     * written; 0 = nothing to show.
     */
    fun formatWarning(
        state: OverlayState,
        out: CharArray,
        armed: Boolean = false,
    ): Int {
        if (state.warning == OverlayWarning.DETECTOR_UNAVAILABLE) return putText(out, 0, WARNING_DETECTOR_UNAVAILABLE)
        if (state.warning == OverlayWarning.CAPTURE_NOT_DISPLAY_SIZED) return putText(out, 0, WARNING_CAPTURE_SIZE)
        if (!state.dotsVisible) return putText(out, 0, WARNING_NOT_VISIBLE)
        if (state.warning == OverlayWarning.BUILT_IN_MODEL_FAILED) {
            return ModelInputText.write(state.modelInputWidth, state.modelInputHeight, out, putText(out, 0, WARNING_BUILT_IN_FAILED))
        }
        if (state.warning == OverlayWarning.MODEL_WRONG_SHAPE) return putText(out, 0, WARNING_WRONG_SHAPE)
        if (state.warning == OverlayWarning.IMPORTED_MODEL_NOT_USED) return putText(out, 0, WARNING_IMPORTED_NOT_USED)
        if (state.warning == OverlayWarning.THERMAL_STEPPED_DOWN) return putText(out, 0, NOTE_THERMAL_STEPPED_DOWN)
        if (armed) return putText(out, 0, HINT_TAP_A_PERSON)
        return 0
    }

    /**
     * Whether the HUD text of [after] differs from that of [before], using the same rounding as
     * [formatStats] (so jitter below the printed precision does not count). Pure, allocation-free.
     *
     * The overlay is part of the capture, so a HUD refresh causes a frame whose numbers would change the
     * text again. To break that loop the fps is compared as displayed (every fps below [idleFps] is 0) and
     * a difference in the inference time is ignored while [after] is idle.
     */
    fun hudChanged(
        before: OverlayState,
        after: OverlayState,
        idleFps: Float,
    ): Boolean {
        if (before.accelerator != after.accelerator) return true
        if (before.timestampSource != after.timestampSource) return true
        if (before.captureWidthImg != after.captureWidthImg) return true
        if (before.captureHeightImg != after.captureHeightImg) return true
        if (before.dotsVisible != after.dotsVisible) return true
        if (before.warning != after.warning) return true
        if (before.modelInputWidth != after.modelInputWidth) return true
        if (before.modelInputHeight != after.modelInputHeight) return true
        if (before.modelImported != after.modelImported) return true
        if (detectionCount(before) != detectionCount(after)) return true
        if (before.tracks.count != after.tracks.count) return true
        if (shownFps(before, idleFps) != shownFps(after, idleFps)) return true
        if (isIdle(after, idleFps)) return false
        return quantize(before.inferenceMs, TENTHS_PER_UNIT) != quantize(after.inferenceMs, TENTHS_PER_UNIT)
    }

    /** People in the newest frame of [state]; [NOT_AVAILABLE] when there is no frame. */
    private fun detectionCount(state: OverlayState): Int = state.frame?.detections?.size ?: NOT_AVAILABLE

    /** Whether the capture of [state] is idle: its fps is a valid number below [idleFps]. */
    private fun isIdle(
        state: OverlayState,
        idleFps: Float,
    ): Boolean = state.captureFps >= 0f && state.captureFps < idleFps

    /** The fps as printed: 0 while idle, else rounded; [NOT_AVAILABLE] for an invalid value. */
    private fun shownFps(
        state: OverlayState,
        idleFps: Float,
    ): Int = if (isIdle(state, idleFps)) 0 else quantize(state.captureFps, WHOLE_PER_UNIT)

    private fun acceleratorText(accelerator: Accelerator?): String =
        when (accelerator) {
            Accelerator.GPU -> "GPU"
            Accelerator.CPU -> "CPU"
            null -> UNKNOWN
        }

    private fun timestampSourceText(source: TimestampSource?): String =
        when (source) {
            TimestampSource.IMAGE -> "img"
            TimestampSource.ARRIVAL -> "arrival"
            null -> UNKNOWN
        }

    /** [value] times [scale], rounded; [NOT_AVAILABLE] when [value] is not finite, negative or too large. */
    private fun quantize(
        value: Float,
        scale: Float,
    ): Int = if (value.isFinite() && value >= 0f && value <= MAX_SHOWN_VALUE) (value * scale).roundToInt() else NOT_AVAILABLE

    private fun putChar(
        out: CharArray,
        pos: Int,
        char: Char,
    ): Int {
        if (pos >= out.size) return pos
        out[pos] = char
        return pos + 1
    }

    private fun putText(
        out: CharArray,
        pos: Int,
        text: String,
    ): Int {
        var next = pos
        for (i in 0 until text.length) {
            next = putChar(out, next, text[i])
        }
        return next
    }

    /**
     * Writes a non-negative whole number in decimal; [NOT_AVAILABLE] or less prints [UNKNOWN]. Internal: the
     * overlay writes the track id of the debug marks with it (allocation-free, cut at the end of [out]).
     */
    internal fun putWhole(
        out: CharArray,
        pos: Int,
        value: Int,
    ): Int {
        if (value < 0) return putText(out, pos, UNKNOWN)
        var divisor = 1
        while (value / divisor >= DECIMAL_BASE) divisor *= DECIMAL_BASE
        var next = pos
        while (divisor > 0) {
            next = putChar(out, next, '0' + (value / divisor) % DECIMAL_BASE)
            divisor /= DECIMAL_BASE
        }
        return next
    }

    /** Writes [hundredths] hundredths of a unit as `whole.ff`; a negative value prints [UNKNOWN]. */
    private fun putHundredths(
        out: CharArray,
        pos: Int,
        hundredths: Int,
    ): Int {
        if (hundredths < 0) return putText(out, pos, UNKNOWN)
        var next = putWhole(out, pos, hundredths / (DECIMAL_BASE * DECIMAL_BASE))
        next = putChar(out, next, '.')
        next = putChar(out, next, '0' + (hundredths / DECIMAL_BASE) % DECIMAL_BASE)
        return putChar(out, next, '0' + hundredths % DECIMAL_BASE)
    }

    /** Writes [tenths] tenths of a unit as `whole.fraction`; a negative value prints [UNKNOWN]. */
    private fun putTenths(
        out: CharArray,
        pos: Int,
        tenths: Int,
    ): Int {
        if (tenths < 0) return putText(out, pos, UNKNOWN)
        var next = putWhole(out, pos, tenths / DECIMAL_BASE)
        next = putChar(out, next, '.')
        return putChar(out, next, '0' + tenths % DECIMAL_BASE)
    }
}
