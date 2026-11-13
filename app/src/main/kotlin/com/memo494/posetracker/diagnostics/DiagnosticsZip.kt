package com.memo494.posetracker.diagnostics

import android.content.Context
import android.os.Build
import com.memo494.posetracker.BuildConfig
import com.memo494.posetracker.BuildLabel
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelpack.ModelInfo
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collects what a bug report needs and writes it as one zip in the cache directory. It holds no secrets:
 * only app and device info, the settings, the capture statistics, the app log, `model_info.json` and the
 * last crash. File and zip work: call it off the main thread.
 */
internal object DiagnosticsZip {
    /** Directory inside `cacheDir`; the `FileProvider` paths file (`diagnostics_paths.xml`) names the same one. */
    const val DIRECTORY = "diagnostics"

    /** Name of the zip; each call overwrites the previous one. */
    const val FILE_NAME = "pose-tracker-diagnostics.zip"

    /** Builds and writes the zip, returning the file. Throws when the file cannot be written. */
    fun write(context: Context): File {
        val entries =
            DiagnosticsReport.entries(
                appInfo = appInfo(),
                deviceInfo = deviceInfo(context),
                config = TrackerConfig().toString(),
                stats = CaptureStatsHolder.latest,
                logLines = AppLog.lines(),
                modelInfo = readModelInfo(context),
                lastCrash = CrashStore.read(context),
            )
        val directory = File(context.cacheDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        val file = File(directory, FILE_NAME)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun appInfo(): Map<String, String> =
        linkedMapOf(
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE.toString(),
            "gitSha" to BuildConfig.GIT_SHA,
            "buildLabel" to BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
        )

    private fun deviceInfo(context: Context): Map<String, String> {
        val metrics = context.resources.displayMetrics
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "androidRelease" to Build.VERSION.RELEASE,
            "sdkLevel" to Build.VERSION.SDK_INT.toString(),
            "displayPx" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "densityDpi" to metrics.densityDpi.toString(),
        )
    }

    private fun readModelInfo(context: Context): String? =
        try {
            context.assets.open(ModelInfo.ASSET_NAME).use { it.readBytes().decodeToString() }
        } catch (e: Throwable) {
            AppLog.log("model_info.json could not be read for the diagnostics", e)
            null
        }
}
