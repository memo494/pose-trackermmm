package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.ModelInputText
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus

/** One file of the diagnostics zip: its [name] inside the zip and its [text]. */
internal class ReportEntry(
    val name: String,
    val text: String,
)

/**
 * Builds the files of the diagnostics zip from already collected values. Pure and deterministic: the same
 * inputs give the same entries in the same order; a missing part becomes an entry that says it is missing,
 * never a silent omission.
 */
internal object DiagnosticsReport {
    private const val SUMMARY = "summary.txt"
    private const val CONFIG = "config.txt"
    private const val LOG = "log.txt"
    private const val MODEL_INFO_JSON = "model_info.json"
    private const val MODEL_INFO_MISSING = "model_info.txt"
    private const val LAST_CRASH = "last_crash.txt"
    private const val NO_SESSION = "capture: no session yet"
    private const val EMPTY_LOG = "(log is empty)"
    private const val MODEL_INFO_UNREADABLE = "model_info.json could not be read from the app assets."
    private const val NO_CRASH = "no crash recorded"
    private const val UNKNOWN = "unknown"
    private const val NONE = "none"
    private const val SOURCE_BUILT_IN = "builtin"
    private const val SOURCE_IMPORTED = "imported"

    /**
     * Always five entries in this order: `summary.txt`, `config.txt`, `log.txt`, `model_info.json` (named
     * `model_info.txt` and holding a sentence when [modelInfo] is `null`) and `last_crash.txt`.
     *
     * @param appInfo version, SHA and similar, listed in the map's iteration order.
     * @param deviceInfo device and OS values, listed in the map's iteration order.
     * @param config the printed `TrackerConfig`.
     * @param stats the last capture snapshot; `null` = no capture session has run yet.
     * @param logLines the in-app log, oldest first.
     * @param modelInfo text of `model_info.json`; `null` = it could not be read.
     * @param lastCrash text of the last saved crash; `null` = none.
     */
    fun entries(
        appInfo: Map<String, String>,
        deviceInfo: Map<String, String>,
        config: String,
        stats: CaptureStats?,
        logLines: List<String>,
        modelInfo: String?,
        lastCrash: String?,
    ): List<ReportEntry> =
        listOf(
            ReportEntry(SUMMARY, summary(appInfo, deviceInfo, stats)),
            ReportEntry(CONFIG, withNewline(config)),
            ReportEntry(LOG, if (logLines.isEmpty()) "$EMPTY_LOG\n" else logLines.joinToString("\n", postfix = "\n")),
            modelEntry(modelInfo),
            ReportEntry(LAST_CRASH, withNewline(lastCrash ?: NO_CRASH)),
        )

    private fun modelEntry(modelInfo: String?): ReportEntry =
        if (modelInfo != null) {
            ReportEntry(MODEL_INFO_JSON, modelInfo)
        } else {
            ReportEntry(MODEL_INFO_MISSING, "$MODEL_INFO_UNREADABLE\n")
        }

    private fun summary(
        appInfo: Map<String, String>,
        deviceInfo: Map<String, String>,
        stats: CaptureStats?,
    ): String {
        val out = StringBuilder()
        out.append("[app]\n")
        appInfo.forEach { (key, value) -> line(out, key, value) }
        out.append("[device]\n")
        deviceInfo.forEach { (key, value) -> line(out, key, value) }
        out.append("[capture]\n")
        if (stats == null) {
            out.append(NO_SESSION).append('\n')
        } else {
            appendStats(out, stats)
        }
        return out.toString()
    }

    private fun appendStats(
        out: StringBuilder,
        stats: CaptureStats,
    ) {
        line(out, "accelerator", stats.accelerator ?: UNKNOWN)
        line(out, "captureFps", stats.captureFps)
        line(out, "inferenceMs", stats.inferenceMs)
        line(out, "captureWidthImg", stats.captureWidthImg)
        line(out, "captureHeightImg", stats.captureHeightImg)
        line(out, "timestampSource", stats.timestampSource ?: UNKNOWN)
        line(out, "warning", stats.warning ?: NONE)
        line(out, "dotsVisible", stats.dotsVisible)
        line(out, "frames", stats.frames)
        line(out, "droppedSizeMismatch", stats.droppedSizeMismatch)
        line(out, "copyFailures", stats.copyFailures)
        line(out, "arrivalFallbacks", stats.arrivalFallbacks)
        line(out, "failures", stats.failures)
        line(out, "displayWidthPx", stats.displayWidthPx)
        line(out, "displayHeightPx", stats.displayHeightPx)
        line(out, "detections", stats.detections)
        line(out, "framesWithDetections", stats.framesWithDetections)
        line(out, "maxRawScore", stats.maxRawScore)
        line(out, "peakMaxRawScore", stats.peakMaxRawScore)
        line(out, "rawRowsAboveThreshold", stats.rawRowsAboveThreshold)
        line(out, "nanRawRows", stats.nanRawRows)
        line(out, "headXImg", stats.headXImg)
        line(out, "headYImg", stats.headYImg)
        appendModel(out, stats.model)
    }

    /**
     * The model of the session: `modelFile`, `modelSha256` (first [TrackerConfig.SHA256_SHORT_CHARS] hex characters)
     * and `modelOutputRows` describe the imported model in use (`builtin` / `none` otherwise); a refused imported
     * model is named in `modelFallbackFile`.
     */
    private fun appendModel(
        out: StringBuilder,
        model: ModelStatus?,
    ) {
        if (model == null) {
            line(out, "modelSource", UNKNOWN)
            return
        }
        val inUse = if (model.source == ModelSource.IMPORTED) model.meta else null
        line(out, "modelSource", if (model.source == ModelSource.IMPORTED) SOURCE_IMPORTED else SOURCE_BUILT_IN)
        line(out, "modelFile", inUse?.displayName ?: SOURCE_BUILT_IN)
        line(out, "modelSha256", inUse?.let(::shortSha) ?: NONE)
        line(out, "modelInputSize", ModelInputText.text(model.inputWidth, model.inputHeight))
        line(out, "modelOutputRows", inUse?.outputRows ?: NONE)
        line(out, "modelFallback", model.fallback?.name ?: NONE)
        line(out, "modelFallbackMessage", model.message?.let(::oneLine) ?: NONE)
        val refused = if (model.source == ModelSource.BUILT_IN) model.meta else null
        if (refused != null) line(out, "modelFallbackFile", "${refused.displayName} ${shortSha(refused)}")
    }

    private fun shortSha(meta: ImportedModelMeta): String = meta.sha256.take(TrackerConfig.SHA256_SHORT_CHARS)

    /** [text] with line breaks turned into spaces, so that it stays one `key=value` line. */
    private fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ')

    private fun line(
        out: StringBuilder,
        key: String,
        value: Any,
    ) {
        out.append("$key=$value\n")
    }

    private fun withNewline(text: String): String = if (text.endsWith("\n")) text else "$text\n"
}
