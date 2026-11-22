package com.memo494.posetracker.control

import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.core.modelimport.ModelOrientation

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
    /**
     * The model the slot of the screen's current orientation holds, as last read from the model store (the control
     * screen is shown in the orientation the next start captures).
     */
    val activeModel: ActiveModel = ActiveModel.BuiltIn,
    /** The imported models in the library (M1b-1), in the order the store lists them. */
    val library: List<LibraryItem> = emptyList(),
    /** The library model chosen for portrait (its sha256), or `null` for the built-in model. */
    val portraitSha: String? = null,
    /** The library model chosen for landscape (its sha256), or `null` for the built-in model. */
    val landscapeSha: String? = null,
    /** `true` when the library holds the most models it may; Import is disabled until one is deleted. */
    val libraryFull: Boolean = false,
    /** `true` after the first tap on "Remove all imported models" until the second tap or the timeout. */
    val removeAllArmed: Boolean = false,
    /** `true` from the moment a picked file is handed to the importer until its result arrives; Start is disabled. */
    val importing: Boolean = false,
    /** What the last import said; `null` before any import, after a new one starts, after "Use built-in model" and after a slot change or a delete. */
    val importResult: ImportSummary? = null,
    /** Why the built-in model is used although an imported one may be expected, or `null`. */
    val modelNotice: ModelNotice? = null,
    /**
     * `true` when any imported model file is stored, even one no meta describes (an orphan left by a process
     * death), so **Use built-in model** can remove it.
     */
    val modelFileStored: Boolean = false,
    /** The orientation the control screen is shown in (the one `activeModel` was read for); the built-in model differs per orientation. */
    val activeOrientation: ModelOrientation = ModelOrientation.PORTRAIT,
    /**
     * The "Debug marks (box and body points)" switch: `true` draws the full box and the keypoint dots as well.
     * It is read by the next Start only; changing it while running changes nothing in the session.
     */
    val debugMarks: Boolean = TrackerConfig.DEFAULT_DEBUG_MARKS,
    /** `true` once the user moved the switch on this screen; a saved value read later is stale and is dropped. */
    val debugMarksTouched: Boolean = false,
)

/** The model the next capture start will use. Pure data: no files, no Android types. */
internal sealed interface ActiveModel {
    /** The model bundled with the app for the orientation (`BuiltInModels`: 448x960 portrait, 960x448 landscape). */
    data object BuiltIn : ActiveModel

    /**
     * The imported model [displayName] (a sanitised file name) with the input size [inputText] in model pixels
     * (`ModelInputText.text`: `640` when square, `832x384` otherwise).
     */
    data class Imported(
        val displayName: String,
        val inputText: String,
    ) : ActiveModel
}

/**
 * One model of the library as the screen lists it.
 *
 * @property sha256 the key of the model; what a delete and a slot choice refer to.
 * @property displayName the sanitised file name.
 * @property inputText the input size text (`ModelInputText.text`: `640` or `832x384`).
 * @property blocked `true` when a crash at its load switched it off; it cannot be chosen for a slot.
 */
internal data class LibraryItem(
    val sha256: String,
    val displayName: String,
    val inputText: String,
    val blocked: Boolean = false,
)

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
    INPUT_ASPECT,
    INPUT_SIZE_NOT_MULTIPLE,
    INPUT_SIZE_OUT_OF_RANGE,
    OUTPUT_COUNT,
    OUTPUT_TYPE,
    OUTPUT_CLASSIC_HEAD,
    OUTPUT_ROWS,
    OUTPUT_SHAPE,
}

/** Why an import failed without a verdict on the file: one value per kind of the importer, so each has its own sentence. */
internal enum class ImportFailure {
    /** Another import is still running. */
    BUSY,

    /** The crash marker could not be saved, so the import was not run. */
    GUARD_NOT_STORED,

    /** The model library is full: a model must be deleted first. */
    LIBRARY_FULL,

    /** An I/O or other unexpected error; [ImportSummary.Failed.message] says which. */
    IO,
}

