package com.memo494.posetracker.detector

import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.PoseDetection
import java.io.Closeable

/**
 * The model behind [LiteRtPoseDetector]: one loaded network that turns a frame into raw detections.
 *
 * A seam so that the accelerator ladder, the score filter and the thread rule can be tested on the JVM
 * without LiteRT. The production implementation is [UltralyticsPoseEngine]. Thread-confined like the
 * detector that owns it.
 */
internal interface PoseEngine : Closeable {
    /** The backend that actually runs the model. */
    val accelerator: Accelerator

    /** Width of the model input, in model pixels (Mdl space), read from the model. */
    val inputWidth: Int

    /** Height of the model input, in model pixels (Mdl space), read from the model. */
    val inputHeight: Int

    /** Model run plus output readback of the last [detect], in milliseconds; 0 before the first call. */
    val lastInferenceMs: Float

    /** Highest raw person score of the last [detect] over all output rows (see [PoseDetector.lastMaxRawScore]). */
    val lastMaxRawScore: Float
        get() = PoseDetector.NOT_MEASURED.toFloat()

    /** Output rows of the last [detect] whose raw score passed the engine's threshold; [PoseDetector.NOT_MEASURED] if unknown. */
    val lastRawRowsAboveThreshold: Int
        get() = PoseDetector.NOT_MEASURED

    /** Output rows of the last [detect] with a NaN score; [PoseDetector.NOT_MEASURED] if unknown. */
    val lastNanRawRows: Int
        get() = PoseDetector.NOT_MEASURED

    /**
     * Runs the model on [frame] and returns every person it found, in Img space, in the order the model
     * ranks them, without any score or count limit applied.
     */
    fun detect(frame: CaptureFrame): List<PoseDetection>
}

/** Creates a [PoseEngine] on the calling thread. */
internal fun interface PoseEngineFactory {
    /**
     * Creates an engine that asks for [accelerator]; the engine's own `accelerator` says what it got.
     * Throws when the model cannot be loaded.
     */
    fun create(accelerator: Accelerator): PoseEngine
}
