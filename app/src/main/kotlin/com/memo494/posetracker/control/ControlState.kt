package com.memo494.posetracker.control

import com.memo494.posetracker.core.TrackerConfig

/** What the control screen knows. Pure data: [ControlReducer] is the only place that changes it. */
internal data class ControlState(
    /** `true` when "display over other apps" is granted; re-checked every time the screen resumes. */
    val overlayPermission: Boolean = false,
    /** `true` while the capture service has a session (mirrors `CaptureService.running`). */
    val running: Boolean = false,
    /** `true` from the moment a start is accepted until the consent dialog answers; Start is disabled meanwhile. */
    val awaitingConsent: Boolean = false,
    /** `true` once the notification permission was requested in this activity; it is asked at most once. */
    val notificationPermissionAsked: Boolean = false,
    /** A message the screen shows next to the controls, or `null` when there is nothing to tell. */
    val notice: ControlNotice? = null,
    /** The model the next start will use, as last read from the model store. */
    val activeModel: ActiveModel = ActiveModel.BuiltIn,
    /** `true` from the moment a picked file is handed to the importer until its result arrives; Start is disabled. */
    val importing: Boolean = false,
    /** What the last import said; `null` before any import, after a new one starts and after "Use built-in model". */
    val importResult: ImportSummary? = null,
    /** Why the built-in model is used although an imported one may be expected, or `null`. */
    val modelNotice: ModelNotice? = null,
    /** Side of the built-in model's square input, in model pixels (Mdl), for the "Built-in (416)" line. */
    val builtInInputSize: Int = TrackerConfig.DEFAULT_MODEL_INPUT_SIZE,
)

/** The model the next capture start will use. Pure data: no files, no Android types. */
internal sealed interface ActiveModel {
    /** The model bundled with the app (its input size is `TrackerConfig.modelInputSize`). */
    data object BuiltIn : ActiveModel

    /** The imported model [displayName] (a sanitised file name) with a square input of [inputSize] model pixels. */
    data class Imported(
        val displayName: String,
        val inputSize: Int,
    ) : ActiveModel
}

/** Why a picked file was refused: one value per rule of the importer, so each has its own sentence. */
internal enum class ModelRejectionReason {
    /** The file is smaller than any model can be. */
    TOO_SMALL,

    /** The file is larger than the import limit. */
    TOO_LARGE,

    /** The file ended before its type could be checked. */
    HEADER_TOO_SHORT,

    /** The file is not a TensorFlow Lite file. */
    NOT_TFLITE,

    /** The model runtime could not open or read the file. */
    UNREADABLE,

    INPUT_COUNT,
    INPUT_TYPE,
    INPUT_NOT_NCHW,
    INPUT_NHWC,
    INPUT_NOT_SQUARE,
    INPUT_SIZE_NOT_MULTIPLE,
    INPUT_SIZE_OUT_OF_RANGE,
    OUTPUT_COUNT,
    OUTPUT_TYPE,
    OUTPUT_CLASSIC_HEAD,
    OUTPUT_SHAPE,
}

/** What an import came to, as small pure data (the importer's own outcome types stay out of the state). */
internal sealed interface ImportSummary {
    /** The model [displayName] with input size [inputSize] is now the imported model. */
    data class Accepted(
        val displayName: String,
        val inputSize: Int,
    ) : ImportSummary

    /** The file was refused for [reason]; [details] names the size, the runtime's message or the shapes found. */
    data class Rejected(
        val reason: ModelRejectionReason,
        val details: String,
    ) : ImportSummary

    /** An unexpected error (not a verdict on the file); [message] is a short technical line. */
    data class Failed(
        val message: String,
    ) : ImportSummary
}

/** What the model block tells about the built-in fallback. */
internal enum class ModelNoticeKind {
    /** The imported model's file is gone. */
    FILE_MISSING,

    /** The app died while the runtime opened an imported file; imported models are switched off. */
    GUARD_TRIPPED_PROBE,

    /** The app died while the runtime loaded the imported model; it is switched off. */
    GUARD_TRIPPED_LOAD,

    /** The safety marker could not be saved, so the last session did not try the imported model. */
    GUARD_NOT_STORED,

    /** The last session could not load the imported model ([ModelNotice.message] says why). */
    LOAD_FAILED,

    /** What is stored about the imported model could not be read ([ModelNotice.message] says why). */
    STATE_UNREADABLE,
}

/** A [kind] of notice with the optional one-line technical [message] of the failure behind it. */
internal data class ModelNotice(
    val kind: ModelNoticeKind,
    val message: String? = null,
)