/** What an import came to, as small pure data (the importer's own outcome types stay out of the state). */
internal sealed interface ImportSummary {
    /**
     * The model [displayName] with input size [inputText] (see [ActiveModel.Imported]) is now in the library, and
     * [slot] is the orientation whose slot it was put into, or `null` when the owner has to pick it by hand.
     * [duplicate] is `true` when the library already held this model (nothing was added, no slot was assigned).
     */
    data class Accepted(
        val displayName: String,
        val inputText: String,
        val slot: ModelOrientation? = null,
        val duplicate: Boolean = false,
    ) : ImportSummary

    /** The file was refused for [reason]; [details] names the size, the runtime's message or the shapes found. */
    data class Rejected(
        val reason: ModelRejectionReason,
        val details: String,
    ) : ImportSummary

    /** The import did not run to a verdict ([kind]); [message] is a short technical line for [ImportFailure.IO], else `null`. */
    data class Failed(
        val kind: ImportFailure,
        val message: String?,
    ) : ImportSummary
}

/** What the model block tells about the built-in fallback. */
internal enum class ModelNoticeKind {
    /** The imported model's file is gone. */
    FILE_MISSING,

    /**
     * The app died while the runtime opened a new file (it was never stored) and no stored model could be kept:
     * the built-in model is used.
     */
    GUARD_TRIPPED_PROBE,

    /**
     * The app died while the runtime opened a new file (it was never stored), and the previously imported model
     * still matches its stored length and sha256, so it stays in use. Goes away at the next Start of that model.
     */
    PROBE_TRIP_MODEL_KEPT,

    /** The app died while the runtime loaded the imported model; it is switched off. */
    GUARD_TRIPPED_LOAD,

    /** The safety marker could not be saved, so the last session did not try the imported model. */
    GUARD_NOT_STORED,

    /** The last session could not load the imported model ([ModelNotice.message] says why). */
    LOAD_FAILED,

    /** The bundled model of the orientation failed at the last session, so the square fallback model ran ([ModelNotice.message] says why). */
    BUILT_IN_FAILED,

    /** The bundled model of the orientation killed the app before and is switched off ([ModelNotice.message] says so). */
    BUILT_IN_BLOCKED,

    /** What is stored about the imported model could not be read ([ModelNotice.message] says why). */
    STATE_UNREADABLE,

    /** The list of switched-off models could not be read, so every imported model counts as switched off. */
    BLOCKED_UNREADABLE,
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

    /** The user pressed "Remove all imported models": the first tap arms it, a second tap before the timeout removes. */
    data object RemoveAllClicked : ControlEvent

    /** The time to confirm "Remove all imported models" ran out. */
    data object RemoveAllDisarm : ControlEvent

    /** The user switched "Debug marks" on or off. Allowed in every state; it is used from the next Start. */
    data class DebugMarksToggled(
        val enabled: Boolean,
    ) : ControlEvent

    /** The saved switch value was read off the main thread when the screen started. */
    data class DebugMarksLoaded(
        val enabled: Boolean,
    ) : ControlEvent

    /** The user pressed "Delete" on the library model [sha256]. */
    data class DeleteModelClicked(
        val sha256: String,
    ) : ControlEvent

    /** The user chose the library model [sha256] (`null` = the built-in model) for [orientation]. */
    data class SlotPicked(
        val orientation: ModelOrientation,
        val sha256: String?,
    ) : ControlEvent

