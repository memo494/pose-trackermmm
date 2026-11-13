package com.memo494.posetracker.control

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ControlReducerTest {
    private val ready = ControlState(overlayPermission = true)

    private fun reduce(
        state: ControlState,
        event: ControlEvent,
        notificationPermissionMissing: Boolean = false,
    ): ControlStep = ControlReducer.reduce(state, event, notificationPermissionMissing)

    // Rule 1: the overlay permission check.

    @Test
    fun overlayChecked_storesTheAnswerWithoutEffect() {
        val granted = reduce(ControlState(), ControlEvent.OverlayPermissionChecked(true))
        assertThat(granted.state.overlayPermission).isTrue()
        assertThat(granted.effect).isNull()

        val revoked = reduce(ready, ControlEvent.OverlayPermissionChecked(false))
        assertThat(revoked.state.overlayPermission).isFalse()
        assertThat(revoked.effect).isNull()
    }

    @Test
    fun overlayChecked_grantedClearsTheOverlayNotice() {
        val state = ControlState(notice = ControlNotice.OVERLAY_PERMISSION_NEEDED)

        val step = reduce(state, ControlEvent.OverlayPermissionChecked(true))

        assertThat(step.state.notice).isNull()
    }

    @Test
    fun overlayChecked_grantedKeepsAnUnrelatedNotice() {
        val state = ControlState(notice = ControlNotice.CONSENT_DENIED)

        val step = reduce(state, ControlEvent.OverlayPermissionChecked(true))

        assertThat(step.state.notice).isEqualTo(ControlNotice.CONSENT_DENIED)
    }

    @Test
    fun overlayChecked_notGrantedKeepsTheOverlayNotice() {
        val state = ControlState(notice = ControlNotice.OVERLAY_PERMISSION_NEEDED)

        val step = reduce(state, ControlEvent.OverlayPermissionChecked(false))

        assertThat(step.state.notice).isEqualTo(ControlNotice.OVERLAY_PERMISSION_NEEDED)
    }

    // Rule 2: the settings button.

    @Test
    fun openOverlaySettings_effectOnlyStateUnchanged() {
        val state = ControlState(notice = ControlNotice.OVERLAY_PERMISSION_NEEDED)

        val step = reduce(state, ControlEvent.OpenOverlaySettingsClicked)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isEqualTo(ControlEffect.OPEN_OVERLAY_SETTINGS)
    }

    // Rule 3: Start does nothing while running or while the consent dialog is open.

    @Test
    fun start_whileRunning_doesNothing() {
        val state = ready.copy(running = true)

        val step = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = true)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun start_whileAwaitingConsent_doesNothing() {
        val state = ready.copy(awaitingConsent = true)

        val step = reduce(state, ControlEvent.StartClicked)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    // Rule 4: no overlay permission, no consent dialog.

    @Test
    fun start_withoutOverlayPermission_showsNoticeAndNoEffect() {
        val step = reduce(ControlState(), ControlEvent.StartClicked, notificationPermissionMissing = true)

        assertThat(step.state.notice).isEqualTo(ControlNotice.OVERLAY_PERMISSION_NEEDED)
        assertThat(step.state.awaitingConsent).isFalse()
        assertThat(step.state.notificationPermissionAsked).isFalse()
        assertThat(step.effect).isNull()
    }

    // Rules 5 and 6: the notification permission is asked first, once.

    @Test
    fun start_withMissingNotificationPermissionNotAskedYet_asksIt() {
        val step = reduce(ready, ControlEvent.StartClicked, notificationPermissionMissing = true)

        assertThat(step.state.notificationPermissionAsked).isTrue()
        assertThat(step.state.awaitingConsent).isFalse()
        assertThat(step.effect).isEqualTo(ControlEffect.REQUEST_NOTIFICATION_PERMISSION)
    }

    @Test
    fun start_withMissingNotificationPermissionAlreadyAsked_launchesConsent() {
        val state = ready.copy(notificationPermissionAsked = true)

        val step = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = true)

        assertThat(step.state.awaitingConsent).isTrue()
        assertThat(step.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
    }

    @Test
    fun start_withNotificationPermissionGranted_launchesConsentAndClearsNotice() {
        val state = ready.copy(notice = ControlNotice.CONSENT_DENIED)

        val step = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = false)

        assertThat(step.state.awaitingConsent).isTrue()
        assertThat(step.state.notificationPermissionAsked).isFalse()
        assertThat(step.state.notice).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
    }

    // Rule 7: the notification answer never blocks.

    @Test
    fun notificationResult_grantedAndDenied_bothLaunchConsent() {
        val asked = ready.copy(notificationPermissionAsked = true)
        for (granted in listOf(true, false)) {
            val step = reduce(asked, ControlEvent.NotificationPermissionResult(granted))

            assertThat(step.state.awaitingConsent).isTrue()
            assertThat(step.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
        }
    }

    @Test
    fun notificationResult_whileRunning_doesNothing() {
        val state = ready.copy(running = true, notificationPermissionAsked = true)

        val step = reduce(state, ControlEvent.NotificationPermissionResult(true))

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun notificationResult_whileAwaitingConsent_doesNotLaunchTwice() {
        val state = ready.copy(awaitingConsent = true, notificationPermissionAsked = true)

        val step = reduce(state, ControlEvent.NotificationPermissionResult(true))

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    // Rules 8 and 9: the consent answer.

    @Test
    fun consentGranted_startsTheServiceAndClearsNotice() {
        val state = ready.copy(awaitingConsent = true, notice = ControlNotice.CONSENT_DENIED)

        val step = reduce(state, ControlEvent.ConsentResult(true))

        assertThat(step.state.awaitingConsent).isFalse()
        assertThat(step.state.notice).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.START_SERVICE)
    }

    @Test
    fun consentGranted_afterRecreation_stillStartsTheService() {
        // The activity was recreated while the dialog was open: its state is fresh, the result still arrives.
        val step = reduce(ControlState(), ControlEvent.ConsentResult(true))

        assertThat(step.effect).isEqualTo(ControlEffect.START_SERVICE)
    }

    @Test
    fun consentDenied_showsNoticeAndStartIsAvailableAgain() {
        val state = ready.copy(awaitingConsent = true)

        val denied = reduce(state, ControlEvent.ConsentResult(false))

        assertThat(denied.state.awaitingConsent).isFalse()
        assertThat(denied.state.notice).isEqualTo(ControlNotice.CONSENT_DENIED)
        assertThat(denied.effect).isNull()

        val retry = reduce(denied.state, ControlEvent.StartClicked)
        assertThat(retry.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
        assertThat(retry.state.notice).isNull()
    }

    // Rule 10: the running flag.

    @Test
    fun runningTrue_setsRunningAndClearsAwaitingAndNotice() {
        val state = ready.copy(awaitingConsent = true, notice = ControlNotice.CONSENT_DENIED)

        val step = reduce(state, ControlEvent.RunningChanged(true))

        assertThat(step.state.running).isTrue()
        assertThat(step.state.awaitingConsent).isFalse()
        assertThat(step.state.notice).isNull()
        assertThat(step.effect).isNull()
    }

    @Test
    fun runningFalse_clearsRunningOnly() {
        val state = ready.copy(running = true, notificationPermissionAsked = true)

        val step = reduce(state, ControlEvent.RunningChanged(false))

        assertThat(step.state).isEqualTo(state.copy(running = false))
        assertThat(step.effect).isNull()
    }

    @Test
    fun runningFalse_whileAwaitingConsent_keepsWaiting() {
        // The collector re-emits "not running" when the screen comes back from the consent dialog.
        val state = ready.copy(awaitingConsent = true)

        val step = reduce(state, ControlEvent.RunningChanged(false))

        assertThat(step.state.awaitingConsent).isTrue()
    }

    // Rule 10b: a start that failed (refused foreground, no projection, overlay refused, capture setup failed).

    @Test
    fun startFailed_showsTheNoticeAndStopsWaitingWithoutEffect() {
        val state = ready.copy(awaitingConsent = true)

        val step = reduce(state, ControlEvent.StartFailed)

        assertThat(step.state.awaitingConsent).isFalse()
        assertThat(step.state.notice).isEqualTo(ControlNotice.START_FAILED)
        assertThat(step.effect).isNull()
    }

    @Test
    fun startFailed_whileRunning_keepsRunningAsItIs() {
        val running = reduce(ready.copy(running = true), ControlEvent.StartFailed)
        val stopped = reduce(ready.copy(running = false), ControlEvent.StartFailed)

        assertThat(running.state.running).isTrue()
        assertThat(running.state.notice).isEqualTo(ControlNotice.START_FAILED)
        assertThat(stopped.state.running).isFalse()
    }

    @Test
    fun startFailed_inEveryState_showsTheNoticeWithoutEffect() {
        val flags = listOf(true, false)
        for (overlay in flags) {
            for (running in flags) {
                for (awaiting in flags) {
                    for (asked in flags) {
                        for (notice in listOf(null) + ControlNotice.entries) {
                            val state = ControlState(overlay, running, awaiting, asked, notice)

                            val step = reduce(state, ControlEvent.StartFailed)

                            val expected = state.copy(awaitingConsent = false, notice = ControlNotice.START_FAILED)
                            assertThat(step.state).isEqualTo(expected)
                            assertThat(step.effect).isNull()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun start_clearsTheStartFailedNotice() {
        val state = ready.copy(notice = ControlNotice.START_FAILED)

        val step = reduce(state, ControlEvent.StartClicked)

        assertThat(step.state.notice).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
    }

    @Test
    fun start_askingTheNotificationPermission_clearsTheStartFailedNotice() {
        val state = ready.copy(notice = ControlNotice.START_FAILED)

        val step = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = true)

        assertThat(step.state.notice).isNull()
    }

    @Test
    fun runningTrue_clearsTheStartFailedNotice() {
        val state = ready.copy(notice = ControlNotice.START_FAILED)

        val step = reduce(state, ControlEvent.RunningChanged(true))

        assertThat(step.state.notice).isNull()
    }

    @Test
    fun runningFalse_keepsTheStartFailedNotice() {
        val state = ready.copy(running = true, notice = ControlNotice.START_FAILED)

        val step = reduce(state, ControlEvent.RunningChanged(false))

        assertThat(step.state.notice).isEqualTo(ControlNotice.START_FAILED)
    }

    // Rule 11: Stop.

    @Test
    fun stop_whileRunning_stopsTheService() {
        val state = ready.copy(running = true)

        val step = reduce(state, ControlEvent.StopClicked)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isEqualTo(ControlEffect.STOP_SERVICE)
    }

    @Test
    fun stop_whileNotRunning_doesNothing() {
        val step = reduce(ready, ControlEvent.StopClicked)

        assertThat(step.state).isEqualTo(ready)
        assertThat(step.effect).isNull()
    }

    // Totality: every event in every state answers without throwing.

    @Test
    fun everyEventInEveryState_returnsAStep() {
        val events =
            listOf(
                ControlEvent.OverlayPermissionChecked(true),
                ControlEvent.OverlayPermissionChecked(false),
                ControlEvent.StartClicked,
                ControlEvent.NotificationPermissionResult(true),
                ControlEvent.NotificationPermissionResult(false),
                ControlEvent.ConsentResult(true),
                ControlEvent.ConsentResult(false),
                ControlEvent.RunningChanged(true),
                ControlEvent.RunningChanged(false),
                ControlEvent.StopClicked,
                ControlEvent.OpenOverlaySettingsClicked,
                ControlEvent.StartFailed,
            )
        val flags = listOf(true, false)
        val notices = listOf(null) + ControlNotice.entries
        var steps = 0
        for (overlay in flags) {
            for (running in flags) {
                for (awaiting in flags) {
                    for (asked in flags) {
                        for (notice in notices) {
                            val state = ControlState(overlay, running, awaiting, asked, notice)
                            for (event in events) {
                                for (missing in flags) {
                                    ControlReducer.reduce(state, event, missing)
                                    steps++
                                }
                            }
                        }
                    }
                }
            }
        }
        val stateFlagCombinations = flags.size * flags.size * flags.size * flags.size
        assertThat(steps).isEqualTo(stateFlagCombinations * notices.size * events.size * flags.size)
    }

    // The whole flow.

    @Test
    fun happyPath_startStopStart_asksConsentEveryTimeAndNotificationOnce() {
        var state = ControlState()

        state = reduce(state, ControlEvent.OverlayPermissionChecked(true)).state
        state = reduce(state, ControlEvent.RunningChanged(false)).state

        val start = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = true)
        assertThat(start.effect).isEqualTo(ControlEffect.REQUEST_NOTIFICATION_PERMISSION)
        state = start.state

        val denied = ControlEvent.NotificationPermissionResult(false)
        val notification = reduce(state, denied, notificationPermissionMissing = true)
        assertThat(notification.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
        state = notification.state

        val consent = reduce(state, ControlEvent.ConsentResult(true), notificationPermissionMissing = true)
        assertThat(consent.effect).isEqualTo(ControlEffect.START_SERVICE)
        state = consent.state

        state = reduce(state, ControlEvent.RunningChanged(true)).state
        assertThat(state.running).isTrue()
        assertThat(state.awaitingConsent).isFalse()

        val stop = reduce(state, ControlEvent.StopClicked, notificationPermissionMissing = true)
        assertThat(stop.effect).isEqualTo(ControlEffect.STOP_SERVICE)
        state = stop.state

        state = reduce(state, ControlEvent.RunningChanged(false)).state
        assertThat(state.running).isFalse()

        val again = reduce(state, ControlEvent.StartClicked, notificationPermissionMissing = true)
        assertThat(again.effect).isEqualTo(ControlEffect.LAUNCH_CONSENT)
        assertThat(again.state.awaitingConsent).isTrue()
    }
}
