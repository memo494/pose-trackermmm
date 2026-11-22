package com.memo494.posetracker.detector

import android.content.Context
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.modelpack.ModelInfo
import com.memo494.posetracker.core.modelpack.ModelIntegrityException
import com.memo494.posetracker.core.modelpack.ModelStager
import com.memo494.posetracker.core.modelpack.Sha256
import java.io.File
import java.io.FileNotFoundException

/** Builds the production [PoseDetector]. */
object PoseDetectors {
    /** Directory under `noBackupFilesDir` that holds the staged model files. */
    private const val MODELS_DIRECTORY = "models"

    /**
     * Creates the detector for the model size in [config]: reads `model_info.json` from the assets, stages the
     * matching `.tflite` file into `noBackupFilesDir/models` (its SHA-256 is checked before LiteRT opens it by
     * path), loads it GPU-first with a CPU fallback and checks that the model reports the configured square input size.
     *
     * Call on the thread that will own the detector (the capture thread) and use and close it there. Never call
     * it on the main thread: it reads and hashes several megabytes and compiles the model.
     *
     * @throws java.io.IOException when an asset cannot be read or the staging fails, including
     *   `ModelIntegrityException` when the file does not match its SHA-256.
     * @throws IllegalArgumentException when `model_info.json` is invalid or has no entry for the size.
     * @throws IllegalStateException when the model is unusable (input layout not NCHW, unknown accelerator,
     *   input size different from the configured square one).
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
                UltralyticsPoseEngine.create(appContext, modelFile, useGpu, config.scoreThreshold, config.rawNmsIou)
            }
        return LiteRtPoseDetector.create(factory, config.scoreThreshold, config.modelInputSize, config.modelInputSize)
    }

    /**
     * Creates the detector for a model the owner imported (M0c): the same GPU-first, CPU-fallback path as
     * [fromAssets], but over [file] as it is, with no assets and no staging. [file] must be the app's private copy
     * that `ImportedModelRules` accepted; [meta] is what the importer recorded for it.
     *
     * Before LiteRT opens the file it must exist, have exactly [ImportedModelMeta.bytes] bytes and hash to
     * [ImportedModelMeta.sha256] (see [verifyImportedFile]). The detector then has to report
     * [ImportedModelMeta.inputWidth] x [ImportedModelMeta.inputHeight] as its input size.
     *
     * Call on the thread that will own the detector (the capture thread) and use and close it there. Never call
     * it on the main thread: it hashes the file and compiles the model. The caller (the session start) falls back
     * to the built-in model, [fromAssets], on any exception listed here, and reports it.
     *
     * @throws FileNotFoundException when [file] does not exist or is not a regular file.
     * @throws ModelIntegrityException (an [java.io.IOException]) when the length or the SHA-256 differs from
     *   [meta]; [fromAssets] throws the same type for its hash mismatch.
     * @throws java.io.IOException when the file cannot be read.
     * @throws IllegalStateException when the model is unusable (input layout not NCHW, unknown accelerator,
     *   input width or height different from the meta's).
     * @throws Exception LiteRT exceptions when the CPU attempt cannot load the model either.
     */
    fun fromImported(
        context: Context,
        file: File,
        meta: ImportedModelMeta,
        config: TrackerConfig,
    ): PoseDetector {
        verifyImportedFile(file, meta)
        val appContext = context.applicationContext
        val factory =
            PoseEngineFactory { accelerator ->
                val useGpu = accelerator == Accelerator.GPU
                UltralyticsPoseEngine.create(appContext, file, useGpu, config.scoreThreshold, config.rawNmsIou)
            }
        return LiteRtPoseDetector.create(factory, config.scoreThreshold, meta.inputWidth, meta.inputHeight)
    }

    /**
     * The checks [fromImported] makes before LiteRT opens [file]: it exists as a file, its length is
     * [ImportedModelMeta.bytes] (checked first, so a wrong file is refused without reading it) and its SHA-256 is
     * [ImportedModelMeta.sha256]. Reads the whole file once, so worker thread only.
     *
     * @throws FileNotFoundException when [file] is missing or not a regular file.
     * @throws ModelIntegrityException when the length or the hash differs.
     * @throws java.io.IOException when the file cannot be read.
     */
    internal fun verifyImportedFile(
        file: File,
        meta: ImportedModelMeta,
    ) {
        if (!file.isFile) throw FileNotFoundException("imported model file is missing: ${file.name}")
        val actualBytes = file.length()
        if (actualBytes != meta.bytes) {
            throw ModelIntegrityException("imported model ${file.name} has $actualBytes bytes but ${meta.bytes} were expected")
        }
        val actualSha256 = file.inputStream().use { Sha256.hex(it) }
        if (actualSha256 != meta.sha256) {
            throw ModelIntegrityException(
                "imported model ${file.name} is corrupt: expected sha256 ${meta.sha256} but got $actualSha256",
            )
        }
    }
}
