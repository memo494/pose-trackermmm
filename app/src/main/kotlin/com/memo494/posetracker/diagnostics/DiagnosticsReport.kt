package com.memo494.posetracker.diagnostics

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
    }

    private fun line(
        out: StringBuilder,
        key: String,
        value: Any,
    ) {
        out.append("$key=$value\n")
    }

    private fun withNewline(text: String): String = if (text.endsWith("\n")) text else "$text\n"
}
