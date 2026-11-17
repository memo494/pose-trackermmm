package com.memo494.posetracker.diagnostics

/** Short notice shown under the Share and Save diagnostics buttons: a failure, or [SAVED]. */
internal enum class DiagnosticsNotice {
    /** The zip could not be written. */
    PREPARE_FAILED,

    /** The zip exists but the share sheet could not be opened. */
    SHARE_FAILED,

    /** The zip was saved to the place the user picked (the only notice that is not a failure). */
    SAVED,

    /** The zip could not be saved to the place the user picked. */
    SAVE_FAILED,
}

/**
 * What the diagnostics part of the control screen shows. Plain screen state kept by the activity; it is
 * separate from the control reducer on purpose.
 *
 * @property logVisible whether the log is shown.
 * @property logText the log lines shown (newest last, one text); only meaningful while [logVisible].
 * @property crashNotice whether the "stopped unexpectedly last time" notice is shown.
 * @property sharing `true` while the zip is being prepared or saved, so the buttons cannot be pressed twice.
 * @property notice a failure (or [DiagnosticsNotice.SAVED]) to tell the user about; `null` = none.
 */
internal data class DiagnosticsUiState(
    val logVisible: Boolean = false,
    val logText: String = "",
    val crashNotice: Boolean = false,
    val sharing: Boolean = false,
    val notice: DiagnosticsNotice? = null,
)