/** Messages the control screen can show. */
internal enum class ControlNotice {
    /** Start was pressed without the "display over other apps" permission. */
    OVERLAY_PERMISSION_NEEDED,

    /** The screen-capture consent was denied or cancelled, or the service could not be started. */
    CONSENT_DENIED,

    /** The capture service could not start or its setup failed; the diagnostics hold the reason. */
    START_FAILED,
}

/** Everything that can happen on the control screen. */
internal sealed interface ControlEvent {
    /** The overlay permission was read again ([granted] is the current answer). */
    data class OverlayPermissionChecked(
        val granted: Boolean,
    ) : ControlEvent

    /** The user pressed Start. */
    data object StartClicked : ControlEvent

    /** The notification permission dialog answered. The answer never blocks the start. */
    data class NotificationPermissionResult(
        val granted: Boolean,
    ) : ControlEvent

    /** The screen-capture consent dialog answered ([granted] is `false` for denied or cancelled). */
    data class ConsentResult(
        val granted: Boolean,
    ) : ControlEvent

    /** `CaptureService.running` changed. */
    data class RunningChanged(
        val running: Boolean,
    ) : ControlEvent

    /** The user pressed Stop. */
    data object StopClicked : ControlEvent

    /** A start failed after the consent (`CaptureService.startFailures` grew). */
    data object StartFailed : ControlEvent

    /** The user pressed the button that opens the "display over other apps" settings. */
    data object OpenOverlaySettingsClicked : ControlEvent

    /** The user pressed "Import model". */
    data object ImportClicked : ControlEvent

    /** The file picker answered; [uriString] is `null` when it was cancelled. */
    data class ImportPicked(
        val uriString: String?,
    ) : ControlEvent

    /** The importer finished with [summary]. */
    data class ImportFinished(
        val summary: ImportSummary,
    ) : ControlEvent

    /** The user pressed "Use built-in model". */
    data object UseBuiltInClicked : ControlEvent

    /** The model store (and the last session) was read off the main thread: the model and the notice to show. */
    data class ModelStateLoaded(
        val active: ActiveModel,
        val notice: ModelNotice?,
    ) : ControlEvent
}

/** The one side effect the activity must perform after a step. */
internal enum class ControlEffect {
    OPEN_OVERLAY_SETTINGS,
    REQUEST_NOTIFICATION_PERMISSION,
    LAUNCH_CONSENT,
    START_SERVICE,
    STOP_SERVICE,

    /** Open the system file picker; its answer comes back as `ImportPicked`. */
    OPEN_MODEL_PICKER,

    /** Import the file named by [ControlStep.importUri] on a worker thread; the answer is `ImportFinished`. */
    RUN_IMPORT,

    /** Delete the imported model on a worker thread, then read the model state again. */
    CLEAR_IMPORTED,

    /** Read the model store and the last session's model status on a worker thread; the answer is `ModelStateLoaded`. */
    LOAD_MODEL_STATE,
}

/**
 * Result of one reduction: the new [state] and the [effect] to perform, if any.
 * [importUri] is set only with [ControlEffect.RUN_IMPORT]: the picked document, as text.
 */
internal data class ControlStep(
    val state: ControlState,
    val effect: ControlEffect?,
    val importUri: String? = null,
)

/** Pure decision logic of the control screen: total (every event in every state answers) and free of Android. */
internal object ControlReducer {
    /**
     * Applies [event] to [state]. [notificationPermissionMissing] is read by the caller at the moment of the
     * event (it is `true` only on API 33+ when POST_NOTIFICATIONS is not granted).
     */
    fun reduce(
        state: ControlState,
        event: ControlEvent,
        notificationPermissionMissing: Boolean,
    ): ControlStep =
        when (event) {
            is ControlEvent.OverlayPermissionChecked -> onOverlayChecked(state, event.granted)
            ControlEvent.OpenOverlaySettingsClicked -> ControlStep(state, ControlEffect.OPEN_OVERLAY_SETTINGS)
            ControlEvent.StartClicked -> onStart(state, notificationPermissionMissing)
            is ControlEvent.NotificationPermissionResult -> onNotificationResult(state)
            is ControlEvent.ConsentResult -> onConsent(state, event.granted)
            is ControlEvent.RunningChanged -> onRunning(state, event.running)
            ControlEvent.StopClicked -> onStop(state)
            ControlEvent.StartFailed -> onStartFailed(state)
            ControlEvent.ImportClicked -> onImportClicked(state)
            is ControlEvent.ImportPicked -> onImportPicked(state, event.uriString)
            is ControlEvent.ImportFinished -> onImportFinished(state, event.summary)
            ControlEvent.UseBuiltInClicked -> onUseBuiltIn(state)
            is ControlEvent.ModelStateLoaded -> onModelStateLoaded(state, event)
        }