    /**
     * The model store (and the last session) was read off the main thread: the model of the screen's orientation and
     * the notice to show, and the library with the two choices.
     */
    data class ModelStateLoaded(
        val active: ActiveModel,
        val notice: ModelNotice?,
        val fileStored: Boolean = false,
        val library: List<LibraryItem> = emptyList(),
        val portraitSha: String? = null,
        val landscapeSha: String? = null,
        val libraryFull: Boolean = false,
        val orientation: ModelOrientation = ModelOrientation.PORTRAIT,
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

    /** Delete every imported model on a worker thread, then read the model state again. */
    CLEAR_IMPORTED,

    /** After `TrackerConfig.removeAllConfirmMs`, send `RemoveAllDisarm`. */
    SCHEDULE_REMOVE_ALL_DISARM,

    /** Delete the library model [ControlStep.modelSha] on a worker thread, then read the model state again. */
    DELETE_MODEL,

    /** Store the choice [ControlStep.slotOrientation] / [ControlStep.modelSha] on a worker thread, then read the model state again. */
    SET_SLOT,

    /** Read the model store and the last session's model status on a worker thread; the answer is `ModelStateLoaded`. */
    LOAD_MODEL_STATE,

    /** Save [ControlStep.debugMarks] in the app preferences on a worker thread. */
    PERSIST_DEBUG_MARKS,
}

/**
 * Result of one reduction: the new [state] and the [effect] to perform, if any.
 * [importUri] is set only with [ControlEffect.RUN_IMPORT]: the picked document, as text.
 * [debugMarks] is set only with [ControlEffect.START_SERVICE] (the value the service gets as an Intent extra) and
 * [ControlEffect.PERSIST_DEBUG_MARKS] (the value to save); otherwise `null`.
 * [modelSha] is set with [ControlEffect.DELETE_MODEL] (the model to delete) and [ControlEffect.SET_SLOT] (the model to
 * choose, `null` = built-in); [slotOrientation] only with [ControlEffect.SET_SLOT].
 */
internal data class ControlStep(
    val state: ControlState,
    val effect: ControlEffect?,
    val importUri: String? = null,
    val debugMarks: Boolean? = null,
    val modelSha: String? = null,
    val slotOrientation: ModelOrientation? = null,
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
    ): ControlStep {
        val ends = state.removeAllArmed && event.endsRemoveAllConfirm()
        return step(if (ends) state.copy(removeAllArmed = false) else state, event, notificationPermissionMissing)
    }

    /** Another action started: the first tap on "Remove all imported models" no longer waits for its second tap. */
    private fun ControlEvent.endsRemoveAllConfirm(): Boolean =
        this is ControlEvent.ImportClicked ||
            this is ControlEvent.ImportPicked ||
            this is ControlEvent.DeleteModelClicked ||
            this is ControlEvent.SlotPicked ||
            this is ControlEvent.StartClicked

    private fun step(
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
            ControlEvent.RemoveAllClicked -> onRemoveAll(state)
            ControlEvent.RemoveAllDisarm -> ControlStep(state.copy(removeAllArmed = false), null)
            is ControlEvent.DeleteModelClicked -> onDeleteModel(state, event.sha256)
            is ControlEvent.SlotPicked -> onSlotPicked(state, event.orientation, event.sha256)
            is ControlEvent.ModelStateLoaded -> onModelStateLoaded(state, event)
            is ControlEvent.DebugMarksToggled -> onDebugMarksToggled(state, event.enabled)
            is ControlEvent.DebugMarksLoaded -> onDebugMarksLoaded(state, event.enabled)
        }

    private fun onDebugMarksToggled(
        state: ControlState,
        enabled: Boolean,
    ): ControlStep =
        ControlStep(
            state.copy(debugMarks = enabled, debugMarksTouched = true),
            ControlEffect.PERSIST_DEBUG_MARKS,
            debugMarks = enabled,
        )

