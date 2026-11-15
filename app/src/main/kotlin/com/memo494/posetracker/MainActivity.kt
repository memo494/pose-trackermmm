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
import android.provider.OpenableColumns
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
import com.memo494.posetracker.control.ControlModelMapper
import com.memo494.posetracker.control.ControlReducer
import com.memo494.posetracker.control.ControlScreen
import com.memo494.posetracker.control.ControlState
import com.memo494.posetracker.control.ImportFailure
import com.memo494.posetracker.control.ImportResults
import com.memo494.posetracker.control.ImportSummary
import com.memo494.posetracker.core.TrackerConfig
import com.memo494.posetracker.diagnostics.AppLog
import com.memo494.posetracker.diagnostics.CrashNotice
import com.memo494.posetracker.diagnostics.CrashStore
import com.memo494.posetracker.diagnostics.DiagnosticsNotice
import com.memo494.posetracker.diagnostics.DiagnosticsSection
import com.memo494.posetracker.diagnostics.DiagnosticsShare
import com.memo494.posetracker.diagnostics.DiagnosticsUiState
import com.memo494.posetracker.diagnostics.DiagnosticsZip
import com.memo494.posetracker.modelimport.ImportedModelStore
import com.memo494.posetracker.modelimport.ModelImporter
import com.memo494.posetracker.modelimport.ModelStatus
import com.memo494.posetracker.modelimport.ModelStatusHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Control screen: permissions, screen-capture consent, Start and Stop (ARCHITECTURE §1, §14).
 * Every decision comes from [ControlReducer]; this class only reads the system and performs the effect it returns.
 *
 * Model import: the picked file is copied at once by [ModelImporter] on `Dispatchers.IO`, so no persistable URI
 * permission is needed. There is no service for it. The import is single flight per process
 * ([ModelImporter.isImporting]): a recreated activity (rotation) seeds `importing` from it, so Import, Use
 * built-in model and Start stay disabled until the running import ends, and the result goes through the
 * process-wide [ImportResults] that every activity collects, so the new one learns it and reloads the model state.
 * If the whole process dies mid-import, the copy is lost and the next model-state read shows what was stored.
 */
class MainActivity : ComponentActivity() {
    /** The last [ImportResults] sequence this activity handled; read before `importing` is seeded (see [onImportResult]). */
    private var seenImportSequence = ImportResults.latest.value.sequence

    private val controlState = mutableStateOf(ControlState(importing = ModelImporter.isImporting()))
    private val diagnosticsState = mutableStateOf(DiagnosticsUiState())
    private var seenStartFailures = 0

    /** Counts the model-state reads; only the newest read may answer (main thread only). */
    private var modelLoadSeq = 0

