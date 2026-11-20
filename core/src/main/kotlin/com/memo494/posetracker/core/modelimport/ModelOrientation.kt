package com.memo494.posetracker.core.modelimport

/**
 * The two orientations a model can be chosen for (M1b-1). The orientation is the capture geometry: a capture
 * wider than high is [LANDSCAPE], everything else (taller than wide, square, unknown) is [PORTRAIT].
 *
 * @property logName the word the log line and `summary.txt` use.
 */
enum class ModelOrientation(
    val logName: String,
) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    ;

    companion object {
        /** The orientation of a capture of [widthImg] x [heightImg] pixels (Img space); `width > height` is landscape. */
        fun of(
            widthImg: Int,
            heightImg: Int,
        ): ModelOrientation = if (widthImg > heightImg) LANDSCAPE else PORTRAIT
    }
}
