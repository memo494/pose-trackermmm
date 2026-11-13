package com.memo494.posetracker.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Builds the share-sheet intent for the diagnostics zip. */
internal object DiagnosticsShare {
    private const val AUTHORITY_SUFFIX = ".diagnostics"
    private const val ZIP_MIME_TYPE = "application/zip"

    /**
     * An `ACTION_SEND` chooser for [file], which must be inside the cache `diagnostics/` directory. The read
     * grant is given through the content URI, in the flags and in the clip data so it reaches the chooser.
     * Throws `IllegalArgumentException` when [file] is outside the provider's paths.
     */
    fun chooser(
        context: Context,
        file: File,
        title: String,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val send = Intent(Intent.ACTION_SEND)
        send.type = ZIP_MIME_TYPE
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.clipData = ClipData.newRawUri(file.name, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }
}
