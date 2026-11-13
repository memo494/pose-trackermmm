package com.memo494.posetracker

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.memo494.posetracker.capture.CaptureLog
import com.memo494.posetracker.capture.CaptureService
import com.memo494.posetracker.control.ControlEffect
import com.memo494.posetracker.control.ControlEvent
import com.memo494.posetracker.control.ControlReducer
import com.memo494.posetracker.control.ControlScreen
import com.memo494.posetracker.control.ControlState
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.diagnostics.CrashNotice
import com.memo494.posetracker.diagnostics.CrashStore
import com.memo494.posetracker.diagnostics.DiagnosticsNotice
import com.memo494.posetracker.diagnostics.DiagnosticsSection
import com.memo494.posetracker.diagnostics.DiagnosticsShare
import com.memo494.posetracker.diagnostics.DiagnosticsUiState
import com.memo494.posetracker.diagnostics.DiagnosticsZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Control screen: permissions, screen-capture consent, Start and Stop (ARCHITECTURE §1, §14).
 * Every decision comes from [ControlReducer]; this class only reads the system and performs the effect it returns.
 */
class MainActivity : ComponentActivity() {
    private val controlState = mutableStateOf(ControlState())
    private val diagnosticsState = mutableStateOf(DiagnosticsUiState())

    // Registered at activity level so a result that arrives after a recreation still reaches the new instance.
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onConsentResult(result)
        }
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            dispatch(ControlEvent.NotificationPermissionResult(granted))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ControlScreen(
                    state = controlState.value,
                    buildLabel = BuildLabel.line(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                    onStartClick = { dispatch(ControlEvent.StartClicked) },
                    onStopClick = { dispatch(ControlEvent.StopClicked) },
                    onOpenOverlaySettingsClick = { dispatch(ControlEvent.OpenOverlaySettingsClicked) },
                    crashContent = {
                        CrashNotice(
                            state = diagnosticsState.value,
                            onShareClick = { shareDiagnostics() },
                            onDismissClick = { dismissCrashNotice() },
                        )
                    },
                    diagnosticsContent = {
                        DiagnosticsSection(
                            state = diagnosticsState.value,
                            onShareClick = { shareDiagnostics() },
                            onToggleLogClick = { toggleLog() },
                            onRefreshLogClick = { refreshLog() },
                        )
                    },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureService.running.collect { dispatch(ControlEvent.RunningChanged(it)) }
            }
        }
        showCrashNoticeIfAny()
    }

    override fun onResume() {
        super.onResume()
        dispatch(ControlEvent.OverlayPermissionChecked(Settings.canDrawOverlays(this)))
    }

    /**
     * Runs [event] through the reducer, stores the new state and performs the effect. [consentResult] is only
     * passed on by the consent callback, so the consent Intent reaches `START_SERVICE` without being stored.
     */
    private fun dispatch(
        event: ControlEvent,
        consentResult: ActivityResult? = null,
    ) {
        val step = ControlReducer.reduce(controlState.value, event, notificationPermissionMissing())
        controlState.value = step.state
        when (step.effect) {
            ControlEffect.OPEN_OVERLAY_SETTINGS -> openOverlaySettings()
            ControlEffect.REQUEST_NOTIFICATION_PERMISSION -> requestNotificationPermission()
            ControlEffect.LAUNCH_CONSENT -> launchConsent()
            ControlEffect.START_SERVICE -> startCapture(consentResult)
            ControlEffect.STOP_SERVICE -> stopCapture()
            null -> Unit
        }
    }

    private fun notificationPermissionMissing(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val answer = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        return answer != PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        val action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        try {
            startActivity(Intent(action, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            CaptureLog.log("overlay settings for this app not found, opening the general page", e)
            try {
                startActivity(Intent(action))
            } catch (e2: ActivityNotFoundException) {
                CaptureLog.log("overlay settings not found", e2)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            dispatch(ControlEvent.NotificationPermissionResult(true))
            return
        }
        try {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            CaptureLog.log("notification permission request failed", e)
            dispatch(ControlEvent.NotificationPermissionResult(false))
        }
    }

    private fun launchConsent() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            if (manager == null) {
                CaptureLog.log("no MediaProjectionManager on this device", null)
                dispatch(ControlEvent.ConsentResult(false))
                return
            }
            val intent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
            consentLauncher.launch(intent)
        } catch (e: Exception) {
            CaptureLog.log("launching the screen-capture consent failed", e)
            dispatch(ControlEvent.ConsentResult(false))
        }
    }

    private fun onConsentResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            dispatch(ControlEvent.ConsentResult(false))
            return
        }
        dispatch(ControlEvent.ConsentResult(true), result)
    }

    /** Starts the service at once with the callback's own consent Intent; any failure is shown as a denied consent. */
    private fun startCapture(consentResult: ActivityResult?) {
        val data = consentResult?.data
        if (consentResult == null || data == null) {
            CaptureLog.log("start refused: no consent result to pass on", null)
            dispatch(ControlEvent.ConsentResult(false))
            return
        }
        try {
            ContextCompat.startForegroundService(this, CaptureService.startIntent(this, consentResult.resultCode, data))
        } catch (e: Exception) {
            CaptureLog.log("starting the capture service failed", e)
            dispatch(ControlEvent.ConsentResult(false))
        }
    }

    private fun stopCapture() {
        try {
            startService(CaptureService.stopIntent(this))
        } catch (e: Exception) {
            CaptureLog.log("stopping the capture service failed", e)
        }
    }

    private fun updateDiagnostics(change: (DiagnosticsUiState) -> DiagnosticsUiState) {
        diagnosticsState.value = change(diagnosticsState.value)
    }

    /** Reads the saved crash off the main thread and shows the notice when there is one. */
    private fun showCrashNoticeIfAny() {
        lifecycleScope.launch {
            val crash = withContext(Dispatchers.IO) { CrashStore.read(applicationContext) }
            if (crash != null) updateDiagnostics { it.copy(crashNotice = true) }
        }
    }

    private fun dismissCrashNotice() {
        updateDiagnostics { it.copy(crashNotice = false) }
        clearSavedCrash()
    }

    private fun clearSavedCrash() {
        lifecycleScope.launch { withContext(Dispatchers.IO) { CrashStore.clear(applicationContext) } }
    }

    /** Writes the zip off the main thread, then opens the share sheet; any failure becomes a short notice. */
    private fun shareDiagnostics() {
        if (diagnosticsState.value.sharing) return
        updateDiagnostics { it.copy(sharing = true, notice = null) }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { writeZipOrNull() }
            val notice = if (file == null) DiagnosticsNotice.PREPARE_FAILED else openShareSheet(file)
            updateDiagnostics { it.copy(sharing = false, notice = notice) }
            if (file != null && notice == null && diagnosticsState.value.crashNotice) dismissCrashNotice()
        }
    }

    private fun writeZipOrNull(): File? =
        try {
            DiagnosticsZip.write(applicationContext)
        } catch (e: Throwable) {
            AppLog.log("writing the diagnostics zip failed", e)
            null
        }

    /** Starts the chooser for [file]; returns `null` on success or the notice to show. */
    private fun openShareSheet(file: File): DiagnosticsNotice? =
        try {
            startActivity(DiagnosticsShare.chooser(this, file, getString(R.string.diagnostics_share_title)))
            null
        } catch (e: Exception) {
            AppLog.log("opening the share sheet failed", e)
            DiagnosticsNotice.SHARE_FAILED
        }

    private fun toggleLog() {
        val show = !diagnosticsState.value.logVisible
        updateDiagnostics { it.copy(logVisible = show, logText = if (show) logText() else "") }
    }

    private fun refreshLog() {
        updateDiagnostics { it.copy(logText = logText()) }
    }

    /** The newest log lines, newest last. */
    private fun logText(): String = AppLog.lines().takeLast(TrackerConfig.LOG_SCREEN_LINES).joinToString("\n")
}
