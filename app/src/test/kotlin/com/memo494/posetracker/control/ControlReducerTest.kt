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

    // The model block: import, reset and the stored model state.

    private val imported = ActiveModel.Imported("yolo26n-pose_640.tflite", 640)
    private val tripped = ModelNotice(ModelNoticeKind.GUARD_TRIPPED_LOAD)
    private val rejected = ImportSummary.Rejected(ModelRejectionReason.INPUT_NHWC, "input [1,640,640,3] float32")

    @Test
    fun modelBlock_startsWithTheBuiltInModelAndNothingToTell() {
        val state = ControlState()

        assertThat(state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(state.importing).isFalse()
        assertThat(state.importResult).isNull()
        assertThat(state.modelNotice).isNull()
    }

    @Test
    fun importClicked_whileStopped_opensThePickerAndChangesNothing() {
        val state = ready.copy(activeModel = imported, importResult = rejected, modelNotice = tripped)

        val step = reduce(state, ControlEvent.ImportClicked)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isEqualTo(ControlEffect.OPEN_MODEL_PICKER)
    }

    @Test
    fun importClicked_whileRunningStartingOrImporting_isIgnored() {
        val blocked = listOf(ready.copy(running = true), ready.copy(awaitingConsent = true), ready.copy(importing = true))
        for (state in blocked) {
            val step = reduce(state, ControlEvent.ImportClicked)

            assertThat(step.state).isEqualTo(state)
            assertThat(step.effect).isNull()
        }
    }

    @Test
    fun importPicked_cancelled_leavesEverythingAsItWas() {
        val states =
            listOf(
                ready,
                ready.copy(activeModel = imported, importResult = rejected, modelNotice = tripped),
                ready.copy(running = true),
                ready.copy(importing = true),
            )
        for (state in states) {
            val step = reduce(state, ControlEvent.ImportPicked(null))

            assertThat(step.state).isEqualTo(state)
            assertThat(step.effect).isNull()
            assertThat(step.importUri).isNull()
        }
    }

    @Test
    fun importPicked_whileStopped_startsTheImportAndForgetsTheOldResult() {
        val state = ready.copy(activeModel = imported, importResult = rejected, modelNotice = tripped)

        val step = reduce(state, ControlEvent.ImportPicked("content://docs/model"))

        assertThat(step.state).isEqualTo(state.copy(importing = true, importResult = null))
        assertThat(step.effect).isEqualTo(ControlEffect.RUN_IMPORT)
        assertThat(step.importUri).isEqualTo("content://docs/model")
    }

    @Test
    fun importPicked_whileRunningStartingOrImporting_isIgnored() {
        val blocked = listOf(ready.copy(running = true), ready.copy(awaitingConsent = true), ready.copy(importing = true))
        for (state in blocked) {
            val step = reduce(state, ControlEvent.ImportPicked("content://docs/model"))

            assertThat(step.state).isEqualTo(state)
            assertThat(step.effect).isNull()
            assertThat(step.importUri).isNull()
        }
    }

    @Test
    fun importFinished_accepted_switchesTheActiveModelAndClearsTheOldNotice() {
        val state = ready.copy(importing = true, modelNotice = tripped)
        val summary = ImportSummary.Accepted("yolo26n-pose_640.tflite", 640)

        val step = reduce(state, ControlEvent.ImportFinished(summary))

        assertThat(step.state.importing).isFalse()
        assertThat(step.state.activeModel).isEqualTo(imported)
        assertThat(step.state.importResult).isEqualTo(summary)
        assertThat(step.state.modelNotice).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.LOAD_MODEL_STATE)
    }

    @Test
    fun importFinished_rejected_keepsThePreviousModelAndShowsTheReason() {
        val state = ready.copy(importing = true, activeModel = imported, modelNotice = tripped)

        val step = reduce(state, ControlEvent.ImportFinished(rejected))

        assertThat(step.state.importing).isFalse()
        assertThat(step.state.activeModel).isEqualTo(imported)
        assertThat(step.state.importResult).isEqualTo(rejected)
        assertThat(step.state.modelNotice).isEqualTo(tripped)
        assertThat(step.effect).isEqualTo(ControlEffect.LOAD_MODEL_STATE)
    }

    @Test
    fun importFinished_failed_keepsTheBuiltInModelAndShowsTheMessage() {
        val state = ready.copy(importing = true)
        val failed = ImportSummary.Failed(ImportFailure.IO, "IOException: no space")

        val step = reduce(state, ControlEvent.ImportFinished(failed))

        assertThat(step.state.importing).isFalse()
        assertThat(step.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(step.state.importResult).isEqualTo(failed)
        assertThat(step.effect).isEqualTo(ControlEffect.LOAD_MODEL_STATE)
    }

    @Test
    fun importFinished_withoutAnImportInProgress_isIgnored() {
        val state = ready.copy(activeModel = imported)

        val step = reduce(state, ControlEvent.ImportFinished(rejected))

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun useBuiltInClicked_whileStopped_clearsTheImportedModelTheNoticeAndTheResult() {
        val state = ready.copy(activeModel = imported, importResult = rejected, modelNotice = tripped)

        val step = reduce(state, ControlEvent.UseBuiltInClicked)

        assertThat(step.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(step.state.importResult).isNull()
        assertThat(step.state.modelNotice).isNull()
        assertThat(step.effect).isEqualTo(ControlEffect.CLEAR_IMPORTED)
    }

    @Test
    fun useBuiltInClicked_whileRunningStartingOrImporting_isIgnored() {
        val base = ready.copy(activeModel = imported, modelNotice = tripped)
        val blocked = listOf(base.copy(running = true), base.copy(awaitingConsent = true), base.copy(importing = true))
        for (state in blocked) {
            val step = reduce(state, ControlEvent.UseBuiltInClicked)

            assertThat(step.state).isEqualTo(state)
            assertThat(step.effect).isNull()
        }
    }

    @Test
    fun modelStateLoaded_withATrippedGuard_showsTheNoticeAndTheBuiltInModel() {
        val state = ready.copy(activeModel = imported)

        val step = reduce(state, ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, tripped))

        assertThat(step.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(step.state.modelNotice).isEqualTo(tripped)
        assertThat(step.effect).isNull()
    }

    @Test
    fun modelStateLoaded_withAnImportedModel_replacesTheModelAndKeepsTheResult() {
        val state = ready.copy(modelNotice = tripped, importResult = rejected)

        val step = reduce(state, ControlEvent.ModelStateLoaded(imported, null))

        assertThat(step.state.activeModel).isEqualTo(imported)
        assertThat(step.state.modelNotice).isNull()
        assertThat(step.state.importResult).isEqualTo(rejected)
    }

    @Test
    fun modelStateLoaded_withAnOrphanFileAndNoNotice_remembersThatAFileIsStored() {
        val step = reduce(ready, ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null, fileStored = true))

        assertThat(step.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(step.state.modelNotice).isNull()
        assertThat(step.state.modelFileStored).isTrue()
    }

    @Test
    fun modelStateLoaded_withNothingOnDisk_forgetsTheStoredFile() {
        val step = reduce(ready.copy(modelFileStored = true), ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null))

        assertThat(step.state.modelFileStored).isFalse()
    }

    @Test
    fun importFinished_accepted_marksAFileAsStored() {
        val step = reduce(ready.copy(importing = true), ControlEvent.ImportFinished(ImportSummary.Accepted("a.tflite", 640)))

        assertThat(step.state.modelFileStored).isTrue()
    }

    @Test
    fun useBuiltIn_withOnlyAnOrphanFile_isAllowedAndClearsTheStoredFlag() {
        val state = ready.copy(modelFileStored = true)

        val step = reduce(state, ControlEvent.UseBuiltInClicked)

        assertThat(step.effect).isEqualTo(ControlEffect.CLEAR_IMPORTED)
        assertThat(step.state.modelFileStored).isFalse()
    }

    @Test
    fun importingSeededFromARunningImport_keepsImportUseBuiltInAndStartDisabled() {
        val state = ready.copy(importing = true)

        assertThat(reduce(state, ControlEvent.ImportClicked).effect).isNull()
        assertThat(reduce(state, ControlEvent.UseBuiltInClicked).effect).isNull()
        assertThat(reduce(state, ControlEvent.StartClicked).effect).isNull()
    }

    @Test
    fun importFinished_arrivingAtARecreatedScreen_clearsImportingAndReloadsTheModel() {
        val state = ready.copy(importing = true)

        val step = reduce(state, ControlEvent.ImportFinished(ImportSummary.Accepted("a.tflite", 640)))

        assertThat(step.state.importing).isFalse()
        assertThat(step.state.importResult).isEqualTo(ImportSummary.Accepted("a.tflite", 640))
        assertThat(step.effect).isEqualTo(ControlEffect.LOAD_MODEL_STATE)
    }

    @Test
    fun modelStateLoaded_whileRunning_isStored() {
        val step = reduce(ready.copy(running = true), ControlEvent.ModelStateLoaded(imported, null))

        assertThat(step.state.activeModel).isEqualTo(imported)
    }

    @Test
    fun modelStateLoaded_whileImporting_isIgnored() {
        val state = ready.copy(importing = true)

        val step = reduce(state, ControlEvent.ModelStateLoaded(imported, tripped))

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun start_whileImporting_isIgnored() {
        val state = ready.copy(importing = true)

        val step = reduce(state, ControlEvent.StartClicked)

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun notificationResult_whileImporting_doesNotLaunchTheConsent() {
        val state = ready.copy(importing = true)

        val step = reduce(state, ControlEvent.NotificationPermissionResult(true))

        assertThat(step.state).isEqualTo(state)
        assertThat(step.effect).isNull()
    }

    @Test
    fun modelEvents_inEveryState_returnAStep() {
        val events =
            listOf(
                ControlEvent.ImportClicked,
                ControlEvent.ImportPicked(null),
                ControlEvent.ImportPicked("content://docs/model"),
                ControlEvent.ImportFinished(ImportSummary.Accepted("a.tflite", 416)),
                ControlEvent.ImportFinished(rejected),
                ControlEvent.ImportFinished(ImportSummary.Failed(ImportFailure.IO, "x")),
                ControlEvent.UseBuiltInClicked,
                ControlEvent.ModelStateLoaded(ActiveModel.BuiltIn, null),
                ControlEvent.ModelStateLoaded(imported, tripped),
            )
        val flags = listOf(true, false)
        var steps = 0
        for (running in flags) {
            for (awaiting in flags) {
                for (importing in flags) {
                    for (model in listOf(ActiveModel.BuiltIn, imported)) {
                        for (notice in listOf(null, tripped)) {
                            val state =
                                ready.copy(
                                    running = running,
                                    awaitingConsent = awaiting,
                                    importing = importing,
                                    activeModel = model,
                                    modelNotice = notice,
                                )
                            for (event in events) {
                                val step = reduce(state, event)
                                // Only a stopped, idle screen may start work on the model files.
                                if (running || awaiting || importing) {
                                    assertThat(step.effect).isNotEqualTo(ControlEffect.OPEN_MODEL_PICKER)
                                    assertThat(step.effect).isNotEqualTo(ControlEffect.RUN_IMPORT)
                                    assertThat(step.effect).isNotEqualTo(ControlEffect.CLEAR_IMPORTED)
                                }
                                steps++
                            }
                        }
                    }
                }
            }
        }
        assertThat(steps).isEqualTo(flags.size * flags.size * flags.size * 2 * 2 * events.size)
    }

    @Test
    fun modelFlow_rejectThenAcceptThenUseBuiltIn() {
        var state = reduce(ControlState(), ControlEvent.OverlayPermissionChecked(true)).state

        val picked = reduce(state, ControlEvent.ImportPicked("content://docs/a"))
        state = picked.state
        assertThat(state.importing).isTrue()
        assertThat(reduce(state, ControlEvent.StartClicked).effect).isNull()

        state = reduce(state, ControlEvent.ImportFinished(rejected)).state
        assertThat(state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(state.importResult).isEqualTo(rejected)

        state = reduce(state, ControlEvent.ImportPicked("content://docs/b")).state
        assertThat(state.importResult).isNull()
        state = reduce(state, ControlEvent.ImportFinished(ImportSummary.Accepted("b.tflite", 640))).state
        assertThat(state.activeModel).isEqualTo(ActiveModel.Imported("b.tflite", 640))

        val reset = reduce(state, ControlEvent.UseBuiltInClicked)
        assertThat(reset.effect).isEqualTo(ControlEffect.CLEAR_IMPORTED)
        assertThat(reset.state.activeModel).isEqualTo(ActiveModel.BuiltIn)
        assertThat(reset.state.importResult).isNull()
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

    // Debug marks (M0c T7): a switch, saved off the main thread, handed to the service at Start.

    @Test
    fun debugMarks_defaultIsOff() {
        assertThat(ControlState().debugMarks).isFalse()
    }

    @Test
    fun debugMarksToggled_whileStopped_changesStateAndAsksToPersist() {
        val on = reduce(ready, ControlEvent.DebugMarksToggled(true))

        assertThat(on.state.debugMarks).isTrue()
        assertThat(on.effect).isEqualTo(ControlEffect.PERSIST_DEBUG_MARKS)
        assertThat(on.debugMarks).isTrue()

        val off = reduce(on.state, ControlEvent.DebugMarksToggled(false))

        assertThat(off.state.debugMarks).isFalse()
        assertThat(off.effect).isEqualTo(ControlEffect.PERSIST_DEBUG_MARKS)
        assertThat(off.debugMarks).isFalse()
    }

    @Test
    fun debugMarksToggled_whileRunning_changesStateAndAsksToPersist() {
        val running = ready.copy(running = true)

        val step = reduce(running, ControlEvent.DebugMarksToggled(true))

        assertThat(step.state.debugMarks).isTrue()
        assertThat(step.state.running).isTrue()
        assertThat(step.effect).isEqualTo(ControlEffect.PERSIST_DEBUG_MARKS)
        assertThat(step.debugMarks).isTrue()
    }

    @Test
    fun debugMarksToggled_leavesTheRestOfTheStateAlone() {
        val busy = ready.copy(awaitingConsent = true, notice = ControlNotice.CONSENT_DENIED, importing = true)

        val step = reduce(busy, ControlEvent.DebugMarksToggled(true))

        assertThat(step.state).isEqualTo(busy.copy(debugMarks = true, debugMarksTouched = true))
    }

    @Test
    fun debugMarksLoaded_appliesTheStoredValueWithoutEffect() {
        val step = reduce(ready, ControlEvent.DebugMarksLoaded(true))

        assertThat(step.state.debugMarks).isTrue()
        assertThat(step.effect).isNull()
        assertThat(step.debugMarks).isNull()
    }

    @Test
    fun debugMarksLoadedAfterAToggle_isDropped() {
        val toggled = reduce(ready, ControlEvent.DebugMarksToggled(true)).state

        val step = reduce(toggled, ControlEvent.DebugMarksLoaded(false))

        assertThat(step.state).isEqualTo(toggled)
        assertThat(step.state.debugMarks).isTrue()
        assertThat(step.effect).isNull()
        assertThat(step.debugMarks).isNull()
    }

    @Test
    fun debugMarksLoadedAfterAToggleBackOff_isDroppedToo() {
        val on = reduce(ready, ControlEvent.DebugMarksToggled(true)).state
        val off = reduce(on, ControlEvent.DebugMarksToggled(false)).state

        val step = reduce(off, ControlEvent.DebugMarksLoaded(true))

        assertThat(step.state.debugMarks).isFalse()
    }

    @Test
    fun debugMarksLoadedBeforeAnyToggle_doesNotCountAsATouch() {
        val loaded = reduce(ready, ControlEvent.DebugMarksLoaded(true)).state

        assertThat(loaded.debugMarksTouched).isFalse()
        assertThat(reduce(loaded, ControlEvent.DebugMarksLoaded(false)).state.debugMarks).isFalse()
    }

    @Test
    fun consentGranted_carriesTheCurrentDebugMarksToTheService() {
        val on = ready.copy(awaitingConsent = true, debugMarks = true)
        val off = ready.copy(awaitingConsent = true, debugMarks = false)

        val startOn = reduce(on, ControlEvent.ConsentResult(true))
        val startOff = reduce(off, ControlEvent.ConsentResult(true))

        assertThat(startOn.effect).isEqualTo(ControlEffect.START_SERVICE)
        assertThat(startOn.debugMarks).isTrue()
        assertThat(startOff.effect).isEqualTo(ControlEffect.START_SERVICE)
        assertThat(startOff.debugMarks).isFalse()
    }

    @Test
    fun consentGranted_afterAToggleDuringTheDialog_usesTheNewValue() {
        var state = ready.copy(awaitingConsent = true)
        state = reduce(state, ControlEvent.DebugMarksToggled(true)).state

        val step = reduce(state, ControlEvent.ConsentResult(true))

        assertThat(step.debugMarks).isTrue()
    }

    @Test
    fun otherSteps_carryNoDebugMarksValue() {
        assertThat(reduce(ready, ControlEvent.StartClicked).debugMarks).isNull()
        assertThat(reduce(ready.copy(awaitingConsent = true), ControlEvent.ConsentResult(false)).debugMarks).isNull()
    }
}
