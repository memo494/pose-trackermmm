package com.memo494.posetracker.detector

import android.content.Context
import android.graphics.Bitmap
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.capture.CaptureFrame
import com.memo494.posetracker.core.model.PoseDetection
import com.ultralytics.yolo.PoseEstimator
import java.io.File
import java.nio.ByteBuffer

/**
 * The production [PoseEngine]: the vendored Ultralytics [PoseEstimator] (LiteRT `CompiledModel`) behind
 * [CaptureFrame]. Android glue, not covered by JVM tests; kept small on purpose.
 *
 * Thread-confined: created, used and closed on one thread (the capture thread). Per frame it allocates nothing
 * of its own: one source [Bitmap] and one [ByteBuffer] wrapper are reused, the bitmap is re-created only when
 * the frame size changes. The vendored decoder still allocates per frame until M1 (ARCHITECTURE §2.1).
 *
 * There is deliberately **no warm-up call** here: the vendored `LiteRtModel` already runs one inference inside
 * its accelerator ladder (GPU, then CPU), so shader compilation has happened before the first frame.
 *
 * The wrapper keeps a reference to the capture thread's pixel array only to avoid re-wrapping it every frame;
 * the array is read only inside [detect] and the reference is dropped by [close].
 */
internal class UltralyticsPoseEngine private constructor(
    private val estimator: PoseEstimator,
    override val accelerator: Accelerator,
    override val inputSize: Int,
) : PoseEngine {
    private var inferenceMs = 0f
    private var sourceBitmap: Bitmap? = null
    private var wrappedArray: ByteArray? = null
    private var wrappedBuffer: ByteBuffer? = null
    private var closed = false

    override val lastInferenceMs: Float
        get() = inferenceMs

    override fun detect(frame: CaptureFrame): List<PoseDetection> {
        check(!closed) { "UltralyticsPoseEngine is closed" }
        val bitmap = sourceBitmapFor(frame.widthImg, frame.heightImg)
        val pixels = bufferFor(frame.rgba)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        val result = estimator.predict(bitmap, frame.widthImg, frame.heightImg)
        inferenceMs = result.inferenceMs.toFloat()
        return YoloResultMapper.toDetections(result)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            estimator.close()
        } finally {
            sourceBitmap?.recycle()
            sourceBitmap = null
            wrappedArray = null
            wrappedBuffer = null
        }
    }

    private fun sourceBitmapFor(
        widthImg: Int,
        heightImg: Int,
    ): Bitmap {
        val current = sourceBitmap
        if (current != null && current.width == widthImg && current.height == heightImg) return current
        current?.recycle()
        sourceBitmap = null
        val created = Bitmap.createBitmap(widthImg, heightImg, Bitmap.Config.ARGB_8888)
        created.setHasAlpha(false)
        sourceBitmap = created
        return created
    }

    private fun bufferFor(rgba: ByteArray): ByteBuffer {
        val cached = wrappedBuffer
        if (cached != null && wrappedArray === rgba) return cached
        val wrapped = ByteBuffer.wrap(rgba)
        wrappedArray = rgba
        wrappedBuffer = wrapped
        return wrapped
    }

    companion object {
        private const val LABEL_PERSON = "person"
        private const val ACCELERATOR_GPU = "GPU"
        private const val ACCELERATOR_CPU = "CPU"

        /**
         * Loads the model at [modelFile] on the calling thread. [useGpu] asks for the GPU; the vendored model
         * falls back to the CPU by itself and [accelerator] reports what it got. Nothing is leaked when the
         * model turns out to be unusable: the estimator is closed before the exception propagates.
         *
         * @param scoreThreshold minimum person confidence, unitless in [0, 1].
         * @throws IllegalStateException when the input layout was not recognised as NCHW `[1,3,S,S]`, the
         *   input is not square, or the accelerator is neither GPU nor CPU; LiteRT exceptions for a model
         *   that cannot be loaded.
         */
        fun create(
            context: Context,
            modelFile: File,
            useGpu: Boolean,
            scoreThreshold: Float,
        ): UltralyticsPoseEngine {
            val estimator =
                PoseEstimator(
                    context,
                    modelFile.absolutePath,
                    listOf(LABEL_PERSON),
                    useGpu,
                    scoreThreshold,
                    TrackerConfig.MAX_DETECTIONS_PER_FRAME,
                )
            try {
                return wrap(estimator)
            } catch (e: Throwable) {
                runCatching { estimator.close() }
                throw e
            }
        }

        private fun wrap(estimator: PoseEstimator): UltralyticsPoseEngine {
            check(estimator.inputUsesNchw) { "model input layout was not recognised as NCHW [1,3,S,S]" }
            val modelInput = estimator.inputSize
            check(modelInput.width == modelInput.height) {
                "model input ${modelInput.width}x${modelInput.height} is not square"
            }
            val accelerator =
                when (val name = estimator.accelerator) {
                    ACCELERATOR_GPU -> Accelerator.GPU
                    ACCELERATOR_CPU -> Accelerator.CPU
                    else -> error("unknown accelerator \"$name\"")
                }
            return UltralyticsPoseEngine(estimator, accelerator, modelInput.width)
        }
    }
}