    // Registered at activity level so a result that arrives after a recreation still reaches the new instance.
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onConsentResult(result)
        }
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            dispatch(ControlEvent.NotificationPermissionResult(granted))
        }

    // A .tflite has no registered MIME type, so the picker is opened for every type.
    private val modelPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            dispatch(ControlEvent.ImportPicked(uri?.toString()))
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
                    onImportClick = { dispatch(ControlEvent.ImportClicked) },
                    onUseBuiltInClick = { dispatch(ControlEvent.UseBuiltInClicked) },
                    onDebugMarksChange = { dispatch(ControlEvent.DebugMarksToggled(it)) },
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
        // Failures before this activity existed (for example before a rotation) are not shown again.
        seenStartFailures = CaptureService.startFailures.value
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Also the first value of every start of the screen, so the model block is read when the screen
                    // comes up and again when a session stops (the session publishes its model status).
                    CaptureService.running.collect {
                        dispatch(ControlEvent.RunningChanged(it))
                        loadModelState()
                    }
                }
                launch { ImportResults.latest.collect { onImportResult(it) } }
                CaptureService.startFailures.collect { failures ->
                    if (failures > seenStartFailures) dispatch(ControlEvent.StartFailed)
                    seenStartFailures = failures
                }
            }
        }
        showCrashNoticeIfAny()
        loadDebugMarks()
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
            ControlEffect.START_SERVICE -> startCapture(consentResult, step.debugMarks == true)
            ControlEffect.STOP_SERVICE -> stopCapture()
            ControlEffect.OPEN_MODEL_PICKER -> openModelPicker()
            ControlEffect.RUN_IMPORT -> runImport(step.importUri)
            ControlEffect.CLEAR_IMPORTED -> clearImported()
            ControlEffect.LOAD_MODEL_STATE -> loadModelState()
            ControlEffect.PERSIST_DEBUG_MARKS -> saveDebugMarks(step.debugMarks == true)
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
    private fun startCapture(
        consentResult: ActivityResult?,
        debugMarks: Boolean,
    ) {
        val data = consentResult?.data
        if (consentResult == null || data == null) {
            CaptureLog.log("start refused: no consent result to pass on", null)
            dispatch(ControlEvent.ConsentResult(false))
            return
        }
        try {
            ContextCompat.startForegroundService(this, CaptureService.startIntent(this, consentResult.resultCode, data, debugMarks))
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

    private fun openModelPicker() {
        try {
            modelPickerLauncher.launch(arrayOf(MODEL_PICKER_MIME_TYPE))
        } catch (e: Exception) {
            CaptureLog.log("opening the model picker failed", e)
        }
    }

    /**
     * Imports the picked document off the main thread and publishes the outcome to [ImportResults]; the collector
     * ([onImportResult]) reports it, also in an activity that was recreated meanwhile. The publish runs inside the
     * worker block, so a cancelled `lifecycleScope` cannot lose it.
     */
    private fun runImport(uriString: String?) {
        if (uriString == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ImportResults.publish(importOrFail(Uri.parse(uriString))) }
        }
    }

    /**
     * A finished import. Each result is handled once per activity (`seenImportSequence`, read in the field
     * initialiser before `importing` is seeded, so an import that ends in between is still delivered). When this
     * activity is waiting for it the reducer finishes the import; otherwise (it started after the import began and
     * missed the flag) only the model state is read again.
     */
    private fun onImportResult(result: ImportResults.Result) {
        val summary = result.summary
        if (result.sequence <= seenImportSequence || summary == null) return
        seenImportSequence = result.sequence
        // A new model is a new situation: what the last session said about the old one no longer applies.
        if (summary is ImportSummary.Accepted) acknowledgedStatus = ModelStatusHolder.latest()
        if (controlState.value.importing) dispatch(ControlEvent.ImportFinished(summary)) else loadModelState()
    }

    /** Worker thread. Never throws: any failure, `Error`s included, becomes [ImportSummary.Failed]. */
    private fun importOrFail(uri: Uri): ImportSummary =
        try {
            val (name, size) = readNameAndSize(uri)
            val importer = ModelImporter(ImportedModelStore(applicationContext))
            val source = { contentResolver.openInputStream(uri) ?: throw IOException("The picked file cannot be opened") }
            ControlModelMapper.summary(importer.import(source, name, size))
        } catch (t: Throwable) {
            AppLog.log("model import failed", t)
            ImportSummary.Failed(ImportFailure.IO, t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
        }

    /**
     * The provider's file name and size, each `null` when it does not say (or the query fails): the importer
     * counts the bytes itself and falls back to a neutral name.
     */
    private fun readNameAndSize(uri: Uri): Pair<String?, Long?> {
        val unknown: Pair<String?, Long?> = Pair(null, null)
        return try {
            val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use unknown
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                Pair(name, size)
            } ?: unknown
        } catch (e: Exception) {
            AppLog.log("reading the picked file's name and size failed", e)
            unknown
        }
    }

    /** Deletes the imported model off the main thread, then reads the model state again. */
    private fun clearImported() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { clearOrLog() }
            acknowledgedStatus = ModelStatusHolder.latest()
            loadModelState()
        }
    }

    private fun clearOrLog() {
        try {
            if (!ImportedModelStore(applicationContext).clear()) AppLog.log("clearing the imported model was incomplete", null)
        } catch (t: Throwable) {
            AppLog.log("clearing the imported model failed", t)
        }
    }

    /**
     * Reads the stored model state and the newest session's model status off the main thread. Only the newest
     * read answers, so an older read cannot overwrite a newer one; a failed read leaves the screen as it was.
     */
    private fun loadModelState() {
        val sequence = ++modelLoadSeq
        lifecycleScope.launch {
            val event = withContext(Dispatchers.IO) { readModelState() }
            if (event != null && sequence == modelLoadSeq) dispatch(event)
        }
    }

    private fun readModelState(): ControlEvent.ModelStateLoaded? =
        try {
            val status: ModelStatus? = ModelStatusHolder.latest().takeUnless { it === acknowledgedStatus }
            val store = ImportedModelStore(applicationContext)
            ControlModelMapper.stateLoaded(store.state(), status, store::storedFileMatches)
        } catch (t: Throwable) {
            AppLog.log("reading the model state failed", t)
            null
        }

    /** Reads the saved "Debug marks" switch off the main thread; a failed read leaves the default (off). */
    private fun loadDebugMarks() {
        lifecycleScope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    try {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DEBUG_MARKS, TrackerConfig.DEFAULT_DEBUG_MARKS)
                    } catch (t: Throwable) {
                        AppLog.log("reading the debug marks switch failed", t)
                        null
                    }
                }
            if (saved != null) dispatch(ControlEvent.DebugMarksLoaded(saved))
        }
    }

    /** Saves the "Debug marks" switch off the main thread; a failed write is logged and the switch still works this run. */
    private fun saveDebugMarks(enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_DEBUG_MARKS, enabled).apply()
                } catch (t: Throwable) {
                    AppLog.log("saving the debug marks switch failed", t)
                }
            }
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

    private companion object {
        const val MODEL_PICKER_MIME_TYPE = "*/*"

        /** App preferences file and the key of the "Debug marks" switch; only read and written on `Dispatchers.IO`. */
        const val PREFS_NAME = "pose_tracker_prefs"
        const val KEY_DEBUG_MARKS = "debug_marks"

        /**
         * The session status the owner has already acted on (imported a new model or reset to the built-in one);
         * its fallback notice is not shown again. Compared by identity: a new session publishes a new object.
         */
        @Volatile
        var acknowledgedStatus: ModelStatus? = null
    }
}
