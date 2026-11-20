package com.memo494.posetracker.diagnostics

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.model.ModelInputText
import com.memo494.posetracker.core.modelimport.ImportedModelMeta
import com.memo494.posetracker.core.stats.Percentiles
import com.memo494.posetracker.modelimport.ModelSource
import com.memo494.posetracker.modelimport.ModelStatus
import java.util.Locale

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
    private const val NOT_AVAILABLE = "n/a"
    private const val MS_DECIMALS = 1
    private const val THERMAL_DECIMALS = 2

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
        line(out, "tracksShown", stats.tracksShown)
        line(out, "tracksMostAtOnce", stats.tracksMostAtOnce)
        line(out, "trackBirths", stats.trackBirths)
        line(out, "trackConfirmations", stats.trackConfirmations)
        line(out, "trackIdlePromotions", stats.trackIdlePromotions)
        line(out, "trackRecoveries", stats.trackRecoveries)
        line(out, "trackRemovals", stats.trackRemovals)
        line(out, "locks", stats.locks)
        line(out, "lockRelocks", stats.lockRelocks)
        line(out, "lockRelockRejects", stats.lockRelockRejects)
        line(out, "lockExpired", stats.lockExpired)
        line(out, "assocMisses", stats.assocMisses)
        line(out, "assocMissGate", stats.assocMissGate)
        line(out, "assocMissCost", stats.assocMissCost)
        line(out, "assocMissTaken", stats.assocMissTaken)
        line(out, "assocMissLowBirth", stats.assocMissLowBirth)
        line(out, "assocBirthNearLive", stats.assocBirthNearLive)
        line(out, "assocBirthAfterRemoval", stats.assocBirthAfterRemoval)
        appendModel(out, stats.model)
        appendTiming(out, stats)
    }

    /**
     * The timing percentiles (`timingSamples`, then `<name>MsP50` / `<name>MsP95` for copy, pre, infer, post, frame
     * and resultAge) and the thermal reading. A value that is unknown (no sample, NaN, infinite) is `n/a`, never a
     * number made up.
     */
    private fun appendTiming(
        out: StringBuilder,
        stats: CaptureStats,
    ) {
        val timing = stats.timing
        line(out, "timingSamples", timing.samples)
        percentileLines(out, "copyMs", timing.copyMs)
        percentileLines(out, "preMs", timing.preMs)
        percentileLines(out, "inferMs", timing.inferMs)
        percentileLines(out, "postMs", timing.postMs)
        percentileLines(out, "frameMs", timing.frameMs)
        percentileLines(out, "resultAgeMs", timing.resultAgeMs)
        line(out, "thermalStatus", stats.thermalStatus)
        line(out, "thermalHeadroom", decimals(stats.thermalHeadroom, THERMAL_DECIMALS))
    }

    private fun percentileLines(
        out: StringBuilder,
        name: String,
        percentiles: Percentiles,
    ) {
        line(out, "${name}P50", oneDecimal(percentiles.p50))
        line(out, "${name}P95", oneDecimal(percentiles.p95))
    }

    /** [value] with one decimal and a dot, or `n/a` when it is NaN or infinite. */
    private fun oneDecimal(value: Float): String = decimals(value, MS_DECIMALS)

    /**
     * [value] with [places] decimals and a dot, or `n/a` when it is NaN or infinite. The thermal headroom uses two
     * decimals so that 0.95 is not printed as 1.0, which would read as "throttling" although it was not measured.
     */
    private fun decimals(
        value: Float,
        places: Int,
    ): String = if (value.isFinite()) String.format(Locale.ROOT, "%.${places}f", value) else NOT_AVAILABLE

    /**
     * The model of the session: `modelFile`, `modelSha256` (first [TrackerConfig.SHA256_SHORT_CHARS] hex characters)
     * and `modelOutputRows` describe the imported model in use (`builtin` / `none` otherwise); a refused imported
     * model is named in `modelFallbackFile`. The model fields describe the model loaded now; `modelPortrait` and
     * `modelLandscape` (`builtin` or `name sha-prefix`) say what each orientation is set to, `modelSwitches` how many
     * times a change of orientation changed the model since Start and `modelSwitchMsMax` the longest change (ms).
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
        line(out, "modelPortrait", oneLine(model.portrait))
        line(out, "modelLandscape", oneLine(model.landscape))
        line(out, "modelSwitches", model.switches)
        line(out, "modelSwitchMsMax", model.switchMsMax)
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