    /** The saved value is read off the main thread; one that arrives after the user moved the switch is stale. */
    private fun onDebugMarksLoaded(
        state: ControlState,
        enabled: Boolean,
    ): ControlStep = if (state.debugMarksTouched) ControlStep(state, null) else ControlStep(state.copy(debugMarks = enabled), null)

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
            val started = state.copy(awaitingConsent = false, notice = null)
            return ControlStep(started, ControlEffect.START_SERVICE, debugMarks = state.debugMarks)
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
        if (modelBusy(state) || state.libraryFull) return ControlStep(state, null)
        return ControlStep(state, ControlEffect.OPEN_MODEL_PICKER)
    }

    private fun onImportPicked(
        state: ControlState,
        uriString: String?,
    ): ControlStep {
        if (uriString == null || modelBusy(state) || state.libraryFull) return ControlStep(state, null)
        val started = state.copy(importing = true, importResult = null)
        return ControlStep(started, ControlEffect.RUN_IMPORT, importUri = uriString)
    }

    private fun onImportFinished(
        state: ControlState,
        summary: ImportSummary,
    ): ControlStep {
        if (!state.importing) return ControlStep(state, null)
        val finished = state.copy(importing = false, importResult = summary)
        // The reducer does not know the screen's orientation, so it never names the new model active: only the slot
        // of this orientation decides, and the reload (LOAD_MODEL_STATE) reads it. A refusal or a failure keeps the
        // previous model and notice; the reload then shows what is really stored.
        val next =
            when (summary) {
                is ImportSummary.Accepted -> {
                    finished.copy(
                        modelNotice = null,
                        modelFileStored = true,
                    )
                }

                is ImportSummary.Rejected, is ImportSummary.Failed -> {
                    finished
                }
            }
        return ControlStep(next, ControlEffect.LOAD_MODEL_STATE)
    }

    /** The first tap arms (and the activity schedules the disarm); the second removes everything. */
    private fun onRemoveAll(state: ControlState): ControlStep {
        if (modelBusy(state)) return ControlStep(state, null)
        if (!state.removeAllArmed) return ControlStep(state.copy(removeAllArmed = true), ControlEffect.SCHEDULE_REMOVE_ALL_DISARM)
        return onUseBuiltIn(state)
    }

    private fun onUseBuiltIn(state: ControlState): ControlStep {
        if (modelBusy(state)) return ControlStep(state, null)
        val cleared =
            state.copy(
                removeAllArmed = false,
                activeModel = ActiveModel.BuiltIn,
                importResult = null,
                modelNotice = null,
                modelFileStored = false,
                library = emptyList(),
                portraitSha = null,
                landscapeSha = null,
                libraryFull = false,
            )
        return ControlStep(cleared, ControlEffect.CLEAR_IMPORTED)
    }

    /** The model leaves the list and the choices that pointed to it at once; the reload then shows what is stored. */
    private fun onDeleteModel(
        state: ControlState,
        sha256: String,
    ): ControlStep {
        if (modelBusy(state) || state.library.none { it.sha256 == sha256 }) return ControlStep(state, null)
        val next =
            state.copy(
                importResult = null,
                library = state.library.filterNot { it.sha256 == sha256 },
                portraitSha = state.portraitSha.takeUnless { it == sha256 },
                landscapeSha = state.landscapeSha.takeUnless { it == sha256 },
                libraryFull = false,
            )
        return ControlStep(next, ControlEffect.DELETE_MODEL, modelSha = sha256)
    }

    /** The choice shows at once; a model that is not listed (or is blocked) cannot be chosen. */
    private fun onSlotPicked(
        state: ControlState,
        orientation: ModelOrientation,
        sha256: String?,
    ): ControlStep {
        val pickable = sha256 == null || state.library.any { it.sha256 == sha256 && !it.blocked }
        if (modelBusy(state) || !pickable) return ControlStep(state, null)
        val next =
            when (orientation) {
                ModelOrientation.PORTRAIT -> state.copy(portraitSha = sha256, importResult = null)
                ModelOrientation.LANDSCAPE -> state.copy(landscapeSha = sha256, importResult = null)
            }
        return ControlStep(next, ControlEffect.SET_SLOT, modelSha = sha256, slotOrientation = orientation)
    }

    private fun onModelStateLoaded(
        state: ControlState,
        event: ControlEvent.ModelStateLoaded,
    ): ControlStep {
        // A read that finishes during an import may predate it; the import asks for a fresh read when it ends.
        if (state.importing) return ControlStep(state, null)
        return ControlStep(
            state.copy(
                activeModel = event.active,
                modelNotice = event.notice,
                modelFileStored = event.fileStored,
                library = event.library,
                portraitSha = event.portraitSha,
                landscapeSha = event.landscapeSha,
                libraryFull = event.libraryFull,
                activeOrientation = event.orientation,
            ),
            null,
        )
    }
}
