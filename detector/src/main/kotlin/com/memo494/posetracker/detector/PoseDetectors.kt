package com.memo494.posetracker.detector

import android.content.Context
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelpack.ModelInfo
import com.memo494.posetracker.core.modelpack.ModelStager
import java.io.File

/** Builds the production [PoseDetector]. */
object PoseDetectors {
    /** Directory under `noBackupFilesDir` that holds the staged model files. */
    private const val MODELS_DIRECTORY = "models"

    /**
     * Creates the detector for the model size in [config]: reads `model_info.json` from the assets, stages the
     * matching `.tflite` file into `noBackupFilesDir/models` (its SHA-256 is checked before LiteRT opens it by
     * path), loads it GPU-first with a CPU fallback and checks that the model reports the configured input size.
     *
     * Call on the thread that will own the detector (the capture thread) and use and close it there. Never call
     * it on the main thread: it reads and hashes several megabytes and compiles the model.
     *
     * @throws java.io.IOException when an asset cannot be read or the staging fails, including
     *   `ModelIntegrityException` when the file does not match its SHA-256.
     * @throws IllegalArgumentException when `model_info.json` is invalid or has no entry for the size.
     * @throws IllegalStateException when the model is unusable (input layout not NCHW, input not square, unknown
     *   accelerator, input size different from the configured one).
     * @throws Exception LiteRT exceptions when the CPU attempt cannot load the model either.
     */
    fun fromAssets(
        context: Context,
        config: TrackerConfig,
    ): PoseDetector {
        val appContext = context.applicationContext
        val infoText = appContext.assets.open(ModelInfo.ASSET_NAME).use { it.readBytes().decodeToString() }
        val entry = ModelInfo.parse(infoText).entryFor(config.modelInputSize)
        val stager = ModelStager(File(appContext.noBackupFilesDir, MODELS_DIRECTORY))
        val modelFile = stager.stage(entry.fileName, entry.sha256) { appContext.assets.open(entry.fileName) }
        val factory =
            PoseEngineFactory { accelerator ->
                val useGpu = accelerator == Accelerator.GPU
                UltralyticsPoseEngine.create(appContext, modelFile, useGpu, config.scoreThreshold)
            }
        return LiteRtPoseDetector.create(factory, config.scoreThreshold, entry.inputSize)
    }
}