    private fun onOverlayChecked(
        state: ControlState,
        granted: Boolean,
    ): ControlStep {
        val clearsNotice = granted && state.notice == ControlNotice.OVERLAY_PERMISSION_NEEDED
        val notice = if (clearsNotice) null else state.notice
        return ControlStep(state.copy(overlayPermission = granted, notice = notice), null)
    }

    private fun onStart(
        state: ControlState,
        notificationPermissionMissing: Boolean,
    ): ControlStep {
        if (state.running || state.awaitingConsent || state.importing) return ControlStep(state, null)
        if (!state.overlayPermission) {
            return ControlStep(state.copy(notice = ControlNotice.OVERLAY_PERMISSION_NEEDED), null)
        }
        if (notificationPermissionMissing && !state.notificationPermissionAsked) {
            val asked = state.copy(notificationPermissionAsked = true, notice = null)
            return ControlStep(asked, ControlEffect.REQUEST_NOTIFICATION_PERMISSION)
        }
        return ControlStep(state.copy(awaitingConsent = true, notice = null), ControlEffect.LAUNCH_CONSENT)
    }

    private fun onNotificationResult(state: ControlState): ControlStep {
        if (state.running || state.awaitingConsent || state.importing) return ControlStep(state, null)
        return ControlStep(state.copy(awaitingConsent = true, notice = null), ControlEffect.LAUNCH_CONSENT)
    }

    private fun onConsent(
        state: ControlState,
        granted: Boolean,
    ): ControlStep {
        if (granted) {
            return ControlStep(state.copy(awaitingConsent = false, notice = null), ControlEffect.START_SERVICE)
        }
        return ControlStep(state.copy(awaitingConsent = false, notice = ControlNotice.CONSENT_DENIED), null)
    }

    private fun onRunning(
        state: ControlState,
        running: Boolean,
    ): ControlStep {
        if (!running) return ControlStep(state.copy(running = false), null)
        return ControlStep(state.copy(running = true, awaitingConsent = false, notice = null), null)
    }

    private fun onStartFailed(state: ControlState): ControlStep =
        ControlStep(state.copy(awaitingConsent = false, notice = ControlNotice.START_FAILED), null)

    private fun onStop(state: ControlState): ControlStep {
        if (!state.running) return ControlStep(state, null)
        return ControlStep(state, ControlEffect.STOP_SERVICE)
    }

    /** Import, reset and picking are possible only while the capture is stopped (M0c D1) and nothing else runs. */
    private fun modelBusy(state: ControlState): Boolean = state.running || state.awaitingConsent || state.importing

    private fun onImportClicked(state: ControlState): ControlStep {
        if (modelBusy(state)) return ControlStep(state, null)
        return ControlStep(state, ControlEffect.OPEN_MODEL_PICKER)
    }

    private fun onImportPicked(
        state: ControlState,
        uriString: String?,
    ): ControlStep {
        if (uriString == null || modelBusy(state)) return ControlStep(state, null)
        val started = state.copy(importing = true, importResult = null)
        return ControlStep(started, ControlEffect.RUN_IMPORT, importUri = uriString)
    }

    private fun onImportFinished(
        state: ControlState,
        summary: ImportSummary,
    ): ControlStep {
        if (!state.importing) return ControlStep(state, null)
        val finished = state.copy(importing = false, importResult = summary)
        // A refusal or a failure keeps the previous model and notice; the reload then shows what is really stored.
        val next =
            when (summary) {
                is ImportSummary.Accepted ->
                    finished.copy(activeModel = ActiveModel.Imported(summary.displayName, summary.inputSize), modelNotice = null)
                is ImportSummary.Rejected, is ImportSummary.Failed -> finished
            }
        return ControlStep(next, ControlEffect.LOAD_MODEL_STATE)
    }

    private fun onUseBuiltIn(state: ControlState): ControlStep {
        if (modelBusy(state)) return ControlStep(state, null)
        val cleared = state.copy(activeModel = ActiveModel.BuiltIn, importResult = null, modelNotice = null)
        return ControlStep(cleared, ControlEffect.CLEAR_IMPORTED)
    }

    private fun onModelStateLoaded(
        state: ControlState,
        event: ControlEvent.ModelStateLoaded,
    ): ControlStep {
        // A read that finishes during an import may predate it; the import asks for a fresh read when it ends.
        if (state.importing) return ControlStep(state, null)
        return ControlStep(state.copy(activeModel = event.active, modelNotice = event.notice), null)
    }
}
