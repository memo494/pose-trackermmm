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

    override val inputSize: Int
        get() = engine.inputSize

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
         * @param expectedInputSize the model input side the caller configured, in model pixels.
         * @throws IllegalStateException when the engine's input size differs from [expectedInputSize]; the
         *   engine is closed first (fail closed: a model of another size would break the Mdl-to-Img mapping).
         */
        internal fun create(
            factory: PoseEngineFactory,
            scoreThreshold: Float,
            expectedInputSize: Int,
        ): LiteRtPoseDetector {
            val engine = createEngine(factory)
            if (engine.inputSize != expectedInputSize) {
                val actual = engine.inputSize
                engine.close()
                throw IllegalStateException("model input size is $actual but $expectedInputSize was expected")
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
