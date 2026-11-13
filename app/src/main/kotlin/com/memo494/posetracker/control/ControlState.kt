package com.memo494.posetracker.control

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
}

/** The one side effect the activity must perform after a step. */
internal enum class ControlEffect {
    OPEN_OVERLAY_SETTINGS,
    REQUEST_NOTIFICATION_PERMISSION,
    LAUNCH_CONSENT,
    START_SERVICE,
    STOP_SERVICE,
}

/** Result of one reduction: the new [state] and the [effect] to perform, if any. */
internal data class ControlStep(
    val state: ControlState,
    val effect: ControlEffect?,
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
        if (state.running || state.awaitingConsent) return ControlStep(state, null)
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
        if (state.running || state.awaitingConsent) return ControlStep(state, null)
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
}
