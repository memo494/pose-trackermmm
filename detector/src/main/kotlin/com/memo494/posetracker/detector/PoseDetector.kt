package com.memo494.posetracker.detector

import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.DetectionFrame
import java.io.Closeable

/**
 * Finds people in one captured frame and returns their boxes and keypoints in image space (ARCHITECTURE §6.1).
 *
 * Thread-confined: create, [detect] and [close] on one thread (the capture thread, ARCHITECTURE §5). The
 * model is created on that thread and must be run and closed there; never use a detector on the main thread.
 */
interface PoseDetector : Closeable {
    /** Width of the model input, in model pixels (Mdl space); read from the model, never assumed. */
    val inputWidth: Int

    /** Height of the model input, in model pixels (Mdl space); read from the model, never assumed. */
    val inputHeight: Int

    /** The compute backend that actually runs the model, which can be [Accelerator.CPU] although GPU was asked for. */
    val accelerator: Accelerator

    /**
     * Model run plus output readback of the last [detect] call, in milliseconds; 0 before the first call.
     * The source of `OverlayState.inferenceMs`.
     */
    val lastInferenceMs: Float

    /**
     * Diagnostics of the last [detect] call: the highest raw person score over all model output rows, before
     * any threshold (unitless; NaN rows are skipped). [NOT_MEASURED] (negative) when the detector does not
     * measure it or before the first call.
     */
    val lastMaxRawScore: Float
        get() = NOT_MEASURED.toFloat()

    /** Diagnostics: model output rows of the last [detect] call whose raw score passed the score threshold; [NOT_MEASURED] if unknown. */
    val lastRawRowsAboveThreshold: Int
        get() = NOT_MEASURED

    /** Diagnostics: model output rows of the last [detect] call with a NaN score (garbage output); [NOT_MEASURED] if unknown. */
    val lastNanRawRows: Int
        get() = NOT_MEASURED

    /**
     * Diagnostics: pre-processing (image to model input) of the last [detect] call, in milliseconds.
     * [NOT_MEASURED] (negative) when the detector does not measure it or before the first call.
     */
    val lastPreMs: Float
        get() = NOT_MEASURED.toFloat()

    /**
     * Diagnostics: post-processing (model output to detections) of the last [detect] call, in milliseconds.
     * [NOT_MEASURED] (negative) when the detector does not measure it or before the first call.
     */
    val lastPostMs: Float
        get() = NOT_MEASURED.toFloat()

    /**
     * Detects the people in [frame].
     *
     * The result is in Img space (pixels of the capture content rect) and carries the timestamp, its source and
     * the size of [frame]. The detector reads [frame] only during this call and keeps neither the frame nor its
     * pixel array.
     *
     * @throws IllegalArgumentException when [frame] is empty (a size that is not positive).
     * @throws IllegalStateException when called after [close] or from a thread other than the owner.
     */
    fun detect(frame: CaptureFrame): DetectionFrame

    companion object {
        /** Value of the diagnostics properties when they are not measured. */
        const val NOT_MEASURED = -1
    }
}
