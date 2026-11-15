package com.memo494.posetracker.detector

import android.util.Log
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.DetectionFrame
import com.memo494.posetracker.core.model.PoseDetection

/**
 * The [PoseDetector] over a [PoseEngine]: owns the GPU-then-CPU ladder, the score threshold, the cap on the
 * number of people and the thread rule. Built with [create] (or `PoseDetectors.fromAssets`).
 *
 * Thread-confined: the owner is the thread that called [create]. [detect] and [close] from any other thread
 * throw [IllegalStateException], so a misplaced call fails loudly instead of racing the model.
 *
 * Allocation: besides the returned [DetectionFrame] this class allocates only when it has to drop
 * detections (one list); when nothing is dropped the engine's list is returned as it is.
 */
class LiteRtPoseDetector private constructor(
    private val engine: PoseEngine,
    private val scoreThreshold: Float,
) : PoseDetector {
    private val ownerThread: Thread = Thread.currentThread()
    private var closed = false

    override val inputWidth: Int
        get() = engine.inputWidth

    override val inputHeight: Int
        get() = engine.inputHeight

    override val accelerator: Accelerator
        get() = engine.accelerator

    override val lastInferenceMs: Float
        get() = engine.lastInferenceMs

    override val lastMaxRawScore: Float
        get() = engine.lastMaxRawScore

    override val lastRawRowsAboveThreshold: Int
        get() = engine.lastRawRowsAboveThreshold

    override val lastNanRawRows: Int
        get() = engine.lastNanRawRows

    override val lastPreMs: Float
        get() = engine.lastPreMs

    override val lastPostMs: Float
        get() = engine.lastPostMs

    override fun detect(frame: CaptureFrame): DetectionFrame {
        checkOwnerThread("detect")
        check(!closed) { "LiteRtPoseDetector is closed" }
        require(frame.widthImg > 0 && frame.heightImg > 0) {
            "frame is empty: ${frame.widthImg}x${frame.heightImg}"
        }
        val detections = select(engine.detect(frame))
        return DetectionFrame(frame.timestampNs, frame.timestampSource, frame.widthImg, frame.heightImg, detections)
    }

    override fun close() {
        checkOwnerThread("close")
        if (closed) return
        closed = true
        engine.close()
    }

    private fun checkOwnerThread(action: String) {
        check(Thread.currentThread() === ownerThread) {
            "$action must be called on the owner thread \"${ownerThread.name}\""
        }
    }

    /**
     * The detections with `score >= scoreThreshold` (a NaN score fails that test and is dropped), at most
     * [TrackerConfig.MAX_DETECTIONS_PER_FRAME] of them, in the engine's order. Returns [raw] itself when
     * nothing has to be dropped.
     */
    private fun select(raw: List<PoseDetection>): List<PoseDetection> {
        var passing = 0
        for (i in raw.indices) {
            if (raw[i].score >= scoreThreshold) passing++
        }
        if (passing == raw.size && passing <= TrackerConfig.MAX_DETECTIONS_PER_FRAME) return raw
        val kept = ArrayList<PoseDetection>(minOf(passing, TrackerConfig.MAX_DETECTIONS_PER_FRAME))
        for (i in raw.indices) {
            if (kept.size == TrackerConfig.MAX_DETECTIONS_PER_FRAME) break
            if (raw[i].score >= scoreThreshold) kept.add(raw[i])
        }
        return kept
    }

    companion object {
        private const val TAG = "LiteRtPoseDetector"

        /**
         * Creates the engine on the calling thread, which becomes the owner of the detector.
         *
         * Ladder: GPU first; when that throws any [Exception] it is logged and the CPU is tried; an exception
         * from the CPU attempt propagates. The reported [accelerator] is what the engine says it runs on, not
         * what was asked for.
         *
         * @param expectedInputWidth the model input width the caller expects, in model pixels (Mdl space).
         * @param expectedInputHeight the model input height the caller expects, in model pixels (Mdl space).
         * @throws IllegalStateException when the engine's input width or height differs from the expected one;
         *   the engine is closed first (fail closed: a model of another size would break the Mdl-to-Img mapping).
         */
        internal fun create(
            factory: PoseEngineFactory,
            scoreThreshold: Float,
            expectedInputWidth: Int,
            expectedInputHeight: Int,
        ): LiteRtPoseDetector {
            val engine = createEngine(factory)
            if (engine.inputWidth != expectedInputWidth || engine.inputHeight != expectedInputHeight) {
                val actualWidth = engine.inputWidth
                val actualHeight = engine.inputHeight
                engine.close()
                throw IllegalStateException(
                    "model input size mismatch: expected ${expectedInputWidth}x$expectedInputHeight " +
                        "but the model has ${actualWidth}x$actualHeight",
                )
            }
            return LiteRtPoseDetector(engine, scoreThreshold)
        }

        private fun createEngine(factory: PoseEngineFactory): PoseEngine =
            try {
                factory.create(Accelerator.GPU)
            } catch (e: Exception) {
                Log.w(TAG, "GPU pose engine failed, falling back to CPU", e)
                factory.create(Accelerator.CPU)
            }
    }
}
