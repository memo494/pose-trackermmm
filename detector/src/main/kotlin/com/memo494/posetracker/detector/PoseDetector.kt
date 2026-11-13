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
    /** Side of the square model input, in model pixels (Mdl space); read from the model, never assumed. */
    val inputSize: Int

    /** The compute backend that actually runs the model, which can be [Accelerator.CPU] although GPU was asked for. */
    val accelerator: Accelerator

    /**
     * Model run plus output readback of the last [detect] call, in milliseconds; 0 before the first call.
     * The source of `OverlayState.inferenceMs`.
     */
    val lastInferenceMs: Float

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
}
